// IssueApplicationService — 이슈 CRUD + 전이 유스케이스 조율. 모든 public 메서드 @Transactional 명시

package com.bts.issue.application

import com.bts.issue.adapter.inbound.rest.IssueResponse
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueNotFoundException
import com.bts.issue.domain.IssueProjectNotFoundException
import com.bts.issue.domain.IssueTransitionNotAllowedException
import com.bts.issue.domain.IssueVersionConflictException
import com.bts.issue.event.IssueCreated
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.event.IssueSoftDeleted
import com.bts.issue.event.IssueTransitioned
import com.bts.issue.event.IssueUpdated
import com.bts.issue.port.outbound.IssuePermission
import com.bts.issue.port.outbound.IssuePermissionResolver
import com.bts.issue.port.outbound.IssueScope
import com.bts.issue.repository.IssueRepository
import com.bts.issue.domain.IssueWorkflowNotConfiguredException
import com.bts.shared.workflow.ProjectKey
import com.bts.shared.workflow.TransitionPlan
import com.bts.shared.workflow.TransitionRequest
import com.bts.shared.workflow.TransitionResult
import com.bts.shared.workflow.WorkflowKeyResolver
import com.bts.shared.workflow.WorkflowTransitionPort
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * 이슈 CRUD + 전이 유스케이스를 조율하는 Application Service.
 *
 * - 권한 검증: [IssuePermissionResolver] 를 통해 각 메서드 진입 직후 체크
 * - 이슈 키 발급: [IssueRepository.incrementKeySequence] (pg_advisory_xact_lock 포함)
 * - 이벤트 발행: [IssueEventPublisher] (Propagation.MANDATORY — 같은 트랜잭션)
 * - 워크플로우 전이: [WorkflowTransitionPort] (inbound port — BC 격리 준수)
 * - 워크플로우 키 결정: [WorkflowKeyResolver] (shared-kernel SPI — project-workflow BC 내부 직접 import 금지)
 *
 * 모든 public 메서드는 @Transactional 을 명시한다 (DEVELOPMENT.md §절대규칙).
 */
