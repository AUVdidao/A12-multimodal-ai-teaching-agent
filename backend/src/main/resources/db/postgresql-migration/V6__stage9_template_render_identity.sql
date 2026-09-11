-- Stage 9: renderer output identity and adapter version. Existing rows remain fail-closed until re-rendered.
ALTER TABLE template_rendered_slide_sets
    ADD COLUMN IF NOT EXISTS source_sha256 VARCHAR(64),
    ADD COLUMN IF NOT EXISTS output_sha256 VARCHAR(64),
    ADD COLUMN IF NOT EXISTS output_size_bytes BIGINT,
    ADD COLUMN IF NOT EXISTS adapter_version VARCHAR(120);
