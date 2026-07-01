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

- **BC**: search-export-import(webhook 발송 주체) + issue-tracking(dual-send = 이벤트 발행만, BC 격리 준수).
  두 BC 걸침이나 issue-tracking 쪽은 pgmq 이벤트 발행 추가뿐이라 격리 예외 아님(직접 import 0).
- **재사용 (grill-with-docs 대신 ADR/코드 실증)**:
  - ADR `2026-07-01-fr-api-03-outbound-webhook-bc-and-reuse.md`가 BC 배치·재사용·이벤트소싱을 이미 확정 → grill 인터뷰 가치 낮음.
  - PR2가 도메인(`OutboundWebhook`, `WebhookEventCatalog.PUBLISHABLE={issue.created, issue.transitioned}`)·테이블(V603)·구독 CRUD 완료·머지.
  - FR-NT-05 `WebhookDispatcher`(notification): SSRF(`OutboundUrlValidator` shared)+RestClient(`Redirect.NEVER`)+pgmq 워커 생명주기(Sent/Rejected→delete, Failed→VT 재전달, read_ct>MAX→archive). **PR3는 이 위에 HMAC·구독매칭·발송이력·circuit breaker를 신규 추가**(상위집합).
- **dual-send 접점**: `IssueEventPublisher.publish()`(issue-tracking, `@Transactional MANDATORY` outbox). 현재 `q_issue_events`만 → PUBLISHABLE 이벤트를 `q_webhook_events`로도 발행.
- **이벤트 payload**: `IssueDomainEvent` 7종 중 `IssueCreated`(projectKey 有)·`IssueTransitioned`(**projectKey 無** — issueKey에서 파싱 필요). 구독 projectKey 필터 매칭 시 고려.
- **새 도메인 요소**: `WebhookDelivery`(발송 이력 레코드, `webhook_deliveries` 매핑), circuit breaker(연속 실패 차단), HMAC-SHA256 서명. 모두 ADR/product §5.3에 이미 정의됨(신규 용어 아님).
- **기존 결정 충돌**: 없음. ADR이 재사용을 예견.
- **관련 ADR**: [2026-07-01-fr-api-03-outbound-webhook-bc-and-reuse.md](../decisions/2026-07-01-fr-api-03-outbound-webhook-bc-and-reuse.md), [2026-06-14-fr-nt-05-webhook-dispatch-ssrf.md](../decisions/2026-06-14-fr-nt-05-webhook-dispatch-ssrf.md)
- **스키마 공백(PR3 열린 결정)**: `outbound_webhooks`/`webhook_deliveries`에 circuit breaker 상태 필드 없음 → 저장 위치 결정 필요(spec Phase에서 Maxi 확정).

## 스펙

전체 스펙. [docs/specs/2026-07-01-fr-api-03-pr3-webhook-dispatch.md](../specs/2026-07-01-fr-api-03-pr3-webhook-dispatch.md)

핵심 3줄.
- issue-tracking `IssueEventPublisher`가 PUBLISHABLE 2종(issue.created·issue.transitioned)을 `q_webhook_events`(신규 V034)로도 dual-send.
- search 워커가 큐 소비 → event_filter+projectKey 매칭 구독 fanout → HMAC-SHA256 서명 발송 → `webhook_deliveries` 이력 → in-memory circuit breaker(5회/60초/half-open).
- 발송 이력 조회 API `GET /webhooks/{id}/deliveries`(SYSTEM_ADMIN) PR3 포함. 부분 실패는 delete+이력/circuit 관찰(개별 재시도 큐 없음).

**Maxi 확정 (2회 AskUserQuestion)**.
- circuit breaker = in-memory + 이번 PR 포함 (5회/60초/half-open).
- 발송 이력 조회 API = PR3 포함.
- fanout 부분 실패 = delete + 이력/circuit 관찰 (pgmq VT 재전달만).

## Brainstorming Check

✅ 통과 (gap 3건 식별·해소 — 카탈로그 2종 drift 정본화 / 이력 조회 API PR3 포함 / fanout 재시도 정책). 상세는 spec §Brainstorming Check.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
