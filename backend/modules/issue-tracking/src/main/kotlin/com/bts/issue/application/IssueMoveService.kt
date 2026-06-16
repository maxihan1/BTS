// IssueMoveService — 이슈 단건 프로젝트 간 이동 실행 유스케이스 (FR-MV-01 Task 7)

package com.bts.issue.application

import com.bts.issue.component.repository.ComponentRepository
import com.bts.issue.customfield.repository.CustomFieldDefinitionRepository
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueMoveContext
import com.bts.issue.domain.IssueMoveOperation
import com.bts.issue.domain.IssueNotFoundException
import com.bts.issue.domain.IssueProjectNotFoundException
import com.bts.issue.domain.IssueVersionConflictException
import com.bts.issue.domain.IssueWorkflowNotConfiguredException
import com.bts.issue.history.IssueHistoryRecorder
import com.bts.issue.repository.IssueKeyRedirectRepository
import com.bts.issue.repository.IssueRepository
import com.bts.issue.version.repository.VersionRepository
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.workflow.ProjectKey
import com.bts.shared.workflow.WorkflowKeyResolver
import com.bts.shared.workflow.WorkflowStateCatalog
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 단일 노드(루트 또는 자식) 이동 매핑 스펙.
 *
 * [IssueMoveRequest.subtasks] 배열의 원소로, 각 자식 이슈의 이동 파라미터를 담는다.
 *
 * @param issueKey 자식 이슈 키 (이동 전 원본 키).
 * @param expectedVersion 자식 이슈 OCC 낙관락 버전.
 * @param targetStateKey 대상 상태 키. null 이면 현재 상태 키를 그대로 사용 시도.
 * @param targetStateIsDone 대상 상태가 DONE 카테고리인지 여부.
 * @param componentMapping 원본 컴포넌트 UUID → 대상 컴포넌트 UUID 매핑.
 * @param affectsVersionMapping 원본 affects-version UUID → 대상 버전 UUID 매핑.
 * @param fixVersionMapping 원본 fix-version UUID → 대상 버전 UUID 매핑.
 * @param additionalCustomFields 대상 프로젝트 필수 필드 추가 값.
 */
data class SubtaskMoveSpec(
    val issueKey: String,
    val expectedVersion: Long,
    val targetStateKey: String?,
    val targetStateIsDone: Boolean,
    val componentMapping: Map<UUID, UUID?>,
    val affectsVersionMapping: Map<UUID, UUID?>,
    val fixVersionMapping: Map<UUID, UUID?>,
    val additionalCustomFields: Map<String, Any?>,
)

/**
 * 이슈 이동 결과.
 *
 * 단건 경로에서는 [movedSubtasks] 가 빈 목록이다.
 *
 * @param newKey 이동된 루트 이슈의 새 키.
 * @param movedSubtasks 동반 이동된 자식 이슈 결과 목록.
 */
data class MoveResult(
    val newKey: IssueKey,
    val movedSubtasks: List<MovedNode>,
)

/**
 * 이동된 자식 노드 결과.
 *
 * @param previousKey 이동 전 원본 자식 이슈 키.
 * @param newKey 이동 후 새 자식 이슈 키.
 */
data class MovedNode(
    val previousKey: IssueKey,
    val newKey: IssueKey,
)

/**
 * 이슈 이동 실행 요청 DTO.
 *
 * Jira 마법사 2단계(매핑 적용 후 실제 이동)에 해당하는 파라미터를 담는다.
 * preview(1단계)에서 사용자가 확인한 매핑 정보를 그대로 전달한다.
 *
 * @param targetProjectKey 이동 대상 프로젝트 키.
 * @param expectedVersion OCC 낙관락 버전. 읽은 version 과 일치해야 이동이 진행된다.
 * @param targetStateKey 대상 프로젝트에서 적용할 상태 키. null 이면 소스 상태 키를 그대로 사용 시도.
 * @param targetStateIsDone 대상 상태가 DONE 카테고리인지 여부. false 이면 resolution_id 를 null 로 clear 한다(C4).
 * @param componentMapping 원본 컴포넌트 UUID → 대상 컴포넌트 UUID 매핑. 값이 null 이면 미매핑(제거).
 * @param affectsVersionMapping 원본 affects-version UUID → 대상 버전 UUID 매핑.
 * @param fixVersionMapping 원본 fix-version UUID → 대상 버전 UUID 매핑.
 * @param additionalCustomFields 대상 프로젝트에서 필수이지만 기존 이슈에 없는 커스텀 필드 추가 값.
 * @param subtasks 동반 이동할 직접 자식 노드별 매핑. 빈 배열이면 단건 경로(회귀 보존).
 */
