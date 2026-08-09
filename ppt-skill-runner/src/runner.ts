import crypto from "node:crypto";
import fs from "node:fs/promises";
import path from "node:path";
import { executeCommand } from "./command";
import { RunnerConfig } from "./config";
import { RunnerError } from "./errors";
import { validatePreviewDirectory } from "./preview";
import { cleanupExpiredResults, RESULT_RETENTION_DAYS_DEFAULT, RetentionCleanup, RetentionCleanupReport } from "./retention";
import { validateOutline } from "./schema";
import { assertNoSymlinks, assertPathInside, ensureDirectory, validateOutlineSecurity, validatePreset } from "./security";
import { CommandExecutor, GenerationRequest, GenerationResult } from "./types";

export class PresentationRunner {
  private initialization?: Promise<void>;

  constructor(
    private readonly config: RunnerConfig,
    private readonly executor: CommandExecutor = executeCommand,
    private readonly cleanup: RetentionCleanup = cleanupExpiredResults,
  ) {}

  async initialize(): Promise<void> {
    if (!this.initialization) this.initialization = this.runStartupCleanup();
    await this.initialization;
  }

  async generate(request: GenerationRequest): Promise<GenerationResult> {
    const startedAt = Date.now();
    validateOutline(request.outline);
    validateOutlineSecurity(request.outline);
    const preset = request.stylePreset?.trim() || this.config.defaultPreset;
    validatePreset(preset);
    const outlineSlideCount = outlineSlideCountOf(request.outline);
    await this.initialize();

    const tempRoot = await ensureDirectory(this.config.tempRoot);
    const resultRoot = await ensureDirectory(this.config.resultRoot);
    const jobId = crypto.randomUUID();
    const taskDirectory = path.join(tempRoot, jobId);
    const resultDirectory = path.join(resultRoot, jobId);
    assertPathInside(tempRoot, taskDirectory);
    assertPathInside(resultRoot, resultDirectory);
    await fs.mkdir(taskDirectory, { recursive: false });

    try {
      const taskReal = await fs.realpath(taskDirectory);
      assertPathInside(tempRoot, taskReal);
      const outlinePath = path.join(taskReal, "outline.json");
      const presentationPath = path.join(taskReal, "presentation.pptx");
      const qaReportPath = path.join(taskReal, "qa-report.json");
      await fs.writeFile(outlinePath, `${JSON.stringify(request.outline, null, 2)}\n`, { encoding: "utf8", flag: "wx" });
      await assertNoSymlinks(taskReal);

      const buildStartedAt = Date.now();
      const buildResult = await this.executor({
        command: this.config.nodeCommand,
        args: [
          path.join(this.config.skillHome, "scripts", "build_deck_pptxgenjs.js"),
          "--outline", outlinePath,
          "--output", presentationPath,
          "--style-preset", preset,
          "--asset-root", taskReal
        ],
        cwd: taskReal,
        timeoutMs: this.config.timeoutMs
      });
      const buildDurationMs = Date.now() - buildStartedAt;
      if (buildResult.exitCode !== 0) {
        throw commandFailure("BUILD_FAILED", "presentation-skill build failed", buildResult);
      }
      await assertRegularNonEmptyFile(presentationPath, taskReal, "PPTX_MISSING");
      const pptxSlideCount = await assertPptxPackage(presentationPath, taskReal);
      if (pptxSlideCount !== undefined && pptxSlideCount !== outlineSlideCount) {
        throw new RunnerError(
          "PPTX_SLIDE_COUNT_MISMATCH",
          `PPTX slide count does not match Runner outline (${pptxSlideCount}/${outlineSlideCount})`,
          502,
        );
      }

      const qaStartedAt = Date.now();
      const qaDirectory = path.join(taskReal, "qa");
      const qaResult = await this.executor({
        command: this.config.pythonCommand,
        args: [
          path.join(this.config.skillHome, "scripts", "qa_gate.py"),
          "--input", presentationPath,
          "--outdir", qaDirectory,
          "--style-preset", preset,
          "--outline", outlinePath,
          "--strict-geometry",
          "--skip-render",
          "--skip-manual-review",
          "--fail-on-design-warnings",
          "--report", qaReportPath
        ],
        cwd: taskReal,
        timeoutMs: this.config.timeoutMs
      });
      const qaDurationMs = Date.now() - qaStartedAt;
      if (qaResult.exitCode !== 0) {
        throw commandFailure("QA_FAILED", "presentation-skill QA gate rejected the deck", qaResult);
      }
      await assertRegularNonEmptyFile(qaReportPath, taskReal, "QA_REPORT_MISSING");
      const upstreamQa = JSON.parse(await fs.readFile(qaReportPath, "utf8")) as Record<string, unknown>;
      const qaReport = {
        ...upstreamQa,
        passed: true,
        qaLevel: "AUTOMATED_GEOMETRY_ONLY",
        renderSkipped: true,
        manualReviewSkipped: true
      };

      const previewsDirectory = path.join(taskReal, "previews");
      await fs.mkdir(previewsDirectory, { recursive: false });
      await this.renderPreviews(presentationPath, previewsDirectory, taskReal);
      const preview = await validatePreviewDirectory(previewsDirectory, taskReal, {
        expectedSlideCount: pptxSlideCount ?? outlineSlideCount,
        jobId,
        dpi: this.config.previewDpi,
        maxFileBytes: this.config.previewMaxFileBytes,
        maxTotalBytes: this.config.previewMaxTotalBytes,
      });
      const finalQaReport = {
        ...qaReport,
        previewRenderingImplemented: true,
        previewSlideCount: preview.slideCount,
      };
      await fs.writeFile(qaReportPath, `${JSON.stringify(finalQaReport, null, 2)}\n`, "utf8");

      await fs.mkdir(resultDirectory, { recursive: false });
      const finalOutline = path.join(resultDirectory, "outline.json");
      const finalPresentation = path.join(resultDirectory, "presentation.pptx");
      const finalQaReportPath = path.join(resultDirectory, "qa-report.json");
      await Promise.all([
        fs.copyFile(outlinePath, finalOutline),
        fs.copyFile(presentationPath, finalPresentation),
        fs.copyFile(qaReportPath, finalQaReportPath)
      ]);
      await copyPreviewDirectory(previewsDirectory, path.join(resultDirectory, "previews"));
      await assertNoSymlinks(resultDirectory);
      const file = await fs.readFile(finalPresentation);

      return {
        jobId,
        status: "SUCCEEDED",
        fileName: "presentation.pptx",
        sizeBytes: file.byteLength,
        sha256: crypto.createHash("sha256").update(file).digest("hex"),
        qa: { passed: true, qaLevel: "AUTOMATED_GEOMETRY_ONLY", report: finalQaReport },
        preview,
        buildDurationMs,
        qaDurationMs,
        totalDurationMs: Date.now() - startedAt,
        files: {
          presentation: `/internal/ppt-skill/v1/jobs/${jobId}/presentation.pptx`,
          outline: `/internal/ppt-skill/v1/jobs/${jobId}/outline.json`,
          qaReport: `/internal/ppt-skill/v1/jobs/${jobId}/qa-report.json`
        }
      };
    } catch (error) {
      await fs.rm(resultDirectory, { recursive: true, force: true });
      throw error;
    } finally {
      await fs.rm(taskDirectory, { recursive: true, force: true });
    }
  }

