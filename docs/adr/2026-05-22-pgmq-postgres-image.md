<!-- ADR: pgmq 포함 postgres 이미지 결정 — dev/test/prod 일관 -->
<!-- RED: infra/docker-compose.dev.yml postgres:16-alpine 에 pgmq 미포함 확인 — V002__pgmq_queue_issue_events.sql CREATE EXTENSION pgmq 실패 위험 -->
