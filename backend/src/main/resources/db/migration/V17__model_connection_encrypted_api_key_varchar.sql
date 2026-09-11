-- V14 created this H2 column as CLOB, while the application stores an opaque
-- AES-GCM Base64 string through a regular String property. Preserve the
-- column name, data, nullability, and table constraints while aligning the
-- physical type with the field-level VARCHAR JDBC mapping.
alter table model_connections
    alter column encrypted_api_key varchar not null;
