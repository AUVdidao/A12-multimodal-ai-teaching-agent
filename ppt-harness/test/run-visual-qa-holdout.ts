import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { loadConfig } from "../src/config.js";
import { runHoldoutEvaluation } from "./visual-qa-holdout.js";

async function main(): Promise<void> {
  const testDir = path.dirname(fileURLToPath(import.meta.url));
  const fixtureDir = process.env.PPT_VISUAL_QA_HOLDOUT_DIR || path.join(testDir, "fixtures", "visual-qa-holdout");
  const outputDir = process.env.PPT_VISUAL_QA_HOLDOUT_REPORT_DIR || fixtureDir;
  const result = await runHoldoutEvaluation({
    fixtureDir,
    goldSetPath: path.join(fixtureDir, "holdout-gold-set.json"),
    integrityPath: path.join(fixtureDir, "holdout-integrity-report.json"),
    frozenPath: path.join(fixtureDir, "candidate-a-frozen.json"),
    outputDir,
    config: loadConfig(),
  });
  const generalization = result.report.generalization as { conclusion: string } | null | undefined;
  process.stdout.write(`${JSON.stringify({ reportPath: path.join(outputDir, "holdout-evaluation-report.json"), evaluationStatus: result.report.evaluationStatus, generalization: generalization?.conclusion ?? "NOT_EVALUATED_PROVIDER_BLOCKED", production: (result.report.production as { recommendation: string }).recommendation, baseline: result.baseline.metrics, candidateA: result.candidateA.metrics, requests: (result.report.requestBudget as Record<string, unknown>) }, null, 2)}\n`);
}

main().catch(error => {
  process.stderr.write(`${error instanceof Error ? error.stack ?? error.message : String(error)}\n`);
  process.exitCode = 1;
});
