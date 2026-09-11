-- Generation is an explicit teacher action, so one Locked Specification may
-- have more than one terminal attempt. Keep the Mission-level active-job
-- fence as the database backstop for concurrent requests and out-of-band
-- writers.
DROP INDEX IF EXISTS generation_jobs_specification_uq;

CREATE INDEX IF NOT EXISTS generation_jobs_mission_idx
    ON generation_jobs(mission_id, created_at DESC);

CREATE UNIQUE INDEX IF NOT EXISTS generation_jobs_active_mission_uq
    ON generation_jobs(mission_id)
    WHERE status IN ('QUEUED', 'RUNNING', 'VERIFYING');
