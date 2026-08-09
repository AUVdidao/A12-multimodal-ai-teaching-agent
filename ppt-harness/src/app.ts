import { Pool } from "pg";
import { ControlledArtifactStore } from "./artifact-store.js";
import { HarnessConfig, loadConfig } from "./config.js";
import { PgJobRepository } from "./repository.js";
import { PptSkillRunnerClient } from "./runner-client.js";
import { TemplateRegistry } from "./template-registry.js";
import { PresentationWorkflowService } from "./workflow.js";
import { KimiVisualQaProvider, VisualQaService } from "./visual-qa.js";

export type HarnessApplication = { config: HarnessConfig; repository: PgJobRepository; workflow: PresentationWorkflowService; close: () => Promise<void> };

export async function createHarnessApplication(config = loadConfig()): Promise<HarnessApplication> {
  const pool = new Pool({ connectionString: config.databaseUrl, max: 8 });
  const repository = new PgJobRepository(pool);
  await repository.initialize();
  const runner = new PptSkillRunnerClient(config);
  const workflow = new PresentationWorkflowService(config, repository, new TemplateRegistry(), runner, new ControlledArtifactStore(), new VisualQaService(runner, new KimiVisualQaProvider(config)));
  await workflow.resumeRecoverable();
  return { config, repository, workflow, close: () => repository.close() };
}
