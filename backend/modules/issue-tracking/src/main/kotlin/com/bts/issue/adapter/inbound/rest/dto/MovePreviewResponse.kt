// 이슈 이동 preview 응답 DTO — 도메인 내부 필드를 제거한 정식 와이어 형태 (FR-MV-01 C2)

package com.bts.issue.adapter.inbound.rest.dto

import com.bts.issue.application.CustomFieldPreviewSection
import com.bts.issue.application.MovePreview
import com.bts.issue.application.ResourceMappingSection
import com.bts.issue.application.SubtaskPreviewNode
import com.bts.issue.application.VersionMappingSection
import com.bts.issue.application.WorkflowPreviewSection
import com.bts.issue.component.web.dto.ComponentResponse
import com.bts.issue.customfield.web.dto.CustomFieldResponse
import com.bts.issue.version.web.dto.VersionResponse
import com.bts.shared.workflow.WorkflowStateView
import java.util.UUID

/**
 * POST /api/v1/issues/{key}/move/preview 성공 응답 DTO.
 *
 * [com.bts.issue.application.MovePreview] application 모델을 와이어 안전 형태로 변환한다.
 * 도메인 객체 ([com.bts.issue.version.domain.Version], [com.bts.issue.component.domain.Component],
 * [com.bts.issue.customfield.domain.CustomFieldDefinition]) 가 와이어에 직접 노출되지 않도록
 * 각 섹션을 정식 응답 DTO 로 래핑한다.
 *
 * @property version 루트 이슈의 OCC 버전.
 * @property workflow 워크플로우 상태 호환성 섹션.
 * @property components 컴포넌트 자동매핑 섹션.
 * @property affectsVersions affectsVersions 자동매핑 섹션.
 * @property fixVersions fixVersions 자동매핑 섹션.
 * @property customFields 커스텀필드 호환성 섹션.
 * @property subtasks 직접 자식 이슈 노드별 preview 섹션. 서브태스크 없으면 빈 목록.
 */
data class MovePreviewResponse(
    val version: Long,
    val workflow: WorkflowPreviewSectionResponse,
    val components: ResourceMappingSectionResponse,
    val affectsVersions: VersionMappingSectionResponse,
    val fixVersions: VersionMappingSectionResponse,
    val customFields: CustomFieldPreviewSectionResponse,
    val subtasks: List<SubtaskPreviewNodeResponse>,
) {
    companion object {
        /**
         * [MovePreview] application 모델을 [MovePreviewResponse] DTO 로 변환한다.
         *
         * @param preview 변환 대상 application 모델.
         * @return [MovePreviewResponse] 인스턴스.
         */
        fun from(preview: MovePreview): MovePreviewResponse =
            MovePreviewResponse(
                version = preview.version,
                workflow = WorkflowPreviewSectionResponse.from(preview.workflow),
                components = ResourceMappingSectionResponse.from(preview.components),
                affectsVersions = VersionMappingSectionResponse.from(preview.affectsVersions),
                fixVersions = VersionMappingSectionResponse.from(preview.fixVersions),
                customFields = CustomFieldPreviewSectionResponse.from(preview.customFields),
                subtasks = preview.subtasks.map(SubtaskPreviewNodeResponse::from),
            )
    }
}

/**
 * 워크플로우 상태 호환성 섹션 응답 DTO.
 *
 * [WorkflowStateView] 는 key/name/isDone 원시 타입만 포함하므로 그대로 노출한다.
 *
 * @property compatible 현재 상태가 대상 워크플로우에 존재하면 true.
 * @property targetStates 대상 프로젝트의 전체 워크플로우 상태 목록.
 * @property suggestedStateKey 권장 대상 상태 키. 없으면 null.
 */
data class WorkflowPreviewSectionResponse(
    val compatible: Boolean,
    val targetStates: List<WorkflowStateView>,
    val suggestedStateKey: String?,
) {
    companion object {
        /**
         * [WorkflowPreviewSection] 을 [WorkflowPreviewSectionResponse] 로 변환한다.
         *
         * @param section 변환 대상 application 모델.
         * @return [WorkflowPreviewSectionResponse] 인스턴스.
         */
        fun from(section: WorkflowPreviewSection): WorkflowPreviewSectionResponse =
            WorkflowPreviewSectionResponse(
                compatible = section.compatible,
                targetStates = section.targetStates,
                suggestedStateKey = section.suggestedStateKey,
            )
    }
}

/**
 * 컴포넌트 자동매핑 섹션 응답 DTO.
 *
 * [com.bts.issue.component.domain.Component] 도메인 객체를 [ComponentResponse] 로 변환하여
 * [deletedAt] 등 내부 필드가 와이어에 노출되지 않게 한다.
 *
 * @property current 현재 이슈에 연결된 컴포넌트 목록 (DTO).
 * @property target 대상 프로젝트의 전체 활성 컴포넌트 목록 (DTO).
 * @property autoMapping 원본 컴포넌트 id → 이름 일치 시 대상 컴포넌트 id, 없으면 null.
 */
