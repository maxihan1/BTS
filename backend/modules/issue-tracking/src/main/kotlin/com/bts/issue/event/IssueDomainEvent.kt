// pgmq 큐에 발행되는 이슈 도메인 이벤트 sealed interface — Jackson @JsonTypeInfo 다형성 직렬화 지원

package com.bts.issue.event

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueKey
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import java.time.Instant
import java.util.UUID

/**
 * 이슈 도메인 이벤트 루트 타입.
 *
 * pgmq 큐에 JSON 으로 직렬화되어 발행된다. 소비자(알림·자동화·Slack 워커)는
 * `"type"` 필드로 구체 이벤트 클래스를 식별해 역직렬화한다.
 *
 * 모든 구체 클래스는 [JsonTypeName] 에 `issue.<verb>` 형식 식별자를 선언한다.
 *
 * DATA.md §7 (pgmq) 참조.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = IssueCreated::class, name = "issue.created"),
    JsonSubTypes.Type(value = IssueUpdated::class, name = "issue.updated"),
    JsonSubTypes.Type(value = IssueTransitioned::class, name = "issue.transitioned"),
    JsonSubTypes.Type(value = IssueSoftDeleted::class, name = "issue.soft_deleted"),
    JsonSubTypes.Type(value = IssueMentioned::class, name = "issue.mentioned"),
    JsonSubTypes.Type(value = IssueDueSoon::class, name = "issue.due_soon"),
    JsonSubTypes.Type(value = IssueOverdue::class, name = "issue.overdue"),
    JsonSubTypes.Type(value = IssueCommented::class, name = "issue.commented"),
    JsonSubTypes.Type(value = IssueCommentDeleted::class, name = "issue.comment_deleted"),
    JsonSubTypes.Type(value = IssueAssigned::class, name = "issue.assigned"),
)
sealed interface IssueDomainEvent

/**
 * 이슈가 새로 생성되었을 때 발행되는 이벤트.
 *
 * @property issueKey 발급된 이슈 키. 예: `ATLAS-42`
 * @property projectKey 소속 프로젝트 키. 예: `ATLAS`
 * @property summary 이슈 제목.
 * @property reporterId 생성자 행위자 ID.
 * @property actorId 이 이벤트를 유발한 행위자 ID(생성 시점엔 reporterId 와 동일). 알림 수신자 자기제외에 사용.
 * @property occurredAt 이벤트 발생 시각 (UTC).
 */
@JsonTypeName("issue.created")
data class IssueCreated(
    val issueKey: IssueKey,
    val projectKey: String,
    val summary: String,
    val reporterId: ActorId,
    val actorId: ActorId,
    val occurredAt: Instant,
) : IssueDomainEvent

/**
 * 이슈 필드가 수정되었을 때 발행되는 이벤트.
 *
 * @property issueKey 수정된 이슈의 키.
 * @property fields 변경된 필드 이름 집합. 예: `{"summary", "description"}`
 * @property occurredAt 이벤트 발생 시각 (UTC).
 */
@JsonTypeName("issue.updated")
data class IssueUpdated(
    val issueKey: IssueKey,
    val fields: Set<String>,
    val occurredAt: Instant,
) : IssueDomainEvent

/**
 * 이슈 상태가 전환(FSM transition)되었을 때 발행되는 이벤트.
 *
 * ## [cause] — 소비자에게 준 「거를 선택권」 (FR-WF-07 · spec F11·F17)
 * 일반 전환은 이 값을 싣지 않아 `null` 이다. 현재 유일한 non-null 값은
 * [com.bts.issue.bulk.application.BulkItemApplier.CAUSE_STATUS_MIGRATION] (`"STATUS_MIGRATION"`)
 * 으로, 워크플로우에서 빠지는 상태에 남은 이슈를 관리자 조작 1회로 옮길 때 실린다.
 *
 * 그 1회가 이 이벤트를 **대상 건수만큼** 만든다. 소비자에 사외 아웃바운드 웹훅
 * (`search-export-import` 의 `WebhookDispatchWorker`)이 있어 이관 N건이 곧 외부 호출 N건이고,
 * 한 번 나간 웹훅은 되돌릴 수 없다. 그렇다고 발행을 끄면 이 이벤트를 구독하는 검색 색인·보드·
 * 자동화가 옮겨간 이슈를 모른 채 남아 **DB 는 맞는데 화면이 틀린** 상태가 된다(spec G2).
 * 그래서 끄지 않고 **표시만 실어** 거를지 말지를 소비자가 정하게 했다.
 *
 * ## 하위호환 (F17)
 * `cause` 는 nullable 신규 필드이고 기본값이 `null` 이라 기존 발행부는 호출을 바꾸지 않는다.
 * 큐를 지나는 소비자는 전원 `ObjectMapper.readTree` 로 [com.fasterxml.jackson.databind.JsonNode]
 * 를 읽으므로 새 필드가 붙어도 깨지지 않고, `cause` 없는 옛 메시지는 `null` 로 복원된다
 * (`BulkItemApplierStatusMigrationTest` 의 왕복 테스트가 양방향을 고정한다).
 *
 * @property issueKey 전환된 이슈의 키.
 * @property fromState 전환 전 상태 이름. 예: `"open"`
 * @property toState 전환 후 상태 이름. 예: `"in_progress"`
 * @property actorId 전환을 수행한 행위자 ID. 알림 수신자 자기제외에 사용.
 * @property occurredAt 이벤트 발생 시각 (UTC).
 * @property cause 이 전환을 일으킨 특수 경로 표시. 일반 전환은 `null` 이다.
 */
