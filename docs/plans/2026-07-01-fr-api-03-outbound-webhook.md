# FR-API-03 — Webhook (외부 시스템 통지)

> slug: fr-api-03-outbound-webhook
> type: feature
> agent: backend-engineer (D2/D4 HMAC·SSRF security-engineer 공동, D3 db-engineer, D6 frontend-engineer, D7 qa-engineer)
> 생성: 2026-07-01

## Brief

FR-API-03 — 구독형 아웃바운드 Webhook(외부 시스템 통지). search-export-import BC(§5.3).
선행. §5.1(FR-API-01 REST API 표준), identity-access §2.10(감사).

- D1. 도메인 — OutboundWebhook
- D2. 명세 — HMAC-SHA256 서명 + 재시도 + circuit breaker
- D3. 데이터 모델 — outbound_webhooks(url, secret_encrypted, event_filter) + webhook_deliveries(status, response_code)
- D4. 백엔드 — pgmq event → HTTP 발송 + 재시도
- D5. 백엔드 테스트 — 재시도 + circuit breaker
- D6. 프론트 UI — Webhook 관리 페이지 + 발송 이력
- D7. E2E

**핵심 쟁점 — FR-NT-05 중복**. notification BC가 이미 webhook 발송 인프라 보유
(WebhookDispatcher/WebhookUrlValidator/WebhookDispatchWorker, ADR 2026-06-14-fr-nt-05-webhook-dispatch-ssrf).
단 FR-NT-05는 워크플로우 전이 전용(post-action). FR-API-03는 구독형 범용(이벤트필터+HMAC+이력+circuit breaker).
→ (1) 인프라 재사용 범위, (2) BC 경계(search-export-import FR vs notification webhook 인프라)를 bts-domain서 확정.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
