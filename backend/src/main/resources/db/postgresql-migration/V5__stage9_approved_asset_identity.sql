-- Stage 9: approval-time immutable file identity. Existing rows remain blocked until migrated.
ALTER TABLE approved_asset_manifests
    ADD COLUMN IF NOT EXISTS approved_file_last_modified_utc VARCHAR(64);
