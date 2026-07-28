ALTER TABLE ppt_harness.job
  ADD COLUMN IF NOT EXISTS current_step VARCHAR(64),
  ADD COLUMN IF NOT EXISTS progress_percent INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS attempt_count INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS completed_at TIMESTAMPTZ;

ALTER TABLE ppt_harness.step
  ADD COLUMN IF NOT EXISTS progress_percent INTEGER NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS ppt_harness_checkpoint_latest_idx
  ON ppt_harness.checkpoint(job_id, checkpoint_type, id DESC);
