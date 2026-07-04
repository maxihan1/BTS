-- search-export-import BC Import 값 매핑 — import_value_mappings 테이블 (FR-IM-02 PR-C)

CREATE TABLE import_value_mappings (
    import_job_id  UUID NOT NULL REFERENCES import_jobs(id) ON DELETE CASCADE,
    target_field   TEXT NOT NULL,
    source_value   TEXT NOT NULL,
    target_value   TEXT NOT NULL,
    PRIMARY KEY (import_job_id, target_field, source_value),
    CONSTRAINT chk_import_value_mappings_field
        CHECK (target_field IN ('STATUS','TYPE','PRIORITY'))
);