@JsonTypeName("issue.transitioned")
data class IssueTransitioned(
    val issueKey: IssueKey,
    val fromState: String,
    val toState: String,
    val actorId: ActorId,
    val occurredAt: Instant,
    val cause: String? = null,
) : IssueDomainEvent

/**
 * 이슈가 소프트 삭제(soft delete)되었을 때 발행되는 이벤트.
 *
 * 물리 삭제가 아니므로 이슈 키는 영구 보존된다 (DATA.md §1.1).
 *
 * @property issueKey 삭제된 이슈의 키.
 * @property occurredAt 이벤트 발생 시각 (UTC).
 */
@JsonTypeName("issue.soft_deleted")
data class IssueSoftDeleted(
    val issueKey: IssueKey,
    val occurredAt: Instant,
) : IssueDomainEvent

/**
 * 이슈 본문(또는 향후 댓글)에서 @멘션이 감지되었을 때 발행되는 이벤트.
 *
 * diff 기반 발행 — 기존 본문에 이미 있던 멘션은 제외하고 신규 추가된 멘션 대상만 포함.
 * 자기 자신([actorId])은 [mentionedUserIds]에서 제외된다.
 * 알림 전달 및 수신자 권한 검증은 소비자(FR-NT 워커) 책임.
 *
 * @property issueKey 멘션이 발생한 이슈의 키.
 * @property projectKey 소속 프로젝트 키. 예: `ATLAS`
 * @property mentionedUserIds 멘션 대상 사용자 UUID 목록 (해석·dedup·자기제외 후, UUID 오름차순 정렬 — 결정적 직렬화).
 * @property actorId 멘션을 작성한 행위자 ID.
 * @property sourceField 멘션이 포함된 본문의 출처. 값 집합은 [com.bts.issue.mention.MentionSource]
 *   가 정본이며 `"description"`(이슈 본문 · 생성·수정 공용) 과 `"comment"`(댓글) **둘뿐**이다.
 * @property commentId `sourceField` 가 `"comment"` 일 때 그 댓글의 UUID, 그 외에는 null.
 *   ★기본값 `null` 은 **생략 가능한 편의가 아니라 하위호환 장치**다 — 이 필드가 생기기 전에
 *   `q_issue_events` 로 들어간 메시지에는 키가 아예 없고, 기본값이 없으면 워커가 그 메시지를
 *   역직렬화하지 못해 큐가 막힌다. 지우지 말 것.
 *   현재 이 값을 읽는 소비자는 없다(2026-09-05 실측) — 인박스 딥링크 FR 이 쓸 자리를 미리 낸 것이고,
 *   이벤트 스키마 변경을 두 번 하지 않으려는 선반영이다.
 * @property occurredAt 이벤트 발생 시각 (UTC).
 */
@JsonTypeName("issue.mentioned")
data class IssueMentioned(
    val issueKey: IssueKey,
    val projectKey: String,
    val mentionedUserIds: List<UUID>,
    val actorId: ActorId,
    val sourceField: String,
    val occurredAt: Instant,
    val commentId: UUID? = null,
) : IssueDomainEvent

/**
 * 마감일 스캔 스케줄러가 발행하는 "마감 임박" 이벤트.
 *
 * 발행 주체: 마감일 스캔 스케줄러 (DueDateScanWorker).
 * 수신자: notification resolver — issueKey 로 수신자 목록을 포트 조회해 알림 전달.
 * notification BC 의 NotificationEventType 이 "issue.due_soon" 식별자로 매핑한다.
 *
 * @property issueKey 마감 임박 이슈의 키 문자열. 예: PROJ-1
 * @property projectKey 소속 프로젝트 키. 예: PROJ
 * @property occurredAt 이벤트 발생 시각 (UTC).
 */
@JsonTypeName("issue.due_soon")
data class IssueDueSoon(
    val issueKey: String,
    val projectKey: String,
    val occurredAt: Instant,
) : IssueDomainEvent

/**
 * 마감일 스캔 스케줄러가 발행하는 "마감 초과" 이벤트.
 *
 * 발행 주체: 마감일 스캔 스케줄러 (DueDateScanWorker).
 * 수신자: notification resolver — issueKey 로 수신자 목록을 포트 조회해 알림 전달.
 * notification BC 의 NotificationEventType 이 "issue.overdue" 식별자로 매핑한다.
 *
 * @property issueKey 마감 초과 이슈의 키 문자열. 예: PROJ-1
 * @property projectKey 소속 프로젝트 키. 예: PROJ
 * @property occurredAt 이벤트 발생 시각 (UTC).
 */
