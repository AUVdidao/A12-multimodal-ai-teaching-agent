import assert from "node:assert/strict";
import crypto from "node:crypto";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { RunnerConfig } from "../src/config";
import { RunnerError } from "../src/errors";
import { PresentationRunner } from "../src/runner";
import { assertNoSymlinks, validateOutlineSecurity } from "../src/security";
import { CommandExecutor, CommandSpec } from "../src/types";

const fixturePath = path.resolve(__dirname, "..", "..", "fixtures", "grade-8-biology-photosynthesis-outline.json");

test("legal fixture generates isolated valid PPTX with correct digest", async () => {
  await withRunner(async ({ runner, tempRoot, calls }) => {
    const outline = await readFixture();
    const result = await runner.generate({ outline, stylePreset: "forest-research" });
    assert.equal(result.status, "SUCCEEDED");
    assert.equal(result.qa.qaLevel, "AUTOMATED_GEOMETRY_ONLY");
    const expectedPptx = minimalPptx();
    assert.equal(result.sizeBytes, expectedPptx.byteLength);
    assert.equal(result.sha256, crypto.createHash("sha256").update(expectedPptx).digest("hex"));
    assert.equal((await fs.readdir(tempRoot)).length, 0, "temporary task directory must be cleaned");
    const build = calls.find((call) => call.args[0].endsWith("build_deck_pptxgenjs.js"));
    assert.ok(build);
    const assetRootIndex = build.args.indexOf("--asset-root");
    assert.ok(assetRootIndex > 0);
    assert.equal(build.args[assetRootIndex + 1], build.cwd, "asset-root must be the isolated task directory");
  });
});

test("MIT editorial template preset is passed to the controlled renderer", async () => {
  await withRunner(async ({ runner, calls }) => {
    const outline = await readFixture();
    await runner.generate({ outline, stylePreset: "a12-editorial-grid" });
    const build = calls.find((call) => call.args[0].endsWith("build_deck_pptxgenjs.js"));
    assert.ok(build);
    const presetIndex = build.args.indexOf("--style-preset");
    assert.equal(build.args[presetIndex + 1], "a12-editorial-grid");
  });
});

test("invalid outline is rejected before command execution", async () => {
  await withRunner(async ({ runner, calls }) => {
    await assert.rejects(() => runner.generate({ outline: { title: "invalid" } }), hasCode("INVALID_OUTLINE"));
    assert.equal(calls.length, 0);
  });
});

test("unsupported variant is rejected", async () => {
  await withRunner(async ({ runner }) => {
    const outline = await readFixture();
    (outline.slides as Array<Record<string, unknown>>)[1].variant = "hero-orbit";
    await assert.rejects(() => runner.generate({ outline }), hasCode("INVALID_OUTLINE"));
  });
});

test("build failure never returns success and cleans task directory", async () => {
  await withRunner(async ({ config, tempRoot }) => {
    const runner = new PresentationRunner(config, mockExecutor({ buildExitCode: 7 }).executor);
    const outline = await readFixture();
    await assert.rejects(() => runner.generate({ outline }), hasCode("BUILD_FAILED"));
    assert.equal((await fs.readdir(tempRoot)).length, 0);
  });
});

test("non-PPTX build output is rejected before QA", async () => {
  await withRunner(async ({ config }) => {
    const runner = new PresentationRunner(config, mockExecutor({ invalidPptx: true }).executor);
    const outline = await readFixture();
    await assert.rejects(() => runner.generate({ outline }), hasCode("PPTX_INVALID"));
  });
});

test("QA failure never returns success and removes partial result", async () => {
  await withRunner(async ({ config, resultRoot }) => {
    const runner = new PresentationRunner(config, mockExecutor({ qaExitCode: 3 }).executor);
    const outline = await readFixture();
    await assert.rejects(() => runner.generate({ outline }), hasCode("QA_FAILED"));
    assert.equal((await fs.readdir(resultRoot)).length, 0);
  });
});

test("path traversal and external asset URIs are rejected", async () => {
  const outline = await readFixture();
  (outline.slides as Array<Record<string, unknown>>)[1].image = "../secret.png";
  assert.throws(() => validateOutlineSecurity(outline), hasCode("ASSET_PATH_FORBIDDEN"));
  (outline.slides as Array<Record<string, unknown>>)[1].image = "https://example.com/image.png";
  assert.throws(() => validateOutlineSecurity(outline), hasCode("EXTERNAL_URI_FORBIDDEN"));
});

test("symbolic links in controlled task trees are rejected", async (t) => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "a12-ppt-symlink-"));
  const target = path.join(root, "target.txt");
  const link = path.join(root, "link.txt");
  try {
    await fs.writeFile(target, "target", "utf8");
    try {
      await fs.symlink(target, link, "file");
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code === "EPERM") {
        t.skip("Windows developer mode does not allow symlink creation; Docker/Linux test covers it");
        return;
      }
      throw error;
    }
    await assert.rejects(() => assertNoSymlinks(root), hasCode("SYMLINK_FORBIDDEN"));
  } finally {
    await fs.rm(root, { recursive: true, force: true });
  }
});

