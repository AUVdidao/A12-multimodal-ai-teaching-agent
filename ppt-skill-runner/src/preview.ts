import crypto from "node:crypto";
import fs from "node:fs/promises";
import path from "node:path";
import { RunnerError } from "./errors";
import { assertNoSymlinks, assertPathInside } from "./security";

export interface PreviewFile {
  slideNumber: number;
  fileName: string;
  sizeBytes: number;
  sha256: string;
  width: number;
  height: number;
  downloadRef: string;
}

export interface PreviewManifest {
  rendered: true;
  format: "png";
  dpi: number;
  slideCount: number;
  totalSizeBytes: number;
  files: PreviewFile[];
}

export interface PreviewValidationOptions {
  expectedSlideCount: number;
  jobId: string;
  dpi: number;
  maxFileBytes: number;
  maxTotalBytes: number;
}

const PNG_SIGNATURE = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
const PREVIEW_NAME = /^slide-(\d+)\.png$/;

export async function validatePreviewDirectory(
  previewsDirectory: string,
  taskRoot: string,
  options: PreviewValidationOptions,
): Promise<PreviewManifest> {
  const previewsReal = await fs.realpath(previewsDirectory).catch(() => {
    throw new RunnerError("PREVIEW_INVALID_FILE", "Preview output directory is missing", 502);
  });
  assertPathInside(taskRoot, previewsReal);
  await assertNoSymlinks(previewsReal);

  const entries = await fs.readdir(previewsReal, { withFileTypes: true });
  const candidates = entries.filter(entry => entry.isFile());
  if (entries.some(entry => !entry.isFile()) || candidates.length !== options.expectedSlideCount) {
    throw new RunnerError(
      "PREVIEW_COUNT_MISMATCH",
      `Preview count does not match PPTX slide count (${candidates.length}/${options.expectedSlideCount})`,
      502,
    );
  }

  const files: PreviewFile[] = [];
  const seen = new Set<number>();
  let totalSizeBytes = 0;
  let dimensions: { width: number; height: number } | undefined;
  for (const entry of candidates) {
    const match = PREVIEW_NAME.exec(entry.name);
    if (!match) throw new RunnerError("PREVIEW_INVALID_FILE", `Unexpected preview filename: ${entry.name}`, 502);
    const slideNumber = Number(match[1]);
    if (!Number.isSafeInteger(slideNumber) || slideNumber < 1 || slideNumber > options.expectedSlideCount || seen.has(slideNumber)) {
      throw new RunnerError("PREVIEW_COUNT_MISMATCH", `Invalid or duplicate preview slide number: ${entry.name}`, 502);
    }
    seen.add(slideNumber);
    const filePath = path.join(previewsReal, entry.name);
    const stat = await fs.lstat(filePath);
    if (!stat.isFile() || stat.isSymbolicLink() || stat.size <= 0) {
      throw new RunnerError("PREVIEW_INVALID_FILE", `Preview is not a non-empty regular file: ${entry.name}`, 502);
    }
    const realPath = await fs.realpath(filePath);
    assertPathInside(taskRoot, realPath);
    if (stat.size > options.maxFileBytes) {
      throw new RunnerError("PREVIEW_TOO_LARGE", `Preview exceeds the per-file size limit: ${entry.name}`, 502);
    }
    totalSizeBytes += stat.size;
    if (totalSizeBytes > options.maxTotalBytes) {
      throw new RunnerError("PREVIEW_TOO_LARGE", "Preview set exceeds the total size limit", 502);
    }

    const bytes = await fs.readFile(filePath);
    const png = parsePng(bytes, entry.name);
    if (!dimensions) dimensions = { width: png.width, height: png.height };
    if (dimensions.width !== png.width || dimensions.height !== png.height) {
      throw new RunnerError("PREVIEW_INVALID_FILE", "Preview PNG dimensions are inconsistent", 502);
    }
    files.push({
      slideNumber,
      fileName: entry.name,
      sizeBytes: stat.size,
      sha256: crypto.createHash("sha256").update(bytes).digest("hex"),
      width: png.width,
      height: png.height,
      downloadRef: `/internal/ppt-skill/v1/jobs/${options.jobId}/previews/${slideNumber}`,
    });
  }

  files.sort((left, right) => left.slideNumber - right.slideNumber);
  for (const [index, file] of files.entries()) {
    if (file.slideNumber !== index + 1) {
      throw new RunnerError("PREVIEW_COUNT_MISMATCH", "Preview slide numbers are not contiguous", 502);
    }
  }

  return {
    rendered: true,
    format: "png",
    dpi: options.dpi,
    slideCount: files.length,
    totalSizeBytes,
    files,
  };
}

function parsePng(bytes: Buffer, fileName: string): { width: number; height: number } {
  if (bytes.length < 33 || !bytes.subarray(0, PNG_SIGNATURE.length).equals(PNG_SIGNATURE)) {
    throw new RunnerError("PREVIEW_INVALID_FILE", `Preview is not a valid PNG: ${fileName}`, 502);
  }
  if (bytes.readUInt32BE(8) < 13 || bytes.toString("ascii", 12, 16) !== "IHDR") {
    throw new RunnerError("PREVIEW_INVALID_FILE", `Preview PNG is missing a valid IHDR: ${fileName}`, 502);
  }
  const width = bytes.readUInt32BE(16);
  const height = bytes.readUInt32BE(20);
  if (width <= 0 || height <= 0) {
    throw new RunnerError("PREVIEW_INVALID_FILE", `Preview PNG has invalid dimensions: ${fileName}`, 502);
  }
  return { width, height };
}