@JsonTypeName("issue.overdue")
data class IssueOverdue(
    val issueKey: String,
    val projectKey: String,
    val occurredAt: Instant,
) : IssueDomainEvent

/**
 * 이슈에 댓글이 작성되었을 때 발행되는 이벤트 (FR-AT-01 Task 10 — automation COMMENTED 트리거).
 *
 * 발행 주체: [com.bts.issue.comment.application.CommentApplicationService.create].
 * 수신자: automation 모듈의 `AutomationEventWorker` — `q_automation_events` fan-out 큐를 폴링해
 * COMMENTED 트리거로 등록된 [com.bts.automation.domain.AutomationRule] 을 매칭한다
 * (ADR 2026-07-10-fr-at-01-automation-triggers D2·D3).
 *
 * `q_issue_events` 로도 함께 발행되어 NotificationWorker 가 이 타입을 **의도적으로 소비**한다
 * (`NotificationEventType.ISSUE_COMMENTED` + V401 시드가 FR-NT-01 §9.1.2 매트릭스에 이미 존재 —
 * 게이트2 옵션A로 사전 설계된 댓글 인앱 알림 경로를 완성. 작성자 자기제외·가시성 필터 적용).
 *
 * @property issueKey 댓글이 작성된 이슈의 키.
 * @property projectKey 소속 프로젝트 키. 예: `ATLAS`
 * @property commentId 작성된 댓글의 UUID.
 * @property actorId 댓글을 작성한 행위자 ID.
 * @property occurredAt 이벤트 발생 시각 (UTC).
 */
@JsonTypeName("issue.commented")
data class IssueCommented(
    val issueKey: IssueKey,
    val projectKey: String,
    val commentId: UUID,
    val actorId: ActorId,
    val occurredAt: Instant,
) : IssueDomainEvent

/**
 * 이슈 댓글이 **삭제**되었을 때 발행되는 이벤트.
 *
 * ## 왜 필요한가 — FR-CO-02 모더레이션의 마무리
 * 댓글 삭제는 작성자 **또는 `SOFT_DELETE` 보유자(모더레이터)** 가 할 수 있다. 그런데
 * **내 댓글이 모더레이터에게 지워져도 아무 신호가 없었다.** 감사 이력(`issue_change_group`)에는
 * 남지만 그건 조회해야 보이는 기록이지 밀어주는 신호가 아니다.
 * 즉 이 이벤트는 새 기능이 아니라 **이미 배포된 모더레이션 기능의 빠진 절반**이다.
 *
 * ## [commentAuthorId] 를 페이로드에 싣는 이유 — cross-BC 포트를 만들지 않기 위해
 * 알림 BC 가 「누구에게 알릴지」 를 알려면 댓글 저작자가 필요하다. 그것을 조회로 얻으려면
 * notification → issue-tracking 방향의 **신규 cross-BC 포트**가 필요한데, 페이로드에 실으면 불요다.
 * `IssueMentioned` 의 `mentionedUserIds` 가 같은 방식으로 이미 동작한다
 * (`EventRecipientResolver.resolveMentioned` — 포트 조회 없이 페이로드에서 바로 해석).
 *
 * ## 자기 자신에게는 알리지 않는다
 * [actorId] == [commentAuthorId] 인 자기 삭제는 알릴 이유가 없다. 그 판정은 수신자 해석 단계의
 * **자기제외**가 담당하므로 이벤트는 두 값을 모두 싣기만 한다(발행 조건으로 거르지 않는다) —
 * 발행을 조건부로 만들면 automation 이 나중에 이 이벤트를 소비할 때 누락이 생긴다.
 *
 * @property issueKey 댓글이 달렸던 이슈의 키.
 * @property projectKey 소속 프로젝트 키.
 * @property commentId 삭제된 댓글의 UUID.
 * @property commentAuthorId 삭제된 댓글의 **작성자** ID — 알림 수신자.
 * @property actorId 삭제를 **수행한** 행위자 ID. 자기제외 판정에 쓴다.
 * @property occurredAt 이벤트 발생 시각 (UTC).
 */
@JsonTypeName("issue.comment_deleted")
data class IssueCommentDeleted(
    val issueKey: IssueKey,
    val projectKey: String,
    val commentId: UUID,
    val commentAuthorId: ActorId,
    val actorId: ActorId,
    val occurredAt: Instant,
) : IssueDomainEvent

/**
 * 이슈 담당자가 배정 또는 변경되었을 때 발행되는 이벤트 (FR-SL-02 — Slack 할당 알림).
 *
 * 담당자가 실제로 바뀔 때만 발행된다. 요청값이 기존 담당자와 동일한 no-op 은 발행하지 않는다
 * (dedupKey 결정성 유지 — 임의 재발행으로 인한 중복 알림 방지).
 *
 * @property issueKey 담당자가 변경된 이슈의 키.
 * @property actorId 변경을 수행한 행위자 ID. 알림 수신자 자기제외에 사용.
 * @property occurredAt 이벤트 발생 시각 (UTC).
 */
@JsonTypeName("issue.assigned")
data class IssueAssigned(
    val issueKey: IssueKey,
    val actorId: ActorId,
    val occurredAt: Instant,
) : IssueDomainEvent
