import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { loadConfig, RunnerConfig } from "../src/config";
import { RunnerError } from "../src/errors";
import { PresentationRunner } from "../src/runner";
import { cleanupExpiredResults, RetentionCleanupReport } from "../src/retention";

const DAY_MS = 24 * 60 * 60 * 1000;
const EXPIRED_JOB_ID = "33333333-3333-4333-8333-333333333333";

test("retention config defaults to the Harness seven-day policy and accepts an override", () => {
  assert.equal(loadConfig({}).resultRetentionDays, 7);
  assert.equal(loadConfig({ PPT_SKILL_RESULT_RETENTION_DAYS: "14" }).resultRetentionDays, 14);
});

test("expired UUID result is deleted as one complete job", async () => {
  await withResultRoot(async resultRoot => {
    const jobDirectory = await createResult(resultRoot, EXPIRED_JOB_ID, 8);
    const report = await cleanupExpiredResults(resultRoot, 7);
    assert.equal(report.deleted, 1);
    assert.equal(await exists(jobDirectory), false);
  });
});

test("fresh UUID result is kept", async () => {
  await withResultRoot(async resultRoot => {
    const jobDirectory = await createResult(resultRoot, "44444444-4444-4444-8444-444444444444", 1);
    const report = await cleanupExpiredResults(resultRoot, 7);
    assert.equal(report.kept, 1);
    assert.equal(await exists(jobDirectory), true);
  });
});

test("non-UUID result directory is untouched", async () => {
  await withResultRoot(async resultRoot => {
    const directory = path.join(resultRoot, "keep-me");
    await fs.mkdir(directory, { recursive: true });
    await fs.writeFile(path.join(directory, "presentation.pptx"), "keep", "utf8");
    await fs.utimes(directory, new Date(Date.now() - 8 * DAY_MS), new Date(Date.now() - 8 * DAY_MS));
    const report = await cleanupExpiredResults(resultRoot, 7);
    assert.equal(report.inspected, 0);
    assert.equal(await exists(directory), true);
  });
});

test("UUID symlink is rejected without following or deleting its outside target", async (t) => {
  await withResultRoot(async resultRoot => {
    const outsideDirectory = path.join(path.dirname(resultRoot), "outside-result");
    const link = path.join(resultRoot, "55555555-5555-4555-8555-555555555555");
    await fs.mkdir(outsideDirectory, { recursive: true });
    const marker = path.join(outsideDirectory, "marker.txt");
    await fs.writeFile(marker, "must survive", "utf8");
    try {
      await fs.symlink(outsideDirectory, link, process.platform === "win32" ? "junction" : "dir");
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code === "EPERM") {
        t.skip("platform does not permit creating symlinks for the retention fixture");
        return;
      }
      throw error;
    }
    const report = await cleanupExpiredResults(resultRoot, 7);
    assert.equal(report.skipped[0]?.code, "SYMLINK_FORBIDDEN");
    assert.equal((await fs.lstat(link)).isSymbolicLink(), true);
    assert.equal(await exists(marker), true);
    await fs.rm(outsideDirectory, { recursive: true, force: true });
  });
});

test("cleanup is idempotent after whole-job deletion", async () => {
  await withResultRoot(async resultRoot => {
    await createResult(resultRoot, "66666666-6666-4666-8666-666666666666", 8);
    const first = await cleanupExpiredResults(resultRoot, 7);
    const second = await cleanupExpiredResults(resultRoot, 7);
    assert.equal(first.deleted, 1);
    assert.equal(second.deleted, 0);
    assert.equal(second.failures.length, 0);
  });
});

test("startup cleanup failure is recorded without preventing Runner initialization", async () => {
  await withResultRoot(async resultRoot => {
    let cleanupCalls = 0;
    const cleanup = async (): Promise<RetentionCleanupReport> => {
      cleanupCalls += 1;
      throw new Error("simulated cleanup failure");
    };
    const runner = new PresentationRunner(testConfig(resultRoot), undefined, cleanup);
    await assert.doesNotReject(() => runner.initialize());
    assert.equal(cleanupCalls, 1);
  });
});

test("generation and result lookup do not repeat the startup cleanup scan", async () => {
  await withResultRoot(async resultRoot => {
    let cleanupCalls = 0;
    const cleanup = async (): Promise<RetentionCleanupReport> => {
      cleanupCalls += 1;
      return { cutoffMs: Date.now(), inspected: 0, kept: 0, deleted: 0, skipped: [], failures: [] };
    };
    const runner = new PresentationRunner(testConfig(resultRoot), undefined, cleanup);
    await runner.initialize();
    await runner.initialize();
    assert.equal(cleanupCalls, 1);
    await assert.rejects(() => runner.generate({ outline: { title: "invalid" } }), hasCode("INVALID_OUTLINE"));
    await assert.rejects(() => runner.resolveResultFile(EXPIRED_JOB_ID, "presentation.pptx"), hasCode("RESULT_NOT_FOUND"));
    assert.equal(cleanupCalls, 1);
  });
});

async function createResult(resultRoot: string, jobId: string, ageDays: number): Promise<string> {
  const directory = path.join(resultRoot, jobId);
  const previews = path.join(directory, "previews");
  await fs.mkdir(previews, { recursive: true });
  await fs.writeFile(path.join(directory, "presentation.pptx"), "pptx", "utf8");
  await fs.writeFile(path.join(directory, "outline.json"), "{}", "utf8");
  await fs.writeFile(path.join(directory, "qa-report.json"), "{}", "utf8");
  await fs.writeFile(path.join(previews, "slide-01.png"), "png", "utf8");
  const old = new Date(Date.now() - ageDays * DAY_MS);
  await fs.utimes(directory, old, old);
  return directory;
}

function testConfig(resultRoot: string): RunnerConfig {
  return {
    port: 0,
    host: "127.0.0.1",
    skillHome: resultRoot,
    tempRoot: path.join(resultRoot, "temp"),
    resultRoot,
    pythonCommand: "python3",
    nodeCommand: process.execPath,
    timeoutMs: 10_000,
    previewTimeoutMs: 180_000,
    previewDpi: 150,
    previewMaxFileBytes: 20 * 1024 * 1024,
    previewMaxTotalBytes: 200 * 1024 * 1024,
    resultRetentionDays: 7,
    defaultPreset: "forest-research",
  };
}

async function withResultRoot(run: (resultRoot: string) => Promise<void>): Promise<void> {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "a12-ppt-retention-test-"));
  const resultRoot = path.join(root, "results");
  await fs.mkdir(resultRoot, { recursive: true });
  try {
    await run(resultRoot);
  } finally {
    await fs.rm(root, { recursive: true, force: true });
  }
}

async function exists(filePath: string): Promise<boolean> {
  try {
    await fs.lstat(filePath);
    return true;
  } catch {
    return false;
  }
}

function hasCode(code: string) {
  return (error: unknown) => error instanceof RunnerError && error.code === code;
}
