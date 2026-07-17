-- rule_executions.trigger_type COMMENT 재발행 — 트리거 5종 → 6종 drift 해소 (FR-AT-07 PR-C)
-- ⚠ V번호는 automation BC 예약 범위 V300~V399 (DATA.md §4 BC 별 100단위). V300~V309 사용 중 → V310.
--
-- V305:41 의 COMMENT 는 트리거를 5종(ISSUE_CREATED/UPDATED/COMMENTED/SCHEDULED/WEBHOOK)만 열거한다.
-- 그런데 PR_MERGED 실행이 **이 컬럼에 실제로 적재된다**(GitWebhookService → q_automation_execution →
-- 실행 워커 → RuleExecutionRepository). 즉 살아있는 DB 객체의 코멘트가 실제 적재 값과 어긋나 있다.
--
-- V309 는 automation_rules.trigger_type 의 동형 문제를 인지해 COMMENT 를 재발행했지만
-- rule_executions.trigger_type 을 빠뜨렸다. 이 마이그레이션이 그 누락분만 채운다.
--
-- ★ V305 를 직접 편집하지 않는 이유 — 이미 적용된 마이그레이션이라 편집하면 Flyway 체크섬 드리프트가
--   발생한다(적용된 마이그레이션 불변). V309 역시 dev DB(5433)에 이미 적용돼 있어(flyway_history_automation
--   조회로 확인) 편집 대상이 아니다. 그래서 신규 V310 으로 발행한다(V306:21-22 · V309:22-25 동형 절차).
--
-- COMMENT 재발행은 DDL 이지만 데이터·제약을 건드리지 않는다 — 잠금 시간이 짧고 backfill 도 불필요하다.
-- CHECK 제약은 rule_executions 에 없다(실행 이력은 감사 독립성상 트리거 화이트리스트를 강제하지 않는다).

COMMENT ON COLUMN rule_executions.trigger_type IS 'fire-time 트리거 타입(ISSUE_CREATED/UPDATED/COMMENTED/SCHEDULED/WEBHOOK/PR_MERGED)';
