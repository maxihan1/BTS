// IssueTypeApplicationService 입력 DTO — 이슈 타입 CRUD 요청 데이터 클래스

package com.bts.issue.type.application

/**
 * 이슈 타입 생성 요청 DTO.
 *
 * @param key URL-safe 소문자 슬러그. 예: `"feature"`. [com.bts.shared.issue.IssueTypeKey] 형식 강제.
 * @param name 표시 이름. 빈 문자열 불가.
 * @param description 선택적 설명. null 허용.
 * @param iconName 아이콘 식별자. null 허용.
 * @param hierarchyLevel 계층 깊이. {-1, 0, 1} 허용. 기본값 0.
 */
data class CreateIssueTypeRequest(
    val key: String,
    val name: String,
    val description: String?,
    val iconName: String?,
    val hierarchyLevel: Int = 0,
)

/**
 * 이슈 타입 수정 요청 DTO.
 *
 * key / isStandard 는 불변이므로 포함하지 않는다.
 *
 * @param name 새 표시 이름. 빈 문자열 불가.
 * @param description 새 설명. null 허용.
 * @param iconName 새 아이콘 식별자. null 허용.
 * @param hierarchyLevel 새 계층 깊이. {-1, 0, 1} 허용.
 */
data class UpdateIssueTypeRequest(
    val name: String,
    val description: String?,
    val iconName: String?,
    val hierarchyLevel: Int,
)
