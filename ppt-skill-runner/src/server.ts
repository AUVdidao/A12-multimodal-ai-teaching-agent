import fs from "node:fs";
import path from "node:path";
import Fastify from "fastify";
import { loadConfig } from "./config";
import { RunnerError } from "./errors";
import { PresentationRunner } from "./runner";

export function buildServer() {
  const config = loadConfig();
  const runner = new PresentationRunner(config);
  const app = Fastify({ logger: true, bodyLimit: 2 * 1024 * 1024 });

  app.get("/health", async () => ({ status: "UP", service: "a12-ppt-skill-runner", qaLevel: "AUTOMATED_GEOMETRY_ONLY" }));

  app.post("/internal/ppt-skill/v1/generations", async (request, reply) => {
    const result = await runner.generate(request.body as { outline: Record<string, unknown>; stylePreset?: string });
    return reply.code(201).send(result);
  });

  app.get<{ Params: { jobId: string; fileName: string } }>("/internal/ppt-skill/v1/jobs/:jobId/:fileName", async (request, reply) => {
    const filePath = await runner.resolveResultFile(request.params.jobId, request.params.fileName);
    const contentType = path.extname(filePath) === ".pptx"
      ? "application/vnd.openxmlformats-officedocument.presentationml.presentation"
      : "application/json; charset=utf-8";
    return reply.type(contentType).header("Content-Disposition", `attachment; filename=\"${path.basename(filePath)}\"`).send(fs.createReadStream(filePath));
  });

  app.setErrorHandler((error, _request, reply) => {
    if (error instanceof RunnerError) {
      return reply.code(error.statusCode).send({ code: error.code, message: error.message, details: error.details });
    }
    requestLogSafe(app, error);
    return reply.code(500).send({ code: "INTERNAL_ERROR", message: "Runner execution failed" });
  });
  return { app, config };
}

function requestLogSafe(app: ReturnType<typeof Fastify>, error: unknown): void {
  app.log.error({ err: error }, "Unhandled runner error");
}

if (require.main === module) {
  const { app, config } = buildServer();
  app.listen({ host: config.host, port: config.port }).catch((error) => {
    app.log.error(error);
    process.exit(1);
  });
}
