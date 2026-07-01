# FR-API-03 PR3 — Webhook 발송 실행 계층 (fanout/dispatch)

> slug: fr-api-03-pr3-webhook-dispatch
> type: api
> agent: backend-engineer
> 생성: 2026-07-01

## Brief

FR-API-03(구독형 아웃바운드 Webhook)의 4-PR 분할 중 **PR3 — 발송 실행 계층**.
PR1(shared-kernel 추출, #211)·PR2(구독 CRUD + 테이블, #212) 완료 후속.

범위:
- issue-tracking `IssueEventPublisher` dual-send — 전용 큐 `q_webhook_events`로도 이벤트 발행
  (q_issue_events는 NotificationWorker 독점소비라 경합불가 — ADR 확정)
- search-export-import fanout/dispatch 워커 — 큐 소비 → 구독 매칭(event_filter) → 발송
- HMAC-SHA256 서명 (요청 헤더)
- circuit breaker (연속 실패 시 구독 일시 차단)
- 발송이력 기록 (`webhook_deliveries` append-only 테이블 — PR2 V603에서 생성됨)

관련: ADR docs/decisions/2026-07-01-fr-api-03-outbound-webhook-bc-and-reuse.md
FR-NT-05 webhook dispatcher(notification 전이 webhook)의 상위집합.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
