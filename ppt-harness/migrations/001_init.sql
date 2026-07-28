CREATE SCHEMA IF NOT EXISTS ppt_harness;

CREATE TABLE IF NOT EXISTS ppt_harness.job (
  id UUID PRIMARY KEY,
  request_id VARCHAR(128) NOT NULL UNIQUE,
  project_id BIGINT NOT NULL,
  status VARCHAR(64) NOT NULL,
  template_id VARCHAR(128) NOT NULL,
  template_version VARCHAR(64) NOT NULL,
  locale VARCHAR(32) NOT NULL,
  target_slide_count INTEGER NOT NULL,
  requirement_snapshot JSONB NOT NULL,
  artifact_ref JSONB,
  error_code VARCHAR(96),
  error_message VARCHAR(512),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS ppt_harness.step (
  id BIGSERIAL PRIMARY KEY,
  job_id UUID NOT NULL REFERENCES ppt_harness.job(id) ON DELETE CASCADE,
  status VARCHAR(64) NOT NULL,
  message VARCHAR(512) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS ppt_harness.checkpoint (
  id BIGSERIAL PRIMARY KEY,
  job_id UUID NOT NULL REFERENCES ppt_harness.job(id) ON DELETE CASCADE,
  checkpoint_type VARCHAR(64) NOT NULL,
  payload JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS ppt_harness.qa_report (
  id BIGSERIAL PRIMARY KEY,
  job_id UUID NOT NULL UNIQUE REFERENCES ppt_harness.job(id) ON DELETE CASCADE,
  qa_level VARCHAR(64) NOT NULL,
  passed BOOLEAN NOT NULL,
  report JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ppt_harness_job_status_idx ON ppt_harness.job(status, updated_at DESC);
CREATE INDEX IF NOT EXISTS ppt_harness_step_job_idx ON ppt_harness.step(job_id, id);
