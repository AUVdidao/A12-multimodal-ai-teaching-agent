import crypto from "node:crypto";
import { ControlledArtifactStore } from "./artifact-store.js";
import { HarnessError, PresentationJob, PresentationJobRequest, SlideSpec } from "./domain.js";
import { PgJobRepository } from "./repository.js";
import { toRunnerOutline } from "./runner-outline-adapter.js";
import { PptSkillRunnerClient } from "./runner-client.js";
import { selectSlideSpecProvider, SlideSpecProvider } from "./slide-spec-provider.js";
import { validateSlideSpec } from "./slide-spec.js";
import { TemplateRegistry } from "./template-registry.js";
import { HarnessConfig } from "./config.js";

export class PresentationWorkflowService {
  private readonly provider: SlideSpecProvider;
  constructor(
    private readonly config: HarnessConfig,
    private readonly repository: PgJobRepository,
    private readonly templates: TemplateRegistry,
    private readonly runner: PptSkillRunnerClient,
    private readonly artifacts: ControlledArtifactStore,
  ) { this.provider = selectSlideSpecProvider(config); }

  async submit(input: PresentationJobRequest): Promise<PresentationJob> {
    const existing = await this.repository.findByRequestId(input.requestId);
    if (existing) return existing;
    await this.templates.get(input.templateId, input.templateVersion);
    const job = await this.repository.create(crypto.randomUUID(), input);
    void this.process(job.id);
    return job;
  }

  async process(jobId: string): Promise<void> {
    const job = await this.repository.get(jobId);
    if (!job || ["CANCELLED", "SUCCEEDED", "FAILED"].includes(job.status)) return;
    try {
      await this.repository.incrementAttempt(job.id);
      await this.transition(job.id, "LOADING_REQUIREMENT", "Loading immutable requirement snapshot", 5);
      await this.transition(job.id, "LOADING_TEMPLATE", "Loading selected template version", 10);
      const template = await this.templates.get(job.templateId, job.templateVersion);
      await this.transition(job.id, "BUILDING_TEMPLATE_CONTEXT", "Building template context from the selected version", 16);
      await this.repository.saveCheckpoint(job.id, "TEMPLATE", { templateId: template.templateId, version: template.version });
      await this.ensureActive(job.id);

      const checkpointSpec = await this.repository.latestCheckpoint(job.id, "VALIDATED_SLIDE_SPEC");
      let spec: SlideSpec;
      if (checkpointSpec) {
        spec = checkpointSpec as unknown as SlideSpec;
        await this.transition(job.id, "VALIDATING_SLIDE_SPEC", "Reusing the most recent validated SlideSpec checkpoint", 35);
      } else {
        await this.transition(job.id, "GENERATING_SLIDE_SPEC", "Generating structured SlideSpec", 25);
        spec = await this.provider.create(job, template);
        await this.repository.saveCheckpoint(job.id, "SLIDE_SPEC", spec as unknown as Record<string, unknown>);
        await this.transition(job.id, "VALIDATING_SLIDE_SPEC", "Validating template layout and content capacity", 35);
        try {
          validateSlideSpec(spec, template, job.targetSlideCount);
        } catch (error) {
          if (this.config.maxRepairAttempts > 0 && this.config.generationSource === "KIMI" && this.provider.repair) {
            await this.transition(job.id, "REPAIRING_SLIDE_SPEC", "Requesting one schema-constrained SlideSpec repair", 31);
            spec = await this.provider.repair(job, template, spec, error instanceof Error ? error.message : "SlideSpec validation failed");
            validateSlideSpec(spec, template, job.targetSlideCount);
          } else {
            throw error;
          }
        }
        await this.repository.saveCheckpoint(job.id, "VALIDATED_SLIDE_SPEC", spec as unknown as Record<string, unknown>);
      }
      await this.ensureActive(job.id);

      await this.transition(job.id, "RENDERING_PPTX", "Rendering native editable PPTX through the controlled runner", 50);
      const runnerResult = await this.runner.generate(toRunnerOutline(spec), template.stylePreset);
      if (runnerResult.status !== "SUCCEEDED") throw new HarnessError("PPT_BUILD_FAILED", "PPT runner did not complete the task", 502);
      await this.repository.saveCheckpoint(job.id, "RUNNER", { runnerJobId: runnerResult.jobId, totalDurationMs: runnerResult.totalDurationMs });
      await this.ensureActive(job.id);

      // The current runner profile returns deterministic QA only. This state preserves the explicit preview boundary
      // without claiming visual review has occurred.
      await this.transition(job.id, "RENDERING_PREVIEW", "Preparing preview handoff metadata; page preview rendering is not enabled in this runner profile", 66);
      await this.transition(job.id, "RUNNING_DETERMINISTIC_QA", "Verifying deterministic geometry QA", 75);
      if (!runnerResult.qa?.passed) throw new HarnessError("PPT_QA_FAILED", "PPT runner quality gate did not pass", 422);
      if (runnerResult.qa.qaLevel !== "AUTOMATED_GEOMETRY_ONLY") throw new HarnessError("UNSUPPORTED_QA_LEVEL", "Runner returned an unsupported QA level", 422);
      await this.repository.saveQaReport(job.id, runnerResult.qa.qaLevel, true, {
        ...runnerResult.qa.report,
        visualReviewImplemented: false,
        previewRenderingImplemented: false,
        retentionDays: this.config.artifactRetentionDays,
      });
      await this.ensureActive(job.id);

      if (this.config.visualReviewEnabled) {
        await this.transition(job.id, "VISUAL_REVIEW", "Visual review is configured but no implementation is available", 82);
        throw new HarnessError("VISUAL_REVIEW_UNAVAILABLE", "Visual review is enabled but not implemented in this harness version", 503);
      }

      await this.transition(job.id, "FINALIZING", "Saving controlled presentation artifact", 88);
      const presentation = await this.runner.download(runnerResult.files.presentation);
      const saved = await this.artifacts.save(job.id, "presentation.pptx", presentation);
      if (saved.sizeBytes !== runnerResult.sizeBytes) throw new HarnessError("PPT_SIZE_MISMATCH", "Runner file size verification failed", 502);
      if (saved.sha256 !== runnerResult.sha256) throw new HarnessError("PPT_HASH_MISMATCH", "Runner file hash verification failed", 502);
      await this.repository.setArtifact(job.id, {
        fileName: "presentation.pptx", sizeBytes: saved.sizeBytes, sha256: saved.sha256,
        qaLevel: runnerResult.qa.qaLevel, qaPassed: true, runnerJobId: runnerResult.jobId,
        downloadRef: `/api/v1/presentation-jobs/${job.id}/artifact`
      });
      await this.transition(job.id, "SUCCEEDED", "Presentation is ready for Spring Boot artifact handoff", 100);
    } catch (error) {
      if ((await this.repository.get(jobId))?.status === "CANCELLED") return;
      const safe = error instanceof HarnessError ? error : new HarnessError("HARNESS_INTERNAL_ERROR", "Presentation generation did not complete", 500);
      await this.transition(jobId, "FAILED", safe.message, 100, { code: safe.code, message: safe.message });
    }
  }

