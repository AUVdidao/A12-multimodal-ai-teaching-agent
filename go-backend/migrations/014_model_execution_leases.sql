-- A short-lived, one-use capability for Java template analysis.
-- The bearer token authenticates the service; this row binds one provider
-- call to one Mission selection and one verified preview manifest.
CREATE TABLE IF NOT EXISTS model_execution_leases (
    id UUID PRIMARY KEY,
    owner_user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    mission_id BIGINT NOT NULL REFERENCES missions(id) ON DELETE CASCADE,
    model_connection_id BIGINT NOT NULL REFERENCES model_connections(id) ON DELETE CASCADE,
    mission_file_id BIGINT NOT NULL REFERENCES mission_files(id) ON DELETE CASCADE,
    purpose TEXT NOT NULL CHECK (purpose = 'TEMPLATE_ANALYZER'),
    analysis_run_id TEXT NOT NULL,
    source_version_id BIGINT NOT NULL CHECK (source_version_id > 0),
    rendered_slide_set_id BIGINT NOT NULL CHECK (rendered_slide_set_id > 0),
    processing_run_id BIGINT NOT NULL CHECK (processing_run_id > 0),
    source_sha256 TEXT NOT NULL CHECK (source_sha256 ~ '^[0-9a-fA-F]{64}$'),
    input_sha256 TEXT NOT NULL CHECK (input_sha256 ~ '^[0-9a-fA-F]{64}$'),
    prompt_sha256 TEXT NOT NULL CHECK (prompt_sha256 ~ '^[0-9a-fA-F]{64}$'),
    preview_storage_key TEXT NOT NULL,
    preview_sha256 TEXT NOT NULL CHECK (preview_sha256 ~ '^[0-9a-fA-F]{64}$'),
    preview_size_bytes BIGINT NOT NULL CHECK (preview_size_bytes > 0),
    preview_media_type TEXT NOT NULL CHECK (preview_media_type IN ('image/png', 'image/jpeg')),
    nonce_hash TEXT NOT NULL CHECK (nonce_hash ~ '^[0-9a-fA-F]{64}$'),
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (mission_id, purpose, analysis_run_id)
);
CREATE INDEX IF NOT EXISTS model_execution_leases_expiry_idx
    ON model_execution_leases (expires_at, consumed_at);