test("preset command injection is rejected without spawning", async () => {
  await withRunner(async ({ runner, calls }) => {
    const outline = await readFixture();
    await assert.rejects(
      () => runner.generate({ outline, stylePreset: "forest-research; whoami" }),
      hasCode("UNSUPPORTED_PRESET")
    );
    assert.equal(calls.length, 0);
  });
});

test("generated-image field is forbidden in Phase 1", async () => {
  const outline = await readFixture();
  (outline.slides as Array<Record<string, unknown>>)[1].generated_image = { prompt: "forbidden" };
  assert.throws(() => validateOutlineSecurity(outline), hasCode("GENERATED_IMAGE_FORBIDDEN"));
});

interface MockOptions { buildExitCode?: number; qaExitCode?: number; invalidPptx?: boolean }

function mockExecutor(options: MockOptions = {}): { executor: CommandExecutor; calls: CommandSpec[] } {
  const calls: CommandSpec[] = [];
  const executor: CommandExecutor = async (spec) => {
    calls.push(spec);
    if (spec.args[0].endsWith("build_deck_pptxgenjs.js")) {
      if (options.buildExitCode) return { exitCode: options.buildExitCode, stdout: "", stderr: "fixture build failure" };
      const output = spec.args[spec.args.indexOf("--output") + 1];
      await fs.writeFile(output, options.invalidPptx ? "not-a-pptx" : minimalPptx());
      return { exitCode: 0, stdout: "built", stderr: "" };
    }
    if (spec.args[0].endsWith("qa_gate.py")) {
      if (options.qaExitCode) return { exitCode: options.qaExitCode, stdout: "", stderr: "fixture QA failure" };
      const report = spec.args[spec.args.indexOf("--report") + 1];
      await fs.mkdir(path.dirname(report), { recursive: true });
      await fs.writeFile(report, JSON.stringify({ ok: true, geometry_error_count: 0 }), "utf8");
      return { exitCode: 0, stdout: "qa passed", stderr: "" };
    }
    return { exitCode: 99, stdout: "", stderr: "unexpected command" };
  };
  return { executor, calls };
}

function minimalPptx(): Buffer {
  const names = ["[Content_Types].xml", "ppt/presentation.xml"];
  const locals: Buffer[] = [];
  const centrals: Buffer[] = [];
  let offset = 0;
  for (const name of names) {
    const nameBuffer = Buffer.from(name, "utf8");
    const local = Buffer.alloc(30 + nameBuffer.length);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(nameBuffer.length, 26);
    nameBuffer.copy(local, 30);
    locals.push(local);
    const central = Buffer.alloc(46 + nameBuffer.length);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(20, 4);
    central.writeUInt16LE(20, 6);
    central.writeUInt16LE(nameBuffer.length, 28);
    central.writeUInt32LE(offset, 42);
    nameBuffer.copy(central, 46);
    centrals.push(central);
    offset += local.length;
  }
  const centralSize = centrals.reduce((sum, item) => sum + item.length, 0);
  const footer = Buffer.alloc(22);
  footer.writeUInt32LE(0x06054b50, 0);
  footer.writeUInt16LE(names.length, 8);
  footer.writeUInt16LE(names.length, 10);
  footer.writeUInt32LE(centralSize, 12);
  footer.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, ...centrals, footer]);
}

async function withRunner(run: (context: {
  runner: PresentationRunner;
  config: RunnerConfig;
  tempRoot: string;
  resultRoot: string;
  calls: CommandSpec[];
}) => Promise<void>): Promise<void> {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "a12-ppt-runner-test-"));
  const tempRoot = path.join(root, "temp");
  const resultRoot = path.join(root, "results");
  const config: RunnerConfig = {
    port: 0,
    host: "127.0.0.1",
    skillHome: path.join(root, "skill"),
    tempRoot,
    resultRoot,
    pythonCommand: "python3",
    nodeCommand: process.execPath,
    timeoutMs: 10_000,
    defaultPreset: "forest-research"
  };
  const mocked = mockExecutor();
  try {
    await run({ runner: new PresentationRunner(config, mocked.executor), config, tempRoot, resultRoot, calls: mocked.calls });
  } finally {
    await fs.rm(root, { recursive: true, force: true });
  }
}

async function readFixture(): Promise<Record<string, unknown>> {
  return JSON.parse(await fs.readFile(fixturePath, "utf8")) as Record<string, unknown>;
}

function hasCode(code: string) {
  return (error: unknown) => error instanceof RunnerError && error.code === code;
}
