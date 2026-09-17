-- KnowledgeChunk.content is also PostgreSQL TEXT.  It was affected by the
-- same @Lob mapping and must be restored before the mapping is removed.

CREATE TEMP TABLE lessonforge_chunk_lob_restore_candidates (
    chunk_id bigint NOT NULL,
    source_oid oid NOT NULL,
    source_bytes bigint NOT NULL,
    source_sha256 char(64) NOT NULL,
    restored_text text NOT NULL,
    restored_chars bigint NOT NULL,
    restored_sha256 char(64) NOT NULL,
    readable boolean NOT NULL
) ON COMMIT DROP;

WITH numeric_values AS (
    SELECT id AS chunk_id,
           CASE
               WHEN content ~ '^[0-9]{1,10}$' AND content::numeric <= 4294967295
               THEN content::oid
           END AS source_oid
    FROM knowledge_chunks
    WHERE content ~ '^[0-9]{1,10}$'
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
INSERT INTO lessonforge_chunk_lob_restore_candidates (
    chunk_id, source_oid, source_bytes, source_sha256, restored_text,
    restored_chars, restored_sha256, readable
)
SELECT chunk_id,
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
        FROM lessonforge_chunk_lob_restore_candidates
        WHERE NOT readable
    ) THEN
        RAISE EXCEPTION 'LessonForge knowledge chunk migration found unreadable or empty content';
    END IF;
END
$$;

INSERT INTO lessonforge_text_lob_migration_audit (
    source_table, source_column, source_row_id, source_oid, source_bytes,
    source_sha256, restored_chars, restored_sha256, readable
)
SELECT 'knowledge_chunks', 'content', chunk_id, source_oid, source_bytes,
       source_sha256, restored_chars, restored_sha256, readable
FROM lessonforge_chunk_lob_restore_candidates
ON CONFLICT (source_table, source_column, source_row_id) DO UPDATE
SET source_oid = EXCLUDED.source_oid,
    source_bytes = EXCLUDED.source_bytes,
    source_sha256 = EXCLUDED.source_sha256,
    restored_chars = EXCLUDED.restored_chars,
    restored_sha256 = EXCLUDED.restored_sha256,
    readable = EXCLUDED.readable,
    migrated_at = current_timestamp;

UPDATE knowledge_chunks target
SET content = restore.restored_text
FROM lessonforge_chunk_lob_restore_candidates restore
WHERE restore.chunk_id = target.id;

DO $$
DECLARE
    remaining bigint;
BEGIN
    SELECT count(*) INTO remaining
    FROM knowledge_chunks chunks
    JOIN pg_largeobject_metadata metadata
      ON metadata.oid = CASE
          WHEN chunks.content ~ '^[0-9]{1,10}$'
               AND chunks.content::numeric <= 4294967295
          THEN chunks.content::oid
      END;
    IF remaining <> 0 THEN
        RAISE EXCEPTION 'LessonForge knowledge chunk migration left % OID references', remaining;
    END IF;
END
$$;

