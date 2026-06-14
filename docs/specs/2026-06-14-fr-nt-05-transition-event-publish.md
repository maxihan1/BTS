# FR-NT-05 Webhook 알림 채널 (PR 1: 전이 post-action 이벤트 pgmq 발행) — 스펙

> FR: FR-NT-05 (신규, FR-NT-02에서 분리) · BC: issue-tracking · type: backend
> ADR: [2026-06-14-fr-nt-05-transition-event-outbox.md](../decisions/2026-06-14-fr-nt-05-transition-event-outbox.md)
> 작성 방식: backend FR 직접 기술 스펙 (office-hours 대체, 메모리 bts-spec-office-hours-mismatch)

## 배경 (코드 실측)

워크플로우 전이 후처리(post-action)는 `PostActionPlan.emitEvents`로 도메인 이벤트를 **계산만** 한다(GAP-2 — 실행은 호출자 BC 책임). 4종 post-action이 이벤트를 emit한다.

| post-action | emit 이벤트 type | payload |
|---|---|---|
| CallWebhookPostAction | `WebhookRequested` | `{issueKey, url, method}` |
| AddWatcherPostAction | `WatcherAdded` | (워처) |
| NotifyPostAction | `NotificationRequested` | (알림) |
| RunAutomationPostAction | `AutomationRequested` | (자동화) |

그러나 실제 전이 실행 경로 `IssueApplicationService.transitionIssue()`는 `plan.emitEvents`를 **전부 버리고** `plan.toStateKey`만 사용한다. → 어느 이벤트도 pgmq에 발행되지 않는 근본 결함. WebhookRequested는 dry-run 미리보기(`POST /workflows/{key}/transitions`)의 HTTP 응답에만 노출된다.

현재 4종 default 워크플로우 YAML에 post-action 설정 0건 → `plan.emitEvents`는 실무상 항상 빈 리스트. **이 PR은 휴면 인프라**(배관)로, 향후 워크플로우 정의에 post-action이 설정되면 활성화된다.

## 사용자 시나리오 (Given-When-Then)

- **S1 (정상 발행)**: Given 전이에 CallWebhook post-action이 설정된 워크플로우, When 사용자가 그 전이를 실행(상태 변경 성공), Then `WebhookRequested` 이벤트가 `q_transition_events` 큐에 enqueue된다(상태 변경과 동일 트랜잭션).
- **S2 (롤백 시 미발행)**: Given 동일 설정, When 전이 중 버전 충돌 등으로 트랜잭션 롤백, Then 이벤트도 함께 폐기되어 큐에 남지 않는다(outbox 정합).
- **S3 (dry-run 미발행)**: Given 동일 설정, When `POST /api/v1/workflows/{key}/transitions`(미리보기)만 호출, Then 상태 변경이 없으므로 어떤 이벤트도 발행되지 않는다(HTTP 응답의 events 노출은 유지).
- **S4 (bulk 전이)**: Given 동일 설정, When 일괄 전이(bulk)로 N개 이슈를 전이, Then 각 이슈 전이마다 emitEvents가 발행된다(bulk는 `transitionIssue()`를 거치므로 자동 커버).
- **S5 (post-action 없음)**: Given post-action 미설정 워크플로우(현 default), When 전이 실행, Then `plan.emitEvents`가 비어 아무 것도 발행되지 않는다(no-op, 회귀 0).

## 기능 요구사항 (FR)

- **FR-NT-05-A**: `transitionIssue()`는 `repo.applyTransition()` 성공(updatedRows≠0) 직후, 클래스-레벨 `@Transactional`(REQUIRED) 안에서 `plan.emitEvents`의 **모든** `DomainEvent`를 pgmq 큐 `q_transition_events`에 발행한다(generic — 특정 type 필터 없음, ADR "4종 공통").
- **FR-NT-05-B**: 발행은 신규 아웃바운드 어댑터 `TransitionEventPublisher`(@Component, jOOQ `DSLContext` + Jackson `ObjectMapper`, `@Transactional(MANDATORY)`)가 담당. `IssueEventPublisher` 패턴 1:1 미러. 각 `DomainEvent{type, payload}`를 JSON 직렬화 후 `SELECT pgmq.send('q_transition_events', ?::jsonb)`.
- **FR-NT-05-C**: pgmq 큐 `q_transition_events`를 Flyway 마이그레이션(issue-tracking V022)으로 생성. `V002__pgmq_queue_issue_events.sql` 패턴(`pgmq.create`) + `init_codegen.sql` 미러(메모리 jooq-init-codegen-mirror).
- **FR-NT-05-D (전수 동기화)**: 신규 FR이므로 정본 동기화 — fr-index(FR-NT 4→5·합계 122→123·§A.2 notification·상단 주석, FR-NT-02 stale "Webhook" 표기 정정) / SDD §9 + 02-requirements / product `notification-dashboard.md`(FR-NT-05 § 추가) / README §1 / CLAUDE.md / `verify-master-plan.sh` 통과.

## 비기능 요구사항 (NFR)

