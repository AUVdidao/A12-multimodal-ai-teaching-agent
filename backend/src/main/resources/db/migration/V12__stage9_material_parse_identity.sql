ALTER TABLE parse_results ADD COLUMN IF NOT EXISTS analysis_run_id VARCHAR(128);
ALTER TABLE parse_results ADD COLUMN IF NOT EXISTS source_version_id BIGINT;
ALTER TABLE parse_results ADD COLUMN IF NOT EXISTS parser_snapshot_checksum VARCHAR(64);

CREATE INDEX IF NOT EXISTS ix_parse_results_material_identity
    ON parse_results (material_id, analysis_run_id, source_version_id);

CREATE UNIQUE INDEX IF NOT EXISTS uk_parse_results_complete_identity
    ON parse_results (material_id, analysis_run_id, source_version_id, parser_snapshot_checksum);
