-- issue-tracking 모듈 pgmq 큐 — q_transition_events. 워크플로우 전이 post-action 도메인 이벤트 발행용.
--
-- 발행 이벤트 (com.bts.shared.workflow.DomainEvent):
--   - WebhookRequested — 전이 완료 후 Webhook 호출이 필요한 경우.
--                        payload: { type, payload: { issueKey, url, method, ... } }
--   - 이후 추가 post-action 이벤트도 동일 큐를 사용한다.
--
-- enqueue 방식: pgmq.send(queue_name, payload::jsonb) — DATA.md §7.2 트랜잭션 발행 규칙 준수.
-- 컨슈머: 별도 워커 프로세스 또는 @Scheduled 폴링 (notification/automation BC, 본 마이그레이션 범위 외).

-- pgmq extension 보장 — V002 에서 이미 적용됐으나 모듈별 Flyway namespace 라 명시
CREATE EXTENSION IF NOT EXISTS pgmq CASCADE;

-- 큐 생성 — q_transition_events
SELECT pgmq.create('q_transition_events');
