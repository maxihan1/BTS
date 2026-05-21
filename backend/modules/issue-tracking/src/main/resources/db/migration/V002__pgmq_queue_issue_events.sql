-- issue-tracking 모듈 pgmq 큐 — q_issue_events. 발행 이벤트 4종 (IssueCreated/IssueUpdated/IssueTransitioned/IssueSoftDeleted). PR #10 의 pgmq 확장 활용.
--
-- 이벤트 발행 4종 (com.bts.issue.event.IssueDomainEvent 하위 sealed class):
--   1. IssueCreated     — IssueApplicationService.createIssue() 성공 직후, 동일 트랜잭션 내 enqueue.
--                         payload: { eventType, issueId, issueKey, projectId, reporterId, summary, createdAt }
--   2. IssueUpdated     — IssueApplicationService.updateIssue() 성공 직후, 동일 트랜잭션 내 enqueue.
--                         payload: { eventType, issueId, issueKey, changedFields: [...], updatedAt }
--   3. IssueTransitioned — IssueApplicationService.transitionIssue() 성공 직후, 동일 트랜잭션 내 enqueue.
--                         payload: { eventType, issueId, issueKey, fromState, toState, actorId, transitionedAt }
--   4. IssueSoftDeleted  — IssueApplicationService.deleteIssue() 성공 직후, 동일 트랜잭션 내 enqueue.
--                         payload: { eventType, issueId, issueKey, deletedAt }
--
-- enqueue 방식: pgmq.send(queue_name, payload::jsonb) — DATA.md §7.2 트랜잭션 발행 규칙 준수.
-- 컨슈머: 별도 워커 프로세스 또는 @Scheduled 폴링 (backend-engineer 영역, 본 마이그레이션 범위 외).

-- pgmq extension 보장 — PR #10 이 project-workflow V001 에서 도입했으나 모듈별 Flyway namespace 라 명시
CREATE EXTENSION IF NOT EXISTS pgmq CASCADE;

-- 큐 생성 — q_issue_events
SELECT pgmq.create('q_issue_events');

COMMENT ON SCHEMA pgmq IS 'PostgreSQL 기반 메시지 큐 (Kafka 대체). DATA.md §7.2.';
