ALTER TABLE template_profile_versions
    ADD COLUMN IF NOT EXISTS material_parse_binding_json TEXT;

ALTER TABLE template_profile_versions
    ADD COLUMN IF NOT EXISTS material_parse_binding_checksum VARCHAR(64);
