import crypto from "node:crypto";
import { execFileSync } from "node:child_process";
import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { OBJECTIVE_CODES, SUBJECTIVE_CODES, candidateSystemPrompt } from "./visual-qa-precision-experiment.js";
import { VISUAL_ISSUE_CODES } from "../src/visual-qa.js";

const RESPONSE_SCHEMA = {
  type: "object",
  additionalProperties: false,
  required: ["slideNumber", "issues"],
  properties: {
    slideNumber: { type: "integer", minimum: 1 },
    issues: {
      type: "array",
      items: {
        type: "object",
        additionalProperties: false,
        required: ["slideNumber", "code", "severity", "confidence", "description", "repairHint"],
        properties: {
          slideNumber: { type: "integer", minimum: 1 },
          code: { type: "string", enum: VISUAL_ISSUE_CODES },
          severity: { type: "string", enum: ["INFO", "WARNING", "ERROR"] },
          confidence: { type: "number", minimum: 0, maximum: 1 },
          description: { type: "string" },
          repairHint: { type: "string" },
        },
      },
    },
  },
} as const;

const USER_PROMPT_TEMPLATE = {
  parts: [
    {
      type: "text",
      shape: {
        slideNumber: "number",
        expectedVisualContext: "the SlideSpec visual context without Gold labels",
        outputShape: "one slideNumber plus issues array matching the frozen response schema",
        schemaRepair: "present only for the one allowed repair request",
      },
    },
    { type: "image_url", value: "data:image/png;base64,<holdout PNG bytes>" },
  ],
  repair: "The previous response failed local schema validation; return one corrected JSON object only.",
};

function canonical(value: unknown): string {
  return JSON.stringify(value);
}

function hash(value: unknown): string {
  return crypto.createHash("sha256").update(canonical(value)).digest("hex");
}

async function main(): Promise<void> {
  const testDir = path.dirname(fileURLToPath(import.meta.url));
  const outputPath = process.env.PPT_VISUAL_QA_FROZEN_PROMPT || path.join(testDir, "fixtures", "visual-qa-holdout", "candidate-a-frozen.json");
  const prompt = {
    review: candidateSystemPrompt("A", "REVIEW"),
    repair: candidateSystemPrompt("A", "REPAIR"),
  };
  const taxonomy = {
    objective: OBJECTIVE_CODES,
    subjective: SUBJECTIVE_CODES,
    all: VISUAL_ISSUE_CODES,
  };
  const promptMaterial = { prompt, userPromptTemplate: USER_PROMPT_TEMPLATE, responseSchema: RESPONSE_SCHEMA, taxonomy };
  const policy = {
    objectiveThreshold: 0.90,
    subjectiveThreshold: 0.95,
    actionability: "confidence below the code-group threshold is IGNORED_FOR_GATE; otherwise objective ERROR is ACTIONABLE and all other findings are REVIEW_ONLY",
    automaticRepairEligible: false,
  };
  const sourceCommit = process.env.PPT_VISUAL_QA_SOURCE_COMMIT?.trim() || execFileSync("git", ["rev-parse", "HEAD"], { encoding: "utf8" }).trim();
  const frozen = {
    version: 2,
    variant: "CANDIDATE_A",
    frozenAt: new Date().toISOString(),
    sourceCommit,
    model: process.env.KIMI_MODEL?.trim() || "kimi-k2.6",
    prompt,
    userPromptTemplate: USER_PROMPT_TEMPLATE,
    responseSchema: RESPONSE_SCHEMA,
    taxonomy,
    policy,
    promptHash: hash(promptMaterial),
    policyHash: hash(policy),
  };
  await fs.mkdir(path.dirname(outputPath), { recursive: true });
  await fs.writeFile(outputPath, `${JSON.stringify(frozen, null, 2)}\n`, "utf8");
  process.stdout.write(`${JSON.stringify({ outputPath, sourceCommit, promptHash: frozen.promptHash, policyHash: frozen.policyHash }, null, 2)}\n`);
}

main().catch(error => {
  process.stderr.write(`${error instanceof Error ? error.stack ?? error.message : String(error)}\n`);
  process.exitCode = 1;
});
