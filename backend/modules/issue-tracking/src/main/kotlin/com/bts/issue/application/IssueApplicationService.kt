// IssueApplicationService — 이슈 CRUD + 전이 유스케이스 조율. 모든 public 메서드 @Transactional 명시

package com.bts.issue.application

import com.bts.issue.adapter.inbound.rest.IssueResponse
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueImpact
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueNotFoundException
import com.bts.issue.domain.IssuePriority
import com.bts.issue.domain.IssueProjectNotFoundException
import com.bts.issue.domain.IssueTransitionNotAllowedException
import com.bts.issue.domain.IssueVersionConflictException
import com.bts.issue.domain.IssueWorkflowNotConfiguredException
import com.bts.issue.event.IssueCreated
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.event.IssueSoftDeleted
import com.bts.issue.event.IssueTransitioned
import com.bts.issue.event.IssueUpdated
import com.bts.issue.markdown.MarkdownRenderer
import com.bts.issue.port.outbound.IssuePermission
import com.bts.issue.port.outbound.IssuePermissionResolver
import com.bts.issue.port.outbound.IssueScope
import com.bts.issue.repository.IssueFieldPatch
import com.bts.issue.repository.IssueRepository
import com.bts.issue.type.domain.IssueTypeNotFoundException
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.issue.IssueTypeKey
import com.bts.shared.workflow.AvailableTransitionView
import com.bts.shared.workflow.AvailableTransitionsRequest
import com.bts.shared.workflow.AvailableTransitionsResult
import com.bts.shared.workflow.ProjectKey
import com.bts.shared.workflow.TransitionPlan
import com.bts.shared.workflow.TransitionRequest
import com.bts.shared.workflow.TransitionResult
import com.bts.shared.workflow.WorkflowKeyResolver
import com.bts.shared.workflow.WorkflowStartState
import com.bts.shared.workflow.WorkflowTransitionPort
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
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
 * - 이슈 타입 결정: [IssueTypeRepository.findByKey] (task fallback) / [IssueTypeRepository.findById] (지정 타입 검증)
 *   FR-6 — 모든 이슈는 유효한 타입을 보유해야 한다. typeId 미지정 시 표준 task 타입으로 자동 fallback.
 * - 이벤트 발행: [IssueEventPublisher] (Propagation.MANDATORY — 같은 트랜잭션)
 * - 워크플로우 전이: [WorkflowTransitionPort] (inbound port — BC 격리 준수)
 * - 워크플로우 키 결정: [WorkflowKeyResolver] (shared-kernel SPI — project-workflow BC 내부 직접 import 금지)
 *
 * 모든 public 메서드는 @Transactional 을 명시한다 (DEVELOPMENT.md §절대규칙).
 *
 * TooManyFunctions: 이슈 CRUD + 전이 유스케이스 전반을 단일 Application Service 가 담당하므로 함수 수 임계치(11)를 초과한다.
 * availableTransitions 추가로 11개가 됐으나 책임 분리보다 응집이 더 적합한 구조이므로 Suppress 처리.
 */