data class IssueMoveRequest(
    val targetProjectKey: String,
    val expectedVersion: Long,
    val targetStateKey: String?,
    val targetStateIsDone: Boolean,
    val componentMapping: Map<UUID, UUID?>,
    val affectsVersionMapping: Map<UUID, UUID?>,
    val fixVersionMapping: Map<UUID, UUID?>,
    val additionalCustomFields: Map<String, Any?>,
    val subtasks: List<SubtaskMoveSpec> = emptyList(),
)

/**
 * 이슈 단건 프로젝트 간 이동 유스케이스.
 *
 * Jira 마법사 2단계(매핑 적용) 에 해당하며, 다음 불변식을 단일 @Transactional 안에서 원자적으로 보장한다.
 *
 * ### 불변식
 * 1. issues.id 불변 — 이슈 고유 식별자(UUID)는 프로젝트 이동 후에도 바뀌지 않는다.
 * 2. 키 발번 — 대상 프로젝트의 key_sequence 를 pg_advisory_xact_lock 으로 보호하여 중복 없이 증가한다.
 * 3. redirect 영구 보존 — issue_key_redirects 에 (oldKey → newKey) 행을 반드시 삽입한다 (DATA.md §2).
 * 4. resolution_id clear — 대상 상태가 DONE 이 아니면 resolution_id 를 null 로 clear 한다 (C4).
 * 5. parent_id 초기화 — 외부 프로젝트 부모와의 연결을 끊는다.
 *
 * TooManyFunctions: 이동 유스케이스의 단계별 private 헬퍼가 11개를 초과하나 단일 유스케이스 응집이 더 적합.
 */
