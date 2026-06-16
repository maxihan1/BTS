// IssueMoveService — 이슈 단건 프로젝트 간 이동 실행 유스케이스 (FR-MV-01 Task 7)

package com.bts.issue.application

import com.bts.issue.component.repository.ComponentRepository
import com.bts.issue.customfield.repository.CustomFieldDefinitionRepository
import com.bts.issue.domain.ActorId
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
@Suppress("TooManyFunctions")
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
    ): IssueKey {
        val targetProjectKey = request.targetProjectKey

        // 1. SELECT FOR UPDATE — 비관락 (TOCTOU 방지)
        val issue =
            issueRepository.findByKeyForUpdate(issueKey)
                ?: throw IssueNotFoundException(issueKey)

        // 2. OCC 검증
        if (request.expectedVersion != issue.version) {
            throw IssueVersionConflictException(issueKey, issue.version)
        }

        // 3. 권한 검증
        assertPermission(actor, IssuePermission.UPDATE, IssueScope.Project(issueKey.projectPrefix))
        assertPermission(actor, IssuePermission.CREATE, IssueScope.Project(targetProjectKey))

        // 4. 대상 워크플로우 미설정 검증 (resolveExisting — 부수효과 없는 읽기 전용)
        // WorkflowSchemeNoDefaultException 은 project-workflow BC 내부 예외이므로 직접 import 불가.
        // simpleName 비교로 감지하고 BC 경계 공개 예외 IssueWorkflowNotConfiguredException 으로 변환한다.
        // resolveExisting 이 null 반환하면 기본 워크플로우 없음 → 422.
        val hasWorkflow =
            try {
                workflowKeyResolver.resolveExisting(ProjectKey.of(targetProjectKey), null) != null
            } catch (e: RuntimeException) {
                if (e.javaClass.simpleName == "WorkflowSchemeNoDefaultException") false else throw e
            }
        if (!hasWorkflow) throw IssueWorkflowNotConfiguredException(targetProjectKey, null)

        // 대상 워크플로우 상태 목록 조회 — issueTypeKey=null 은 이슈 타입 무관 전체 상태 목록.
        val targetStates = workflowStateCatalog.listStates(ProjectKey.of(targetProjectKey), null)
        val targetStateKeys = targetStates.map { it.key }.toSet()

        // 5. 도메인 검증 (EC1/EC15/EC7/EC8/EC9)
        val hasSubtasks = issueRepository.countDirectChildren(issue.id.value) > 0
        val targetProjectId =
            issueRepository.findProjectIdByKey(targetProjectKey)
                ?: throw IssueProjectNotFoundException(targetProjectKey)

        val componentMappingTargetIds = request.componentMapping.values.filterNotNull().toSet()
        val versionMappingTargetIds =
            (
                request.affectsVersionMapping.values.filterNotNull() +
                    request.fixVersionMapping.values.filterNotNull()
            ).toSet()

        // 대상 프로젝트의 실제 컴포넌트/버전/필수필드 집합 — EC8/EC9 서버측 검증
        // MovePreviewService 와 동일한 repo 메서드로 조회하여 클라이언트 신뢰 없이 DB 실존 집합을 확보한다.
        val targetProjectComponentIds =
            componentRepository.findByProject(targetProjectId)
                .mapNotNull { comp -> comp.id }
                .toSet()
        val targetProjectVersionIds =
            versionRepository.findByProject(targetProjectId)
                .mapNotNull { ver -> ver.id }
                .toSet()
        val targetDefinitions = customFieldDefinitionRepository.findActiveByProject(targetProjectId)
        val requiredFieldKeys = targetDefinitions.filter { def -> def.required }.map { def -> def.key }.toSet()

        val ctx =
            IssueMoveContext(
                sourceProjectKey = issueKey.projectPrefix,
                targetProjectKey = targetProjectKey,
                hasSubtasks = hasSubtasks,
                sourceStatusKey = issue.currentStateKey,
                targetWorkflowStatuses = targetStateKeys,
                targetStateKey = request.targetStateKey,
                componentMappingTargetIds = componentMappingTargetIds,
                targetProjectComponentIds = targetProjectComponentIds,
                versionMappingTargetIds = versionMappingTargetIds,
                targetProjectVersionIds = targetProjectVersionIds,
                requiredFieldKeys = requiredFieldKeys,
                providedFieldKeys = request.additionalCustomFields.keys + issue.customFields.keys,
            )
        IssueMoveOperation.validate(ctx)

        // 6. 대상 키 발번 (pg_advisory_xact_lock 포함)
        val seq = issueRepository.incrementKeySequence(targetProjectKey)
        val newKey = IssueKey.of(targetProjectKey, seq)

        // 대상 상태 결정 (EC7 통과 보장됨)
        val resolvedStateKey =
            if (issue.currentStateKey in targetStateKeys) {
                issue.currentStateKey
            } else {
                // IssueMoveOperation.validate(EC7) 통과 후에는 targetStateKey 가 반드시 non-null.
                requireNotNull(request.targetStateKey) {
                    "targetStateKey must be set when source state is not in target workflow"
                }
            }

        // 커스텀 필드 필터링 (대상 프로젝트에 있는 키만 유지 + 추가 필드)
        val filteredCustomFields = buildFilteredCustomFields(issue.customFields, request.additionalCustomFields)

        // resolution_id C4: DONE 이 아니면 null clear
        val resolvedResolutionId = if (request.targetStateIsDone) issue.resolutionId else null

        // 7. issues UPDATE (project_id / key / state / custom_fields / parent_id=null / version bump)
        val updatedRows =
            issueRepository.moveIssue(
                oldKey = issueKey,
                newKey = newKey,
                targetProjectId = targetProjectId,
                targetStateKey = resolvedStateKey,
                resolvedResolutionId = resolvedResolutionId,
                filteredCustomFields = filteredCustomFields,
                expectedVersion = request.expectedVersion,
            )
        if (updatedRows == 0) {
            throw IssueVersionConflictException(issueKey, issue.version)
        }

        // 8. 조인 테이블 교체 (컴포넌트 / affects-version / fix-version)
        // moveIssue 가 이미 version bump 했으므로 조인 테이블은 id 기준 직접 replace
        replaceComponentsAfterMove(issue.id.value, issue.componentIds, request.componentMapping)
        replaceVersionsAfterMove(
            issueId = issue.id.value,
            sourceVersionIds = issue.affectsVersionIds,
            mapping = request.affectsVersionMapping,
            isFixVersion = false,
        )
        replaceVersionsAfterMove(
            issueId = issue.id.value,
            sourceVersionIds = issue.fixVersionIds,
            mapping = request.fixVersionMapping,
            isFixVersion = true,
        )

        // 9. redirect 영구 보존 (DATA.md §2)
        redirectRepository.insert(issueKey, newKey)

        // 10. 히스토리 기록
        val afterIssue =
            issue.copy(
                key = newKey,
                projectId = targetProjectId,
                currentStateKey = resolvedStateKey,
                resolutionId = resolvedResolutionId,
                parentId = null,
                customFields = filteredCustomFields,
                version = request.expectedVersion + 1,
            )
        historyRecorder.record(
            before = issue,
            after = afterIssue,
            actor = actor,
            projectId = issue.projectId,
        )

        log.info(
            "issue_moved oldKey={} newKey={} targetProject={} state={} actor={}",
            issueKey.value,
            newKey.value,
            targetProjectKey,
            resolvedStateKey,
            actor.value,
        )

        return newKey
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
