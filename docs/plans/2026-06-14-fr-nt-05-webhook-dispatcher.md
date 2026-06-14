# FR-NT-05 PR2 — Webhook 디스패처 (q_transition_events 소비 + HTTP POST)

> slug: fr-nt-05-webhook-dispatcher
> type: backend
> agent: backend-engineer
> primary_bc: notification (classify는 project-workflow 오판 — /bts-domain서 확정)
> 생성: 2026-06-14

## Brief

FR-NT-05 PR2 — notification BC가 pgmq 큐 `q_transition_events`의 `WebhookRequested` 메시지를 소비해 외부 URL로 HTTP POST를 보내는 Webhook 디스패처를 구현한다. pgmq consumer 메시지 생명주기(delete/archive/stale 재청) 준수. 완료 시 FR-NT-05를 부분완료[~]에서 완료로 전환하고 product notification-dashboard.md §2.5 D단계를 마킹한다.

직전 PR1(#140, squash 01eec8b8)에서 **발행 파이프라인**은 이미 배선됨 — issue-tracking의 `transitionIssue()`가 `plan.emitEvents`(WebhookRequested 등 4종)를 전용 큐 `q_transition_events`에 outbox 발행. 이번 PR2는 그 큐의 **소비 + 외부 전송** 쪽.

classify: type=backend, agent=backend-engineer, slug=fr-nt-05-webhook-dispatcher

## 도메인 정리

- **BC: notification** (코드 실측 확정). classify는 "transition" 키워드로 project-workflow 오판. 실제는 notification BC가 `q_transition_events`를 소비. PR1 ADR(`2026-06-14-fr-nt-05-transition-event-outbox.md`)이 "PR2 = notification 소비/디스패처"를 명시적으로 예고 — 기존 결정과 충돌 0.
- **유일 소비자 확정**. `q_transition_events`는 PR1이 만든 전용 큐(V022). 현재 소비자 0. PR2 워커가 단독 소비자라 `NotificationWorker`의 q_issue_events 경쟁 소비 경고(여러 consumer가 메시지 선점) 해당 없음.
- **소비할 메시지 형식** (V022 주석 + 발행측 실측). `{"type":"WebhookRequested","payload":{"issueKey":"...","url":"...","method":"..."}}`. `CallWebhookPostAction.evaluate()`가 만들고 `TransitionEventPublisher.publish()`가 `pgmq.send(q_transition_events, json::jsonb)`로 발행.
- **정석 템플릿 = `NotificationWorker`** (`backend/modules/notification/.../worker/NotificationWorker.kt`). pgmq 생명주기 패턴 그대로 차용: `@Scheduled` 폴링 → `pgmq.read(q, vt, batch)` → 성공시 `pgmq.delete` → 예외시 delete 안 함(vt 만료 재전달, at-least-once) → `read_ct>MAX_RECEIVE_COUNT`시 `pgmq.archive`(dead-letter). `@Transactional` 없음(의도적, learnings transaction-self-invocation). 미지원 type은 delete(ack)로 무한 재전달 회피.
- **신규 용어**: 없음 (Webhook·디스패처는 표준어, glossary 추가 불요). glossary grep 0건 — 기존 미등록이나 일반 용어라 신설 안 함.
- **신규 ADR 후보**: **첫 백엔드 아웃바운드 HTTP**(외부 URL POST)라 SSRF 방어 수위·HTTP 클라이언트·재시도 의미를 `/bts-spec`/`/bts-review-plan`에서 결정 후 ADR 작성 여부 판단.
- **관련 ADR**: [2026-06-14-fr-nt-05-transition-event-outbox.md](../decisions/2026-06-14-fr-nt-05-transition-event-outbox.md) (PR1, 본 PR이 그 큐·envelope 소비), [2026-06-12-notification-inapp-channel-delivery.md](../decisions/2026-06-12-notification-inapp-channel-delivery.md) (인앱 채널 선례).

## 스펙

전체 스펙. [docs/specs/2026-06-14-fr-nt-05-webhook-dispatcher.md](../specs/2026-06-14-fr-nt-05-webhook-dispatcher.md)

핵심 3줄 요약.
- notification BC `WebhookDispatchWorker`가 `q_transition_events`를 폴링 → `WebhookRequested`만 외부 URL로 RestClient 전송, 생명주기는 NotificationWorker와 동일(read→delete/재전달/archive).
- **D1 SSRF 가드** — DNS 해석 후 내부망 IP(loopback/link-local/private/metadata) + 비-http 스킴 차단. **D2 body** — `{"event":"WebhookRequested","issueKey":"<key>"}` + application/json.
- 신규 마이그레이션 0(큐는 PR1 V022가 생성). RestClient는 spring-web 내장(신규 의존성 0).

Maxi 게이트 결정. D1=내부망 차단 리스트, D2=JSON 엔벨로프.

## Brainstorming Check

✅ 통과 (직접 sanity check, 보안 갭 2건 발견·반영).
- G1 → FR8 추가. RestClient 리다이렉트 추적 차단(3xx 경유 SSRF 우회 방지).
- G2 → 한계 명시. TOCTOU/DNS rebinding은 resolve-then-connect 구조 한계, admin URL+PR2 범위 수용·문서화.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
