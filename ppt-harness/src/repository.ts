import { Pool } from "pg";
import { JobArtifact, JobEvent, JobStatus, PresentationJob, PresentationJobRequest } from "./domain.js";

type JobRow = {
  id: string; request_id: string; project_id: string; status: JobStatus; current_step: JobStatus | null;
  progress_percent: number; attempt_count: number; template_id: string; template_version: string;
  locale: string; target_slide_count: number; requirement_snapshot: Record<string, unknown>; artifact_ref: JobArtifact | null;
  error_code: string | null; error_message: string | null; created_at: Date; updated_at: Date; completed_at: Date | null;
};

export class PgJobRepository {
  constructor(private readonly pool: Pool) {}

  async initialize(): Promise<void> {
    await this.pool.query("SELECT 1 FROM ppt_harness.job LIMIT 1");
    // Existing local volumes do not replay docker-entrypoint init scripts. Keep the idempotent migration here.
    await this.pool.query("ALTER TABLE ppt_harness.job ADD COLUMN IF NOT EXISTS current_step VARCHAR(64), ADD COLUMN IF NOT EXISTS progress_percent INTEGER NOT NULL DEFAULT 0, ADD COLUMN IF NOT EXISTS attempt_count INTEGER NOT NULL DEFAULT 0, ADD COLUMN IF NOT EXISTS completed_at TIMESTAMPTZ");
    await this.pool.query("ALTER TABLE ppt_harness.step ADD COLUMN IF NOT EXISTS progress_percent INTEGER NOT NULL DEFAULT 0");
    await this.pool.query("CREATE INDEX IF NOT EXISTS ppt_harness_checkpoint_latest_idx ON ppt_harness.checkpoint(job_id, checkpoint_type, id DESC)");
  }

  async create(id: string, input: PresentationJobRequest): Promise<PresentationJob> {
    const result = await this.pool.query<JobRow>(
      `INSERT INTO ppt_harness.job (id, request_id, project_id, status, current_step, progress_percent, template_id, template_version, locale, target_slide_count, requirement_snapshot)
       VALUES ($1, $2, $3, 'QUEUED', 'QUEUED', 0, $4, $5, $6, $7, $8::jsonb) RETURNING *`,
      [id, input.requestId, input.projectId, input.templateId, input.templateVersion, input.locale, input.targetSlideCount, JSON.stringify(input.requirementSnapshot)]
    );
    await this.appendEvent(id, "QUEUED", "Task accepted and queued", 0);
    return mapJob(result.rows[0]);
  }

  async findByRequestId(requestId: string): Promise<PresentationJob | undefined> {
    const result = await this.pool.query<JobRow>("SELECT * FROM ppt_harness.job WHERE request_id = $1", [requestId]);
    return result.rows[0] ? mapJob(result.rows[0]) : undefined;
  }

  async get(id: string): Promise<PresentationJob | undefined> {
    const result = await this.pool.query<JobRow>("SELECT * FROM ppt_harness.job WHERE id = $1", [id]);
    return result.rows[0] ? mapJob(result.rows[0]) : undefined;
  }

  async listEvents(id: string, afterId = 0): Promise<JobEvent[]> {
    const result = await this.pool.query<{ id: number; status: JobStatus; message: string; progress_percent: number; created_at: Date }>(
      "SELECT id, status, message, progress_percent, created_at FROM ppt_harness.step WHERE job_id = $1 AND id > $2 ORDER BY id", [id, afterId]
    );
    return result.rows.map(row => ({ id: row.id, status: row.status, message: row.message, progressPercent: row.progress_percent, createdAt: row.created_at.toISOString() }));
  }

  async updateStatus(id: string, status: JobStatus, message: string, progressPercent: number, error?: { code: string; message: string }): Promise<void> {
    const completed = ["SUCCEEDED", "FAILED", "CANCELLED"].includes(status);
    await this.pool.query(
      `UPDATE ppt_harness.job SET status=$2, current_step=$2, progress_percent=$3, error_code=$4, error_message=$5,
         completed_at=CASE WHEN $6 THEN now() ELSE completed_at END, updated_at=now() WHERE id=$1`,
      [id, status, progressPercent, error?.code ?? null, error?.message ?? null, completed]
    );
    await this.appendEvent(id, status, message, progressPercent);
  }

