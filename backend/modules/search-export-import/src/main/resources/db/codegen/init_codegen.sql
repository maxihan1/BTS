-- jOOQ 코드 생성용 초기화 SQL (search-export-import BC) — V600 saved_filters 구조 미러 (codegen 입력, V600 과 정확히 일치 유지)

CREATE TABLE saved_filters (
    id          UUID PRIMARY KEY,
    owner_id    UUID         NOT NULL,
    name        VARCHAR(100) NOT NULL,
    aql_query   TEXT         NOT NULL,
    project_key VARCHAR(50)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_saved_filters_owner_name UNIQUE (owner_id, name)
);

CREATE INDEX idx_saved_filters_owner ON saved_filters (owner_id);
CREATE INDEX idx_saved_filters_project ON saved_filters (project_key);
