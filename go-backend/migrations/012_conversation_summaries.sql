CREATE TABLE IF NOT EXISTS conversation_summaries (
    mission_id BIGINT PRIMARY KEY REFERENCES missions(id) ON DELETE CASCADE,
    summary_version INTEGER NOT NULL DEFAULT 1,
    summarized_through_message_id BIGINT REFERENCES mission_messages(id),
    summary TEXT NOT NULL,
    source_agent_run_id UUID REFERENCES agent_runs(id),
    prompt_version TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS conversation_summaries_source_idx
    ON conversation_summaries(source_agent_run_id);