@Suppress("TooManyFunctions", "LongParameterList")
@Service
@Transactional
class IssueApplicationService(
    private val repo: IssueRepository,
    private val issueTypeRepository: IssueTypeRepository,
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
     * 4. typeId 결정 — request.typeId 가 null 이면 task fallback (FR-6: 모든 이슈는 유효 타입 보유)
     *    non-null 이면 해당 타입 존재/활성 검증. 없으면 IssueTypeNotFoundException.
     * 5. [WorkflowKeyResolver.resolveStart] 로 초기 상태 키 결정
     *    — WorkflowSchemeNoDefaultException 발생 시 [IssueWorkflowNotConfiguredException] 으로 변환 (BC 격리)
     * 6. Issue.create
     * 7. DB INSERT
     * 8. IssueCreated 이벤트 발행
     *
     * @param actor 이슈를 생성하는 행위자.
     * @param request 생성 요청 DTO. typeId null 이면 task 타입으로 fallback.
     * @return 삽입된 [Issue].
     * @throws IssueAccessDeniedException 권한 없을 때.
     * @throws IssueTypeNotFoundException request.typeId 가 non-null 이지만 활성 타입이 없을 때.
     * @throws IssueWorkflowNotConfiguredException 프로젝트에 기본 워크플로우 스킴이 없을 때.
     *
     * TooGenericExceptionCaught/ThrowsCount: BC 격리 — project-workflow 내부 예외를 직접 import 할 수 없으므로
     * javaClass.simpleName 으로 감지한다. RuntimeException catch 는 의도적인 설계 (DEVELOPMENT.md §1.1).
     */
    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
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

        val resolvedTypeId = resolveTypeId(request.typeId)

        val startState =
            try {
                workflowKeyResolver.resolveStart(ProjectKey.of(request.projectKey), null)
            } catch (e: RuntimeException) {
                // BC 격리: WorkflowSchemeNoDefaultException 직접 import 불가 — 클래스명 비교로 처리
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
                typeId = resolvedTypeId,
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
        log.info("issue_created key={} typeId={} actor={}", saved.key.value, resolvedTypeId.value, actor.value)
        return saved
    }

    /**
     * 이슈 단건을 조회한다.
     *
     * 단건 경로이므로 description 을 HTML 로 렌더하여 descriptionHtml 에 채운다 (C3).
     * 목록 경로([listIssues])는 N건 렌더 비용 방지를 위해 descriptionHtml=null 유지.
     *
     * @param actor 조회 행위자.
     * @param key 조회할 이슈 키.
     * @return [IssueResponse] DTO. descriptionHtml 이 채워져 있다.
     * @throws IssueAccessDeniedException 권한 없을 때.
     * @throws IssueNotFoundException 이슈가 없거나 소프트 삭제된 경우.
     */
    @Transactional(readOnly = true)
    fun findByKey(
        actor: ActorId,
        key: IssueKey,
    ): IssueResponse {
        assertPermission(actor, IssuePermission.VIEW, IssueScope.Issue(key.value))
        val response = repo.findByKeyWithType(key) ?: throw IssueNotFoundException(key)
        return response.withRenderedHtml()
    }

    /**
     * 이슈 필드를 수정한다 (낙관락).
     *
     * 흐름.
     * 1. UPDATE 권한 검증 (Issue 범위)
     * 2. 이슈 조회 — 미존재 시 IssueNotFoundException
     * 3. typeId non-null 이면 활성 타입 존재 검증 — 없으면 IssueTypeNotFoundException
     *    (CREATE 의 null=task fallback 과 달리 PATCH 의 null=변경없음 시맨틱)
     * 4. priority/impact non-null 이면 범위 검증 — 위반 시 IllegalArgumentException
     * 5. labels non-null 이면 [Issue.normalizeLabels] 로 도메인 검증 + 정규화 —
     *    공백-only/50자 초과/21개 초과 시 IllegalArgumentException
     * 6. changedFields 계산 — empty 이면 no-op 반환
     * 7. [IssueRepository.updateFields] 호출 — 0 row 반환 시 IssueVersionConflictException
     * 8. 변경 후 이슈 재조회
     * 9. IssueUpdated 이벤트 발행 (변경 필드 목록 포함)
     *
     * ### merge-patch 3-상태 sentinel 규칙 (B1)
     * - description/environment: null=무변경, ""=DB NULL 클리어, 값=설정.
     * - labels: null=무변경, []=전체 제거, 값=교체.
     * - priority/impact: null=무변경, 값=설정.
     *
     * @param actor 수정 행위자.
     * @param key 수정할 이슈 키.
     * @param request 수정 요청 DTO. 각 필드 null=무변경 (RFC 7396 JSON Merge Patch).
     * @return 수정된 이슈의 [IssueResponse].
     * @throws IssueAccessDeniedException 권한 없을 때.
     * @throws IssueNotFoundException 이슈가 없거나 소프트 삭제된 경우.
     * @throws IssueTypeNotFoundException request.typeId 가 non-null 이지만 활성 타입이 없을 때.
     * @throws IssueVersionConflictException 낙관락 충돌 시.
     * @throws IllegalArgumentException priority 가 1..5 범위 밖이거나 impact 가 1..3 범위 밖일 때.
     */
    fun updateIssue(
        actor: ActorId,
        key: IssueKey,
        request: UpdateIssueRequest,
    ): IssueResponse {
        assertPermission(actor, IssuePermission.UPDATE, IssueScope.Issue(key.value))
        val existing = repo.findByKey(key) ?: throw IssueNotFoundException(key)

        // typeId non-null 이면 활성 타입 존재 검증. null=변경없음 (resolveTypeId 의 null=fallback 과 다른 시맨틱).
        if (request.typeId != null) {
            issueTypeRepository.findById(request.typeId) ?: throw IssueTypeNotFoundException(request.typeId)
        }

        validatePriorityImpactRanges(request.priority, request.impact)

        // 라벨 도메인 검증 + 정규화 — null=무변경(스킵), non-null=도메인 권위 검증 필수.
        // BLOCKER 1: PATCH 경로에서 도메인 validateAndNormalizeLabels 를 우회하는 경로를 차단한다.
        val normalizedLabels: List<String>? = request.labels?.let { Issue.normalizeLabels(it) }

        val changedFields = buildChangedFields(existing, request, normalizedLabels)
        if (changedFields.isEmpty()) {
            log.info("issue_update_noop key={} actor={}", key.value, actor.value)
            return (repo.findByKeyWithType(key) ?: throw IssueNotFoundException(key)).withRenderedHtml()
        }
        val updatedRows =
            repo.updateFields(
                key = key,
                patch =
                    IssueFieldPatch(
                        summary = request.summary,
                        typeId = request.typeId,
                        description = request.description,
                        priority = request.priority,
                        labels = normalizedLabels,
                        environment = request.environment,
                        impact = request.impact,
                    ),
                expectedVersion = request.expectedVersion,
            )
        if (updatedRows == 0) {
            throw IssueVersionConflictException(key, existing.version)
        }
        eventPublisher.publish(
            IssueUpdated(
                issueKey = key,
                fields = changedFields,
                occurredAt = Instant.now(clock),
            ),
        )
        log.info("issue_updated key={} fields={} actor={}", key.value, changedFields, actor.value)
        return (repo.findByKeyWithType(key) ?: throw IssueNotFoundException(key)).withRenderedHtml()
    }

    /**
     * 이슈의 현재 상태에서 이동 가능한 전이 목록을 조회한다.
     *
     * 흐름.
     * 1. VIEW 권한 검증 (Issue 범위)
     * 2. 이슈 조회 — 미존재 시 IssueNotFoundException
     * 3. [resolveWorkflowKeyReadOnly] 로 workflowKey 결정 (auto-assign 없음) —
     *    미할당이면 IssueWorkflowNotConfiguredException(422) 변환,
     *    WorkflowSchemeNoDefaultException 발생 시에도 동일 변환
     * 4. workflowPort.availableTransitions 호출 — [AvailableTransitionsResult] sealed 분기 처리
     *
     * 읽기 경로이므로 [WorkflowKeyResolver.resolveExisting] 을 사용한다.
     * [WorkflowKeyResolver.resolveStart](auto-assign 포함)는 쓰기 경로
     * (createIssue/transitionIssue)에서만 호출한다.
     *
     * @param actor 조회 행위자.
     * @param key 조회할 이슈 키.
     * @return 현재 상태에서 이동 가능한 [AvailableTransitionView] 목록.
     * @throws IssueAccessDeniedException 권한 없을 때.
     * @throws IssueNotFoundException 이슈가 없거나 소프트 삭제된 경우.
     * @throws IssueWorkflowNotConfiguredException 프로젝트에 워크플로우 스킴이 미할당이거나
     *   워크플로우 row 가 없을 때. 부수 효과(DB 쓰기/이벤트)는 발생하지 않는다.
     */
    @Transactional(readOnly = true)
    fun availableTransitions(
        actor: ActorId,
        key: IssueKey,
    ): List<AvailableTransitionView> {
        assertPermission(actor, IssuePermission.VIEW, IssueScope.Issue(key.value))
        val issue = repo.findByKey(key) ?: throw IssueNotFoundException(key)
        val resolvedWorkflow = resolveWorkflowKeyReadOnly(key)
        val req =
            AvailableTransitionsRequest(
                workflowKey = resolvedWorkflow.workflowKey,
                fromStateKey = issue.currentStateKey,
                issueKey = key.value,
                actorId = actor.value.toString(),
                actorRoles = emptySet(),
                issueFields = mapOf("summary" to issue.summary),
            )
        return when (val result = workflowPort.availableTransitions(req)) {
            is AvailableTransitionsResult.Success -> result.transitions
            is AvailableTransitionsResult.WorkflowNotFound ->
                throw IssueWorkflowNotConfiguredException(key.projectPrefix, null)
        }
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
    @Suppress("ThrowsCount", "TooGenericExceptionCaught")
    fun transitionIssue(
        actor: ActorId,
        key: IssueKey,
        request: TransitionIssueRequest,
    ): IssueResponse {
        assertPermission(actor, IssuePermission.TRANSITION, IssueScope.Issue(key.value))
        val issue = repo.findByKeyForUpdate(key) ?: throw IssueNotFoundException(key)
        val resolvedWorkflow = resolveWorkflowKey(key)
        val transitionReq =
            TransitionRequest(
                workflowKey = resolvedWorkflow.workflowKey,
                issueKey = key.value,
                fromStateKey = issue.currentStateKey,
                toStateKey = request.toStateKey,
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
        log.info(
            "issue_transitioned key={} from={} to={} actor={}",
            key.value,
            issue.currentStateKey,
            plan.toStateKey,
            actor.value,
        )
        return (repo.findByKeyWithType(key) ?: throw IssueNotFoundException(key)).withRenderedHtml()
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
        return repo.listWithType(projectKey, pageable)
    }

    // ── private helpers ────────────────────────────────────────────────────────

    /**
     * request.typeId 로부터 유효한 [IssueTypeId] 를 결정한다.
     *
     * FR-6 — 모든 이슈는 유효한 타입을 보유해야 한다.
     * - null 이면 표준 task 타입으로 fallback (issue_types.key = "task").
     * - non-null 이면 활성 타입 존재 여부 검증 후 그 id 반환. 없으면 [IssueTypeNotFoundException].
     *
     * @param requestedTypeId 컨트롤러에서 전달된 typeId. null 허용.
     * @return 유효성이 보장된 [IssueTypeId].
     * @throws IssueTypeNotFoundException requestedTypeId 가 non-null 이지만 활성 타입이 없을 때.
     */
    private fun resolveTypeId(requestedTypeId: IssueTypeId?): IssueTypeId {
        if (requestedTypeId == null) {
            val taskType = issueTypeRepository.findByKey(IssueTypeKey("task"))
            return taskType?.id ?: error("표준 task 타입이 DB에 없습니다. V003 마이그레이션 확인 필요.")
        }
        issueTypeRepository.findById(requestedTypeId)
            ?: throw IssueTypeNotFoundException(requestedTypeId)
        return requestedTypeId
    }

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

    /**
     * 쓰기 경로용 — [WorkflowKeyResolver.resolveStart](auto-assign 포함) 를 호출하고
     * [WorkflowSchemeNoDefaultException] 을 [IssueWorkflowNotConfiguredException] 으로 변환한다.
     *
     * createIssue / transitionIssue 에서만 사용한다.
     * 읽기 경로에서는 [resolveWorkflowKeyReadOnly] 를 사용해야 한다.
     *
     * issue-tracking BC 는 project-workflow 내부 예외를 직접 import 할 수 없으므로
     * javaClass.simpleName 로 감지한다 (BC 격리, DEVELOPMENT.md §1.1).
     *
     * @param key 워크플로우를 resolve 할 이슈 키.
     * @return [WorkflowStartState] — workflowKey + startStateKey.
     * @throws IssueWorkflowNotConfiguredException WorkflowSchemeNoDefaultException 발생 시.
     *
     * TooGenericExceptionCaught: project-workflow 내부 예외를 직접 import 할 수 없으므로
     * RuntimeException 을 catch 하여 simpleName 으로 감지한다. 의도적인 설계 (DEVELOPMENT.md §1.1).
     */
    @Suppress("TooGenericExceptionCaught")
    private fun resolveWorkflowKey(key: IssueKey): WorkflowStartState =
        try {
            workflowKeyResolver.resolveStart(ProjectKey.of(key.projectPrefix), null)
        } catch (e: RuntimeException) {
            if (e.javaClass.simpleName == "WorkflowSchemeNoDefaultException") {
                throw IssueWorkflowNotConfiguredException(key.projectPrefix, null)
            }
            throw e
        }

    /**
     * 읽기 경로용 — [WorkflowKeyResolver.resolveExisting](auto-assign 없음) 를 호출한다.
     *
     * availableTransitions 에서만 사용한다.
     * null 반환(스킴 미할당) 및 [WorkflowSchemeNoDefaultException] 을 모두
     * [IssueWorkflowNotConfiguredException](422) 으로 변환한다.
     *
     * DB 쓰기 및 이벤트 발행이 일어나지 않는다.
     *
     * @param key 워크플로우를 resolve 할 이슈 키.
     * @return [WorkflowStartState] — workflowKey + startStateKey.
     * @throws IssueWorkflowNotConfiguredException 스킴 미할당 또는 WorkflowSchemeNoDefaultException 발생 시.
     *
     * TooGenericExceptionCaught: project-workflow 내부 예외를 직접 import 할 수 없으므로
     * RuntimeException 을 catch 하여 simpleName 으로 감지한다. 의도적인 설계 (DEVELOPMENT.md §1.1).
     */
    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    private fun resolveWorkflowKeyReadOnly(key: IssueKey): WorkflowStartState {
        val result =
            try {
                workflowKeyResolver.resolveExisting(ProjectKey.of(key.projectPrefix), null)
            } catch (e: RuntimeException) {
                if (e.javaClass.simpleName == "WorkflowSchemeNoDefaultException") {
                    throw IssueWorkflowNotConfiguredException(key.projectPrefix, null)
                }
                throw e
            }
        return result ?: throw IssueWorkflowNotConfiguredException(key.projectPrefix, null)
    }

    /**
     * priority/impact 범위를 검증한다.
     *
     * non-null 인 값에 대해 [IssuePriority.fromNumber] / [IssueImpact.fromNumber] 를 호출한다.
     * 범위 밖이면 해당 함수 내부에서 [IllegalArgumentException] 을 던진다.
     * null 이면 무변경이므로 검증 대상 아님.
     *
     * @param priority 검증할 우선순위 값. null 이면 스킵.
     * @param impact 검증할 영향도 값. null 이면 스킵.
     * @throws IllegalArgumentException priority 가 1..5 밖이거나 impact 가 1..3 밖일 때.
     */
    private fun validatePriorityImpactRanges(
        priority: Int?,
        impact: Int?,
    ) {
        if (priority != null) IssuePriority.fromNumber(priority)
        if (impact != null) IssueImpact.fromNumber(impact)
    }

    /**
     * 수정 요청에서 실제로 값이 달라지는 필드 이름 집합을 계산한다.
     *
     * ### 3-상태 sentinel 규칙 (B1)
     * - summary/typeId: null=무변경, 값=변경(기존값과 다를 때만 changedFields 포함).
     * - description/environment: null=무변경, ""=클리어(기존 non-null 이면 변경), 값=변경(기존값과 다를 때).
     * - labels: null=무변경, []=전체 제거(기존 비어있지 않으면 변경), 값=교체(기존과 다를 때).
     *   [normalizedLabels] 는 [Issue.normalizeLabels] 를 거친 정규화 값이어야 한다.
     * - priority/impact: null=무변경, 값=변경(기존값과 다를 때).
     *
     * @param normalizedLabels labels 를 [Issue.normalizeLabels] 로 정규화한 결과. null=무변경.
     * @return 변경된 필드 이름 집합. 비어있으면 no-op.
     */
    private fun buildChangedFields(
        existing: Issue,
        request: UpdateIssueRequest,
        normalizedLabels: List<String>?,
    ): Set<String> {
        val fields = mutableSetOf<String>()
        if (request.summary != null && existing.summary != request.summary) fields.add("summary")
        if (request.typeId != null && existing.typeId != request.typeId) fields.add("typeId")

        // description: null=무변경, ""=클리어(기존 non-null 이면 변경), 값=설정(기존과 다를 때)
        if (isTextFieldChanged(existing.description, request.description)) fields.add("description")

        // environment: null=무변경, ""=클리어(기존 non-null 이면 변경), 값=설정(기존과 다를 때)
        if (isTextFieldChanged(existing.environment, request.environment)) fields.add("environment")

        // labels: null=무변경, []=전체 제거(기존 비어있지 않으면 변경), 값=교체(기존 정규화값과 다를 때)
        // normalizedLabels 는 도메인 정규화(dedup/trim) 후의 최종값과 비교한다.
        if (normalizedLabels != null && existing.labels != normalizedLabels) fields.add("labels")

        // priority: null=무변경, 값=변경(기존과 다를 때)
        if (request.priority != null && existing.priority != request.priority) fields.add("priority")

        // impact: null=무변경, 값=변경(기존과 다를 때)
        if (request.impact != null && existing.impact != request.impact) fields.add("impact")

        return fields
    }

    /**
     * Nullable 텍스트 필드(description, environment)의 3-상태 변경 여부를 판정한다.
     *
     * - requestValue=null → 무변경 → false
     * - requestValue="" → 클리어 → 기존값이 non-null 이면 true
     * - requestValue=값 → 기존값과 다르면 true
     */
    private fun isTextFieldChanged(
        existingValue: String?,
        requestValue: String?,
    ): Boolean {
        if (requestValue == null) return false
        return existingValue != requestValue
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

    /**
     * 단건 조회 응답에 descriptionHtml 을 채운다 (C3).
     *
     * description 이 null 이면 descriptionHtml 도 null 유지.
     * non-null 이면 [MarkdownRenderer.renderSafe] 로 렌더하여 채운다.
     *
     * 목록 경로([listIssues])는 N건 렌더 비용 방지를 위해 이 함수를 호출하지 않는다.
     */
    private fun IssueResponse.withRenderedHtml(): IssueResponse =
        copy(descriptionHtml = description?.let { MarkdownRenderer.renderSafe(it) })
}
