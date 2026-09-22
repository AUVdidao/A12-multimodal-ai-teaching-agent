ALTER TABLE game_jobs
    DROP CONSTRAINT IF EXISTS game_jobs_game_type_check;

ALTER TABLE game_jobs
    ADD CONSTRAINT game_jobs_game_type_check
    CHECK (game_type IN ('SINGLE_CHOICE', 'TRUE_FALSE', 'MATCHING', 'RUNNER'));

ALTER TABLE game_artifacts
    DROP CONSTRAINT IF EXISTS game_artifacts_game_type_check;

ALTER TABLE game_artifacts
    ADD CONSTRAINT game_artifacts_game_type_check
    CHECK (game_type IN ('SINGLE_CHOICE', 'TRUE_FALSE', 'MATCHING', 'RUNNER'));
