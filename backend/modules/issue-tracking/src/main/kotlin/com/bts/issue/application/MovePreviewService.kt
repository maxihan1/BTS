// 이슈 이동 preview 서비스 — 대상 프로젝트 이동 시 비호환 항목 계산 (FR-MV-01)

package com.bts.issue.application

import com.bts.issue.component.domain.Component
import com.bts.issue.component.repository.ComponentRepository
import com.bts.issue.customfield.domain.CustomFieldDefinition
import com.bts.issue.customfield.repository.CustomFieldDefinitionRepository
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueNotFoundException
import com.bts.issue.domain.IssueProjectNotFoundException
import com.bts.issue.repository.IssueRepository
import com.bts.issue.version.domain.Version
import com.bts.issue.version.repository.VersionRepository
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.issue.IssueTypeKey
import com.bts.shared.workflow.ProjectKey
import com.bts.shared.workflow.WorkflowStateCatalog
import com.bts.shared.workflow.WorkflowStateView
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

// ── Preview 응답 모델 ─────────────────────────────────────────────────────────

/**
 * 이슈 이동 preview 응답 — 워크플로우/컴포넌트/버전/커스텀필드 호환성 정보를 담는다.
 *
 * Controller DTO 는 Task 8(MoveController)에서 별도로 정의한다.
 *
 * @property workflow 워크플로우 상태 호환성 정보.
 * @property components 컴포넌트 자동매핑 정보.
 * @property affectsVersions affectsVersions 자동매핑 정보.
 * @property fixVersions fixVersions 자동매핑 정보.
 * @property customFields 커스텀필드 호환성 정보.
 * @property subtasks 직접 자식 이슈 노드별 매핑 섹션. 서브태스크 없으면 빈 목록.
 */
data class MovePreview(
    val workflow: WorkflowPreviewSection,
    val components: ResourceMappingSection,
    val affectsVersions: VersionMappingSection,
    val fixVersions: VersionMappingSection,
    val customFields: CustomFieldPreviewSection,
    val subtasks: List<SubtaskPreviewNode> = emptyList(),
)

/**
 * 서브태스크 노드별 preview 섹션.
 *
 * 부모 이슈의 직접 자식 각각에 대한 호환성 정보를 담는다.
 *
 * @property issueKey 자식 이슈 키 (이동 전 원본 키).
 * @property issueTypeKey 자식 이슈 타입 키. null 이면 타입 조회 실패.
 * @property workflow 워크플로우 상태 호환성 정보.
 * @property components 컴포넌트 자동매핑 정보.
 * @property affectsVersions affectsVersions 자동매핑 정보.
 * @property fixVersions fixVersions 자동매핑 정보.
 * @property customFields 커스텀필드 호환성 정보.
 */
data class SubtaskPreviewNode(
    val issueKey: String,
    val issueTypeKey: String?,
    val workflow: WorkflowPreviewSection,
    val components: ResourceMappingSection,
    val affectsVersions: VersionMappingSection,
    val fixVersions: VersionMappingSection,
    val customFields: CustomFieldPreviewSection,
)

/**
 * 워크플로우 상태 호환성 섹션.
 *
 * @property compatible 현재 상태가 대상 워크플로우에 존재하면 true.
 * @property targetStates 대상 프로젝트의 전체 워크플로우 상태 목록.
 * @property suggestedStateKey 권장 대상 상태 키.
 *   compatible=true 이면 현재 상태 키, false 이면 대상 상태 목록 첫 번째 키.
 *   대상 상태 목록이 비어있으면 null.
 */
data class WorkflowPreviewSection(
    val compatible: Boolean,
    val targetStates: List<WorkflowStateView>,
    val suggestedStateKey: String?,
)

/**
 * 컴포넌트 자동매핑 섹션.
 *
 * @property current 현재 이슈에 연결된 컴포넌트 목록.
 * @property target 대상 프로젝트의 전체 활성 컴포넌트 목록.
 * @property autoMapping 원본 컴포넌트 id → 이름 일치 시 대상 컴포넌트 id, 없으면 null.
 */
