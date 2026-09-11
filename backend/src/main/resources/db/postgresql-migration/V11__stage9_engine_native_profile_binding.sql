ALTER TABLE template_profile_versions
    ADD COLUMN IF NOT EXISTS engine_native_profile_json TEXT,
    ADD COLUMN IF NOT EXISTS engine_native_profile_checksum VARCHAR(64);
