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
 * 이슈 상태가 전이(FSM transition)되었을 때 발행되는 이벤트.
 *
 * @property issueKey 전이된 이슈의 키.
 * @property fromState 전이 전 상태 이름. 예: `"open"`
 * @property toState 전이 후 상태 이름. 예: `"in_progress"`
 * @property actorId 전이를 수행한 행위자 ID. 알림 수신자 자기제외에 사용.
 * @property occurredAt 이벤트 발생 시각 (UTC).
 */
@JsonTypeName("issue.transitioned")
data class IssueTransitioned(
    val issueKey: IssueKey,
    val fromState: String,
    val toState: String,
    val actorId: ActorId,
    val occurredAt: Instant,
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
 * @property sourceField 멘션이 포함된 필드명. 현재 `"description"`, 향후 댓글 지원 시 `"comment"`.
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
 * `q_issue_events` 로도 함께 발행되지만(uniform [IssueDomainEvent] 발행 모델), NotificationWorker 는
 * 이 타입을 모르는 이벤트로 취급해 무해하게 삭제(delete)한다 — 알림 발송 회귀 없음(리뷰 E2 확인).
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
