ALTER TABLE model_connections
    ADD COLUMN IF NOT EXISTS provider TEXT NOT NULL DEFAULT 'CUSTOM',
    ADD COLUMN IF NOT EXISTS capabilities JSONB NOT NULL DEFAULT '{"supportsTools":true,"supportsJSONMode":true,"supportsVision":false,"supportsStreaming":false}'::jsonb,
    ADD COLUMN IF NOT EXISTS capability_verification JSONB NOT NULL DEFAULT '{"supportsTools":"DECLARED","supportsJSONMode":"DECLARED","supportsVision":"DECLARED","supportsStreaming":"DECLARED"}'::jsonb;

UPDATE model_connections
SET provider = 'CUSTOM'
WHERE provider IS NULL OR btrim(provider) = '';

UPDATE model_connections
SET capabilities = '{"supportsTools":true,"supportsJSONMode":true,"supportsVision":false,"supportsStreaming":false}'::jsonb
WHERE capabilities IS NULL OR jsonb_typeof(capabilities) <> 'object';

UPDATE model_connections
SET capability_verification = '{"supportsTools":"DECLARED","supportsJSONMode":"DECLARED","supportsVision":"DECLARED","supportsStreaming":"DECLARED"}'::jsonb
WHERE capability_verification IS NULL OR jsonb_typeof(capability_verification) <> 'object';
