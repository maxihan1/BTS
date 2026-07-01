# FR-API-03 PR2 — 구독형 아웃바운드 Webhook 구독 관리 계층

> slug: fr-api-03-pr2-webhook-subscription-crud
> type: feature
> agent: backend-engineer (마이그레이션=db-engineer, secret 암호화=security-engineer는 plan task meta로 지정)
> 생성: 2026-07-01

## Brief

FR-API-03(외부 시스템 통지용 구독형 아웃바운드 Webhook, search-export-import BC)의 **PR2 — 구독 관리 계층**.

범위 (이번 PR):
- `OutboundWebhook` 구독 도메인 애그리거트
- 구독 CRUD REST API (생성/조회/수정/삭제)
- secret 암호화: identity-access `SecretEncryptor`(AES-256-GCM) 재사용
- V603 마이그레이션: `outbound_webhooks(url, secret_encrypted, event_filter)` + `webhook_deliveries(status, response_code)` 테이블

범위 제외 (PR3 이후):
- 실제 이벤트 발송 (issue-tracking dual-send → q_webhook_events, fanout/dispatch 워커, HMAC-SHA256 서명, circuit breaker, 발송 이력 기록)
- 관리 UI + E2E (PR4)

선행:
- PR1(shared-kernel `com.bts.shared.http` 인프라 추출: OutboundUrlValidator + OutboundHttpClientConfig)은 main 머지 완료 (#211, squash f339ff06).
- 관련 ADR: docs/decisions/2026-07-01-fr-api-03-outbound-webhook-bc-and-reuse.md
- 참고: FR-NT-05 전이 webhook(이미 완료) — 상위집합 관계.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
