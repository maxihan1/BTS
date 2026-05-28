// pgmq 큐에 발행되는 이슈 도메인 이벤트 sealed interface — Jackson @JsonTypeInfo 다형성 직렬화 지원

package com.bts.issue.event

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueKey
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import java.time.Instant

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
 * @property toState 전이 후 상태 이름. 예: `"IN_PROGRESS"`
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
