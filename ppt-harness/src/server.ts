import Fastify from "fastify";
import { createHarnessApplication } from "./app.js";
import { HarnessError, PresentationJob, PresentationJobRequest } from "./domain.js";
import { toPublicQaReport } from "./qa-report.js";
import { TemplateRegistry } from "./template-registry.js";

export async function buildServer() {
  const app = Fastify({ logger: true, bodyLimit: 1024 * 1024 });
  const harness = await createHarnessApplication();
  const templates = new TemplateRegistry();
  app.addHook("onClose", async () => harness.close());
  app.get("/health", async () => ({ status: "UP", service: "a12-ppt-harness", generationSource: harness.config.generationSource, visualReviewEnabled: harness.config.visualReviewEnabled }));
  app.get("/api/v1/presentation-templates", async () => ({ templates: await templates.list() }));
  app.get<{ Params: { templateId: string }; Querystring: { version?: string } }>("/api/v1/presentation-templates/:templateId", async request => ({ template: await templates.get(request.params.templateId, request.query.version || "1.0.0") }));
  app.post("/api/v1/presentation-jobs", async (request, reply) => {
    const job = await harness.workflow.submit(parseRequest(request.body));
    return reply.code(202).send(publicJob(job));
  });
  app.get<{ Params: { taskId: string } }>("/api/v1/presentation-jobs/:taskId", async request => {
    const job = await harness.workflow.get(request.params.taskId);
    if (!job) throw new HarnessError("TASK_NOT_FOUND", "Presentation task was not found", 404);
    return publicJob(job);
  });
  app.get<{ Params: { taskId: string } }>("/api/v1/presentation-jobs/:taskId/events", async (request, reply) => {
    const job = await harness.workflow.get(request.params.taskId);
    if (!job) throw new HarnessError("TASK_NOT_FOUND", "Presentation task was not found", 404);
    reply.hijack(); reply.raw.setHeader("Content-Type", "text/event-stream"); reply.raw.setHeader("Cache-Control", "no-cache"); reply.raw.setHeader("Connection", "keep-alive");
    let lastId = Number(request.headers["last-event-id"] || 0);
    let disconnected = false;
    request.raw.on("close", () => { disconnected = true; });
    while (!disconnected) {
      for (const event of await harness.workflow.events(job.id, lastId)) {
        lastId = event.id;
        reply.raw.write(`id: ${event.id}\nevent: status\ndata: ${JSON.stringify(event)}\n\n`);
      }
      const current = await harness.workflow.get(job.id);
      if (!current || ["SUCCEEDED", "FAILED", "CANCELLED"].includes(current.status)) {
        reply.raw.write(`event: end\ndata: ${JSON.stringify({ taskId: job.id, status: current?.status })}\n\n`);
        reply.raw.end();
        break;
      }
      await new Promise(resolve => setTimeout(resolve, harness.config.eventPollIntervalMs));
    }
  });
  app.get<{ Params: { taskId: string } }>("/api/v1/presentation-jobs/:taskId/qa-report", async request => {
    const job = await harness.workflow.get(request.params.taskId);
    if (!job) throw new HarnessError("TASK_NOT_FOUND", "Presentation task was not found", 404);
    const qa = await harness.workflow.qaReport(job.id);
    if (!qa) throw new HarnessError("QA_REPORT_NOT_READY", "Presentation QA report is not ready", 404);
    return { taskId: job.id, ...toPublicQaReport(qa.qaLevel, qa.passed, qa.report) };
  });
  app.get<{ Params: { taskId: string } }>("/api/v1/presentation-jobs/:taskId/artifact", async (request, reply) => {
    const content = await harness.workflow.artifact(request.params.taskId);
    return reply.type("application/vnd.openxmlformats-officedocument.presentationml.presentation").header("Content-Disposition", "attachment; filename=teaching-presentation.pptx").send(Buffer.from(content));
  });
  app.post<{ Params: { taskId: string } }>("/api/v1/presentation-jobs/:taskId/cancel", async request => {
    const job = await harness.workflow.cancel(request.params.taskId);
    if (!job) throw new HarnessError("TASK_NOT_FOUND", "Presentation task was not found", 404);
    return publicJob(job);
  });
  app.setErrorHandler((error, _request, reply) => {
    const safe = error instanceof HarnessError ? error : new HarnessError("HARNESS_INTERNAL_ERROR", "Presentation harness request failed", 500);
    reply.code(safe.statusCode).send({ code: safe.code, message: safe.message });
  });
  return { app, harness };
}

function parseRequest(value: unknown): PresentationJobRequest {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new HarnessError("INVALID_REQUEST", "Presentation task request must be an object", 400);
  const body = value as Record<string, unknown>;
  const requestId = requiredString(body.requestId, "requestId", 128);
  const templateId = requiredString(body.templateId, "templateId", 128);
  const templateVersion = requiredString(body.templateVersion, "templateVersion", 64);
  const locale = requiredString(body.locale, "locale", 32);
  const projectId = Number(body.projectId); const targetSlideCount = Number(body.targetSlideCount);
  if (!Number.isSafeInteger(projectId) || projectId <= 0 || !Number.isSafeInteger(targetSlideCount) || targetSlideCount < 3 || targetSlideCount > 30) throw new HarnessError("INVALID_REQUEST", "projectId or targetSlideCount is invalid", 400);
  if (!body.requirementSnapshot || typeof body.requirementSnapshot !== "object" || Array.isArray(body.requirementSnapshot)) throw new HarnessError("INVALID_REQUEST", "requirementSnapshot is required", 400);
  return { requestId, projectId, templateId, templateVersion, locale, targetSlideCount, requirementSnapshot: body.requirementSnapshot as Record<string, unknown> };
}
function requiredString(value: unknown, label: string, max: number): string { if (typeof value !== "string" || !value.trim() || value.length > max) throw new HarnessError("INVALID_REQUEST", `${label} is invalid`, 400); return value.trim(); }
function publicJob(job: PresentationJob | undefined) {
  if (!job) return undefined;
  const statusUrl = `/api/v1/presentation-jobs/${job.id}`;
  const eventsUrl = `${statusUrl}/events`;
  const qaReportUrl = `${statusUrl}/qa-report`;
  const artifactUrl = `${statusUrl}/artifact`;
  return {
    taskId: job.id, requestId: job.requestId, projectId: job.projectId, status: job.status, currentStep: job.currentStep,
    progressPercent: job.progressPercent, attemptCount: job.attemptCount, template: { id: job.templateId, version: job.templateVersion },
    // Flat URL fields are retained for Spring Boot's typed client. The nested
    // object is for future MCP/REST clients and represents the same resources.
    statusUrl, eventsUrl,
    urls: { status: statusUrl, events: eventsUrl, qaReport: qaReportUrl },
    artifact: job.artifact ? { ...job.artifact, downloadRef: artifactUrl, downloadUrl: artifactUrl } : undefined,
    qa: job.artifact ? { passed: job.artifact.qaPassed, qaLevel: job.artifact.qaLevel, warnings: [] } : undefined,
    error: job.errorCode ? { code: job.errorCode, message: job.errorMessage } : undefined, createdAt: job.createdAt, updatedAt: job.updatedAt, completedAt: job.completedAt
  };
}

if (import.meta.url === `file://${process.argv[1]?.replace(/\\/g, "/")}`) {
  buildServer().then(({ app, harness }) => app.listen({ host: harness.config.host, port: harness.config.port })).catch(error => { console.error(error); process.exit(1); });
}
