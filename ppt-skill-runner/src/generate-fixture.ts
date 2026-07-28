import fs from "node:fs/promises";
import path from "node:path";
import { loadConfig } from "./config";
import { RunnerError } from "./errors";
import { PresentationRunner } from "./runner";

async function main(): Promise<void> {
  const runnerRoot = path.resolve(__dirname, "..", "..");
  const fixturePath = path.join(runnerRoot, "fixtures", "grade-8-biology-photosynthesis-outline.json");
  const outline = JSON.parse(await fs.readFile(fixturePath, "utf8")) as Record<string, unknown>;
  const result = await new PresentationRunner(loadConfig()).generate({ outline, stylePreset: "forest-research" });
  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
}

main().catch((error) => {
  if (error instanceof RunnerError && error.details) {
    process.stderr.write(`${JSON.stringify(error.details, null, 2)}\n`);
  }
  process.stderr.write(`${error instanceof Error ? error.stack ?? error.message : String(error)}\n`);
  process.exit(1);
});
