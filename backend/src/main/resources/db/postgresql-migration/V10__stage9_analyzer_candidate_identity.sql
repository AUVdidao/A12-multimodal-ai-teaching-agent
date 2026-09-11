CREATE UNIQUE INDEX IF NOT EXISTS uk_template_profiles_analyzer_identity
    ON template_profile_versions (project_id, template_id, source_version_id, analysis_run_id, origin);
