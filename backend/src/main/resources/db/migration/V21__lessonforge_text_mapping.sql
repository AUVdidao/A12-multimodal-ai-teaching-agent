-- H2 stores these fields as CLOB/TEXT directly.  The PostgreSQL-only large
-- object restoration is in the matching V21 PostgreSQL migration.
CREATE TABLE IF NOT EXISTS lessonforge_text_lob_migration_audit (
    source_table varchar(64) NOT NULL,
    source_column varchar(64) NOT NULL,
    source_row_id bigint NOT NULL,
    source_oid bigint NOT NULL,
    source_bytes bigint NOT NULL,
    source_sha256 varchar(64) NOT NULL,
    restored_chars bigint NOT NULL,
    restored_sha256 varchar(64) NOT NULL,
    readable boolean NOT NULL,
    migrated_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (source_table, source_column, source_row_id)
);