data class ResourceMappingSectionResponse(
    val current: List<ComponentResponse>,
    val target: List<ComponentResponse>,
    val autoMapping: Map<UUID, UUID?>,
) {
    companion object {
        /**
         * [ResourceMappingSection] 을 [ResourceMappingSectionResponse] 로 변환한다.
         *
         * @param section 변환 대상 application 모델.
         * @return [ResourceMappingSectionResponse] 인스턴스.
         */
        fun from(section: ResourceMappingSection): ResourceMappingSectionResponse =
            ResourceMappingSectionResponse(
                current = section.current.map(ComponentResponse::from),
                target = section.target.map(ComponentResponse::from),
                autoMapping = section.autoMapping,
            )
    }
}

/**
 * 버전 자동매핑 섹션 응답 DTO.
 *
 * [com.bts.issue.version.domain.Version] 도메인 객체를 [VersionResponse] 로 변환하여
 * [deletedAt] 등 내부 필드가 와이어에 노출되지 않게 하고,
 * [VersionResponse] 의 [@JsonFormat] 으로 날짜가 "yyyy-MM-dd" 형식으로 직렬화된다.
 *
 * @property current 현재 이슈에 연결된 버전 목록 (DTO).
 * @property target 대상 프로젝트의 전체 활성 버전 목록 (DTO).
 * @property autoMapping 원본 버전 id → 이름 일치 시 대상 버전 id, 없으면 null.
 */
data class VersionMappingSectionResponse(
    val current: List<VersionResponse>,
    val target: List<VersionResponse>,
    val autoMapping: Map<UUID, UUID?>,
) {
    companion object {
        /**
         * [VersionMappingSection] 을 [VersionMappingSectionResponse] 로 변환한다.
         *
         * @param section 변환 대상 application 모델.
         * @return [VersionMappingSectionResponse] 인스턴스.
         */
        fun from(section: VersionMappingSection): VersionMappingSectionResponse =
            VersionMappingSectionResponse(
                current = section.current.map(VersionResponse::from),
                target = section.target.map(VersionResponse::from),
                autoMapping = section.autoMapping,
            )
    }
}

/**
 * 커스텀필드 호환성 섹션 응답 DTO.
 *
 * [com.bts.issue.customfield.domain.CustomFieldDefinition] 도메인 객체를 [CustomFieldResponse] 로 변환한다.
 *
 * @property removed 현재 이슈에 값이 있지만 대상 프로젝트 정의에는 없는 필드 목록 (DTO).
 * @property requiredMissing 대상 프로젝트에서 필수이지만 현재 이슈에 값이 없는 필드 목록 (DTO).
 */
data class CustomFieldPreviewSectionResponse(
    val removed: List<CustomFieldResponse>,
    val requiredMissing: List<CustomFieldResponse>,
) {
    companion object {
        /**
         * [CustomFieldPreviewSection] 을 [CustomFieldPreviewSectionResponse] 로 변환한다.
         *
         * @param section 변환 대상 application 모델.
         * @return [CustomFieldPreviewSectionResponse] 인스턴스.
         */
        fun from(section: CustomFieldPreviewSection): CustomFieldPreviewSectionResponse =
            CustomFieldPreviewSectionResponse(
                removed = section.removed.map(CustomFieldResponse::from),
                requiredMissing = section.requiredMissing.map(CustomFieldResponse::from),
            )
    }
}

/**
 * 서브태스크 노드별 preview 응답 DTO.
 *
 * [SubtaskPreviewNode] application 모델의 각 섹션을 정식 응답 DTO 로 변환한다.
 *
 * @property issueKey 자식 이슈 키 (이동 전 원본 키).
 * @property issueTypeKey 자식 이슈 타입 키. null 이면 타입 조회 실패.
 * @property version 자식 이슈의 OCC 버전.
 * @property workflow 워크플로우 상태 호환성 섹션 (DTO).
 * @property components 컴포넌트 자동매핑 섹션 (DTO).
 * @property affectsVersions affectsVersions 자동매핑 섹션 (DTO).
 * @property fixVersions fixVersions 자동매핑 섹션 (DTO).
 * @property customFields 커스텀필드 호환성 섹션 (DTO).
 */
data class SubtaskPreviewNodeResponse(
    val issueKey: String,
    val issueTypeKey: String?,
    val version: Long,
    val workflow: WorkflowPreviewSectionResponse,
    val components: ResourceMappingSectionResponse,
    val affectsVersions: VersionMappingSectionResponse,
    val fixVersions: VersionMappingSectionResponse,
    val customFields: CustomFieldPreviewSectionResponse,
) {
    companion object {
        /**
         * [SubtaskPreviewNode] application 모델을 [SubtaskPreviewNodeResponse] DTO 로 변환한다.
         *
         * @param node 변환 대상 application 모델.
         * @return [SubtaskPreviewNodeResponse] 인스턴스.
         */
        fun from(node: SubtaskPreviewNode): SubtaskPreviewNodeResponse =
            SubtaskPreviewNodeResponse(
                issueKey = node.issueKey,
                issueTypeKey = node.issueTypeKey,
                version = node.version,
                workflow = WorkflowPreviewSectionResponse.from(node.workflow),
                components = ResourceMappingSectionResponse.from(node.components),
                affectsVersions = VersionMappingSectionResponse.from(node.affectsVersions),
                fixVersions = VersionMappingSectionResponse.from(node.fixVersions),
                customFields = CustomFieldPreviewSectionResponse.from(node.customFields),
            )
    }
}
