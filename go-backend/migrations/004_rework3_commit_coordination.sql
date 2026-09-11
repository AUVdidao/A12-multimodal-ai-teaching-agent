ALTER TABLE mission_messages
    ADD COLUMN IF NOT EXISTS agent_run_id UUID REFERENCES agent_runs(id) ON DELETE CASCADE,
    ADD COLUMN IF NOT EXISTS output_stage TEXT;

ALTER TABLE questions
    ADD COLUMN IF NOT EXISTS output_stage TEXT;

ALTER TABLE planning_drafts
    ADD COLUMN IF NOT EXISTS output_stage TEXT;

ALTER TABLE activity_events
    ADD COLUMN IF NOT EXISTS idempotency_key TEXT;

CREATE UNIQUE INDEX IF NOT EXISTS mission_messages_agent_output_uq
    ON mission_messages(agent_run_id, output_stage)
    WHERE agent_run_id IS NOT NULL AND output_stage IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS questions_agent_output_uq
    ON questions(agent_run_id, output_stage)
    WHERE output_stage IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS planning_drafts_agent_output_uq
    ON planning_drafts(created_by_agent_run_id, output_stage)
    WHERE output_stage IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS activity_events_idempotency_uq
    ON activity_events(mission_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS artifacts_generation_job_uq
    ON artifacts(generation_job_id);
