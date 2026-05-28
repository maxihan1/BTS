// WorkflowSchemeController 요청/응답 DTO — Scheme CRUD 5 endpoint + Mapping CRUD 2 endpoint 입출력 타입 정의

package com.bts.workflow.scheme.web.dto

import com.bts.workflow.scheme.domain.SchemeIssueTypeMapping
import com.bts.workflow.scheme.domain.WorkflowScheme
/**
 * 매핑 추가 요청 DTO.
 *
 * spec §4.2 POST /api/v1/workflow-schemes/{schemeKey}/mappings body.
 *
 * @property issueTypeKey 매핑 대상 이슈 타입 키. null = default mapping (issueTypeId IS NULL).
 * @property workflowKey 사용할 워크플로우 키.
 */
data class MappingRequestDto(
    val issueTypeKey: String?,
    val workflowKey: String,
)

/**
 * 매핑 응답 DTO.
 *
 * @property id DB PK.
 * @property schemeId 소속 스킴 PK.
 * @property issueTypeId 매핑 대상 이슈 타입 PK. null = default mapping.
 * @property workflowId 사용할 워크플로우 UUID.
 * @property createdAt 생성 시각 (ISO-8601).
 */
data class MappingResponse(
    val id: Long,
    val schemeId: Long,
    val issueTypeId: Long?,
    val workflowId: String,
    val createdAt: String,
) {
    companion object {
        /**
         * 도메인 [SchemeIssueTypeMapping] 을 응답 DTO 로 변환한다.
         *
         * @param mapping 변환할 도메인 객체. id 가 null 이면 예외가 발생한다.
         * @return 응답 DTO 인스턴스.
         */
        fun from(mapping: SchemeIssueTypeMapping): MappingResponse =
            MappingResponse(
                id = requireNotNull(mapping.id) { "SchemeIssueTypeMapping.id must not be null" },
                schemeId = mapping.schemeId.value,
                issueTypeId = mapping.issueTypeId?.value,
                workflowId = mapping.workflowId.toString(),
                createdAt = mapping.createdAt.toString(),
            )
    }
}

/**
 * 워크플로우 스킴 생성 요청 DTO.
 *
 * key/name 빈 문자열 검증은 도메인 계층([WorkflowSchemeKey], [WorkflowScheme.create]) 에서 수행한다.
 *
 * @property key 스킴 식별 키. [WorkflowSchemeKey] 정규식 검증은 도메인 계층에서 수행한다.
 * @property name 스킴 이름. 빈 문자열 불허 (도메인 검증).
 * @property description 스킴 설명. null 허용.
 */
data class CreateWorkflowSchemeRequest(
    val key: String,
    val name: String,
    val description: String?,
)

/**
 * 워크플로우 스킴 수정 요청 DTO.
 *
 * spec §4.1 PUT — name/description 만 변경 가능. key/is_default 변경 불가.
 *
 * @property name 새 스킴 이름. 빈 문자열 불허 (도메인 검증).
 * @property description 새 스킴 설명. null 이면 설명 제거.
 */
data class UpdateWorkflowSchemeRequest(
    val name: String,
    val description: String?,
)

/**
 * 워크플로우 스킴 응답 DTO.
 *
 * @property id DB PK.
 * @property key 스킴 식별 키.
 * @property name 스킴 이름.
 * @property description 스킴 설명. null 허용.
 * @property isDefault 표준 스킴 여부.
 * @property createdAt 생성 시각 (ISO-8601).
 * @property updatedAt 최종 변경 시각 (ISO-8601).
 */
data class WorkflowSchemeResponse(
    val id: Long,
    val key: String,
    val name: String,
    val description: String?,
    val isDefault: Boolean,
    val createdAt: String,
    val updatedAt: String,
) {
    companion object {
        /**
         * 도메인 [WorkflowScheme] 을 응답 DTO 로 변환한다.
         *
         * @param scheme 변환할 도메인 객체. id 가 null 이면 예외가 발생한다.
         * @return 응답 DTO 인스턴스.
         */
        fun from(scheme: WorkflowScheme): WorkflowSchemeResponse =
            WorkflowSchemeResponse(
                id = requireNotNull(scheme.id?.value) { "WorkflowScheme.id must not be null" },
                key = scheme.key.value,
                name = scheme.name,
                description = scheme.description,
                isDefault = scheme.isDefault,
                createdAt = scheme.createdAt.toString(),
                updatedAt = scheme.updatedAt.toString(),
            )
    }
}
