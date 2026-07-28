import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";
import { createHarnessApplication } from "./app.js";
import { HarnessError } from "./domain.js";
import { TemplateRegistry } from "./template-registry.js";

async function main(): Promise<void> {
  const harness = await createHarnessApplication();
  const templates = new TemplateRegistry();
  const server = new McpServer({ name: "a12-ppt-harness", version: "0.1.0" });
  server.tool("list_presentation_templates", "List fixed A12 presentation templates", {}, async () => text(await templates.list()));
  server.tool("get_presentation_template", "Get one fixed A12 presentation template", { templateId: z.string().min(1) }, async ({ templateId }) => text({ template: await templates.get(templateId, "1.0.0") }));
  server.tool("generate_teaching_presentation", "Create an asynchronous teaching presentation task", {
    requestId: z.string().min(1).max(128), projectId: z.number().int().positive(), requirementSnapshot: z.record(z.unknown()),
    templateId: z.string().min(1), templateVersion: z.string().min(1), targetSlideCount: z.number().int().min(3).max(30), locale: z.string().min(1)
  }, async input => text({ task: await harness.workflow.submit(input) }));
  server.tool("get_presentation_job_status", "Read safe presentation task status", { taskId: z.string().uuid() }, async ({ taskId }) => {
    const job = await harness.workflow.get(taskId); if (!job) throw new HarnessError("TASK_NOT_FOUND", "Presentation task was not found", 404); return text({ taskId: job.id, status: job.status, artifact: job.artifact, errorCode: job.errorCode, errorMessage: job.errorMessage });
  });
  server.tool("get_presentation_artifact", "Get controlled presentation artifact reference, not binary data", { taskId: z.string().uuid() }, async ({ taskId }) => {
    const job = await harness.workflow.get(taskId); if (!job?.artifact) throw new HarnessError("ARTIFACT_NOT_READY", "Presentation artifact is not ready", 404); return text({ taskId, artifact: job.artifact });
  });
  server.tool("cancel_presentation_job", "Cancel an active presentation task", { taskId: z.string().uuid() }, async ({ taskId }) => text({ task: await harness.workflow.cancel(taskId) }));
  await server.connect(new StdioServerTransport());
}

function text(value: unknown) { return { content: [{ type: "text" as const, text: JSON.stringify(value) }] }; }
main().catch(error => { console.error(error); process.exit(1); });
