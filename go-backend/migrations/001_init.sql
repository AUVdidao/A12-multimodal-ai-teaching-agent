CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    name TEXT NOT NULL,
    email TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    role TEXT NOT NULL CHECK (role IN ('TEACHER', 'RESEARCHER')),
    status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS users_email_lower_uq ON users (lower(email));

CREATE TABLE IF NOT EXISTS sessions (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash TEXT NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS sessions_token_hash_idx ON sessions(token_hash);

CREATE TABLE IF NOT EXISTS model_connections (
    id BIGSERIAL PRIMARY KEY,
    owner_user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    protocol TEXT NOT NULL CHECK (protocol = 'OPENAI_COMPATIBLE'),
    base_url TEXT NOT NULL,
    model_id TEXT NOT NULL,
    encrypted_api_key TEXT NOT NULL,
    key_hint TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT true,
    verification_status TEXT NOT NULL DEFAULT 'UNVERIFIED' CHECK (verification_status IN ('UNVERIFIED', 'VERIFIED', 'INVALID')),
    last_verified_at TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(owner_user_id, name)
);
CREATE INDEX IF NOT EXISTS model_connections_owner_idx ON model_connections(owner_user_id, created_at, id);

CREATE TABLE IF NOT EXISTS missions (
    id BIGSERIAL PRIMARY KEY,
    owner_teacher_id BIGINT NOT NULL REFERENCES users(id),
    dispatcher_id BIGINT REFERENCES users(id),
    source TEXT NOT NULL CHECK (source IN ('SELF_CREATED', 'ASSIGNED')),
    title TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    deadline TIMESTAMPTZ,
    status TEXT NOT NULL DEFAULT 'IN_PROGRESS' CHECK (status IN ('ASSIGNED', 'IN_PROGRESS', 'SUBMITTED', 'COMPLETED')),
    selected_model_connection_id BIGINT REFERENCES model_connections(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS missions_owner_idx ON missions(owner_teacher_id, updated_at DESC, id DESC);

CREATE TABLE IF NOT EXISTS file_objects (
    id BIGSERIAL PRIMARY KEY,
    owner_user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    storage_key TEXT NOT NULL UNIQUE,
    original_name TEXT NOT NULL,
    mime_type TEXT NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes >= 0),
    sha256 TEXT NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS uploads (
    id UUID PRIMARY KEY,
    owner_user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    file_object_id BIGINT NOT NULL REFERENCES file_objects(id) ON DELETE CASCADE,
    status TEXT NOT NULL DEFAULT 'TEMPORARY' CHECK (status IN ('TEMPORARY', 'BOUND')),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS uploads_owner_idx ON uploads(owner_user_id, status, expires_at);

CREATE TABLE IF NOT EXISTS mission_files (
    id BIGSERIAL PRIMARY KEY,
    mission_id BIGINT NOT NULL REFERENCES missions(id) ON DELETE CASCADE,
    file_object_id BIGINT NOT NULL REFERENCES file_objects(id) ON DELETE RESTRICT,
    role TEXT NOT NULL CHECK (role IN ('MATERIAL', 'TEACHING_PLAN', 'TEMPLATE', 'USER_IMAGE', 'FINAL_PPTX')),
    provenance TEXT NOT NULL DEFAULT 'TEACHER',
    parse_status TEXT NOT NULL DEFAULT 'PENDING' CHECK (parse_status IN ('PENDING', 'READY', 'FAILED')),
    uploaded_by BIGINT NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(mission_id, file_object_id)
);
CREATE INDEX IF NOT EXISTS mission_files_mission_idx ON mission_files(mission_id, created_at, id);

CREATE TABLE IF NOT EXISTS mission_messages (
    id BIGSERIAL PRIMARY KEY,
    mission_id BIGINT NOT NULL REFERENCES missions(id) ON DELETE CASCADE,
    role TEXT NOT NULL CHECK (role IN ('USER', 'ASSISTANT', 'SYSTEM_EVENT')),
    content TEXT NOT NULL,
    message_type TEXT NOT NULL DEFAULT 'TEXT',
    structured_payload JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS mission_messages_mission_idx ON mission_messages(mission_id, created_at, id);

CREATE TABLE IF NOT EXISTS agent_runs (
    id UUID PRIMARY KEY,
    mission_id BIGINT NOT NULL REFERENCES missions(id) ON DELETE CASCADE,
    triggering_message_id BIGINT REFERENCES mission_messages(id),
    model_connection_id BIGINT REFERENCES model_connections(id),
    model_identity_snapshot JSONB NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('QUEUED', 'WAITING_INPUTS', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    error_code TEXT,
    error_message TEXT,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS agent_runs_queue_idx ON agent_runs(status, created_at);
CREATE INDEX IF NOT EXISTS agent_runs_mission_idx ON agent_runs(mission_id, created_at DESC);

CREATE TABLE IF NOT EXISTS questions (
    id UUID PRIMARY KEY,
    mission_id BIGINT NOT NULL REFERENCES missions(id) ON DELETE CASCADE,
    agent_run_id UUID NOT NULL REFERENCES agent_runs(id) ON DELETE CASCADE,
    text TEXT NOT NULL,
    question_type TEXT NOT NULL,
    options_json JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS question_answers (
    id UUID PRIMARY KEY,
    question_id UUID NOT NULL REFERENCES questions(id) ON DELETE CASCADE,
    selected_values_json JSONB,
    text_answer TEXT,
    answered_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS planning_drafts (
    id UUID PRIMARY KEY,
    mission_id BIGINT NOT NULL REFERENCES missions(id) ON DELETE CASCADE,
    version INTEGER NOT NULL,
    markdown TEXT NOT NULL,
    structured_plan_json JSONB NOT NULL,
    created_by_agent_run_id UUID NOT NULL REFERENCES agent_runs(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(mission_id, version)
);
CREATE INDEX IF NOT EXISTS planning_drafts_mission_idx ON planning_drafts(mission_id, version DESC);

CREATE TABLE IF NOT EXISTS locked_specifications (
    id UUID PRIMARY KEY,
    mission_id BIGINT NOT NULL REFERENCES missions(id) ON DELETE CASCADE,
    source_draft_id UUID NOT NULL REFERENCES planning_drafts(id),
    version INTEGER NOT NULL,
    specification_json JSONB NOT NULL,
    template_binding_json JSONB,
    content_hash TEXT NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(mission_id, version)
);

CREATE TABLE IF NOT EXISTS generation_jobs (
    id UUID PRIMARY KEY,
    mission_id BIGINT NOT NULL REFERENCES missions(id) ON DELETE CASCADE,
    specification_id UUID NOT NULL REFERENCES locked_specifications(id),
    status TEXT NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    current_slide INTEGER NOT NULL DEFAULT 0,
    total_slides INTEGER NOT NULL DEFAULT 0,
    artifact_id UUID,
    generation_feedback JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS generation_jobs_queue_idx ON generation_jobs(status, created_at);

CREATE TABLE IF NOT EXISTS artifacts (
    id UUID PRIMARY KEY,
    mission_id BIGINT NOT NULL REFERENCES missions(id) ON DELETE CASCADE,
    generation_job_id UUID NOT NULL REFERENCES generation_jobs(id),
    file_object_id BIGINT NOT NULL REFERENCES file_objects(id),
    version INTEGER NOT NULL,
    content_type TEXT NOT NULL,
    sha256 TEXT NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    size_bytes BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(mission_id, version)
);

CREATE TABLE IF NOT EXISTS activity_events (
    id BIGSERIAL PRIMARY KEY,
    mission_id BIGINT NOT NULL REFERENCES missions(id) ON DELETE CASCADE,
    event_type TEXT NOT NULL,
    summary TEXT NOT NULL,
    reference_type TEXT,
    reference_id TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS activity_events_mission_idx ON activity_events(mission_id, id);

CREATE TABLE IF NOT EXISTS execution_audits (
    id BIGSERIAL PRIMARY KEY,
    request_id UUID NOT NULL,
    actor_user_id BIGINT NOT NULL REFERENCES users(id),
    mission_id BIGINT REFERENCES missions(id) ON DELETE SET NULL,
    model_connection_id BIGINT REFERENCES model_connections(id) ON DELETE SET NULL,
    protocol TEXT NOT NULL,
    base_url_host TEXT NOT NULL,
    model_id TEXT NOT NULL,
    purpose TEXT NOT NULL,
    credential_source TEXT NOT NULL,
    http_status INTEGER NOT NULL,
    latency_ms BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS execution_audits_actor_idx ON execution_audits(actor_user_id, created_at DESC);
