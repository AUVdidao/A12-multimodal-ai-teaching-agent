ALTER TABLE template_profile_versions
    ADD COLUMN IF NOT EXISTS analysis_run_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS analyzer_input_sha256 VARCHAR(64),
    ADD COLUMN IF NOT EXISTS analyzer_output_sha256 VARCHAR(64),
    ADD COLUMN IF NOT EXISTS rendered_output_sha256 VARCHAR(64),
    ADD COLUMN IF NOT EXISTS rendered_output_size_bytes BIGINT,
    ADD COLUMN IF NOT EXISTS analyzer_proposal_json TEXT;
