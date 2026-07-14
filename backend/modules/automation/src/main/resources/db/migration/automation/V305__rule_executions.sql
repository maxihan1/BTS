-- 자동화 룰 실행 이력(감사/디버깅) 테이블 — 룰이 언제·어떤 트리거로·어떤 결과로 실행됐는지 1건씩 (FR-AT-05)
-- ⚠ V번호는 automation BC 예약 범위 V300~V399 (DATA.md §4 BC 별 100단위). V300~V304 사용 중 → V305.
--
-- RuleExecution(한 번의 룰 실행 이력) 애그리거트의 영속 저장소. AutomationExecutionWorker 가
-- ActionExecutor.execute() 반환값(status + outcomes)을 포착해 여기 1행으로 남긴다. 기록 범위는 실행
-- 시도분(SUCCESS/PARTIAL/FAILED + 조건불충족 SKIPPED)이며, 억제창·malformed 는 기록하지 않는다.
--
-- rule_id: automation_rules(id) 를 논리적으로 가리키되 **하드 FK 를 걸지 않는다**(감사 독립성 NFR-4) —
--   룰이 후일 하드 삭제돼도 실행 이력은 audit trail 로 보존돼야 하므로, 참조 무결성 대신 인덱스로만
--   조회 경로를 확보한다. 그래서 소프트 삭제된 룰의 이력도 rule_id/project_key 직접 조회로 접근 가능하다.
-- project_key: 스코프 목록/권한 판정을 룰 조인 없이 수행하기 위한 비정규화 컬럼(NFR-4 정합).
-- trigger_event: 실행 당시 원본 payload — replay(동기 재실행) 시 그대로 재사용하는 재료.
-- outcomes: 단계별 액션 결과 배열 [{position, actionType, success, error}](ActionOutcome 와이어 포맷).
-- replayed_from: 이 row 가 replay 로 생성됐다면 원본 실행 id. 최초 실행이면 NULL.
-- 평가/실행 로직은 DB 가 아니라 앱(ActionExecutor)이 수행한다 — DB 는 형식 없는 이력 저장만 담당한다.

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

-- 룰별 이력 목록(started_at DESC keyset) 조회 경로.
CREATE INDEX idx_rule_executions_rule ON rule_executions (rule_id, started_at DESC);
-- "이 이슈에 영향을 준 자동화" 조회 경로(프로젝트 스코프 + issueKey 필터, started_at DESC keyset).
CREATE INDEX idx_rule_executions_project_issue ON rule_executions (project_key, issue_key, started_at DESC);

COMMENT ON TABLE  rule_executions               IS '자동화 룰 실행 이력 — 룰당 N행, audit trail(FR-AT-05)';
COMMENT ON COLUMN rule_executions.id            IS '실행 이력 PK(UUID) — replayed_from 이 가리키는 대상';
COMMENT ON COLUMN rule_executions.rule_id       IS '실행된 룰 id(automation_rules.id) — 하드 FK 없음(감사 독립성 NFR-4)';
COMMENT ON COLUMN rule_executions.project_key   IS '룰 소속 프로젝트 키 — 비정규화(스코프 목록/권한 판정, 룰 조인 회피)';
COMMENT ON COLUMN rule_executions.trigger_type  IS 'fire-time 트리거 타입(ISSUE_CREATED/UPDATED/COMMENTED/SCHEDULED/WEBHOOK)';
COMMENT ON COLUMN rule_executions.trigger_event IS '실행 당시 원본 트리거 payload(JSONB) — replay 동기 재실행 재료';
COMMENT ON COLUMN rule_executions.issue_key     IS '대상 이슈 키 — SCHEDULED/WEBHOOK 등 이슈 무관 실행은 NULL';
COMMENT ON COLUMN rule_executions.status        IS '실행 결과 — SUCCESS/PARTIAL/FAILED/SKIPPED(조건불충족)';
COMMENT ON COLUMN rule_executions.outcomes      IS '단계별 액션 결과 배열 [{position,actionType,success,error}](ActionOutcome 와이어 포맷)';
COMMENT ON COLUMN rule_executions.replayed_from IS '이 row 가 replay 로 생성됐다면 원본 실행 id — 최초 실행이면 NULL';
COMMENT ON COLUMN rule_executions.started_at    IS '실행 시작 시각(UTC)';
COMMENT ON COLUMN rule_executions.finished_at   IS '실행 종료 시각(UTC)';
COMMENT ON COLUMN rule_executions.created_at    IS 'row 기록 시각(UTC)';
