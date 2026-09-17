UPDATE model_connections
SET capabilities = capabilities
    || '{"supportsChat":true,"supportsEmbeddings":false}'::jsonb
WHERE NOT (capabilities ? 'supportsChat')
   OR NOT (capabilities ? 'supportsEmbeddings');

UPDATE model_connections
SET capability_verification = capability_verification
    || '{"supportsChat":"DECLARED","supportsEmbeddings":"DECLARED"}'::jsonb
WHERE NOT (capability_verification ? 'supportsChat')
   OR NOT (capability_verification ? 'supportsEmbeddings');
