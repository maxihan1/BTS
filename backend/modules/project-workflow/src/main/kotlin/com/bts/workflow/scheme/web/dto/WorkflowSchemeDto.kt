// WorkflowSchemeController 요청/응답 DTO — Scheme CRUD 5 endpoint + Mapping CRUD 2 endpoint 입출력 타입 정의

package com.bts.workflow.scheme.web.dto

import com.bts.shared.issue.IssueTypeRef
import com.bts.workflow.domain.Workflow
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
 * 단건 조회 + 목록 조회용 매핑 상세 응답 DTO.
 *
 * 워크플로우 키/이름, 이슈타입 키/이름을 포함한다.
 * default mapping 은 [issueTypeKey] / [issueTypeName] 이 null 이다.
 *
 * @property id 매핑 DB PK.
 * @property issueTypeKey 매핑 대상 이슈 타입 키. null = default mapping.
 * @property issueTypeName 이슈 타입 표시 이름. null = default mapping 또는 조회 불가.
 * @property workflowKey 사용할 워크플로우 키.
 * @property workflowName 워크플로우 표시 이름.
 * @property isDefault 기본 매핑 여부.
 */
data class MappingResponseDetail(
    val id: Long,
    val issueTypeKey: String?,
    val issueTypeName: String?,
    val workflowKey: String,
    val workflowName: String,
    /**
     * 기본 매핑 여부 — `issue_type_id IS NULL` 인 매핑이 기본 매핑이다.
     *
     * ★ [issueTypeKey] 가 null 인 것으로 판정하면 안 된다. 이슈타입이 실재하는데 cross-BC 조회만
     * 실패한 경우([issueTypeRef] = null)와 진짜 기본 매핑이 구분되지 않아, 조회 실패가 기본 매핑으로
     * 둔갑한다(EC-4). 판정은 도메인 필드 [SchemeIssueTypeMapping.issueTypeId] 로만 한다.
     *
     * 이름 주의 — 스킴의 `isStandard`(시스템 표준 스킴 4종 보호)와는 **다른 개념**이다.
     * 여기서의 `isDefault` 는 스킴 안에서 이슈타입 지정 없이 적용되는 대체 매핑을 뜻한다.
     */
    val isDefault: Boolean,
) {
    companion object {
        /**
         * 도메인 [SchemeIssueTypeMapping], [IssueTypeRef]?, [Workflow] 을 응답 DTO 로 변환한다.
         *
         * @param mapping 변환할 매핑. id 가 null 이면 예외가 발생한다.
         * @param issueTypeRef 이슈 타입 정보. null = default mapping 또는 조회 불가.
         * @param workflow 워크플로우 도메인 객체.
         * @return [MappingResponseDetail] 인스턴스.
         */
        fun from(
            mapping: SchemeIssueTypeMapping,
            issueTypeRef: IssueTypeRef?,
            workflow: Workflow,
        ): MappingResponseDetail =
            MappingResponseDetail(
                id = requireNotNull(mapping.id) { "SchemeIssueTypeMapping.id must not be null" },
                issueTypeKey = issueTypeRef?.key,
                issueTypeName = issueTypeRef?.name,
                workflowKey = workflow.key,
                workflowName = workflow.name,
                // ★ issueTypeRef 가 아니라 도메인 필드로 판정한다 (EC-4 — 조회 실패 ≠ 기본 매핑).
                isDefault = mapping.issueTypeId == null,
            )
    }
}

/**
 * 스킴 단건/목록 상세 응답 DTO — 카운트 + 매핑 리스트 동봉.
 *
 * spec §4.1 GET /api/v1/workflow-schemes + GET /api/v1/workflow-schemes/{schemeKey} 응답.
 *
 * @property id DB PK.
 * @property key 스킴 식별 키.
 * @property name 스킴 이름.
 * @property description 스킴 설명. null 허용.
 * @property isStandard 시스템 표준 스킴 여부 (DB 컬럼 `is_default`, 도메인 `WorkflowScheme.isDefault`).
 * @property createdAt 생성 시각 (ISO-8601).
 * @property updatedAt 최종 변경 시각 (ISO-8601).
 * @property usedByProjectsCount 이 스킴을 사용하는 프로젝트 수.
 * @property mappingsCount 이 스킴에 등록된 매핑 수.
 * @property mappings 매핑 상세 목록. 단건 조회 시 채워지며 목록 조회 시 빈 리스트.
 */
