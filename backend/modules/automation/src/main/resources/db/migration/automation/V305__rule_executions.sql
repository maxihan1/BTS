-- 자동화 룰 실행 이력(감사/디버깅) 테이블 — 룰이 언제·어떤 트리거로·어떤 결과로 실행됐는지 1건씩 (FR-AT-05)
-- ⚠ V번호는 automation BC 예약 범위 V300~V399 (DATA.md §4 BC 별 100단위). V300~V304 사용 중 → V305.
--
-- rule_id 는 automation_rules(id) 를 참조하되 **하드 FK 를 걸지 않는다**(감사 독립성 NFR-4) —
--   룰이 후일 하드 삭제돼도 실행 이력은 audit trail 로 보존돼야 하므로 인덱스로만 조회 경로를 확보한다.

CREATE TABLE rule_executions (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    rule_id       UUID        NOT NULL,                 -- 하드 FK 없음(감사 독립성 NFR-4)
    project_key   TEXT        NOT NULL,                 -- 비정규화(스코프 목록/권한)
    trigger_type  TEXT        NOT NULL,                 -- fire-time 트리거 타입
    trigger_event JSONB       NOT NULL,                 -- 원본 payload(replay 재료)
    issue_key     TEXT,                                 -- 대상 이슈 키(SCHEDULED/WEBHOOK 은 NULL)
    status        TEXT        NOT NULL,                 -- SUCCESS/PARTIAL/FAILED/SKIPPED
    outcomes      JSONB       NOT NULL DEFAULT '[]',    -- [{position,actionType,success,error}]
    replayed_from UUID,                                 -- 이 row 가 replay 면 원본 실행 id
    started_at    TIMESTAMPTZ NOT NULL,                 -- 실행 시작 시각(DATA.md §4 — TIMESTAMPTZ 강제)
    finished_at   TIMESTAMPTZ NOT NULL,                 -- 실행 종료 시각(TIMESTAMPTZ 강제)
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()    -- row 기록 시각(TIMESTAMPTZ 강제)
);

CREATE INDEX idx_rule_executions_rule ON rule_executions (rule_id, started_at DESC);
CREATE INDEX idx_rule_executions_project_issue ON rule_executions (project_key, issue_key, started_at DESC);
