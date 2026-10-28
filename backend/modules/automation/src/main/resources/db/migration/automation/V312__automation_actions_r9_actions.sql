-- automation BC 액션 화이트리스트 확장 — automation_actions.action_type 5종 → 9종 (R9 단계 C · FR-AT-02 · JAU-6)
-- ⚠ V번호는 automation BC 예약 범위 V300~V399 (DATA.md §4 BC 별 100단위). V300~V311 사용 중 → V312.
--
-- TRANSITION:   이슈 상태 전환 {toState}.
-- CREATE_ISSUE: 룰 프로젝트에 이슈/하위 작업 생성 {summary, issueTypeKey, asSubtask}.
-- LINK_ISSUE:   트리거 이슈를 다른 이슈와 연결 {linkTypeCode, targetIssueKey}.
-- ADD_WATCHER:  트리거 이슈에 관찰자 추가 {userId} 또는 {fromField}.
-- Kotlin ActionType enum 에도 같은 상수가 추가된다(앱·DB 이중 방어 유지).
-- 스펙: docs/specs/2026-10-28-r9-automation-catalog.md §C.
--
-- V306 이 만든 CHECK 제약(5종)은 이미 적용된 마이그레이션이라 편집하지 않는다(Flyway 체크섬 불변).
-- V306 과 같은 절차로 DROP → ADD 한다. 제약명은 그대로 둔다(테스트·운영 진단이 이름으로 식별).
--
-- action_type 은 VARCHAR(20) — 가장 긴 'SET_FIX_VERSIONS' 16자 · 새 값 중 가장 긴 'CREATE_ISSUE' 12자라 폭 변경 불필요.
-- 기존 행은 모두 5종 중 하나이므로 새 CHECK(9종 상위집합)를 즉시 만족한다 — backfill 불필요, 검증 실패 없음.

ALTER TABLE automation_actions DROP CONSTRAINT ck_automation_actions_action_type;

ALTER TABLE automation_actions ADD CONSTRAINT ck_automation_actions_action_type CHECK (
    action_type IN (
        'SET_FIELD', 'ASSIGN', 'ADD_COMMENT', 'CALL_WEBHOOK', 'SET_FIX_VERSIONS',
        'TRANSITION', 'CREATE_ISSUE', 'LINK_ISSUE', 'ADD_WATCHER'
    )
);

-- COMMENT 재발행 — 살아있는 DB 객체의 「5종」 drift 방지(V306 · V311 동형).
COMMENT ON COLUMN automation_actions.action_type IS '액션 종류 — CHECK 9종(SET_FIELD/ASSIGN/ADD_COMMENT/CALL_WEBHOOK/SET_FIX_VERSIONS/TRANSITION/CREATE_ISSUE/LINK_ISSUE/ADD_WATCHER)';