data class WorkflowSchemeDetailResponse(
    val id: Long,
    val key: String,
    val name: String,
    val description: String?,
    /**
     * 시스템 표준 스킴 여부 — 삭제·잠긴 필드 변경이 차단되는 보호 대상(V201 시드 4종).
     *
     * 뷰 레이어만 `isStandard` 로 부른다. 도메인 `WorkflowScheme.isDefault` 와 DB 컬럼 `is_default`
     * 는 그대로 둔다(ADR D2 — 변경 범위는 뷰 한정, 마이그레이션 0).
     * [MappingResponseDetail.isDefault] 와 **다른 개념**임에 주의한다.
     */
    val isStandard: Boolean,
    val createdAt: String,
    val updatedAt: String,
    val usedByProjectsCount: Long = 0L,
    val mappingsCount: Long = 0L,
    val mappings: List<MappingResponseDetail> = emptyList(),
) {
    companion object {
        /**
         * 도메인 [WorkflowScheme] 을 카운트 0, 매핑 빈 리스트로 상세 응답 DTO 로 변환한다.
         *
         * @param scheme 변환할 도메인 객체. id 가 null 이면 예외가 발생한다.
         * @return 상세 응답 DTO 인스턴스.
         */
        fun from(scheme: WorkflowScheme): WorkflowSchemeDetailResponse =
            WorkflowSchemeDetailResponse(
                id = requireNotNull(scheme.id?.value) { "WorkflowScheme.id must not be null" },
                key = scheme.key.value,
                name = scheme.name,
                description = scheme.description,
                isStandard = scheme.isDefault,
                createdAt = scheme.createdAt.toString(),
                updatedAt = scheme.updatedAt.toString(),
            )

        /**
         * 도메인 [WorkflowScheme] 을 카운트 + 매핑 리스트와 함께 변환한다.
         *
         * @param scheme 변환할 도메인 객체.
         * @param usedByProjectsCount 이 스킴을 사용하는 프로젝트 수.
         * @param mappings 매핑 상세 목록.
         * @return 상세 응답 DTO 인스턴스.
         */
        fun from(
            scheme: WorkflowScheme,
            usedByProjectsCount: Long,
            mappings: List<MappingResponseDetail>,
        ): WorkflowSchemeDetailResponse =
            WorkflowSchemeDetailResponse(
                id = requireNotNull(scheme.id?.value) { "WorkflowScheme.id must not be null" },
                key = scheme.key.value,
                name = scheme.name,
                description = scheme.description,
                isStandard = scheme.isDefault,
                createdAt = scheme.createdAt.toString(),
                updatedAt = scheme.updatedAt.toString(),
                usedByProjectsCount = usedByProjectsCount,
                mappingsCount = mappings.size.toLong(),
                mappings = mappings,
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
 * @property isStandard 시스템 표준 스킴 여부 (DB 컬럼 `is_default`, 도메인 `WorkflowScheme.isDefault`).
 * @property createdAt 생성 시각 (ISO-8601).
 * @property updatedAt 최종 변경 시각 (ISO-8601).
 */
data class WorkflowSchemeResponse(
    val id: Long,
    val key: String,
    val name: String,
    val description: String?,
    /** 시스템 표준 스킴 여부. 도메인·DB 는 `isDefault`/`is_default` 그대로다(ADR D2 — 뷰 레이어 한정). */
    val isStandard: Boolean,
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
                isStandard = scheme.isDefault,
                createdAt = scheme.createdAt.toString(),
                updatedAt = scheme.updatedAt.toString(),
            )
    }
}