  async get(id: string): Promise<PresentationJob | undefined> { return this.repository.get(id); }
  async events(id: string, afterId = 0) { return this.repository.listEvents(id, afterId); }
  async qaReport(id: string) { return this.repository.getQaReport(id); }
  async artifact(id: string): Promise<Uint8Array> {
    const job = await this.repository.get(id);
    if (!job?.artifact || job.status !== "SUCCEEDED") throw new HarnessError("ARTIFACT_NOT_READY", "Presentation artifact is not ready", 404);
    return this.artifacts.read(id);
  }
  async cancel(id: string): Promise<PresentationJob | undefined> {
    const job = await this.repository.get(id);
    if (!job || ["SUCCEEDED", "FAILED", "CANCELLED"].includes(job.status)) return job;
    await this.transition(id, "CANCELLED", "Task cancelled by caller", job.progressPercent);
    return this.repository.get(id);
  }
  async resumeRecoverable(): Promise<void> {
    for (const job of await this.repository.recoverableJobs()) {
      await this.transition(job.id, "RETRY_PENDING", "Resuming from the most recent safe checkpoint after harness restart", job.progressPercent);
      void this.process(job.id);
    }
  }
  private async transition(id: string, status: Parameters<PgJobRepository["updateStatus"]>[1], message: string, progress: number, error?: { code: string; message: string }): Promise<void> {
    await this.repository.updateStatus(id, status, message, progress, error);
  }
  private async ensureActive(id: string): Promise<void> {
    if ((await this.repository.get(id))?.status === "CANCELLED") throw new HarnessError("TASK_CANCELLED", "Presentation task was cancelled", 409);
  }
}
