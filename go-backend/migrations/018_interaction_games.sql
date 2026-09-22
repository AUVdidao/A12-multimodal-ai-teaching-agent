CREATE TABLE IF NOT EXISTS game_jobs (
    id UUID PRIMARY KEY,
    mission_id BIGINT NOT NULL REFERENCES missions(id) ON DELETE CASCADE,
    source_specification_id UUID NOT NULL REFERENCES locked_specifications(id) ON DELETE RESTRICT,
    source_artifact_id UUID NOT NULL REFERENCES artifacts(id) ON DELETE RESTRICT,
    game_type TEXT NOT NULL CHECK (game_type IN ('SINGLE_CHOICE', 'TRUE_FALSE', 'MATCHING')),
    status TEXT NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    game_spec_json JSONB,
    error_code TEXT,
    error_message TEXT,
    artifact_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS game_jobs_mission_idx
    ON game_jobs(mission_id, created_at DESC, id DESC);

CREATE TABLE IF NOT EXISTS game_artifacts (
    id UUID PRIMARY KEY,
    mission_id BIGINT NOT NULL REFERENCES missions(id) ON DELETE CASCADE,
    game_job_id UUID NOT NULL REFERENCES game_jobs(id) ON DELETE RESTRICT,
    file_object_id BIGINT NOT NULL REFERENCES file_objects(id) ON DELETE RESTRICT,
    version INTEGER NOT NULL CHECK (version > 0),
    game_type TEXT NOT NULL CHECK (game_type IN ('SINGLE_CHOICE', 'TRUE_FALSE', 'MATCHING')),
    content_type TEXT NOT NULL CHECK (content_type = 'text/html'),
    sha256 TEXT NOT NULL CHECK (sha256 ~ '^[0-9a-fA-F]{64}$'),
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0),
    status TEXT NOT NULL CHECK (status IN ('READY', 'PUBLISHED', 'INVALID')),
    game_spec_json JSONB NOT NULL CHECK (jsonb_typeof(game_spec_json) = 'object'),
    public_token TEXT UNIQUE,
    published_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (mission_id, version)
);

CREATE INDEX IF NOT EXISTS game_artifacts_mission_idx
    ON game_artifacts(mission_id, version DESC, id DESC);
CREATE INDEX IF NOT EXISTS game_artifacts_public_token_idx
    ON game_artifacts(public_token) WHERE public_token IS NOT NULL;
