CREATE TABLE IF NOT EXISTS rag_resource_bindings (
    id BIGSERIAL PRIMARY KEY,
    mission_id BIGINT NOT NULL REFERENCES missions(id) ON DELETE CASCADE,
    mission_file_id BIGINT REFERENCES mission_files(id) ON DELETE CASCADE,
    rag_project_id BIGINT NOT NULL,
    rag_material_id BIGINT,
    resource_type TEXT NOT NULL CHECK (resource_type IN ('PROJECT', 'MATERIAL')),
    source_sha256 TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (
        (resource_type = 'PROJECT' AND mission_file_id IS NULL AND rag_material_id IS NULL AND source_sha256 IS NULL)
        OR
        (resource_type = 'MATERIAL' AND mission_file_id IS NOT NULL AND rag_material_id IS NOT NULL AND source_sha256 IS NOT NULL)
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS rag_resource_bindings_project_uq
    ON rag_resource_bindings(mission_id) WHERE resource_type = 'PROJECT';

CREATE UNIQUE INDEX IF NOT EXISTS rag_resource_bindings_material_uq
    ON rag_resource_bindings(mission_file_id) WHERE resource_type = 'MATERIAL';

CREATE INDEX IF NOT EXISTS rag_resource_bindings_mission_idx
    ON rag_resource_bindings(mission_id, resource_type, created_at);
