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

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
