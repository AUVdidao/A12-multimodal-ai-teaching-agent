ALTER TABLE generation_jobs
    ADD COLUMN IF NOT EXISTS generation_mode TEXT NOT NULL DEFAULT 'TEACHER_TEMPLATE',
    ADD COLUMN IF NOT EXISTS fallback_reasons JSONB NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE generation_jobs
    DROP CONSTRAINT IF EXISTS generation_jobs_generation_mode_check;

ALTER TABLE generation_jobs
    ADD CONSTRAINT generation_jobs_generation_mode_check
    CHECK (generation_mode IN ('TEACHER_TEMPLATE', 'SYSTEM_DEFAULT_TEMPLATE'));
