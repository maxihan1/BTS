-- search-export-import BC Import 사용자 매핑 — import_user_mappings 테이블 (FR-IM-02 PR-B)

CREATE TABLE import_user_mappings (
    import_job_id      UUID NOT NULL REFERENCES import_jobs(id) ON DELETE CASCADE,
    source_identifier  TEXT NOT NULL,
    target_user_id     UUID NULL,
    PRIMARY KEY (import_job_id, source_identifier)
);