data class ResourceMappingSection(
    val current: List<Component>,
    val target: List<Component>,
    val autoMapping: Map<UUID, UUID?>,
)

/**
 * 버전 자동매핑 섹션.
 *
 * @property current 현재 이슈에 연결된 버전 목록.
 * @property target 대상 프로젝트의 전체 활성 버전 목록.
 * @property autoMapping 원본 버전 id → 이름 일치 시 대상 버전 id, 없으면 null.
 */
data class VersionMappingSection(
    val current: List<Version>,
    val target: List<Version>,
    val autoMapping: Map<UUID, UUID?>,
)

/**
 * 커스텀필드 호환성 섹션.
 *
 * @property removed 현재 이슈에 값이 있지만 대상 프로젝트 정의에는 없는 필드 목록.
 * @property requiredMissing 대상 프로젝트에서 필수이지만 현재 이슈에 값이 없는 필드 목록.
 */
data class CustomFieldPreviewSection(
    val removed: List<CustomFieldDefinition>,
    val requiredMissing: List<CustomFieldDefinition>,
)

// ── 서비스 ────────────────────────────────────────────────────────────────────

/**
 * 이슈 이동 preview 서비스 — 대상 프로젝트로 이동 시 어떤 매핑이 필요한지를 계산해 반환한다.
 *
 * Jira 이동 마법사의 1단계에 해당하는 읽기 전용 서비스로, 실제 이동(write) 은 수행하지 않는다.
 *
 * ### 권한 게이트
 * 1. 원본 프로젝트 [IssuePermission.UPDATE] (Project 범위)
 * 2. 대상 프로젝트 [IssuePermission.CREATE] (Project 범위)
 * 둘 중 하나라도 없으면 [IssueAccessDeniedException](403) 을 던진다.
 *
 * ### 계산 내용
 * - 워크플로우. 현재 상태가 대상 워크플로우 상태 목록에 있으면 compatible=true, 없으면 false.
 * - 컴포넌트. 이름 일치 자동매핑.
 * - affectsVersions / fixVersions. 컴포넌트와 동형(이름 일치 자동매핑).
 * - 커스텀필드. removed(대상 정의에 없는 현재 필드) + requiredMissing(대상 필수 필드 중 현재 값 없는 것).
 *
 * ### 트랜잭션
 * [WorkflowStateCatalog.listStates] 가 Propagation.MANDATORY 이므로
 * [preview] 는 [Transactional](readOnly=true) 안에서 실행된다.
 */
