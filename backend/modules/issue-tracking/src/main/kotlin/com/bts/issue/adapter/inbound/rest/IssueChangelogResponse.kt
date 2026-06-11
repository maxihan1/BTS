// 이슈 변경 이력 그룹 REST 응답 DTO — ChangelogGroupView 를 JSON 으로 직렬화
package com.bts.issue.adapter.inbound.rest

import com.bts.issue.application.ChangelogGroupView
import com.bts.issue.history.IssueChangeItem
import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant
import java.util.UUID

/**
 * 이슈 변경 이력 그룹 하나의 REST 응답 DTO.
 *
 * [ChangelogGroupView] 를 JSON 으로 변환한다.
 * null 필드는 [JsonInclude.Include.NON_NULL] 로 직렬화에서 제외한다.
 * 프론트의 Zod `.nullish()` 스키마와 정합하도록 null 필드는 키 자체를 응답에 포함하지 않는다.
 *
 * @property actorId 변경 행위자 UUID. 시스템 자동 변경이면 null.
 * @property actorName 행위자 표시명. actorId 가 null 이거나 identity-access 조회 실패 시 null.
 * @property createdAt 변경 발생 시각 (ISO-8601 UTC).
 * @property items 변경 항목 목록.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class IssueChangelogResponse(
    val actorId: UUID?,
    val actorName: String?,
    val createdAt: Instant,
    val items: List<ChangeItemResponse>,
) {
    companion object {
        /**
         * [ChangelogGroupView] 뷰 모델을 [IssueChangelogResponse] 로 변환하는 팩토리.
         *
         * @param view 서비스 계층에서 조합된 뷰 모델.
         * @return JSON 직렬화 가능한 응답 DTO.
         */
        fun from(view: ChangelogGroupView): IssueChangelogResponse =
            IssueChangelogResponse(
                actorId = view.actorId,
                actorName = view.actorName,
                createdAt = view.createdAt,
                items = view.items.map { ChangeItemResponse.from(it) },
            )
    }
}

/**
 * 변경 항목 하나의 REST 응답 DTO.
 *
 * [IssueChangeItem] 도메인 객체를 JSON 으로 변환한다.
 * fromLabel/toLabel 이 null 이면 NON_NULL 정책으로 응답 키가 제거된다 (Zod nullish 정합).
 *
 * @property field 변경된 필드 식별자. 예: "summary", "status", "assignee".
 * @property fromValue 변경 전 원시 값. null 이면 이전 값 없음.
 * @property toValue 변경 후 원시 값. null 이면 값 비움.
 * @property fromLabel 변경 전 표시용 라벨. 라벨 박제가 없으면 null.
 * @property toLabel 변경 후 표시용 라벨. 라벨 박제가 없으면 null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ChangeItemResponse(
    val field: String,
    val fromValue: String?,
    val toValue: String?,
    val fromLabel: String?,
    val toLabel: String?,
) {
    companion object {
        /**
         * [IssueChangeItem] 도메인 객체를 [ChangeItemResponse] 로 변환하는 팩토리.
         *
         * @param item 도메인 변경 항목.
         * @return JSON 직렬화 가능한 응답 DTO.
         */
        fun from(item: IssueChangeItem): ChangeItemResponse =
            ChangeItemResponse(
                field = item.field,
                fromValue = item.fromValue,
                toValue = item.toValue,
                fromLabel = item.fromLabel,
                toLabel = item.toLabel,
            )
    }
}
