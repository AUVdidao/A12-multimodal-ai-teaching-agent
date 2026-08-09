import fs from "node:fs/promises";
import path from "node:path";
import { RunnerError } from "./errors";
import { assertNoSymlinks, assertPathInside } from "./security";

export const RESULT_RETENTION_DAYS_DEFAULT = 7;
const DAY_MS = 24 * 60 * 60 * 1000;
const UUID_DIRECTORY = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export interface RetentionIssue {
  jobId: string;
  code: string;
}

export interface RetentionCleanupReport {
  cutoffMs: number;
  inspected: number;
  kept: number;
  deleted: number;
  skipped: RetentionIssue[];
  failures: RetentionIssue[];
}

export type RetentionCleanup = (resultRoot: string, retentionDays: number, nowMs?: number) => Promise<RetentionCleanupReport>;

export async function cleanupExpiredResults(resultRoot: string, retentionDays: number, nowMs = Date.now()): Promise<RetentionCleanupReport> {
  if (!Number.isSafeInteger(retentionDays) || retentionDays <= 0) {
    throw new RunnerError("RESULT_RETENTION_INVALID", "Runner result retention must be a positive integer", 500);
  }
  if (!Number.isFinite(nowMs)) throw new RunnerError("RESULT_RETENTION_INVALID", "Runner result cleanup time is invalid", 500);

  const rootPath = path.resolve(resultRoot);
  const rootStat = await fs.lstat(rootPath).catch(() => undefined);
  if (!rootStat?.isDirectory() || rootStat.isSymbolicLink()) {
    throw new RunnerError("SYMLINK_FORBIDDEN", "Runner result root must be a regular directory", 500);
  }
  const rootReal = await fs.realpath(rootPath);
  assertPathInside(rootReal, rootReal);
  const cutoffMs = nowMs - retentionDays * DAY_MS;
  const report: RetentionCleanupReport = { cutoffMs, inspected: 0, kept: 0, deleted: 0, skipped: [], failures: [] };
  const entries = await fs.readdir(rootReal, { withFileTypes: true });

  for (const entry of entries) {
    if (!UUID_DIRECTORY.test(entry.name)) continue;
    report.inspected += 1;
    const candidate = path.join(rootReal, entry.name);
    assertPathInside(rootReal, candidate);
    const result = await inspectJobDirectory(candidate, rootReal, cutoffMs);
    if (result.kind === "kept") {
      report.kept += 1;
    } else if (result.kind === "skipped") {
      report.skipped.push({ jobId: entry.name, code: result.code });
    } else if (result.kind === "failure") {
      report.failures.push({ jobId: entry.name, code: result.code });
    } else {
      report.deleted += 1;
    }
  }
  return report;
}

type InspectionResult =
  | { kind: "kept" }
  | { kind: "deleted" }
  | { kind: "skipped"; code: string }
  | { kind: "failure"; code: string };

async function inspectJobDirectory(candidate: string, rootReal: string, cutoffMs: number): Promise<InspectionResult> {
  let stat;
  try {
    stat = await fs.lstat(candidate);
  } catch (error) {
    return { kind: "failure", code: errorCode(error, "RESULT_STAT_FAILED") };
  }
  if (stat.isSymbolicLink()) return { kind: "skipped", code: "SYMLINK_FORBIDDEN" };
  if (!stat.isDirectory()) return { kind: "skipped", code: "NOT_DIRECTORY" };
  try {
    const real = await fs.realpath(candidate);
    assertPathInside(rootReal, real);
    if (stat.mtimeMs >= cutoffMs) return { kind: "kept" };
    await assertNoSymlinks(candidate);
    await fs.rm(candidate, { recursive: true, force: false });
    return { kind: "deleted" };
  } catch (error) {
    if (error instanceof RunnerError && error.code === "PATH_TRAVERSAL") throw error;
    if (error instanceof RunnerError && error.code === "SYMLINK_FORBIDDEN") return { kind: "skipped", code: error.code };
    return { kind: "failure", code: errorCode(error, "RESULT_DELETE_FAILED") };
  }
}

function errorCode(error: unknown, fallback: string): string {
  if (error instanceof RunnerError && /^[A-Z0-9_]{3,80}$/.test(error.code)) return error.code;
  if (error && typeof error === "object" && "code" in error && typeof error.code === "string" && /^[A-Z0-9_]{3,80}$/.test(error.code)) return error.code;
  return fallback;
}
