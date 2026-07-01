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

## 도메인 정리

- **BC**: search-export-import (FR 소속 BC 존중 — Maxi 옵션 A 확정)
- **신규 엔티티/도메인**: `OutboundWebhook`(구독: url + secret_encrypted + event_filter), `WebhookDelivery`(발송 이력: status + response_code), circuit breaker 상태.
- **재사용(shared-kernel 추출)**: notification BC의 `WebhookUrlValidator`(SSRF) + HTTP 클라이언트 설정 → `com.bts.shared.http`로 추출, FR-NT-05와 공유. notification 코드 1회 리팩터링(문서화된 BC 경계 교차).
- **FR-NT-05 관계**: 전이 webhook(post-action)은 그대로 유지, 흡수 안 함. 범용 구독 webhook과 공존.
- **신규 용어 후보(glossary 승인 대기)**: "아웃바운드 Webhook(구독형)", "Webhook Delivery(발송 이력)", "Circuit Breaker(연속 실패 차단)", "HMAC 서명". → Maxi 승인 후 glossary 추가.
- **선행**: FR-API-01/02(REST API 표준 — 페이지네이션/에러봉투) 따름. identity-access §2.10(감사).
- **기존 결정 충돌**: 없음. FR-NT-05 ADR이 예견한 "재사용 결정" 실현.
- **관련 ADR**: [docs/decisions/2026-07-01-fr-api-03-outbound-webhook-bc-and-reuse.md](../decisions/2026-07-01-fr-api-03-outbound-webhook-bc-and-reuse.md) (생성됨), [2026-06-14-fr-nt-05-webhook-dispatch-ssrf.md](../decisions/2026-06-14-fr-nt-05-webhook-dispatch-ssrf.md)
- **마이그레이션 V번호**: search-export-import 최신 V602 → 신규 V603 후보(머지 직전 재확인).

## 스펙

전체 스펙. [docs/specs/2026-07-01-fr-api-03-outbound-webhook.md](../specs/2026-07-01-fr-api-03-outbound-webhook.md)

**전체 FR = 4-PR 분할(Maxi 확정). 본 run = PR1(shared 추출)만.**
- PR1(본 run): SSRF URL 검증기 + HTTP 클라이언트 설정 → shared-kernel `com.bts.shared.http` 추출, FR-NT-05 교체. **순수 리팩터·동작불변**.
- PR2: OutboundWebhook 구독 CRUD + secret 암호화 + V603 테이블.
- PR3: issue-tracking dual-send(q_webhook_events) + fanout/dispatch + HMAC-SHA256 + circuit breaker + 발송이력.
- PR4: 관리 UI + 발송이력 + E2E.

이벤트 소싱(Maxi Q1=A). issue-tracking IssueEventPublisher가 q_webhook_events로도 dual-send → search 워커 소비. (PR3)

핵심 사실(Explore 조사). q_issue_events는 NotificationWorker 독점소비(경합불가). SecretEncryptor(AES-256-GCM, identity-access) 재사용. REST 봉투=AqlSearchPageResponse. validator BC의존 0(추출 안전). shared-kernel은 spring-context 보유하나 **spring-web 없음**(HTTP config 이관 시 추가 필요).

## Brainstorming Check

✅ 통과 (직접 기술 스펙 — 정의된 순수 리팩터). Sanity gap 5건 스펙 §5 선반영. (1)설정키 이관 동작보존 (2)RestClient 빈 주입 (3)spring-web shared 추가 (4)테스트 이전 가짜그린 (5)ArchUnit BC→shared 정방향 확인.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
