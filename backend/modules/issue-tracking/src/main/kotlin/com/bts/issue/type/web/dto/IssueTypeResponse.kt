// IssueTypeController 응답 DTO — 이슈 타입 단건 표현 (FR-IS-02 — hierarchyLevel 추가)

package com.bts.issue.type.web.dto

import com.bts.issue.type.domain.IssueType

/**
 * 이슈 타입 단건 응답 DTO.
 *
 * [IssueType] 도메인 객체를 REST 응답용으로 매핑한다.
 * id 는 DB PK (Long). 저장된 이후에만 응답에 포함되므로 null 불가 (findAll / findById 결과는 항상 id 존재).
 *
 * @property id DB PK.
 * @property key URL-safe 소문자 슬러그. 예: `"task"`.
 * @property name 표시 이름.
 * @property description 선택적 설명. null 허용.
 * @property iconName 아이콘 식별자. null 허용.
 * @property isStandard 표준 타입 여부.
 * @property hierarchyLevel 계층 깊이. epic=1, task/story/bug=0, subtask=-1.
 */
data class IssueTypeResponse(
    val id: Long,
    val key: String,
    val name: String,
    val description: String?,
    val iconName: String?,
    val isStandard: Boolean,
    val hierarchyLevel: Int,
) {
    companion object {
        /**
         * [IssueType] 도메인 객체를 [IssueTypeResponse] 로 변환한다.
         *
         * @param issueType 변환 대상 도메인 객체. [IssueType.id] 는 null 이 아니어야 한다.
         * @throws IllegalArgumentException [issueType.id] 가 null 인 경우.
         */
        fun from(issueType: IssueType): IssueTypeResponse {
            requireNotNull(issueType.id) { "IssueType.id must not be null for response mapping" }
            return IssueTypeResponse(
                id = issueType.id.value,
                key = issueType.key.value,
                name = issueType.name,
                description = issueType.description,
                iconName = issueType.iconName,
                isStandard = issueType.isStandard,
                hierarchyLevel = issueType.hierarchyLevel,
            )
        }
    }
}
