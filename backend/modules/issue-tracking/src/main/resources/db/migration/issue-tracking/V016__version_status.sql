-- 버전 상태(status) + 릴리스 시각(released_at) 컬럼 추가 — FR-VR-02. DATA.md §4-2 NOT NULL DEFAULT / §4-4 TIMESTAMPTZ 준수

-- versions.status: 버전 상태 (UNRELEASED/RELEASED/ARCHIVED). 기존 행은 DEFAULT 로 UNRELEASED.
-- versions.released_at: RELEASED 진입 시각 (Clock 주입). UNRELEASED 진입 시 NULL, ARCHIVED 진입 시 직전값 유지.
ALTER TABLE versions
    ADD COLUMN status      VARCHAR(20) NOT NULL DEFAULT 'UNRELEASED',
    ADD COLUMN released_at TIMESTAMPTZ NULL;

-- 상태 값을 3종으로 제한 — 미정의 문자열 INSERT/UPDATE 를 DB 차원에서 거부.
ALTER TABLE versions
    ADD CONSTRAINT ck_versions_status
    CHECK (status IN ('UNRELEASED', 'RELEASED', 'ARCHIVED'));

COMMENT ON COLUMN versions.status      IS '버전 상태 (UNRELEASED/RELEASED/ARCHIVED). 신규 버전은 UNRELEASED 로 시작 (FR-VR-02).';
COMMENT ON COLUMN versions.released_at IS 'RELEASED 진입 시각 (Clock 주입). UNRELEASED 진입 시 NULL, ARCHIVED 진입 시 직전값 유지 (FR-VR-02).';
