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
)
sealed interface IssueDomainEvent

/**
 * 이슈가 새로 생성되었을 때 발행되는 이벤트.
 *
 * @property issueKey 발급된 이슈 키. 예: `ATLAS-42`
 * @property projectKey 소속 프로젝트 키. 예: `ATLAS`
 * @property summary 이슈 제목.
 * @property reporterId 생성자 행위자 ID.
 * @property occurredAt 이벤트 발생 시각 (UTC).
 */
@JsonTypeName("issue.created")
data class IssueCreated(
    val issueKey: IssueKey,
    val projectKey: String,
    val summary: String,
    val reporterId: ActorId,
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
 * @property occurredAt 이벤트 발생 시각 (UTC).
 */
@JsonTypeName("issue.transitioned")
data class IssueTransitioned(
    val issueKey: IssueKey,
    val fromState: String,
    val toState: String,
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
