DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'ppt_specification_content_blocks'
          AND column_name = 'content'
          AND data_type = 'text'
          AND is_nullable = 'NO'
    ) THEN
        RAISE EXCEPTION 'ppt_specification_content_blocks.content must remain TEXT NOT NULL';
    END IF;
END
$$;