  async incrementAttempt(id: string): Promise<void> {
    await this.pool.query("UPDATE ppt_harness.job SET attempt_count = attempt_count + 1, updated_at=now() WHERE id=$1", [id]);
  }

  async setArtifact(id: string, artifact: JobArtifact): Promise<void> {
    await this.pool.query("UPDATE ppt_harness.job SET artifact_ref=$2::jsonb, updated_at=now() WHERE id=$1", [id, JSON.stringify(artifact)]);
  }

  async saveCheckpoint(id: string, type: string, payload: Record<string, unknown>): Promise<void> {
    await this.pool.query("INSERT INTO ppt_harness.checkpoint (job_id, checkpoint_type, payload) VALUES ($1, $2, $3::jsonb)", [id, type, JSON.stringify(payload)]);
  }

  async latestCheckpoint(id: string, type: string): Promise<Record<string, unknown> | undefined> {
    const result = await this.pool.query<{ payload: Record<string, unknown> }>(
      "SELECT payload FROM ppt_harness.checkpoint WHERE job_id=$1 AND checkpoint_type=$2 ORDER BY id DESC LIMIT 1", [id, type]
    );
    return result.rows[0]?.payload;
  }

  async saveQaReport(id: string, qaLevel: string, passed: boolean, report: Record<string, unknown>): Promise<void> {
    await this.pool.query(
      `INSERT INTO ppt_harness.qa_report (job_id, qa_level, passed, report) VALUES ($1, $2, $3, $4::jsonb)
       ON CONFLICT (job_id) DO UPDATE SET qa_level=EXCLUDED.qa_level, passed=EXCLUDED.passed, report=EXCLUDED.report, created_at=now()`,
      [id, qaLevel, passed, JSON.stringify(report)]
    );
  }

  async getQaReport(id: string): Promise<{ qaLevel: string; passed: boolean; report: Record<string, unknown> } | undefined> {
    const result = await this.pool.query<{ qa_level: string; passed: boolean; report: Record<string, unknown> }>(
      "SELECT qa_level, passed, report FROM ppt_harness.qa_report WHERE job_id = $1", [id]
    );
    const row = result.rows[0];
    return row ? { qaLevel: row.qa_level, passed: row.passed, report: row.report } : undefined;
  }

  async recoverableJobs(): Promise<PresentationJob[]> {
    const result = await this.pool.query<JobRow>(
      "SELECT * FROM ppt_harness.job WHERE status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED') ORDER BY created_at"
    );
    return result.rows.map(mapJob);
  }

  async appendEvent(id: string, status: JobStatus, message: string, progressPercent: number): Promise<void> {
    await this.pool.query("INSERT INTO ppt_harness.step (job_id, status, message, progress_percent) VALUES ($1, $2, $3, $4)", [id, status, message.slice(0, 512), progressPercent]);
  }

  async close(): Promise<void> { await this.pool.end(); }
}

function mapJob(row: JobRow): PresentationJob {
  return {
    id: row.id, requestId: row.request_id, projectId: Number(row.project_id), status: row.status,
    currentStep: row.current_step ?? undefined, progressPercent: row.progress_percent, attemptCount: row.attempt_count,
    templateId: row.template_id, templateVersion: row.template_version, locale: row.locale,
    targetSlideCount: row.target_slide_count, requirementSnapshot: row.requirement_snapshot,
    artifact: row.artifact_ref ?? undefined, errorCode: row.error_code ?? undefined, errorMessage: row.error_message ?? undefined,
    createdAt: row.created_at.toISOString(), updatedAt: row.updated_at.toISOString(), completedAt: row.completed_at?.toISOString()
  };
}
