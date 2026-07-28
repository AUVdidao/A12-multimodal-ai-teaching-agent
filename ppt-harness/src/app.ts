import { Pool } from "pg";
import { ControlledArtifactStore } from "./artifact-store.js";
import { HarnessConfig, loadConfig } from "./config.js";
import { PgJobRepository } from "./repository.js";
import { PptSkillRunnerClient } from "./runner-client.js";
import { TemplateRegistry } from "./template-registry.js";
import { PresentationWorkflowService } from "./workflow.js";

export type HarnessApplication = { config: HarnessConfig; repository: PgJobRepository; workflow: PresentationWorkflowService; close: () => Promise<void> };

export async function createHarnessApplication(config = loadConfig()): Promise<HarnessApplication> {
  const pool = new Pool({ connectionString: config.databaseUrl, max: 8 });
  const repository = new PgJobRepository(pool);
  await repository.initialize();
  const workflow = new PresentationWorkflowService(config, repository, new TemplateRegistry(), new PptSkillRunnerClient(config), new ControlledArtifactStore());
  await workflow.resumeRecoverable();
  return { config, repository, workflow, close: () => repository.close() };
}
