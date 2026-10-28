-- automation BC 트리거 화이트리스트 확장 — automation_rules.trigger_type 6종 → 9종 (R9 · FR-AT-01 · JAU-5)
-- ⚠ V번호는 automation BC 예약 범위 V300~V399 (DATA.md §4 BC 별 100단위). V300~V310 사용 중 → V311.
--
-- ISSUE_TRANSITIONED: 이슈 상태 전환(issue.transitioned)에 발화. triggerConfig fromStates/toStates 로 거른다.
-- ISSUE_ASSIGNED:     담당자 변경(issue.assigned)에 발화.
-- MANUAL:             이슈 화면에서 사람이 골라 실행(POST …/automation/rules/{id}/run).
-- Kotlin TriggerType enum 에도 같은 상수가 추가된다(앱·DB 이중 방어 유지).
-- 스펙: docs/specs/2026-10-28-r9-automation-catalog.md §A·§B.
--
-- V309 가 만든 CHECK 제약(6종)은 이미 적용된 마이그레이션이라 편집하지 않는다(Flyway 체크섬 불변).
-- V309 와 같은 절차로 DROP → ADD 한다. 제약명은 그대로 둔다(테스트·운영 진단이 이름으로 식별).
--
-- trigger_type 은 VARCHAR(20) — 가장 긴 'ISSUE_TRANSITIONED' 가 18자라 폭 변경 불필요.
-- 기존 행은 모두 6종 중 하나이므로 새 CHECK(9종 상위집합)를 즉시 만족한다 — backfill 불필요, 검증 실패 없음.

ALTER TABLE automation_rules DROP CONSTRAINT ck_automation_rules_trigger_type;

ALTER TABLE automation_rules ADD CONSTRAINT ck_automation_rules_trigger_type CHECK (
    trigger_type IN (
        'ISSUE_CREATED', 'ISSUE_UPDATED', 'ISSUE_COMMENTED', 'SCHEDULED', 'WEBHOOK', 'PR_MERGED',
        'ISSUE_TRANSITIONED', 'ISSUE_ASSIGNED', 'MANUAL'
    )
);

-- COMMENT 재발행 — 살아있는 DB 객체의 「6종」 drift 방지(V309 · V310 동형). rule_executions.trigger_type 은
-- CHECK 없는 TEXT 지만 새 트리거의 실행 이력이 적재되므로 같이 갱신한다(V310 의 누락 교훈).
COMMENT ON COLUMN automation_rules.trigger_type IS '트리거 종류 — CHECK 9종(ISSUE_CREATED/ISSUE_UPDATED/ISSUE_COMMENTED/SCHEDULED/WEBHOOK/PR_MERGED/ISSUE_TRANSITIONED/ISSUE_ASSIGNED/MANUAL)';
COMMENT ON COLUMN rule_executions.trigger_type  IS 'fire-time 트리거 타입(ISSUE_CREATED/UPDATED/COMMENTED/SCHEDULED/WEBHOOK/PR_MERGED/ISSUE_TRANSITIONED/ISSUE_ASSIGNED/MANUAL)';