- **트랜잭션 outbox 정합**: 이벤트 발행은 상태 변경과 같은 트랜잭션. 롤백 시 함께 폐기(DATA.md §7.2). `@Transactional(MANDATORY)`로 트랜잭션 없는 호출은 즉시 예외.
- **BC 격리**: issue-tracking은 pgmq 발행만, notification을 직접 import 안 함. 소비/HTTP는 PR 2(notification). 한 PR = 한 BC 준수.
- **회귀 0**: post-action 미설정 시 emitEvents 빈 리스트 → no-op. 기존 전이/IssueTransitioned 발행 동작 불변.
- **성능**: 전이당 emitEvents 수만큼 `pgmq.send`(보통 0~1건), 기존 트랜잭션 내 추가 비용 미미.

## 큐/이벤트 계약 (PR 2 인터페이스)

- **큐**: `q_transition_events` (신규 전용 큐). q_issue_events 재사용 안 함 — 그 큐는 notification `NotificationWorker`가 소비 중이라 새 type 혼입은 오염 위험.
- **메시지**: `{ "type": "<이벤트 type>", "payload": { ... } }` (shared-kernel `DomainEvent` 직렬화). PR 2 디스패처는 `type == "WebhookRequested"`만 처리하고 나머지(WatcherAdded/NotificationRequested/AutomationRequested)는 무시(미래 FR 소비처).

## 엣지 케이스

- updatedRows==0(버전 충돌) → `IssueVersionConflictException` → 발행 코드 도달 전 또는 롤백 → 미발행.
- emitEvents 빈 리스트 → 루프 0회, no-op.
- emitEvents 다건 → 각각 발행(순서는 plan 누적 순서).
- dry-run `WorkflowController.plan`(planTransition)은 상태 미변경·`transitionEventPublisher` 미호출 → 미발행(테스트로 가드).
- bulk 전이 부분 실패 → 성공한 이슈만 각자 트랜잭션에서 발행(BulkItemApplier 항목별 처리 모델 따름).

## 측정 가능한 완료 기준

1. 단위: `TransitionEventPublisher`가 `pgmq.send`를 큐명·직렬화 payload로 호출(MANDATORY 트랜잭션 강제 검증).
2. 통합(Testcontainers): CallWebhook post-action 시드 → 전이 실행 → `q_transition_events`에서 `WebhookRequested` 1건 read 확인. 롤백 시나리오 → 큐 0건. dry-run → 큐 0건.
3. 회귀: post-action 미설정 전이 → 큐 0건 + 기존 IssueTransitioned는 q_issue_events에 정상.
4. `verify-master-plan.sh` exit 0 (FR 123). ktlintCheck/detekt --rerun-tasks green.

## 제약 / 범위 밖

- **webhook 설정 UI/API 없음**: 어느 전이에 어떤 webhook을 걸지는 기존 워크플로우 post-action 정의(YAML/DB) 메커니즘으로 구성. FR-NT-05 PR1은 발행 배관만.
- **HTTP POST 디스패처 없음**: 외부 URL 실제 호출은 PR 2(notification BC).
- **재시도/DLQ 정책**: PR 2 디스패처 설계 사항(pgmq read/archive/재청구). PR1은 발행까지만.
- **createIssue 시작 전이 범위 밖**: `createIssue()`는 `resolveStart`로 초기 상태만 설정하고 post-action을 실행하지 않는다(현 모델). 시작(Create) 전이 post-action emitEvents는 FR-NT-05 범위 밖(현 동작 불변).
- **post-action fieldChanges 적용 범위 밖**: `transitionIssue()`는 `plan.fieldChanges`(SetField 등)도 현재 버린다 — 이는 "전이 시 필드 자동 채움"이라는 **별개 기능**이라 FR-NT-05(이벤트 발행)와 분리. 본 PR은 `emitEvents`만 발행한다(fieldChanges는 기존대로 미적용 유지, 별도 추적).

## Brainstorming Check

✅ 통과 (직접 비판적 gap 분석, backend 직접 스펙 방식). 코드 실측으로 검증한 항목.
- TransitionResult.Success가 TransitionPlan.emitEvents를 운반하며, `TransitionPlan` KDoc이 "호출자 BC가 emitEvents를 outbox에 INSERT 해야 한다"고 명시 → BC 배치(issue-tracking) shared-kernel 계약으로 재확인. FR-NT-05 = 문서화됐으나 미구현된 계약의 구현.
- bulk 전이는 `BulkItemApplier`가 `transitionIssue()`를 호출 → 단일 변경점이 단건+bulk 커버.
- createIssue는 post-action 미실행 → 범위 밖 명시.
- q_issue_events는 NotificationWorker가 소비 중 → 전용 큐 `q_transition_events`로 오염 회피.
- 통합테스트 참조: `IssueTransitionValidatorEndToEndIntegrationTest`(워크플로우 시드+실 전이 end-to-end) 패턴 재사용, post-action 시드로 확장.
- 미해소 잠재(범위 밖 기록): post-action fieldChanges 미적용은 별개 기능으로 분리.
