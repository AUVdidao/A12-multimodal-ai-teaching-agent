ALTER TABLE template_analysis_results
    ADD COLUMN IF NOT EXISTS candidate_profile_sha256 VARCHAR(64);
