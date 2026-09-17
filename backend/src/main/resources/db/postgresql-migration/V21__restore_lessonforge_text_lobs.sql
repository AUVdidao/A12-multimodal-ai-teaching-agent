-- ParseResult used @Lob on fields whose PostgreSQL schema is TEXT.  With the
-- PostgreSQL driver/Hibernate combination this stored large-object OIDs in the
-- TEXT columns instead of the text itself.  Restore the referenced content
-- before the entity mapping stops using @Lob.  Large objects are intentionally
-- retained for a later, separately audited cleanup.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS lessonforge_text_lob_migration_audit (
    source_table varchar(64) NOT NULL,
    source_column varchar(64) NOT NULL,
    source_row_id bigint NOT NULL,
    source_oid oid NOT NULL,
    source_bytes bigint NOT NULL,
    source_sha256 char(64) NOT NULL,
    restored_chars bigint NOT NULL,
    restored_sha256 char(64) NOT NULL,
    readable boolean NOT NULL,
    migrated_at timestamptz NOT NULL DEFAULT current_timestamp,
    PRIMARY KEY (source_table, source_column, source_row_id)
);

CREATE TEMP TABLE lessonforge_text_lob_restore_candidates (
    source_table varchar(64) NOT NULL,
    source_column varchar(64) NOT NULL,
    source_row_id bigint NOT NULL,
    source_oid oid NOT NULL,
    source_bytes bigint NOT NULL,
    source_sha256 char(64) NOT NULL,
    restored_text text NOT NULL,
    restored_chars bigint NOT NULL,
    restored_sha256 char(64) NOT NULL,
    readable boolean NOT NULL
) ON COMMIT DROP;

WITH numeric_values AS (
    SELECT 'parse_results'::varchar(64) AS source_table,
           'summary'::varchar(64) AS source_column,
           id AS source_row_id,
           CASE
               WHEN summary ~ '^[0-9]{1,10}$' AND summary::numeric <= 4294967295
               THEN summary::oid
           END AS source_oid
    FROM parse_results
    WHERE summary ~ '^[0-9]{1,10}$'
    UNION ALL
    SELECT 'parse_results', 'extracted_text', id,
           CASE
               WHEN extracted_text ~ '^[0-9]{1,10}$' AND extracted_text::numeric <= 4294967295
               THEN extracted_text::oid
           END
    FROM parse_results
    WHERE extracted_text ~ '^[0-9]{1,10}$'
    UNION ALL
    SELECT 'parse_result_sections', 'section_value', parse_result_id::bigint * 1000000000 + section_order,
           CASE
               WHEN section_value ~ '^[0-9]{1,10}$' AND section_value::numeric <= 4294967295
               THEN section_value::oid
           END
    FROM parse_result_sections
    WHERE section_value ~ '^[0-9]{1,10}$'
), existing_lobs AS (
    SELECT numeric_values.*, lo_get(numeric_values.source_oid) AS source_bytes_value
    FROM numeric_values
    JOIN pg_largeobject_metadata metadata ON metadata.oid = numeric_values.source_oid
    WHERE numeric_values.source_oid IS NOT NULL
), decoded AS (
    SELECT existing_lobs.*,
           convert_from(existing_lobs.source_bytes_value, 'UTF8') AS restored_text_value
    FROM existing_lobs
)
INSERT INTO lessonforge_text_lob_restore_candidates (
    source_table, source_column, source_row_id, source_oid, source_bytes,
    source_sha256, restored_text, restored_chars, restored_sha256, readable
)
SELECT source_table,
       source_column,
       source_row_id,
       source_oid,
       octet_length(source_bytes_value),
       encode(digest(source_bytes_value, 'sha256'), 'hex'),
       restored_text_value,
       length(restored_text_value),
       encode(digest(convert_to(restored_text_value, 'UTF8'), 'sha256'), 'hex'),
       length(btrim(restored_text_value)) > 0 AND restored_text_value !~ '^[0-9]+$'
FROM decoded;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM lessonforge_text_lob_restore_candidates
        WHERE NOT readable
    ) THEN
        RAISE EXCEPTION 'LessonForge TEXT large-object migration found unreadable or empty content';
    END IF;
END
$$;

INSERT INTO lessonforge_text_lob_migration_audit (
    source_table, source_column, source_row_id, source_oid, source_bytes,
    source_sha256, restored_chars, restored_sha256, readable
)
SELECT source_table, source_column, source_row_id, source_oid, source_bytes,
       source_sha256, restored_chars, restored_sha256, readable
FROM lessonforge_text_lob_restore_candidates
ON CONFLICT (source_table, source_column, source_row_id) DO UPDATE
SET source_oid = EXCLUDED.source_oid,
    source_bytes = EXCLUDED.source_bytes,
    source_sha256 = EXCLUDED.source_sha256,
    restored_chars = EXCLUDED.restored_chars,
    restored_sha256 = EXCLUDED.restored_sha256,
    readable = EXCLUDED.readable,
    migrated_at = current_timestamp;

UPDATE parse_results target
SET summary = restore.restored_text
FROM lessonforge_text_lob_restore_candidates restore
WHERE restore.source_table = 'parse_results'
  AND restore.source_column = 'summary'
  AND restore.source_row_id = target.id;

UPDATE parse_results target
SET extracted_text = restore.restored_text
FROM lessonforge_text_lob_restore_candidates restore
WHERE restore.source_table = 'parse_results'
  AND restore.source_column = 'extracted_text'
  AND restore.source_row_id = target.id;

UPDATE parse_result_sections target
SET section_value = restore.restored_text
FROM lessonforge_text_lob_restore_candidates restore
WHERE restore.source_table = 'parse_result_sections'
  AND restore.source_column = 'section_value'
  AND restore.source_row_id = target.parse_result_id::bigint * 1000000000 + target.section_order;

DO $$
DECLARE
    remaining bigint;
BEGIN
    SELECT count(*) INTO remaining
    FROM (
        SELECT summary AS value FROM parse_results WHERE summary ~ '^[0-9]{1,10}$'
        UNION ALL
        SELECT extracted_text FROM parse_results WHERE extracted_text ~ '^[0-9]{1,10}$'
        UNION ALL
        SELECT section_value FROM parse_result_sections WHERE section_value ~ '^[0-9]{1,10}$'
    ) numeric_values
    JOIN pg_largeobject_metadata metadata
      ON metadata.oid = CASE
          WHEN numeric_values.value::numeric <= 4294967295 THEN numeric_values.value::oid
      END;
    IF remaining <> 0 THEN
        RAISE EXCEPTION 'LessonForge TEXT large-object migration left % OID references', remaining;
    END IF;
END
$$;

