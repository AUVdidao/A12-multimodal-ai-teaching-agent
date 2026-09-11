-- Researcher review is an upstream Mission capability. It is intentionally
-- independent from Generation/PPT Engine and keeps every review submission
-- as an append-only historical row at the application contract.
ALTER TABLE missions DROP CONSTRAINT IF EXISTS missions_status_check;
ALTER TABLE missions ADD CONSTRAINT missions_status_check
    CHECK (status IN ('ASSIGNED', 'IN_PROGRESS', 'SUBMITTED', 'COMPLETED', 'FEEDBACK'));

CREATE TABLE IF NOT EXISTS mission_feedback (
    id UUID PRIMARY KEY,
    mission_id BIGINT NOT NULL REFERENCES missions(id) ON DELETE CASCADE,
    reviewer_user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    submission_version INTEGER NOT NULL CHECK (submission_version > 0),
    rating SMALLINT NOT NULL CHECK (rating BETWEEN 1 AND 5),
    summary TEXT NOT NULL CHECK (btrim(summary) <> ''),
    items_json JSONB NOT NULL DEFAULT '[]'::jsonb
        CHECK (jsonb_typeof(items_json) = 'array'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS mission_feedback_mission_idx
    ON mission_feedback(mission_id, created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS mission_feedback_reviewer_idx
    ON mission_feedback(reviewer_user_id, created_at DESC, id DESC);
