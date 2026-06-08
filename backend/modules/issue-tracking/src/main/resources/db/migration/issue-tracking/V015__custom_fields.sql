-- 프로젝트별 커스텀 필드 정의/선택지 테이블 + issues.custom_fields JSONB + GIN 인덱스 — FR-IS-10. DATA.md §3 소프트 삭제 + §4 TIMESTAMPTZ + §7 FK 인덱스 준수

-- custom_field_definitions 테이블
-- 회사가 이슈에 직접 정의하는 커스텀 필드의 메타데이터(예: "급여 영향도"). 프로젝트별 스코프.
-- 같은 BC(issue-tracking)이므로 projects 실 FK 적용 (components 선례 동형).
-- deleted_at: 소프트 삭제 컬럼 (DATA.md §3) — NULL=활성, NOT NULL=삭제됨. 삭제 후 동일 key 재생성 허용.
CREATE TABLE custom_field_definitions (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id    UUID         NOT NULL REFERENCES projects(id),
    -- key: URL-safe 소문자 식별자(JSONB 키). 활성(deleted_at IS NULL) 기준 프로젝트 내 유일. 생성 후 불변.
    key           TEXT         NOT NULL,
    name          TEXT         NOT NULL,
    description   TEXT         NULL,
    -- field_type: FieldType enum 값(SHORT_TEXT/NUMBER/SINGLE_SELECT 등). 생성 후 불변(타입 변경은 값 정합 붕괴).
    field_type    TEXT         NOT NULL,
    -- required: 이슈 저장 시 필수 입력 여부. 검증은 ApplicationService 가 수행.
    required      BOOLEAN      NOT NULL DEFAULT FALSE,
    -- display_order: 목록/폼 정렬 순서.
    display_order INT          NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    deleted_at    TIMESTAMPTZ  NULL
);

-- FK 인덱스 (DATA.md §7 — PostgreSQL 은 FK 에 인덱스 자동 생성 안 함)
CREATE INDEX idx_custom_field_definitions_project_id ON custom_field_definitions(project_id);

-- 부분 유니크 인덱스: 활성(deleted_at IS NULL) 기준 프로젝트별 key 유일.
-- 소프트 삭제된 row 는 제외되므로 삭제 후 동일 key 재생성 허용 (components 부분 유니크 패턴 동형).
CREATE UNIQUE INDEX ux_custom_field_definitions_project_key_active
    ON custom_field_definitions(project_id, key) WHERE deleted_at IS NULL;

-- custom_field_options 테이블
-- 선택형 필드(SINGLE_SELECT/MULTI_SELECT/RADIO)의 선택지. 정의에 종속.
-- 관계 종속이라 소프트 삭제 없음 — 정의 소프트 삭제 시 옵션은 유지, 정의 하드 삭제 시 FK CASCADE 로 자동 정리.
CREATE TABLE custom_field_options (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    field_id      UUID         NOT NULL REFERENCES custom_field_definitions(id) ON DELETE CASCADE,
    -- value: JSONB 에 저장되는 실제 값. 정의 내 유일.
    value         TEXT         NOT NULL,
    label         TEXT         NOT NULL,
    display_order INT          NOT NULL,
    UNIQUE (field_id, value)
);

-- FK 인덱스 (DATA.md §7). UNIQUE(field_id, value) 인덱스 선두가 field_id 라 단독 조회를 커버하나,
-- 명시적 FK 인덱스 관례 유지를 위해 별도 인덱스 추가 (components 선례 동형).
CREATE INDEX idx_custom_field_options_field_id ON custom_field_options(field_id);

-- issues.custom_fields JSONB 컬럼 추가 — 커스텀 필드 값 저장({field_key: value}).
-- NOT NULL + DEFAULT '{}' 이므로 기존 row backfill 불필요(SDD §05 데이터모델 결선).
-- 타입/참조 정합은 JSONB 스키마리스라 ApplicationService 가 보증(검증 후 저장, 도메인 우회 금지).
ALTER TABLE issues ADD COLUMN custom_fields JSONB NOT NULL DEFAULT '{}'::jsonb;

-- GIN 인덱스: 후속 검색/필터(FR-IS-09 AQL) 대비. SDD §05 line 266 명시.
CREATE INDEX idx_issues_custom_fields ON issues USING GIN (custom_fields);