@Service
@Transactional(readOnly = true)
class MovePreviewService(
    private val permissionResolver: IssuePermissionResolver,
    private val issueRepository: IssueRepository,
    private val componentRepository: ComponentRepository,
    private val versionRepository: VersionRepository,
    private val customFieldDefinitionRepository: CustomFieldDefinitionRepository,
    private val workflowStateCatalog: WorkflowStateCatalog,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 이슈를 대상 프로젝트로 이동할 때 필요한 매핑 정보를 계산하여 반환한다.
     *
     * @param actor 이동을 요청하는 행위자.
     * @param issueKey 이동할 이슈 키.
     * @param targetProjectKey 이동 대상 프로젝트 키.
     * @return [MovePreview] — 워크플로우/컴포넌트/버전/커스텀필드 호환성 정보.
     * @throws IssueAccessDeniedException 원본 UPDATE 또는 대상 CREATE 권한 미보유 시 (403).
     * @throws IssueNotFoundException 이슈가 없거나 소프트 삭제된 경우.
     * @throws IssueProjectNotFoundException 대상 프로젝트가 존재하지 않을 때.
     */
    @Suppress("ThrowsCount")
    fun preview(
        actor: ActorId,
        issueKey: IssueKey,
        targetProjectKey: String,
    ): MovePreview {
        val sourceProjectKey = issueKey.projectPrefix

        assertPermission(actor, IssuePermission.UPDATE, IssueScope.Project(sourceProjectKey))
        assertPermission(actor, IssuePermission.CREATE, IssueScope.Project(targetProjectKey))

        val issue = issueRepository.findByKey(issueKey) ?: throw IssueNotFoundException(issueKey)
        val sourceProjectId = issue.projectId
        val targetProjectId =
            issueRepository.findProjectIdByKey(targetProjectKey)
                ?: throw IssueProjectNotFoundException(targetProjectKey)

        val workflowSection = buildWorkflowSection(issue.currentStateKey, targetProjectKey, issueTypeKey = null)
        val componentSection = buildComponentSection(issue.componentIds, sourceProjectId, targetProjectId)
        val affectsVersionSection = buildVersionSection(issue.affectsVersionIds, sourceProjectId, targetProjectId)
        val fixVersionSection = buildVersionSection(issue.fixVersionIds, sourceProjectId, targetProjectId)
        val customFieldSection = buildCustomFieldSection(issue.customFields, sourceProjectId, targetProjectId)

        val children = issueRepository.findDirectChildren(issue.id.value)
        val subtaskNodes = children.map { child -> buildSubtaskPreviewNode(child, targetProjectKey, targetProjectId) }

        log.info(
            "move_preview issueKey={} targetProject={} subtaskCount={} actor={}",
            issueKey.value,
            targetProjectKey,
            subtaskNodes.size,
            actor.value,
        )

        return MovePreview(
            workflow = workflowSection,
            components = componentSection,
            affectsVersions = affectsVersionSection,
            fixVersions = fixVersionSection,
            customFields = customFieldSection,
            subtasks = subtaskNodes,
        )
    }

    /**
     * 단일 자식 이슈에 대한 SubtaskPreviewNode 를 생성한다.
     *
     * issueTypeKey 는 [IssueRepository.findByKeyWithType] 로 조회한다 (C2 plan 제약).
     * 타입 조회 실패 시 issueTypeKey=null, workflowStateCatalog 에 null 전달.
     *
     * @param child 자식 이슈 도메인 객체.
     * @param targetProjectKey 대상 프로젝트 키.
     * @param targetProjectId 대상 프로젝트 UUID.
     * @return [SubtaskPreviewNode].
     */
    private fun buildSubtaskPreviewNode(
        child: Issue,
        targetProjectKey: String,
        targetProjectId: UUID,
    ): SubtaskPreviewNode {
        val childWithType = issueRepository.findByKeyWithType(child.key)
        val childTypeKey = childWithType?.typeKey?.let { IssueTypeKey(it) }
        val childTypeKeyString = childWithType?.typeKey

        val childSourceProjectId = child.projectId
        return SubtaskPreviewNode(
            issueKey = child.key.value,
            issueTypeKey = childTypeKeyString,
            workflow = buildWorkflowSection(child.currentStateKey, targetProjectKey, childTypeKey),
            components = buildComponentSection(child.componentIds, childSourceProjectId, targetProjectId),
            affectsVersions = buildVersionSection(child.affectsVersionIds, childSourceProjectId, targetProjectId),
            fixVersions = buildVersionSection(child.fixVersionIds, childSourceProjectId, targetProjectId),
            customFields = buildCustomFieldSection(child.customFields, childSourceProjectId, targetProjectId),
        )
    }

    // ── private helpers ────────────────────────────────────────────────────────

    /**
     * 권한 검증 공통 헬퍼.
     *
     * @param actor 검증 대상 행위자.
     * @param permission 검증할 권한.
     * @param scope 권한 적용 범위.
     * @throws IssueAccessDeniedException 권한 없을 때.
     */
    private fun assertPermission(
        actor: ActorId,
        permission: IssuePermission,
        scope: IssueScope,
    ) {
        if (!permissionResolver.hasPermission(actor.value, permission, scope)) {
            throw IssueAccessDeniedException(actor, permission, scope)
        }
    }

    /**
     * 워크플로우 상태 호환성 섹션을 계산한다.
     *
     * 현재 상태 키가 대상 워크플로우 상태 목록에 있으면 compatible=true, 없으면 false.
     * suggestedStateKey 결정 기준.
     * - compatible=true: 현재 상태 키.
     * - compatible=false: 대상 상태 목록 첫 번째 키. 없으면 null.
     *
     * @param currentStateKey 현재 이슈 상태 키.
     * @param targetProjectKey 대상 프로젝트 키.
     * @param issueTypeKey 이슈 타입 키. null 이면 프로젝트 기본 워크플로우 조회.
     * @return [WorkflowPreviewSection].
     */
    private fun buildWorkflowSection(
        currentStateKey: String,
        targetProjectKey: String,
        issueTypeKey: IssueTypeKey?,
    ): WorkflowPreviewSection {
        val targetStates = workflowStateCatalog.listStates(ProjectKey.of(targetProjectKey), issueTypeKey)
        val targetStateKeys = targetStates.map { state -> state.key }.toSet()
        val compatible = currentStateKey in targetStateKeys
        val suggested =
            if (compatible) {
                currentStateKey
            } else {
                targetStates.firstOrNull()?.key
            }
        return WorkflowPreviewSection(
            compatible = compatible,
            targetStates = targetStates,
            suggestedStateKey = suggested,
        )
    }

    /**
     * 컴포넌트 자동매핑 섹션을 계산한다.
     *
     * 현재 이슈에 연결된 컴포넌트 각각에 대해, 대상 프로젝트에 이름이 동일한 컴포넌트가 있으면
     * autoMapping 에 대상 컴포넌트 id 를 채운다. 없으면 null.
     *
     * @param issueComponentIds 현재 이슈에 연결된 컴포넌트 UUID 목록.
     * @param sourceProjectId 원본 프로젝트 UUID.
     * @param targetProjectId 대상 프로젝트 UUID.
     * @return [ResourceMappingSection].
     * @see computeNameMatchMapping
     */
    private fun buildComponentSection(
        issueComponentIds: List<UUID>,
        sourceProjectId: UUID,
        targetProjectId: UUID,
    ): ResourceMappingSection {
        val sourceComponents = componentRepository.findByProject(sourceProjectId)
        val targetComponents = componentRepository.findByProject(targetProjectId)

        val currentComponents = sourceComponents.filter { comp -> comp.id in issueComponentIds }
        val sourceNameById = sourceComponents.idToNameMap { comp -> comp.name }
        val targetIdByName = targetComponents.nameToIdMap { comp -> comp.name }

        val autoMapping = computeNameMatchMapping(issueComponentIds, sourceNameById, targetIdByName)

        return ResourceMappingSection(
            current = currentComponents,
            target = targetComponents,
            autoMapping = autoMapping,
        )
    }

    /**
     * 버전 자동매핑 섹션을 계산한다.
     *
     * [buildComponentSection] 과 동형 — 버전 필드/repo 만 다르다.
     *
     * @param issueVersionIds 현재 이슈에 연결된 버전 UUID 목록.
     * @param sourceProjectId 원본 프로젝트 UUID.
     * @param targetProjectId 대상 프로젝트 UUID.
     * @return [VersionMappingSection].
     * @see computeNameMatchMapping
     */
    private fun buildVersionSection(
        issueVersionIds: List<UUID>,
        sourceProjectId: UUID,
        targetProjectId: UUID,
    ): VersionMappingSection {
        val sourceVersions = versionRepository.findByProject(sourceProjectId)
        val targetVersions = versionRepository.findByProject(targetProjectId)

        val currentVersions = sourceVersions.filter { ver -> ver.id in issueVersionIds }
        val sourceNameById = sourceVersions.idToNameMap { ver -> ver.name }
        val targetIdByName = targetVersions.nameToIdMap { ver -> ver.name }

        val autoMapping = computeNameMatchMapping(issueVersionIds, sourceNameById, targetIdByName)

        return VersionMappingSection(
            current = currentVersions,
            target = targetVersions,
            autoMapping = autoMapping,
        )
    }

    /**
     * 커스텀필드 호환성 섹션을 계산한다.
     *
     * - removed: 현재 이슈에 값이 있는 필드 키 중 대상 프로젝트 정의에 없는 것.
     * - requiredMissing: 대상 프로젝트 필수 필드 중 현재 이슈에 값이 없는 것.
     *
     * @param issueCustomFields 현재 이슈의 커스텀 필드 값 맵.
     * @param sourceProjectId 원본 프로젝트 UUID.
     * @param targetProjectId 대상 프로젝트 UUID.
     * @return [CustomFieldPreviewSection].
     */
    private fun buildCustomFieldSection(
        issueCustomFields: Map<String, Any?>,
        sourceProjectId: UUID,
        targetProjectId: UUID,
    ): CustomFieldPreviewSection {
        val sourceDefinitions = customFieldDefinitionRepository.findActiveByProject(sourceProjectId)
        val targetDefinitions = customFieldDefinitionRepository.findActiveByProject(targetProjectId)

        val targetKeySet = targetDefinitions.map { def -> def.key }.toSet()
        val currentValueKeys = issueCustomFields.keys

        val removed =
            sourceDefinitions.filter { def ->
                def.key in currentValueKeys && def.key !in targetKeySet
            }

        val requiredMissing =
            targetDefinitions.filter { def ->
                def.required && issueCustomFields[def.key] == null
            }

        return CustomFieldPreviewSection(
            removed = removed,
            requiredMissing = requiredMissing,
        )
    }

    /**
     * 이름 일치 기반 자동매핑을 계산한다.
     *
     * 현재 이슈에 연결된 리소스 id 각각에 대해 원본 이름을 조회하고,
     * 대상 프로젝트에 동일 이름의 리소스가 있으면 그 id 를, 없으면 null 을 반환한다.
     *
     * @param currentIds 현재 이슈에 연결된 리소스 UUID 목록.
     * @param sourceNameById 원본 리소스 id → 이름 맵.
     * @param targetIdByName 대상 프로젝트 이름 → id 맵.
     * @return 원본 id → 대상 id(일치 없으면 null) 맵.
     */
    private fun computeNameMatchMapping(
        currentIds: List<UUID>,
        sourceNameById: Map<UUID, String>,
        targetIdByName: Map<String, UUID>,
    ): Map<UUID, UUID?> =
        currentIds.associate { sourceId ->
            val sourceName = sourceNameById[sourceId]
            sourceId to (if (sourceName != null) targetIdByName[sourceName] else null)
        }
}