@Service
@Transactional
@Suppress(
    "TooManyFunctions",
    // 이동 유스케이스 협력자 9개(ComponentRepository/VersionRepository/CustomFieldDefinitionRepository 포함) — 불가분
    "LongParameterList",
)
class IssueMoveService(
    private val issueRepository: IssueRepository,
    private val redirectRepository: IssueKeyRedirectRepository,
    private val permissionResolver: IssuePermissionResolver,
    private val workflowKeyResolver: WorkflowKeyResolver,
    private val workflowStateCatalog: WorkflowStateCatalog,
    private val historyRecorder: IssueHistoryRecorder,
    private val componentRepository: ComponentRepository,
    private val versionRepository: VersionRepository,
    private val customFieldDefinitionRepository: CustomFieldDefinitionRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 이슈를 대상 프로젝트로 이동한다.
     *
     * **단일 @Transactional 안에서 원자적으로 실행된다.**
     *
     * 실행 순서.
     * 1. SELECT FOR UPDATE 비관락 조회 — 미존재 404.
     * 2. OCC: expectedVersion 불일치 → IssueVersionConflictException(409).
     * 3. 권한 검증: 원본 UPDATE + 대상 CREATE.
     * 4. 워크플로우 미설정 검증: resolveExisting null → IssueWorkflowNotConfiguredException(422).
     * 5. IssueMoveOperation.validate — 도메인 규칙 일괄 검증 (EC1/EC15/EC7/EC8/EC9).
     * 6. 대상 키 발번: incrementKeySequence(pg_advisory_xact_lock 포함).
     * 7. issues UPDATE: project_id / key / current_state_key / custom_fields / parent_id=null / version bump.
     *    resolution_id C4: targetStateIsDone=false 이면 null clear.
     * 8. 조인 테이블 교체: 컴포넌트 / affects-version / fix-version.
     * 9. IssueKeyRedirectRepository.insert(oldKey, newKey) — 리다이렉트 영구 보존 (DATA.md §2).
     * 10. 히스토리 기록.
     *
     * @param actor 이동 행위자.
     * @param issueKey 이동할 이슈 키.
     * @param request 이동 요청 DTO.
     * @return 이동된 이슈의 새 키.
     * @throws IssueNotFoundException 이슈가 없을 때.
     * @throws IssueVersionConflictException OCC 충돌 시.
     * @throws IssueAccessDeniedException 권한 없을 때.
     * @throws IssueWorkflowNotConfiguredException 대상 프로젝트 워크플로우 미설정.
     * @throws com.bts.issue.domain.MoveSameProjectException 같은 프로젝트로 이동 시.
     * @throws com.bts.issue.domain.IssueHasSubtasksException 서브태스크 보유 이슈 이동 시.
     * @throws com.bts.issue.domain.InvalidTargetStateException 대상 상태 결정 불가 시.
     * @throws com.bts.issue.domain.InvalidTargetMappingException 컴포넌트/버전 매핑 대상 미존재.
     * @throws com.bts.issue.domain.RequiredFieldMissingException 필수 커스텀필드 누락.
     *
     * TooGenericExceptionCaught: project-workflow BC 내부 예외를 직접 import 할 수 없으므로
     * simpleName 비교로 감지한다. 의도적인 BC 격리 패턴 (DEVELOPMENT.md §1.1).
     */
    @Suppress("ThrowsCount", "LongMethod", "TooGenericExceptionCaught")
    fun move(
        actor: ActorId,
        issueKey: IssueKey,
        request: IssueMoveRequest,
    ): MoveResult {
        val targetProjectKey = request.targetProjectKey

        // 3. 권한 검증 — 리소스 조회보다 먼저 (존재 probe 방지)
        assertPermission(actor, IssuePermission.UPDATE, IssueScope.Project(issueKey.projectPrefix))
        assertPermission(actor, IssuePermission.CREATE, IssueScope.Project(targetProjectKey))

        // 4. 대상 워크플로우 미설정 검증
        // WorkflowSchemeNoDefaultException 은 project-workflow BC 내부 예외이므로 직접 import 불가.
        val hasWorkflow =
            try {
                workflowKeyResolver.resolveExisting(ProjectKey.of(targetProjectKey), null) != null
            } catch (e: RuntimeException) {
                if (e.javaClass.simpleName == "WorkflowSchemeNoDefaultException") false else throw e
            }
        if (!hasWorkflow) throw IssueWorkflowNotConfiguredException(targetProjectKey, null)

        // 대상 워크플로우 상태 목록 조회
        val targetStates = workflowStateCatalog.listStates(ProjectKey.of(targetProjectKey), null)
        val targetStateKeys = targetStates.map { it.key }.toSet()

        val targetProjectId =
            issueRepository.findProjectIdByKey(targetProjectKey)
                ?: throw IssueProjectNotFoundException(targetProjectKey)

        // 대상 프로젝트의 실제 컴포넌트/버전/필수필드 집합 — EC8/EC9 서버측 검증 (단건/동반 공유)
        val targetProjectComponentIds =
            componentRepository.findByProject(targetProjectId).mapNotNull { it.id }.toSet()
        val targetProjectVersionIds =
            versionRepository.findByProject(targetProjectId).mapNotNull { it.id }.toSet()
        val requiredFieldKeys =
            customFieldDefinitionRepository.findActiveByProject(targetProjectId)
                .filter { it.required }.map { it.key }.toSet()

        return if (request.subtasks.isEmpty()) {
            moveSingle(
                actor = actor,
                issueKey = issueKey,
                request = request,
                targetProjectId = targetProjectId,
                targetStateKeys = targetStateKeys,
                targetProjectComponentIds = targetProjectComponentIds,
                targetProjectVersionIds = targetProjectVersionIds,
                requiredFieldKeys = requiredFieldKeys,
            )
        } else {
            moveWithSubtasks(
                actor = actor,
                issueKey = issueKey,
                request = request,
                targetProjectId = targetProjectId,
                targetStateKeys = targetStateKeys,
                targetProjectComponentIds = targetProjectComponentIds,
                targetProjectVersionIds = targetProjectVersionIds,
                requiredFieldKeys = requiredFieldKeys,
            )
        }
    }

    /**
     * 단건 이슈 이동 — 서브태스크 없는 경로(단건 #153 회귀 보존).
     */
    @Suppress("ThrowsCount", "LongMethod", "LongParameterList")
    private fun moveSingle(
        actor: ActorId,
        issueKey: IssueKey,
        request: IssueMoveRequest,
        targetProjectId: UUID,
        targetStateKeys: Set<String>,
        targetProjectComponentIds: Set<UUID>,
        targetProjectVersionIds: Set<UUID>,
        requiredFieldKeys: Set<String>,
    ): MoveResult {
        val targetProjectKey = request.targetProjectKey

        // 1. SELECT FOR UPDATE — 비관락
        val issue = issueRepository.findByKeyForUpdate(issueKey) ?: throw IssueNotFoundException(issueKey)

        // 2. OCC 검증
        if (request.expectedVersion != issue.version) throw IssueVersionConflictException(issueKey, issue.version)

        // 5. 도메인 검증 (EC1/EC15/EC7/EC8/EC9)
        val hasSubtasks = issueRepository.countDirectChildren(issue.id.value) > 0
        val ctx =
            buildMoveContext(
                issueKey = issueKey,
                targetProjectKey = targetProjectKey,
                hasSubtasks = hasSubtasks,
                issue = issue,
                spec = request.toSpec(),
                targetStateKeys = targetStateKeys,
                targetProjectComponentIds = targetProjectComponentIds,
                targetProjectVersionIds = targetProjectVersionIds,
                requiredFieldKeys = requiredFieldKeys,
            )
        IssueMoveOperation.validate(ctx)

        // (C3) before snapshot before moveIssue
        val beforeIssue = issue

        // 6. 키 발번 + 이동
        val newKey = IssueKey.of(targetProjectKey, issueRepository.incrementKeySequence(targetProjectKey))
        val resolvedStateKey = resolveState(issue.currentStateKey, targetStateKeys, request.targetStateKey)
        val filteredCustomFields = buildFilteredCustomFields(issue.customFields, request.additionalCustomFields)
        val resolvedResolutionId = if (request.targetStateIsDone) issue.resolutionId else null

        val updatedRows =
            issueRepository.moveIssue(
                oldKey = issueKey,
                newKey = newKey,
                targetProjectId = targetProjectId,
                targetStateKey = resolvedStateKey,
                resolvedResolutionId = resolvedResolutionId,
                filteredCustomFields = filteredCustomFields,
                expectedVersion = request.expectedVersion,
                newParentId = null,
            )
        if (updatedRows == 0) throw IssueVersionConflictException(issueKey, issue.version)

        replaceComponentsAfterMove(issue.id.value, issue.componentIds, request.componentMapping)
        replaceVersionsAfterMove(issue.id.value, issue.affectsVersionIds, request.affectsVersionMapping, false)
        replaceVersionsAfterMove(issue.id.value, issue.fixVersionIds, request.fixVersionMapping, true)
        redirectRepository.insert(issueKey, newKey)

        historyRecorder.record(
            before = beforeIssue,
            after =
                beforeIssue.copy(
                    key = newKey,
                    projectId = targetProjectId,
                    currentStateKey = resolvedStateKey,
                    resolutionId = resolvedResolutionId,
                    parentId = null,
                    customFields = filteredCustomFields,
                    version = request.expectedVersion + 1,
                ),
            actor = actor,
            projectId = beforeIssue.projectId,
        )

        log.info(
            "issue_moved oldKey={} newKey={} targetProject={} actor={}",
            issueKey.value,
            newKey.value,
            targetProjectKey,
            actor.value,
        )
        return MoveResult(newKey = newKey, movedSubtasks = emptyList())
    }

    /**
     * 루트+자식 동반 이동 — 단일 트랜잭션, id 오름차순 비관락(B2), 노드별 검증(B3), before 스냅샷(C3).
     */
    @Suppress("ThrowsCount", "LongMethod", "LongParameterList")
    private fun moveWithSubtasks(
        actor: ActorId,
        issueKey: IssueKey,
        request: IssueMoveRequest,
        targetProjectId: UUID,
        targetStateKeys: Set<String>,
        targetProjectComponentIds: Set<UUID>,
        targetProjectVersionIds: Set<UUID>,
        requiredFieldKeys: Set<String>,
    ): MoveResult {
        val targetProjectKey = request.targetProjectKey

        // 루트 읽기 (락 없이 id 조회용)
        val rootIssue = issueRepository.findByKey(issueKey) ?: throw IssueNotFoundException(issueKey)

        // 자식 목록 조회
        val children = issueRepository.findDirectChildren(rootIssue.id.value)
        val actualChildKeys = children.map { it.key.value }.toSet()
        val providedChildKeys = request.subtasks.map { it.issueKey }.toSet()
        val childKeysWithOwnChildren =
            children
                .filter { child -> issueRepository.countDirectChildren(child.id.value) > 0 }
                .map { it.key.value }.toSet()

        // (B2) id 오름차순 비관락: 루트+자식 통합 정렬
        val allKeys = listOf(rootIssue) + children
        val sortedByIdAsc = allKeys.sortedBy { it.id.value }
        val lockedIssues = mutableMapOf<String, Issue>()
        for (issueToLock in sortedByIdAsc) {
            val locked =
                issueRepository.findByKeyForUpdate(issueToLock.key)
                    ?: throw IssueNotFoundException(issueToLock.key)
            lockedIssues[locked.key.value] = locked
        }
        val root = lockedIssues[issueKey.value] ?: throw IssueNotFoundException(issueKey)

        // OCC — 루트
        if (request.expectedVersion != root.version) throw IssueVersionConflictException(issueKey, root.version)

        // 자식 OCC
        for (spec in request.subtasks) {
            val childIssue =
                lockedIssues[spec.issueKey]
                    ?: throw IssueNotFoundException(IssueKey(spec.issueKey))
            if (spec.expectedVersion != childIssue.version) {
                throw IssueVersionConflictException(IssueKey(spec.issueKey), childIssue.version)
            }
        }

        // 노드별 ctx 구성 + validateWithSubtasks (B3)
        val rootCtx =
            buildMoveContext(
                issueKey = issueKey,
                targetProjectKey = targetProjectKey,
                hasSubtasks = true,
                issue = root,
                spec = request.toSpec(),
                targetStateKeys = targetStateKeys,
                targetProjectComponentIds = targetProjectComponentIds,
                targetProjectVersionIds = targetProjectVersionIds,
                requiredFieldKeys = requiredFieldKeys,
            )
        val childCtxs =
            request.subtasks.map { spec ->
                val childIssue = lockedIssues[spec.issueKey]!!
                buildMoveContext(
                    issueKey = IssueKey(spec.issueKey),
                    targetProjectKey = targetProjectKey,
                    hasSubtasks = false,
                    issue = childIssue,
                    spec = spec,
                    targetStateKeys = targetStateKeys,
                    targetProjectComponentIds = targetProjectComponentIds,
                    targetProjectVersionIds = targetProjectVersionIds,
                    requiredFieldKeys = requiredFieldKeys,
                )
            }
        IssueMoveOperation.validateWithSubtasks(
            rootCtx = rootCtx,
            childCtxs = childCtxs,
            actualChildKeys = actualChildKeys,
            providedChildKeys = providedChildKeys,
            childKeysWithOwnChildren = childKeysWithOwnChildren,
        )

        // 키 발번 — 루트 먼저, 이후 자식 순서대로
        val newRootKey = IssueKey.of(targetProjectKey, issueRepository.incrementKeySequence(targetProjectKey))
        val childNewKeys =
            request.subtasks.map { spec ->
                spec.issueKey to IssueKey.of(targetProjectKey, issueRepository.incrementKeySequence(targetProjectKey))
            }

        // 루트 이동
        val rootBefore = root
        val rootStateKey = resolveState(root.currentStateKey, targetStateKeys, request.targetStateKey)
        val rootCustomFields = buildFilteredCustomFields(root.customFields, request.additionalCustomFields)
        val rootResolutionId = if (request.targetStateIsDone) root.resolutionId else null
        val rootRows =
            issueRepository.moveIssue(
                oldKey = issueKey,
                newKey = newRootKey,
                targetProjectId = targetProjectId,
                targetStateKey = rootStateKey,
                resolvedResolutionId = rootResolutionId,
                filteredCustomFields = rootCustomFields,
                expectedVersion = request.expectedVersion,
                newParentId = null,
            )
        if (rootRows == 0) throw IssueVersionConflictException(issueKey, root.version)
        replaceComponentsAfterMove(root.id.value, root.componentIds, request.componentMapping)
        replaceVersionsAfterMove(root.id.value, root.affectsVersionIds, request.affectsVersionMapping, false)
        replaceVersionsAfterMove(root.id.value, root.fixVersionIds, request.fixVersionMapping, true)
        redirectRepository.insert(issueKey, newRootKey)
        historyRecorder.record(
            before = rootBefore,
            after =
                rootBefore.copy(
                    key = newRootKey,
                    projectId = targetProjectId,
                    currentStateKey = rootStateKey,
                    resolutionId = rootResolutionId,
                    parentId = null,
                    customFields = rootCustomFields,
                    version = request.expectedVersion + 1,
                ),
            actor = actor,
            projectId = rootBefore.projectId,
        )

        // 자식 이동 — parent_id = 루트 id(불변, C3)
        val movedSubtasks = mutableListOf<MovedNode>()
        for ((specKey, newChildKey) in childNewKeys) {
            val spec = request.subtasks.first { it.issueKey == specKey }
            val childIssue = lockedIssues[specKey]!!
            val childBefore = childIssue
            val childStateKey = resolveState(childIssue.currentStateKey, targetStateKeys, spec.targetStateKey)
            val childCustomFields = buildFilteredCustomFields(childIssue.customFields, spec.additionalCustomFields)
            val childResolutionId = if (spec.targetStateIsDone) childIssue.resolutionId else null
            val childRows =
                issueRepository.moveIssue(
                    oldKey = IssueKey(specKey),
                    newKey = newChildKey,
                    targetProjectId = targetProjectId,
                    targetStateKey = childStateKey,
                    resolvedResolutionId = childResolutionId,
                    filteredCustomFields = childCustomFields,
                    expectedVersion = spec.expectedVersion,
                    newParentId = root.id.value,
                )
            if (childRows == 0) throw IssueVersionConflictException(IssueKey(specKey), childIssue.version)
            replaceComponentsAfterMove(childIssue.id.value, childIssue.componentIds, spec.componentMapping)
            replaceVersionsAfterMove(
                childIssue.id.value,
                childIssue.affectsVersionIds,
                spec.affectsVersionMapping,
                false,
            )
            replaceVersionsAfterMove(childIssue.id.value, childIssue.fixVersionIds, spec.fixVersionMapping, true)
            redirectRepository.insert(IssueKey(specKey), newChildKey)
            historyRecorder.record(
                before = childBefore,
                after =
                    childBefore.copy(
                        key = newChildKey,
                        projectId = targetProjectId,
                        currentStateKey = childStateKey,
                        resolutionId = childResolutionId,
                        parentId = root.id.value,
                        customFields = childCustomFields,
                        version = spec.expectedVersion + 1,
                    ),
                actor = actor,
                projectId = childBefore.projectId,
            )
            movedSubtasks.add(MovedNode(previousKey = IssueKey(specKey), newKey = newChildKey))
        }

        log.info(
            "issue_moved_with_subtasks rootOldKey={} rootNewKey={} subtaskCount={} actor={}",
            issueKey.value,
            newRootKey.value,
            movedSubtasks.size,
            actor.value,
        )
        return MoveResult(newKey = newRootKey, movedSubtasks = movedSubtasks)
    }

    /**
     * IssueMoveRequest 를 단건 spec 으로 변환하는 헬퍼.
     */
    private fun IssueMoveRequest.toSpec(): SubtaskMoveSpec =
        SubtaskMoveSpec(
            issueKey = "",
            expectedVersion = expectedVersion,
            targetStateKey = targetStateKey,
            targetStateIsDone = targetStateIsDone,
            componentMapping = componentMapping,
            affectsVersionMapping = affectsVersionMapping,
            fixVersionMapping = fixVersionMapping,
            additionalCustomFields = additionalCustomFields,
        )

    /**
     * IssueMoveContext 를 빌드하는 헬퍼.
     */
    @Suppress("LongParameterList")
    private fun buildMoveContext(
        issueKey: IssueKey,
        targetProjectKey: String,
        hasSubtasks: Boolean,
        issue: Issue,
        spec: SubtaskMoveSpec,
        targetStateKeys: Set<String>,
        targetProjectComponentIds: Set<UUID>,
        targetProjectVersionIds: Set<UUID>,
        requiredFieldKeys: Set<String>,
    ): IssueMoveContext =
        IssueMoveContext(
            sourceProjectKey = issueKey.projectPrefix,
            targetProjectKey = targetProjectKey,
            hasSubtasks = hasSubtasks,
            sourceStatusKey = issue.currentStateKey,
            targetWorkflowStatuses = targetStateKeys,
            targetStateKey = spec.targetStateKey,
            componentMappingTargetIds = spec.componentMapping.values.filterNotNull().toSet(),
            targetProjectComponentIds = targetProjectComponentIds,
            versionMappingTargetIds =
                (
                    spec.affectsVersionMapping.values.filterNotNull() +
                        spec.fixVersionMapping.values.filterNotNull()
                ).toSet(),
            targetProjectVersionIds = targetProjectVersionIds,
            requiredFieldKeys = requiredFieldKeys,
            providedFieldKeys = spec.additionalCustomFields.keys + issue.customFields.keys,
        )

    /**
     * 대상 상태 키를 결정한다.
     *
     * 현재 상태가 대상 워크플로우에 있으면 그대로 사용, 없으면 요청의 targetStateKey.
     */
    private fun resolveState(
        currentStateKey: String,
        targetStateKeys: Set<String>,
        requestedTargetStateKey: String?,
    ): String =
        if (currentStateKey in targetStateKeys) {
            currentStateKey
        } else {
            requireNotNull(requestedTargetStateKey) {
                "targetStateKey must be set when source state is not in target workflow"
            }
        }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * 권한 검증 헬퍼.
     *
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
     * 커스텀 필드를 필터링하고 추가 필드와 병합한다.
     *
     * 기존 커스텀 필드에서 대상 프로젝트에 없는 키를 제거하고, 추가 필드를 병합한다.
     * 이 메서드는 issue-tracking BC 내에서 대상 프로젝트 정의를 알 수 없으므로,
     * 컨트롤러(T8)에서 preview 결과 기반으로 additionalCustomFields 만 전달받는다.
     * 기존 필드는 그대로 유지하고 추가 필드를 덮어쓴다.
     */
    private fun buildFilteredCustomFields(
        existingCustomFields: Map<String, Any?>,
        additionalCustomFields: Map<String, Any?>,
    ): Map<String, Any?> = existingCustomFields + additionalCustomFields

    /**
     * 이동 후 컴포넌트 연결을 매핑에 따라 교체한다.
     *
     * 매핑된 대상 컴포넌트 ID 로 issue_components 를 교체한다.
     * 매핑되지 않은(null) 원본 컴포넌트는 제거된다.
     * moveIssue 가 이미 version bump 를 수행했으므로 여기서는 OCC 없이 id 직접 교체.
     *
     * @param issueId 이슈 UUID.
     * @param sourceComponentIds 이동 전 컴포넌트 UUID 목록.
     * @param componentMapping 원본 → 대상 컴포넌트 UUID 매핑.
     */
    private fun replaceComponentsAfterMove(
        issueId: UUID,
        sourceComponentIds: List<UUID>,
        componentMapping: Map<UUID, UUID?>,
    ) {
        val targetComponentIds = sourceComponentIds.mapNotNull { srcId -> componentMapping[srcId] }
        issueRepository.deleteComponentsByIssueId(issueId)
        if (targetComponentIds.isNotEmpty()) {
            issueRepository.insertComponents(issueId, targetComponentIds)
        }
    }

    /**
     * 이동 후 버전 연결을 매핑에 따라 교체한다.
     *
     * moveIssue 가 이미 version bump 를 수행했으므로 여기서는 OCC 없이 issueId 기준 DELETE+INSERT.
     *
     * @param issueId 이슈 UUID.
     * @param sourceVersionIds 이동 전 버전 UUID 목록.
     * @param mapping 원본 → 대상 버전 UUID 매핑. 값이 null 이면 미매핑(해당 버전 제거).
     * @param isFixVersion true 이면 fix-version, false 이면 affects-version.
     */
    private fun replaceVersionsAfterMove(
        issueId: UUID,
        sourceVersionIds: List<UUID>,
        mapping: Map<UUID, UUID?>,
        isFixVersion: Boolean,
    ) {
        val targetVersionIds = sourceVersionIds.mapNotNull { srcId -> mapping[srcId] }
        issueRepository.deleteVersionsByIssueId(issueId, isFixVersion)
        if (targetVersionIds.isNotEmpty()) {
            issueRepository.insertVersionLinks(issueId, targetVersionIds, isFixVersion)
        }
    }
}
