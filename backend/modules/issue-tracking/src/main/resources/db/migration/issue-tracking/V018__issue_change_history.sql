-- 이슈 변경 이력 기록 테이블 (change group + item, append-only). FR-HS-01 — FR-AU-10 auth_audit_logs 패턴 차용

-- 1. issue_change_group 테이블
-- 한 번의 이슈 변경(한 트랜잭션)을 묶는 변경 그룹. Jira 의 ChangeHistory 에 대응.
-- append-only: soft-delete(deleted_at) / updated_at 없음 — 한 번 기록되면 수정/삭제 불가 (DATA.md §3 감사 로그 절대 삭제 금지 정합).
-- 이력 보존 우선: issues / users 로의 FK 는 두지 않는다. 부모(이슈/사용자) 삭제와 무관하게 이력은 남아야 한다 (FR-AU-10 동형).
--   issue_id + issue_key 둘 다 저장 — id 로 조인 + key 로 사람이 읽는 식별(이슈 이동 시에도 기록 시점 키 보존).
CREATE TABLE issue_change_group (
    -- id: IDENTITY 자동 발번 PK (SERIAL 대체, PostgreSQL 권장).
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- issue_id: 변경된 이슈 (issues.id 대응). 이력 보존 우선으로 FK 미적용.
    issue_id    UUID         NOT NULL,
    -- issue_key: 기록 시점 이슈 키 (issues.key 동일 길이 VARCHAR(20)). 이슈 이동 후에도 당시 키 보존.
    issue_key   VARCHAR(20)  NOT NULL,
    -- actor_id: 변경 주체 (users.id 대응). NULL=시스템 자동 변경. FK 미적용(이력 보존 우선).
    actor_id    UUID         NULL,
    -- created_at: 변경 발생 시각. TIMESTAMPTZ (DATA.md §4).
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
COMMENT ON TABLE  issue_change_group            IS '이슈 변경 그룹(한 트랜잭션 단위). append-only — 수정/삭제 불가 (FR-HS-01, DATA.md §3).';
COMMENT ON COLUMN issue_change_group.issue_id   IS '변경된 이슈 (issues.id 대응). 이력 보존 우선으로 FK 미적용 (FR-AU-10 패턴).';
COMMENT ON COLUMN issue_change_group.issue_key  IS '기록 시점 이슈 키 (예: BTS-1). 이슈 이동 후에도 당시 키 보존.';
COMMENT ON COLUMN issue_change_group.actor_id   IS '변경 주체 (users.id 대응). NULL=시스템 자동 변경. FK 미적용.';
COMMENT ON COLUMN issue_change_group.created_at IS '변경 발생 시각. TIMESTAMPTZ (DATA.md §4).';

-- 이슈별 이력 최신순 조회 인덱스. (issue_id, created_at DESC, id DESC) — 같은 시각 그룹은 id 역순으로 안정 정렬.
CREATE INDEX idx_issue_change_group_issue ON issue_change_group (issue_id, created_at DESC, id DESC);

-- 2. issue_change_item 테이블
-- 변경 그룹에 속한 개별 필드 변경 항목. 한 그룹은 N 개의 필드 변경(예: 상태 + 담당자 동시 변경)을 가질 수 있다.
-- FK 는 group_id → issue_change_group(id) 하나만. issues / users 로의 FK 는 두지 않는다 (이력 보존 우선).
-- from_value/to_value: 원시 값(예: state key, user id). from_label/to_label: 표시용 라벨(예: "진행 중", "홍길동").
CREATE TABLE issue_change_item (
    -- id: IDENTITY 자동 발번 PK.
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- group_id: 소속 변경 그룹 (issue_change_group.id). 같은 테이블군 내 FK 적용.
    group_id    BIGINT       NOT NULL REFERENCES issue_change_group(id),
    -- field: 변경된 필드 식별자 (예: status, assignee, summary). 64자 제한.
    field       VARCHAR(64)  NOT NULL,
    -- from_value: 변경 전 원시 값 (예: state key, user id). NULL=값 없음.
    from_value  TEXT         NULL,
    -- to_value: 변경 후 원시 값. NULL=값 없음(필드 비움).
    to_value    TEXT         NULL,
    -- from_label: 변경 전 표시용 라벨 (사람이 읽는 값). NULL=라벨 없음.
    from_label  TEXT         NULL,
    -- to_label: 변경 후 표시용 라벨. NULL=라벨 없음.
    to_label    TEXT         NULL
);
COMMENT ON TABLE  issue_change_item            IS '변경 그룹 내 개별 필드 변경 항목. append-only (FR-HS-01).';
COMMENT ON COLUMN issue_change_item.group_id   IS '소속 변경 그룹 (issue_change_group.id). 같은 테이블군 내 실 FK 적용.';
COMMENT ON COLUMN issue_change_item.field      IS '변경된 필드 식별자 (예: status, assignee). 64자 제한.';
COMMENT ON COLUMN issue_change_item.from_value IS '변경 전 원시 값 (예: state key, user id). NULL=값 없음.';
COMMENT ON COLUMN issue_change_item.to_value   IS '변경 후 원시 값. NULL=값 없음(필드 비움).';
COMMENT ON COLUMN issue_change_item.from_label IS '변경 전 표시용 라벨 (사람이 읽는 값). NULL=라벨 없음.';
COMMENT ON COLUMN issue_change_item.to_label   IS '변경 후 표시용 라벨. NULL=라벨 없음.';

-- FK 인덱스 (DATA.md §7 — PostgreSQL 은 FK 에 인덱스 자동 생성 안 함). 그룹→항목 조인용.
CREATE INDEX idx_issue_change_item_group ON issue_change_item (group_id);

-- 필드별 이력 필터 인덱스 (예: 특정 필드의 변경만 조회).
CREATE INDEX idx_issue_change_item_field ON issue_change_item (field);
