CREATE TABLE IF NOT EXISTS model_connection_bindings (
    owner_user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role TEXT NOT NULL CHECK (role IN ('PLANNING', 'TEMPLATE_VISION', 'EMBEDDING')),
    model_connection_id BIGINT NOT NULL REFERENCES model_connections(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (owner_user_id, role)
);

CREATE INDEX IF NOT EXISTS model_connection_bindings_connection_idx
    ON model_connection_bindings(model_connection_id);
