import crypto from "node:crypto";
import fs from "node:fs/promises";
import path from "node:path";
import { executeCommand } from "./command";
import { RunnerConfig } from "./config";
import { RunnerError } from "./errors";
import { validateOutline } from "./schema";
import { assertNoSymlinks, assertPathInside, ensureDirectory, validateOutlineSecurity, validatePreset } from "./security";
import { CommandExecutor, GenerationRequest, GenerationResult } from "./types";

export class PresentationRunner {
  constructor(
    private readonly config: RunnerConfig,
    private readonly executor: CommandExecutor = executeCommand
  ) {}

  async generate(request: GenerationRequest): Promise<GenerationResult> {
    const startedAt = Date.now();
    validateOutline(request.outline);
    validateOutlineSecurity(request.outline);
    const preset = request.stylePreset?.trim() || this.config.defaultPreset;
    validatePreset(preset);

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
      await assertPptxPackage(presentationPath, taskReal);

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
      await fs.writeFile(qaReportPath, `${JSON.stringify(qaReport, null, 2)}\n`, "utf8");

      await fs.mkdir(resultDirectory, { recursive: false });
      const finalOutline = path.join(resultDirectory, "outline.json");
      const finalPresentation = path.join(resultDirectory, "presentation.pptx");
      const finalQaReport = path.join(resultDirectory, "qa-report.json");
      await Promise.all([
        fs.copyFile(outlinePath, finalOutline),
        fs.copyFile(presentationPath, finalPresentation),
        fs.copyFile(qaReportPath, finalQaReport)
      ]);
      await assertNoSymlinks(resultDirectory);
      const file = await fs.readFile(finalPresentation);

      return {
        jobId,
        status: "SUCCEEDED",
        fileName: "presentation.pptx",
        sizeBytes: file.byteLength,
        sha256: crypto.createHash("sha256").update(file).digest("hex"),
        qa: { passed: true, qaLevel: "AUTOMATED_GEOMETRY_ONLY", report: qaReport },
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
async function assertPptxPackage(filePath: string, controlledRoot: string): Promise<void> {
  await assertRegularNonEmptyFile(filePath, controlledRoot, "PPTX_MISSING");
  const content = await fs.readFile(filePath);
  if (content.length < 22 || content.readUInt32LE(0) !== 0x04034b50) {
    throw new RunnerError("PPTX_INVALID", "Generated file is not a valid PPTX package", 502);
  }
  const entries = zipEntryNames(content);
  if (!entries.has("[Content_Types].xml") || !entries.has("ppt/presentation.xml")) {
    throw new RunnerError("PPTX_INVALID", "Generated file is missing required PPTX parts", 502);
  }
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
