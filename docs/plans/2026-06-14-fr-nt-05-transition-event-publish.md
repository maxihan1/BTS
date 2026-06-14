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

## Plan

### Task 1. q_transition_events 큐(V022 + init_codegen 미러) + TransitionEventPublisher

**메타**.
- agent: `backend-engineer`
- files:
  - `backend/modules/issue-tracking/src/main/resources/db/migration/issue-tracking/V022__pgmq_queue_transition_events.sql` (신규)
  - `backend/modules/issue-tracking/src/main/resources/db/codegen/init_codegen.sql` (수정 — pgmq 큐 미러)
  - `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/event/TransitionEventPublisher.kt` (신규)
  - `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/event/TransitionEventPublisherTest.kt` (신규)
- depends-on: []

**RED**: `TransitionEventPublisherTest` (Testcontainers `quay.io/tembo/pg16-pgmq:latest`, `IssueEventPublisherTest` 1:1 미러). `publish(DomainEvent("WebhookRequested", {...}))` → `pgmq.read('q_transition_events')`로 메시지 1건 + `type`/`payload` JSON 필드 검증. MANDATORY 트랜잭션 강제(수동 트랜잭션/커넥션 공유 — IssueEventPublisherTest 동일 방식). 실패: `TransitionEventPublisher` 클래스 없음 + 큐 없음.

**GREEN**:
- V022: `CREATE EXTENSION IF NOT EXISTS pgmq CASCADE;` + `SELECT pgmq.create('q_transition_events');` (V002 패턴 그대로).
- init_codegen.sql: V022 큐 생성 미러 단락 추가(메모리 jooq-init-codegen-mirror — codegen 시점 DB에 큐 존재해야).
- `TransitionEventPublisher`(@Component, `DSLContext`+`ObjectMapper`, `@Transactional(MANDATORY)`, `dsl.execute("SELECT pgmq.send(?, ?::jsonb)", QUEUE_NAME, json)`). `QUEUE_NAME="q_transition_events"`. shared-kernel `DomainEvent{type,payload}` 직렬화.

**REFACTOR**: KDoc(IssueEventPublisher 톤), QUEUE_NAME const + V022 주석 일치.

**검증**: `cd backend && ./gradlew :modules:issue-tracking:test --tests '*TransitionEventPublisherTest*'`

### Task 2. transitionIssue() emitEvents 발행 배선 + end-to-end 통합

**메타**.
- agent: `backend-engineer`
- files:
  - `backend/modules/issue-tracking/src/main/kotlin/com/bts/issue/application/IssueApplicationService.kt` (수정)
  - `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/application/IssueApplicationServiceTransitionTest.kt` (수정 — 단위)
  - `backend/modules/issue-tracking/src/test/kotlin/com/bts/issue/integration/TransitionEmitEventsPublishIntegrationTest.kt` (신규 — 통합)
- depends-on: [1]

**RED**:
- 통합: `TransitionEmitEventsPublishIntegrationTest` (참조 `IssueTransitionValidatorEndToEndIntegrationTest` 시드 패턴 + pg16-pgmq). 전이에 CallWebhook post-action을 시드(`workflow_post_actions` INSERT) → `transitionIssue` 실행 → `q_transition_events`에서 `WebhookRequested` 1건 read. 추가: 버전 충돌 롤백 → 큐 0건, dry-run(`POST /workflows/{key}/transitions`만) → 0건, post-action 미설정 → 0건(+IssueTransitioned는 q_issue_events 정상). 실패: emitEvents가 버려져 큐 0건.
- 단위: `IssueApplicationServiceTransitionTest`에 mock `TransitionEventPublisher` 주입 → emitEvents 보유 plan 전이 시 `publish` 호출 검증, emitEvents 빈 plan은 미호출. 실패: 미배선.

**GREEN**:
- 생성자에 **trailing nullable 기본값** `transitionEventPublisher: TransitionEventPublisher? = null` 추가(securityDirectory/issueTemplateRepository 패턴 동형 — **33개 기존 생성 지점 변경 0**, 메모리 plan-files-constructor-injection-existing-tests 회피).
- `transitionIssue()`: `repo.applyTransition` 성공(updatedRows≠0) 직후, IssueTransitioned 발행 인근에서 `transitionEventPublisher?.let { p -> plan.emitEvents.forEach { p.publish(it) } }` (같은 클래스-레벨 @Transactional REQUIRED 안 → outbox 정합). dry-run 경로(WorkflowController.plan)는 transitionIssue 미경유라 자동 미발행.

**REFACTOR**: 발행 루프를 private helper(`publishTransitionEvents(plan)`)로 추출 + KDoc. nullable fail-safe 사유 주석(prod @Component 주입, 통합테스트가 실 배선 발행 가드).

**검증**: `cd backend && ./gradlew :modules:issue-tracking:test --tests '*TransitionEmitEventsPublish*' --tests '*IssueApplicationServiceTransitionTest*'`

### Task 3. FR-NT-05 신규 FR 전수 동기화

**메타**.
- agent: `backend-engineer`
- files:
  - `docs/plan/fr-index.md`
  - `docs/sdd/02-requirements.md`
  - `docs/sdd/09-notification*.md` (notification 챕터 — 정확명 grep)
  - `docs/plan/product/notification-dashboard.md`
  - `docs/plan/README.md`
  - `CLAUDE.md`
- depends-on: []

**RED**: `bash scripts/verify-master-plan.sh` — FR-NT-05를 일부 정본에만 추가하면 카운트 drift(exit 4). 목표는 모든 정본 123 정합 후 exit 0.

**GREEN** (전수 동기화):
- fr-index: 알림 §헤더 `(FR-NT, 4개)`→`5개`, FR-NT-05 행 추가, 합계 122→123, §A.2 notification 카운트 +1, 상단 주석 `122개`→`123개`, **FR-NT-02 행 "채널 (이메일/인앱/Webhook…)"의 stale Webhook 표기 정정**(인앱/이메일만, Webhook=FR-NT-05).
- SDD: `02-requirements.md` FR 표에 FR-NT-05 행 + notification 챕터(§9)에 FR-NT-05 절(전이 post-action 이벤트 발행→Webhook 디스패처, PR1/PR2 분할 명시).
- product `notification-dashboard.md`: FR-NT-05 § 추가(소속 FR 카운트·§헤더 `(FR-XX,N개)` 갱신, D단계 — PR1=발행 issue-tracking, PR2=디스패처 notification, 부분완료 마킹).
- README §1 BC 테이블 합계(122→123, notification 행).
- CLAUDE.md: `122 FR`/`122개` 표기 → 123.

**REFACTOR**: 링크/표 정렬 일관.

**검증**: `bash scripts/verify-master-plan.sh` (exit 0). 새 카운트 표기를 verify가 못 잡으면 같은 PR에서 verify 스크립트 확장(CLAUDE.md §전수 동기화).

## Plan 메타

- task 수: 3
- depends-on 그래프: T1→T2 (publisher 의존), T3 독립(docs).
- 예상 wave: Wave1 = T1(code)+T3(docs) 병렬, Wave2 = T2. 단 T1·T2는 issue-tracking test 컴파일 단위 공유라 실질 직렬(메모리 bts-plan-wave-gradle-module-compile).
- TDD 강제: yes (T1/T2). T3는 verify-master-plan green이 게이트.
- 추가 검증: ktlintCheck/detekt --rerun-tasks(메모리 — 에이전트 lint false-green 불신, controller 직접 재검증), verify-master-plan.

## 리뷰 결과 (← /bts-review-plan 채움)