  private async renderPreviews(presentationPath: string, previewsDirectory: string, taskRoot: string): Promise<void> {
    assertPathInside(taskRoot, previewsDirectory);
    let result;
    try {
      result = await this.executor({
        command: this.config.pythonCommand,
        args: [
          path.join(this.config.skillHome, "scripts", "render_slides.py"),
          "--input", presentationPath,
          "--outdir", previewsDirectory,
          "--dpi", String(this.config.previewDpi),
          "--format", "png",
        ],
        cwd: taskRoot,
        timeoutMs: this.config.previewTimeoutMs,
      });
    } catch (error) {
      const message = error instanceof Error ? error.message : "Preview renderer did not complete";
      const timedOut = /timed out/i.test(message);
      throw new RunnerError(
        timedOut ? "PREVIEW_RENDER_TIMEOUT" : "PREVIEW_RENDER_FAILED",
        timedOut ? "Preview rendering timed out" : "Preview renderer could not be executed",
        502,
        { message: message.slice(-1000) },
      );
    }
    if (result.exitCode !== 0) {
      throw new RunnerError("PREVIEW_RENDER_FAILED", "render_slides.py failed", 502, {
        exitCode: result.exitCode,
        stdout: result.stdout.slice(-2000),
        stderr: result.stderr.slice(-2000),
      });
    }
  }

  private async runStartupCleanup(): Promise<void> {
    await ensureDirectory(this.config.resultRoot);
    try {
      const report = await this.cleanup(
        this.config.resultRoot,
        this.config.resultRetentionDays ?? RESULT_RETENTION_DAYS_DEFAULT,
      );
      if (report.skipped.length > 0 || report.failures.length > 0) logCleanupIssues(report);
    } catch (error) {
      if (error instanceof RunnerError && ["PATH_TRAVERSAL", "SYMLINK_FORBIDDEN"].includes(error.code)) throw error;
      console.warn(`[runner-retention] cleanup unavailable code=${cleanupErrorCode(error)}`);
    }
  }

  async resolveResultFile(jobId: string, fileName: string): Promise<string> {
    if (!/^[0-9a-f-]{36}$/i.test(jobId) || !["presentation.pptx", "outline.json", "qa-report.json"].includes(fileName)) {
      throw new RunnerError("RESULT_NOT_FOUND", "Result file not found", 404);
    }
    const resultRoot = await ensureDirectory(this.config.resultRoot);
    const candidate = path.join(resultRoot, jobId, fileName);
    assertPathInside(resultRoot, candidate);
    await assertRegularNonEmptyFile(candidate, resultRoot, "RESULT_NOT_FOUND");
    return candidate;
  }

