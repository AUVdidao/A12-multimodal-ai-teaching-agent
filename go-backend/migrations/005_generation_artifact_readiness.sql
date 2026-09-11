ALTER TABLE artifacts
    ADD COLUMN IF NOT EXISTS status TEXT NOT NULL DEFAULT 'READY';

ALTER TABLE artifacts
    DROP CONSTRAINT IF EXISTS artifacts_status_check;

ALTER TABLE artifacts
    ADD CONSTRAINT artifacts_status_check CHECK (status IN ('STAGED', 'READY', 'INVALID'));

ALTER TABLE generation_jobs
    DROP CONSTRAINT IF EXISTS generation_jobs_status_check;

ALTER TABLE generation_jobs
    ADD CONSTRAINT generation_jobs_status_check CHECK (status IN ('QUEUED', 'RUNNING', 'VERIFYING', 'SUCCEEDED', 'FAILED', 'CANCELLED'));

CREATE INDEX IF NOT EXISTS artifacts_readiness_idx
    ON artifacts(status, generation_job_id);
