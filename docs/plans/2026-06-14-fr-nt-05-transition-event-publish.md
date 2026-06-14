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

## 도메인 정리

- **BC: issue-tracking** (classify 초기 `project-workflow`에서 정정 — Maxi 확정 2026-06-14). 담당 에이전트 backend-engineer 동일.
- **핵심 발견(코드 실측)**:
  - `WorkflowPostAction` SPI는 **계산만**(GAP-2): `evaluate(ctx) → PostActionPlan(fieldChanges, emitEvents)`. 실행(필드 적용 + 이벤트 발행)은 **호출자 BC 책임**.
  - `CallWebhookPostAction.evaluate()`가 `DomainEvent("WebhookRequested", {issueKey, url, method})`를 `emitEvents`에 담아 반환. `WorkflowEngine.runPostActions()`가 누적 → `TransitionPlan.emitEvents`.
  - **실제 전이 실행 경로** = `IssueApplicationService.transitionIssue()` (issue-tracking). `workflowPort.plan()`으로 `TransitionPlan`을 받아 `plan.toStateKey`만 사용하고 **`plan.emitEvents`(+post-action fieldChanges)를 그대로 버림**. → WebhookRequested가 어느 큐에도 발행 안 되는 근본 원인.
  - project-workflow `POST /workflows/{key}/transitions`(`WorkflowController.plan`)는 **dry-run 미리보기 전용** — 상태 변경 없이 `TransitionResponseDto.events`로 노출만. `WorkflowEngine.plan()`은 `Propagation.MANDATORY`로 미리보기·실행 양쪽에서 호출되므로 거기서 발행하면 미리보기에서도 webhook 발사(오발사 버그).
- **결정(Maxi 확정 2026-06-14)**: 발행 파이프라인은 **issue-tracking `transitionIssue()`**가 소유. `repo.applyTransition()` 성공(updatedRows≠0) 직후, 같은 클래스-레벨 `@Transactional`(REQUIRED) 안에서 `plan.emitEvents`를 pgmq에 발행(transaction outbox 정합, DATA.md §7.2). 기존 `IssueEventPublisher`/`q_issue_events` 패턴 재사용 후보(큐 선택은 spec에서 확정).
- **기존 결정 충돌(정정됨)**: ADR `2026-06-12-notification-inapp-channel-delivery.md` Amendment의 "① project-workflow BC에 전이 emitEvents 발행 파이프라인" 표현이 GAP-2 아키텍처와 충돌 → FR-NT-05 ADR에서 issue-tracking으로 정정.
- **새 용어**: 없음 (WebhookRequested·PostActionPlan·emitEvents·transaction outbox 모두 기존). glossary 변경 없음.
- **관련 ADR**: [docs/decisions/2026-06-14-fr-nt-05-transition-event-outbox.md](../decisions/2026-06-14-fr-nt-05-transition-event-outbox.md) (생성) · [2026-06-12-notification-inapp-channel-delivery.md](../decisions/2026-06-12-notification-inapp-channel-delivery.md) (정정 노트 추가)
- **PR 2(후속)**: notification BC가 발행된 WebhookRequested를 소비 + 외부 URL HTTP POST 디스패처.

## 스펙

전체 스펙. [docs/specs/2026-06-14-fr-nt-05-transition-event-publish.md](../specs/2026-06-14-fr-nt-05-transition-event-publish.md)

핵심 요약.
- `transitionIssue()`가 `applyTransition` 성공 직후 같은 트랜잭션에서 `plan.emitEvents` **전부**를 신규 큐 `q_transition_events`에 발행(generic outbox). 현재는 버려짐.
- 신규 `TransitionEventPublisher`(@Component, MANDATORY) — `IssueEventPublisher` 1:1 미러. Flyway V022로 `q_transition_events` 큐 생성 + init_codegen 미러.
- bulk 전이는 `transitionIssue()` 경유라 자동 커버. dry-run·롤백은 미발행. post-action 미설정 시 no-op(현 default).
- 신규 FR → fr-index/SDD/product/README/CLAUDE 전수 동기화(FR-NT 4→5, 122→123) + verify-master-plan 통과.

**Maxi 확인 포인트(게이트 1)**:
- (1) generic 발행(모든 emitEvents 4종) vs WebhookRequested만 — 권장 generic(ADR "4종 공통"+근본 결함 수정, shared-kernel KDoc 계약).
- (2) 전용 큐 `q_transition_events` 신규 — 권장(q_issue_events 오염 회피).
- (3) post-action fieldChanges 미적용은 범위 밖(별개 기능)으로 분리.

## Brainstorming Check

✅ 통과 (직접 비판적 gap 분석 — backend 직접 스펙). 코드 실측 검증: TransitionResult→emitEvents 운반·KDoc 계약 확인, bulk 단일 변경점, createIssue 범위 밖, 전용 큐 필요, IT 참조 패턴 확보.

## Plan (← /bts-plan 채움)

## 리뷰 결과 (← /bts-review-plan 채움)
