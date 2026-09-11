CREATE TABLE IF NOT EXISTS mission_file_parse_results (
    id BIGSERIAL PRIMARY KEY,
    mission_file_id BIGINT NOT NULL UNIQUE REFERENCES mission_files(id) ON DELETE CASCADE,
    summary TEXT NOT NULL,
    keywords_json JSONB NOT NULL,
    teaching_stages_json JSONB NOT NULL,
    analysis_text TEXT NOT NULL DEFAULT '',
    extracted_text TEXT NOT NULL DEFAULT '',
    page_count INTEGER CHECK (page_count IS NULL OR page_count >= 0),
    sections_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS mission_file_parse_results_file_idx
    ON mission_file_parse_results(mission_file_id);
