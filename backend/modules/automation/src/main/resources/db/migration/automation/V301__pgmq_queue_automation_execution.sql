-- automation BC pgmq 실행 큐 — q_automation_execution (FR-AT-01, ADR D4)
-- ⚠ V번호는 automation BC 예약 범위 V300~V399 (DATA.md §4 BC 별 100단위).
--
-- 큐 소유권(plan-eng-review E1 확정 — q_webhook_events 선례 준거).
--   • q_automation_execution = **automation 소유**(이 파일). 매칭된 트리거가 { ruleId, triggerType, triggerEvent }
--     를 적재하고, FR-AT-02 액션 executor 가 소비한다. FR-AT-01 은 발화 이음선만(액션 executor 는 후행 FR).
--   • q_automation_events = **여기서 생성하지 않는다**. producer 인 issue-tracking 이 Task 10 에서
--     소유·생성한다(V0xx__pgmq_queue_automation_events.sql). automation `AutomationEventWorker` 는 그 큐를
--     fan-out 으로 받아 소비만 한다(ADR D2). 소비자가 producer 소유 큐를 중복 생성하지 않는다.
--
-- enqueue 방식: pgmq.send('q_automation_execution', payload::jsonb) — DATA.md §7.2 트랜잭션 발행 규칙 준수(Task 4).
-- 컨슈머: FR-AT-02 액션 executor(별도 워커) — 본 마이그레이션 범위 밖.

-- pgmq extension 보장 — automation 모듈은 독립 Flyway namespace 라 확장을 자체 체인에서 명시 생성한다
-- (issue-tracking V002 동형, IF NOT EXISTS 로 공유 DB 재실행 멱등).
CREATE EXTENSION IF NOT EXISTS pgmq CASCADE;

-- 큐 생성 — q_automation_execution (automation 소유)
SELECT pgmq.create('q_automation_execution');
