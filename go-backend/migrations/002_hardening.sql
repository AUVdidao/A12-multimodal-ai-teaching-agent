CREATE UNIQUE INDEX IF NOT EXISTS locked_specifications_source_draft_uq
    ON locked_specifications(source_draft_id);
CREATE UNIQUE INDEX IF NOT EXISTS generation_jobs_specification_uq
    ON generation_jobs(specification_id);

ALTER TABLE generation_jobs
    ADD COLUMN IF NOT EXISTS cancel_requested_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS mission_files_parse_idx
    ON mission_files(mission_id, parse_status, role);
