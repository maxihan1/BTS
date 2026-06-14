# FR-NT-05 — Webhook 알림 채널 (PR 1: 전이 이벤트 pgmq 발행 파이프라인)

> slug: fr-nt-05-transition-event-publish
> type: backend
> agent: backend-engineer
> primary_bc: project-workflow
> 생성: 2026-06-14

## Brief

**신규 FR — FR-NT-05 Webhook 알림 채널** (FR-NT-02에서 분리, ADR `2026-06-12-notification-inapp-channel-delivery.md` Amendment 2026-06-14, Maxi 확정).

FR-NT-05 전체는 cross-BC라 PR 2개로 분할.
- **PR 1 (이번 작업)**: project-workflow BC — 워크플로우 전이 post-action(`CallWebhookPostAction` 등)이 발행하는 이벤트(`WebhookRequested`)를 실제 pgmq 큐에 발행하는 전이 emitEvents 파이프라인.
- **PR 2 (후속 /bts)**: notification BC — `WebhookRequested` 소비 + 외부 URL HTTP POST 디스패처.

**현황(코드 실측)**: `WebhookRequested` 이벤트는 현재 전이 API 응답 `TransitionResponseDto.events`에만 존재하고 **어느 pgmq 큐에도 발행되지 않음**. 이번 PR이 그 발행 파이프라인을 만든다.

**Maxi 게이트 결정(2026-06-14)**:
- FR ID = **FR-NT-05** (알림 FR-NT 그룹, fr-index BC 컬럼=notification). FR 총수 122→123.
- 이번 /bts 실행 = project-workflow 발행 파이프라인 (PR 1). notification 디스패처(PR 2)는 이 이벤트에 의존하므로 후속.

**신규 FR이므로 전수 동기화 필수** (CLAUDE.md §명세/범위 변경, `scripts/verify-master-plan.sh` 통과):
fr-index(FR-NT 4→5·합계 122→123·§A.2 카운트·상단 주석) + SDD(§9 알림 + 02-requirements FR 표) + product `notification-dashboard.md`(FR-NT-02 §2.2 Webhook 제거 미동기 잔재 정리 + 신규 FR-NT-05 §) + README §1 BC 테이블 + CLAUDE.md(122 표기) + ADR + Obsidian. fr-index의 `FR-NT-02 | 채널 (...Webhook...)` stale 행도 이 PR에서 정리.

## 도메인 정리 (← /bts-domain 채움)

## 스펙 (← /bts-spec Phase A 채움)

## Brainstorming Check (← /bts-spec Phase B 채움)

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
