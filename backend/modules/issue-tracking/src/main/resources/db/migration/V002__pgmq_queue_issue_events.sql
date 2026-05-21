-- issue-tracking 모듈 pgmq 큐 — q_issue_events. 발행 이벤트 4종 (IssueCreated/IssueUpdated/IssueTransitioned/IssueSoftDeleted). PR #10 의 pgmq 확장 활용.

-- pgmq extension 보장 — PR #10 이 project-workflow V001 에서 도입했으나 모듈별 Flyway namespace 라 명시
CREATE EXTENSION IF NOT EXISTS pgmq CASCADE;

-- 큐 생성 — q_issue_events
SELECT pgmq.create('q_issue_events');

COMMENT ON SCHEMA pgmq IS 'PostgreSQL 기반 메시지 큐 (Kafka 대체). DATA.md §7.2.';