@Service
@Transactional
class IssueApplicationService(
    private val repo: IssueRepository,
    private val eventPublisher: IssueEventPublisher,
    private val permissionResolver: IssuePermissionResolver,
    private val workflowPort: WorkflowTransitionPort,
    private val workflowKeyResolver: WorkflowKeyResolver,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 새 이슈를 생성한다.
     *
     * 흐름.
     * 1. CREATE 권한 검증 (Project 범위)
     * 2. pg_advisory_xact_lock 으로 보호된 key_sequence 증가
     * 3. IssueKey 발급
     * 4. [WorkflowKeyResolver.resolveStart] 로 초기 상태 키 결정 (issueTypeKey = null, FR-IS-02 이전)
     *    — WorkflowSchemeNoDefaultException 발생 시 [IssueWorkflowNotConfiguredException] 으로 변환 (BC 격리)
     * 5. Issue.create
     * 6. DB INSERT
     * 7. IssueCreated 이벤트 발행
     *
     * @param actor 이슈를 생성하는 행위자.
     * @param request 생성 요청 DTO.
     * @return 삽입된 [Issue].
     * @throws IssueAccessDeniedException 권한 없을 때.
     * @throws IssueWorkflowNotConfiguredException 프로젝트에 기본 워크플로우 스킴이 없을 때.
     */
    fun createIssue(
        actor: ActorId,
        request: CreateIssueRequest,
    ): Issue {
        assertPermission(actor, IssuePermission.CREATE, IssueScope.Project(request.projectKey))

        val seq = repo.incrementKeySequence(request.projectKey)
        val key = IssueKey.of(request.projectKey, seq)
        val projectId =
            repo.findProjectIdByKey(request.projectKey)
                ?: throw IssueProjectNotFoundException(request.projectKey)
        val startState =
            try {
                workflowKeyResolver.resolveStart(ProjectKey.of(request.projectKey), null)
            } catch (e: RuntimeException) {
                if (e.javaClass.simpleName == "WorkflowSchemeNoDefaultException") {
                    throw IssueWorkflowNotConfiguredException(request.projectKey, null)
                }
                throw e
            }
        val issue =
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = key,
                projectId = projectId,
                summary = request.summary,
                reporterId = request.reporterId,
                currentStateKey = startState.startStateKey,
            )
        val saved = repo.insert(issue)
        eventPublisher.publish(
            IssueCreated(
                issueKey = saved.key,
                projectKey = request.projectKey,
                summary = saved.summary,
                reporterId = saved.reporterId,
                occurredAt = Instant.now(clock),
            ),
        )
        log.info("issue_created key={} actor={}", saved.key.value, actor.value)
        return saved
    }

    /**
     * 이슈 단건을 조회한다.
     *
     * @param actor 조회 행위자.
     * @param key 조회할 이슈 키.
     * @return [IssueResponse] DTO.
     * @throws IssueAccessDeniedException 권한 없을 때.
     * @throws IssueNotFoundException 이슈가 없거나 소프트 삭제된 경우.
     */
    @Transactional(readOnly = true)
    fun findByKey(
        actor: ActorId,
        key: IssueKey,
    ): IssueResponse {
        assertPermission(actor, IssuePermission.VIEW, IssueScope.Issue(key.value))
        val issue = repo.findByKey(key) ?: throw IssueNotFoundException(key)
        return IssueResponse.from(issue, key.projectPrefix)
    }

    /**
     * 이슈 필드를 수정한다 (낙관락).
     *
     * 흐름.
     * 1. UPDATE 권한 검증 (Issue 범위)
     * 2. 이슈 조회 — 미존재 시 IssueNotFoundException
     * 3. updateSummary 호출 — 0 row 반환 시 IssueVersionConflictException
     * 4. 변경 후 이슈 재조회
     * 5. IssueUpdated 이벤트 발행 (변경 필드 목록 포함)
     *
     * @param actor 수정 행위자.
     * @param key 수정할 이슈 키.
     * @param request 수정 요청 DTO.
     * @return 수정된 이슈의 [IssueResponse].
     * @throws IssueAccessDeniedException 권한 없을 때.
     * @throws IssueNotFoundException 이슈가 없거나 소프트 삭제된 경우.
     * @throws IssueVersionConflictException 낙관락 충돌 시.
     */
    fun updateIssue(
        actor: ActorId,
        key: IssueKey,
        request: UpdateIssueRequest,
    ): IssueResponse {
        assertPermission(actor, IssuePermission.UPDATE, IssueScope.Issue(key.value))
        val existing = repo.findByKey(key) ?: throw IssueNotFoundException(key)
        val changedFields = buildChangedFields(existing, request)
        if (changedFields.isEmpty()) {
            log.info("issue_update_noop key={} actor={}", key.value, actor.value)
            return IssueResponse.from(existing, key.projectPrefix)
        }
        val newSummary = requireNotNull(request.summary) { "summary must be non-null when changedFields is non-empty" }
        val updatedRows = repo.updateSummary(key, newSummary, request.expectedVersion)
        if (updatedRows == 0) {
            throw IssueVersionConflictException(key, existing.version)
        }
        val updated = repo.findByKey(key) ?: throw IssueNotFoundException(key)
        eventPublisher.publish(
            IssueUpdated(
                issueKey = key,
                fields = changedFields,
                occurredAt = Instant.now(clock),
            ),
        )
        log.info("issue_updated key={} fields={} actor={}", key.value, changedFields, actor.value)
        return IssueResponse.from(updated, key.projectPrefix)
    }

    /**
     * 이슈 상태를 전이한다 (WorkflowKeyResolver → workflowPort.plan() 호출 + 낙관락).
     *
     * 흐름.
     * 1. TRANSITION 권한 검증 (Issue 범위)
     * 2. SELECT FOR UPDATE 로 이슈 조회 (비관락) — 미존재 시 IssueNotFoundException
     * 3. [WorkflowKeyResolver.resolveStart] 로 workflowKey 결정 (issueTypeKey = null, FR-IS-02 이전)
     *    — WorkflowSchemeNoDefaultException 발생 시 [IssueWorkflowNotConfiguredException] 으로 변환 (BC 격리)
     * 4. workflowPort.plan() 호출 — [TransitionResult] sealed 분기 처리
     * 5. applyTransition 호출 — 0 row 면 IssueVersionConflictException
     * 6. IssueTransitioned 이벤트 발행
     *
     * 클래스 레벨 @Transactional(REQUIRED) 이 적용되므로 workflowKeyResolver.resolveStart (MANDATORY),
     * workflowPort.plan (MANDATORY) 호출 모두 만족한다.
     *
     * @param actor 전이 행위자.
     * @param key 전이할 이슈 키.
     * @param request 전이 요청 DTO.
     * @return 전이된 이슈의 [IssueResponse].
     * @throws IssueAccessDeniedException 권한 없을 때.
     * @throws IssueNotFoundException 이슈가 없는 경우.
     * @throws IssueWorkflowNotConfiguredException 프로젝트에 기본 워크플로우 스킴이 없을 때.
     * @throws IssueTransitionNotAllowedException [TransitionResult.ValidatorFailure],
     *   [TransitionResult.WorkflowNotFound], [TransitionResult.ExpressionTimeout] 케이스에서 BC 경계 변환.
     * @throws IssueVersionConflictException 낙관락 충돌 시.
     */
    @Suppress("ThrowsCount")
    fun transitionIssue(
        actor: ActorId,
        key: IssueKey,
        request: TransitionIssueRequest,
    ): IssueResponse {
        assertPermission(actor, IssuePermission.TRANSITION, IssueScope.Issue(key.value))
        val issue = repo.findByKeyForUpdate(key) ?: throw IssueNotFoundException(key)
        val resolvedWorkflow =
            try {
                workflowKeyResolver.resolveStart(ProjectKey.of(key.projectPrefix), null)
            } catch (e: RuntimeException) {
                if (e.javaClass.simpleName == "WorkflowSchemeNoDefaultException") {
                    throw IssueWorkflowNotConfiguredException(key.projectPrefix, null)
                }
                throw e
            }
        val transitionReq =
            TransitionRequest(
                workflowKey = resolvedWorkflow.workflowKey,
                issueKey = key.value,
                fromStateKey = issue.currentStateKey,
                toStateKey = request.toStateKey,
                transitionName = request.transitionName,
                actorId = actor.value.toString(),
                issueFields = mapOf("summary" to issue.summary),
                actorRoles = emptySet(),
                version = request.expectedVersion,
            )
        val plan =
            resolveWorkflowResult(
                workflowPort.plan(transitionReq),
                key,
                issue.currentStateKey,
                request.toStateKey,
            )
        val updatedRows = repo.applyTransition(key, plan.toStateKey, request.expectedVersion)
        if (updatedRows == 0) {
            throw IssueVersionConflictException(key, issue.version)
        }
        eventPublisher.publish(
            IssueTransitioned(
                issueKey = key,
                fromState = issue.currentStateKey,
                toState = plan.toStateKey,
                occurredAt = Instant.now(clock),
            ),
        )
        log.info("issue_transitioned key={} from={} to={} actor={}", key.value, issue.currentStateKey, plan.toStateKey, actor.value)
        val updated = repo.findByKey(key) ?: throw IssueNotFoundException(key)
        return IssueResponse.from(updated, key.projectPrefix)
    }

    /**
     * 이슈를 소프트 삭제한다.
     *
     * 흐름.
     * 1. SOFT_DELETE 권한 검증 (Issue 범위)
     * 2. repo.softDelete 호출 — 0 row 면 IssueNotFoundException (미존재 또는 이미 삭제)
     * 3. IssueSoftDeleted 이벤트 발행
     *
     * @param actor 삭제 행위자.
     * @param key 삭제할 이슈 키.
     * @throws IssueAccessDeniedException 권한 없을 때.
     * @throws IssueNotFoundException 이슈가 없거나 이미 삭제된 경우.
     */
    fun softDeleteIssue(
        actor: ActorId,
        key: IssueKey,
    ) {
        assertPermission(actor, IssuePermission.SOFT_DELETE, IssueScope.Issue(key.value))
        val deletedRows = repo.softDelete(key)
        if (deletedRows == 0) {
            throw IssueNotFoundException(key)
        }
        eventPublisher.publish(
            IssueSoftDeleted(
                issueKey = key,
                occurredAt = Instant.now(clock),
            ),
        )
        log.info("issue_soft_deleted key={} actor={}", key.value, actor.value)
    }

    /**
     * 프로젝트의 활성 이슈 목록을 페이지로 조회한다.
     *
     * @param actor 조회 행위자.
     * @param projectKey 프로젝트 키.
     * @param pageable 페이지 정보. pageSize > 100 이면 거부.
     * @return [Page]<[IssueResponse]>.
     * @throws IssueAccessDeniedException 권한 없을 때.
     * @throws IllegalArgumentException pageSize > 100 일 때.
     */
    @Transactional(readOnly = true)
    fun listIssues(
        actor: ActorId,
        projectKey: String,
        pageable: Pageable,
    ): Page<IssueResponse> {
        require(pageable.pageSize <= 100) {
            "pageSize must be 100 or fewer, but was ${pageable.pageSize}"
        }
        assertPermission(actor, IssuePermission.VIEW, IssueScope.Project(projectKey))
        val page = repo.list(projectKey, pageable)
        val responses = page.content.map { IssueResponse.from(it, projectKey) }
        return PageImpl(responses, pageable, page.totalElements)
    }

    // ── private helpers ────────────────────────────────────────────────────────

    /**
     * [TransitionResult] sealed 분기를 [TransitionPlan] 으로 매핑하거나 BC 경계 예외로 변환한다.
     *
     * ADR 2026-05-26-workflow-transition-port-result-sealed 참조.
     * automation BC 가 동일 패턴을 사용할 경우 이 helper 를 공통 모듈로 이동할 수 있다 (plan F10 deferred G2).
     *
     * @param result workflowPort.plan 반환값.
     * @param issueKey 전이 대상 이슈 키 — 예외 컨텍스트용.
     * @param fromStatus 전이 전 상태 키.
     * @param toStatus 전이 목표 상태 키.
     * @return [TransitionPlan] — [TransitionResult.Success] 케이스에서만 반환.
     * @throws IssueTransitionNotAllowedException [TransitionResult.ValidatorFailure],
     *   [TransitionResult.WorkflowNotFound], [TransitionResult.ExpressionTimeout] 케이스.
     */
    private fun resolveWorkflowResult(
        result: TransitionResult,
        issueKey: IssueKey,
        fromStatus: String,
        toStatus: String,
    ): TransitionPlan =
        when (result) {
            is TransitionResult.Success ->
                result.plan
            is TransitionResult.ValidatorFailure ->
                throw IssueTransitionNotAllowedException(
                    issueKey = issueKey,
                    fromStatus = fromStatus,
                    toStatus = toStatus,
                    reason = result.message,
                )
            is TransitionResult.WorkflowNotFound ->
                throw IssueTransitionNotAllowedException(
                    issueKey = issueKey,
                    fromStatus = fromStatus,
                    toStatus = toStatus,
                    reason = "워크플로우를 찾을 수 없습니다: ${result.key}",
                )
            is TransitionResult.ExpressionTimeout ->
                throw IssueTransitionNotAllowedException(
                    issueKey = issueKey,
                    fromStatus = fromStatus,
                    toStatus = toStatus,
                    reason = result.message,
                )
        }

    private fun buildChangedFields(
        existing: Issue,
        request: UpdateIssueRequest,
    ): Set<String> {
        val fields = mutableSetOf<String>()
        if (request.summary != null && existing.summary != request.summary) fields.add("summary")
        return fields
    }

    private fun assertPermission(
        actor: ActorId,
        permission: IssuePermission,
        scope: IssueScope,
    ) {
        if (!permissionResolver.hasPermission(actor, permission, scope)) {
            throw IssueAccessDeniedException(actor, permission, scope)
        }
    }
}
