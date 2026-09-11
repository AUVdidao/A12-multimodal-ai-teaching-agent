-- The cross-service material identity is the MissionFile plus its immutable
-- source hash. Keep the older per-file guard as an additional fail-closed
-- invariant while making the composite contract explicit for reconciliation.
CREATE UNIQUE INDEX IF NOT EXISTS rag_resource_bindings_material_source_uq
    ON rag_resource_bindings(mission_file_id, source_sha256)
    WHERE resource_type = 'MATERIAL';
