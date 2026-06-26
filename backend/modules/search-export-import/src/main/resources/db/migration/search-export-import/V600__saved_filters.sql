-- search-export-import BC 첫 영속성 — 저장된 필터(saved_filters) 테이블 (FR-SR-03)

CREATE TABLE saved_filters (
    id          UUID PRIMARY KEY,
    owner_id    UUID         NOT NULL,        -- identity-access users.id, FK 없음(BC 격리)
    name        VARCHAR(100) NOT NULL,
    aql_query   TEXT         NOT NULL,
    project_key VARCHAR(50)  NOT NULL,        -- 실행 컨텍스트(AQL 본문 project 미지원)
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_saved_filters_owner_name UNIQUE (owner_id, name)
);

CREATE INDEX idx_saved_filters_owner ON saved_filters (owner_id);
CREATE INDEX idx_saved_filters_project ON saved_filters (project_key);