// ── 파일 로컬 확장 함수 — 자동매핑 헬퍼 ──────────────────────────────────────

/**
 * id가 null이 아닌 요소에 대해 id → [nameExtractor] 결과 맵을 빌드한다.
 *
 * id 가 null 인 요소는 무시한다 (DB id 미할당 상태 방어).
 *
 * @param nameExtractor 리소스 객체에서 이름 문자열을 추출하는 함수.
 * @return id → 이름 맵.
 */
private fun <T> List<T>.idToNameMap(nameExtractor: (T) -> String): Map<UUID, String>
    where T : Any =
    buildMap {
        for (item in this@idToNameMap) {
            val id =
                when (item) {
                    is Component -> item.id
                    is Version -> item.id
                    else -> null
                }
            if (id != null) put(id, nameExtractor(item))
        }
    }

/**
 * id가 null이 아닌 요소에 대해 [nameExtractor] 결과 → id 맵을 빌드한다.
 *
 * 이름 중복 시 나중에 등장한 항목이 이전 항목을 덮어쓴다 (활성 제약으로 중복 없음).
 *
 * @param nameExtractor 리소스 객체에서 이름 문자열을 추출하는 함수.
 * @return 이름 → id 맵.
 */
private fun <T> List<T>.nameToIdMap(nameExtractor: (T) -> String): Map<String, UUID>
    where T : Any =
    buildMap {
        for (item in this@nameToIdMap) {
            val id =
                when (item) {
                    is Component -> item.id
                    is Version -> item.id
                    else -> null
                }
            if (id != null) put(nameExtractor(item), id)
        }
    }