  async resolvePreviewFile(jobId: string, slideNumber: string): Promise<string> {
    if (!/^[0-9a-f-]{36}$/i.test(jobId) || !/^[1-9]\d{0,2}$/.test(slideNumber)) {
      throw new RunnerError("PREVIEW_NOT_FOUND", "Preview file not found", 404);
    }
    const numericSlideNumber = Number(slideNumber);
    const resultRoot = await ensureDirectory(this.config.resultRoot);
    const previewsRoot = path.join(resultRoot, jobId, "previews");
    const fileName = `slide-${String(numericSlideNumber).padStart(2, "0")}.png`;
    const candidate = path.join(previewsRoot, fileName);
    assertPathInside(resultRoot, candidate);
    await assertRegularNonEmptyFile(candidate, resultRoot, "PREVIEW_NOT_FOUND");
    const real = await fs.realpath(candidate);
    assertPathInside(previewsRoot, real);
    return real;
  }
}

function logCleanupIssues(report: RetentionCleanupReport): void {
  console.warn(`[runner-retention] cleanup issues skipped=${report.skipped.length} failures=${report.failures.length}`, JSON.stringify({ skipped: report.skipped, failures: report.failures }));
}

function cleanupErrorCode(error: unknown): string {
  if (error instanceof RunnerError) return error.code;
  if (error && typeof error === "object" && "code" in error && typeof error.code === "string") return error.code;
  return "RESULT_CLEANUP_FAILED";
}

async function assertRegularNonEmptyFile(filePath: string, controlledRoot: string, code: string): Promise<void> {
  const stat = await fs.lstat(filePath).catch(() => undefined);
  if (!stat || !stat.isFile() || stat.isSymbolicLink() || stat.size === 0) {
    throw new RunnerError(code, `Expected non-empty regular file: ${path.basename(filePath)}`, 502);
  }
  const real = await fs.realpath(filePath);
  assertPathInside(controlledRoot, real);
}

/**
 * A successful process exit alone is not sufficient evidence that the output is
 * an editable PPTX. Phase 1 keeps this deterministic and dependency-free by
 * checking the OOXML ZIP directory for the required presentation root part.
 */
async function assertPptxPackage(filePath: string, controlledRoot: string): Promise<number | undefined> {
  await assertRegularNonEmptyFile(filePath, controlledRoot, "PPTX_MISSING");
  const content = await fs.readFile(filePath);
  if (content.length < 22 || content.readUInt32LE(0) !== 0x04034b50) {
    throw new RunnerError("PPTX_INVALID", "Generated file is not a valid PPTX package", 502);
  }
  const entries = zipEntryNames(content);
  if (!entries.has("[Content_Types].xml") || !entries.has("ppt/presentation.xml")) {
    throw new RunnerError("PPTX_INVALID", "Generated file is missing required PPTX parts", 502);
  }
  const slideNumbers = [...entries]
    .map(entry => /^ppt\/slides\/slide(\d+)\.xml$/.exec(entry))
    .filter((match): match is RegExpExecArray => match !== null)
    .map(match => Number(match[1]))
    .filter(number => Number.isSafeInteger(number) && number > 0);
  return slideNumbers.length > 0 ? new Set(slideNumbers).size : undefined;
}

function zipEntryNames(content: Buffer): Set<string> {
  const entries = new Set<string>();
  for (let offset = 0; offset + 46 <= content.length; offset += 1) {
    if (content.readUInt32LE(offset) !== 0x02014b50) continue;
    const nameLength = content.readUInt16LE(offset + 28);
    const extraLength = content.readUInt16LE(offset + 30);
    const commentLength = content.readUInt16LE(offset + 32);
    const end = offset + 46 + nameLength + extraLength + commentLength;
    if (end > content.length) break;
    entries.add(content.subarray(offset + 46, offset + 46 + nameLength).toString("utf8"));
    offset = end - 1;
  }
  return entries;
}

function commandFailure(code: string, message: string, result: { exitCode: number; stdout: string; stderr: string }): RunnerError {
  return new RunnerError(code, message, 502, {
    exitCode: result.exitCode,
    stdout: result.stdout.slice(-4000),
    stderr: result.stderr.slice(-4000)
  });
}

function outlineSlideCountOf(outline: Record<string, unknown>): number {
  const slides = outline.slides;
  if (!Array.isArray(slides) || slides.length < 1) throw new RunnerError("INVALID_OUTLINE", "Runner outline must contain slides", 400);
  return slides.length;
}

async function copyPreviewDirectory(source: string, destination: string): Promise<void> {
  await fs.mkdir(destination, { recursive: false });
  const entries = await fs.readdir(source, { withFileTypes: true });
  for (const entry of entries) {
    if (!entry.isFile()) throw new RunnerError("PREVIEW_INVALID_FILE", `Cannot copy preview entry: ${entry.name}`, 502);
    await fs.copyFile(path.join(source, entry.name), path.join(destination, entry.name));
  }
}
