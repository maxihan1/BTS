# ADR: FR-NT-05 — 전환 post-action emitEvents 의 pgmq 발행 책임은 issue-tracking

> 날짜: 2026-06-14
> 상태: Accepted (Maxi 게이트 확정)
> 관련 FR: FR-NT-05 (Webhook 알림 채널, FR-NT-02에서 분리)
> 관련 ADR: [2026-06-12-notification-inapp-channel-delivery.md](2026-06-12-notification-inapp-channel-delivery.md) (Amendment 정정 대상)
> 관련 PR: #140 (PR 1 — 발행 파이프라인)

## 맥락

FR-NT-05(Webhook 알림 채널)는 워크플로우 전환 시 `CallWebhookPostAction`이 생성하는 `WebhookRequested` 이벤트를 외부 URL로 HTTP POST 하는 기능이다. FR-NT-02 ADR Amendment(2026-06-14)가 이를 전용 후속 FR로 분리하며 "① **project-workflow BC**에 전환 emitEvents 발행 파이프라인, ② notification BC 소비+HTTP 디스패처"로 기술했다.

cross-BC라 PR 2개로 분할한다.
- **PR 1**: 전환 emitEvents → pgmq 발행 파이프라인.
- **PR 2**: notification BC의 WebhookRequested 소비 + HTTP POST 디스패처.

## 결정

**전환 post-action 의 `emitEvents`(WebhookRequested 포함)를 pgmq에 발행하는 책임은 issue-tracking `IssueApplicationService.transitionIssue()`가 진다.** FR-NT-02 ADR Amendment의 "project-workflow BC에 발행 파이프라인" 표현을 issue-tracking으로 정정한다.

발행 위치/방식.
- `repo.applyTransition()` 성공(updatedRows≠0) 직후, 클래스-레벨 `@Transactional`(REQUIRED) 안에서 `plan.emitEvents`를 pgmq에 발행 — 상태 변경과 동일 트랜잭션(transaction outbox, DATA.md §7.2). 롤백 시 이벤트도 폐기되어 큐와 DB가 항상 일치.
- 기존 `IssueEventPublisher` / `q_issue_events` 패턴 재사용 후보. 정확한 큐 이름·이벤트 envelope·payload 스키마는 FR-NT-05 PR 1 spec에서 확정.

## 근거 (코드 실측 2026-06-14)

1. **GAP-2 — post-action은 계산만, 실행은 호출자 BC 책임.** `WorkflowPostAction` SPI(`backend/modules/project-workflow/.../domain/spi/WorkflowPostAction.kt`)의 KDoc이 명시: "이 interface는 무엇을 해야 하는지 `PostActionPlan`으로 반환만 할 뿐, 실제 필드 변경·이벤트 발행 등의 **실행**은 수행하지 않는다. 실행 책임은 이 SPI를 호출하는 BC(예. issue-tracking)가 진다." project-workflow가 직접 발행하는 것은 이 결정에 위배된다.

2. **실제 전환 실행 경로 = issue-tracking.** `IssueApplicationService.transitionIssue()`가 `workflowPort.plan()`으로 `TransitionPlan`을 받아 `plan.toStateKey`만 사용하고 **`plan.emitEvents`(+post-action fieldChanges)를 그대로 버린다.** 이것이 WebhookRequested가 어느 큐에도 발행되지 않는 근본 원인이다. 상태 변경(`repo.applyTransition`)을 소유한 트랜잭션이 여기 있으므로 outbox 정합 발행이 가능한 유일한 지점이다.

3. **project-workflow `plan()` 은 dry-run 겸용 — 발행하면 오발사.** `WorkflowEngine.plan()`은 `Propagation.MANDATORY`로 (a) issue-tracking 실제 전환 실행, (b) `POST /api/v1/workflows/{key}/transitions` 미리보기(상태 변경 없음) 양쪽에서 호출된다. 여기서 발행하면 미리보기(dry-run)에서도 webhook이 발사된다. 발행은 반드시 "실제 전환이 영속된 직후"여야 하므로 issue-tracking 경로로 한정된다.

## 대안 (기각)

- **project-workflow가 발행 (ADR 문구 그대로)**: `plan()`이 dry-run 겸용이라 오발사 위험. 안전하려면 전환 실행 자체를 project-workflow로 이관해야 하는데, 이는 이슈 영속을 issue-tracking이 소유하는 현 아키텍처를 뒤엎는 대규모 재설계라 부적합.

## 영향 / 후속

- FR-NT-02 ADR Amendment(2026-06-12 문서)에 본 정정을 가리키는 노트 추가(전수 동기화).
- PR 2(notification 소비/디스패처)는 본 PR이 확정한 큐·envelope를 소비한다.
- 신규 FR이므로 fr-index(FR-NT 4→5·합계 122→123)·SDD·product `notification-dashboard.md`·README·CLAUDE·Obsidian 전수 동기화는 spec/plan 단계에서 수행(`scripts/verify-master-plan.sh` 통과).
