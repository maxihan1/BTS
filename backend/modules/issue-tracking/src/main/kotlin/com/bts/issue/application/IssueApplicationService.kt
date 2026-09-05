// IssueApplicationService — 이슈 CRUD + 전환 유스케이스 조율. 모든 public 메서드 @Transactional 명시

package com.bts.issue.application

import com.bts.issue.adapter.inbound.rest.IssueResponse
import com.bts.issue.adapter.inbound.rest.cursor.CursorCodec
import com.bts.issue.adapter.inbound.rest.cursor.CursorPosition
import com.bts.issue.adapter.outbound.AlwaysAllowIssueSecurityDirectory
import com.bts.issue.component.repository.ComponentRepository
import com.bts.issue.customfield.domain.CustomFieldValueValidator
import com.bts.issue.customfield.repository.CustomFieldDefinitionRepository
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.AssigneeNotFoundException
import com.bts.issue.domain.ComponentLead
import com.bts.issue.domain.DefaultAssigneeResolver
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueComponentNotFoundException
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueImpact
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueLinkedVersionNotFoundException
import com.bts.issue.domain.IssueMovedException
import com.bts.issue.domain.IssueNotFoundException
import com.bts.issue.domain.IssuePriority
import com.bts.issue.domain.IssueProjectNotFoundException
import com.bts.issue.domain.IssueSecurityLevelNotInSchemeException
import com.bts.issue.domain.IssueTransitionNotAllowedException
import com.bts.issue.domain.IssueVersionConflictException
import com.bts.issue.domain.IssueWorkflowNotConfiguredException
import com.bts.issue.event.IssueAssigned
import com.bts.issue.event.IssueCreated
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.event.IssueMentioned
import com.bts.issue.event.IssueSoftDeleted
import com.bts.issue.event.IssueTransitioned
import com.bts.issue.event.IssueUpdated
import com.bts.issue.event.TransitionEventPublisher
import com.bts.issue.fieldpermission.adapter.AlwaysAllowFieldPermissionResolver
import com.bts.issue.history.IssueHistoryRecorder
import com.bts.issue.markdown.MarkdownRenderer
import com.bts.issue.mention.MentionSource
import com.bts.issue.mention.MentionTargetResolver
import com.bts.issue.mention.MentionTargets
import com.bts.issue.project.archive.ProjectArchiveGuard
import com.bts.issue.project.repository.ProjectLeadRepository
import com.bts.issue.repository.IssueFieldPatch
import com.bts.issue.repository.IssueRepository
import com.bts.issue.resolution.domain.ResolutionNotFoundException
import com.bts.issue.resolution.repository.ResolutionRepository
import com.bts.issue.template.domain.TemplateVariable
import com.bts.issue.template.domain.TemplateVariableSubstitutor
import com.bts.issue.type.domain.IssueTypeNotFoundException
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.issue.version.repository.VersionRepository
import com.bts.shared.board.BoardCardFilter
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.issue.IssueTypeKey
import com.bts.shared.permission.FieldKind
import com.bts.shared.permission.FieldPermissionResolver
import com.bts.shared.permission.FieldRef
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.permission.IssueSecurityDirectory
import com.bts.shared.user.UserLookupPort
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
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/** cursor 페이지네이션 최대 limit. 초과 시 컨트롤러에서 400 반환. */
private const val MAX_CURSOR_LIMIT = 100

/**
 * cursor 페이지네이션 조회 결과 (FR-API-01 Task 3).
 *
 * [IssueApplicationService.listIssuesByCursor] 반환 타입.
 *
 * @param T 응답 데이터 타입.
 * @property items 조회된 이슈 응답 목록.
 * @property next 다음 페이지 cursor 토큰. 마지막 페이지이면 null.
 */
data class CursorPage<T>(
    val items: List<T>,
    val next: String?,
)

/**
 * 이슈 CRUD + 전환 유스케이스를 조율하는 Application Service.
 *
 * - 권한 검증: [IssuePermissionResolver] 를 통해 각 메서드 진입 직후 체크
 * - 이슈 키 발급: [IssueRepository.incrementKeySequence] (pg_advisory_xact_lock 포함)
 * - 이슈 타입 결정: [IssueTypeRepository.findByKey] (task fallback) / [IssueTypeRepository.findById] (지정 타입 검증)
 *   FR-6 — 모든 이슈는 유효한 타입을 보유해야 한다. typeId 미지정 시 표준 task 타입으로 자동 fallback.
 * - 이벤트 발행: [IssueEventPublisher] (Propagation.MANDATORY — 같은 트랜잭션)
 * - 워크플로우 전환: [WorkflowTransitionPort] (inbound port — BC 격리 준수)
 * - 워크플로우 키 결정: [WorkflowKeyResolver] (shared-kernel SPI — project-workflow BC 내부 직접 import 금지)
 * - 이슈 이동 리다이렉트: [IssueKeyRedirectRepository.findCurrentKey] — 옛 키 조회 시 redirect 체인 순회 후
 *   [IssueMovedException] 발행 → 308 Permanent Redirect 응답 (FR-MV-01, DATA.md §2)
 * - 프로젝트 아카이브 잠금: [ProjectArchiveGuard] (FR-PJ-04 PR-4 Task 8) — 쓰기 9종(create/clone/update/
 *   transition/softDelete/changeAssignee/changeComponents/changeAffectsVersions/changeFixVersions)
 *   에서만 `assertPermission` 직후 호출한다. listIssues/listIssuesByCursor 를 비롯한 읽기 경로는
 *   guard 를 참조하지 않는다(EC-3, 상세 배치 원칙은 생성자의 [projectArchiveGuard] 필드 KDoc 참조).
 *
 * 모든 public 메서드는 @Transactional 을 명시한다 (DEVELOPMENT.md §절대규칙).
 *
 * TooManyFunctions: 이슈 CRUD + 전환 유스케이스 전반을 단일 Application Service 가 담당하므로 함수 수 임계치(11)를 초과한다.
 * availableTransitions 추가로 11개, changeAssignee 추가로 12개, changeComponents 추가로 13개가 됐으나
 * 책임 분리보다 응집이 더 적합한 구조이므로 Suppress 처리.
 * LargeClass: 필드 마스킹 헬퍼(FR-PM-07 Task-7) 추가로 임계치를 초과했으나 같은 응집 이유가 적용된다.
 */
@Suppress("TooManyFunctions", "LongParameterList", "LargeClass")
@Service
@Transactional
class IssueApplicationService(
    private val repo: IssueRepository,
    private val issueTypeRepository: IssueTypeRepository,
    private val resolutionRepository: ResolutionRepository,
    private val eventPublisher: IssueEventPublisher,
    private val permissionResolver: IssuePermissionResolver,
    private val workflowPort: WorkflowTransitionPort,
    private val workflowKeyResolver: WorkflowKeyResolver,
    private val userLookupPort: UserLookupPort,
    private val componentRepository: ComponentRepository,
    private val projectLeadRepository: ProjectLeadRepository,
    private val versionRepository: VersionRepository,
    // customFieldDefinitionRepository: 기존 테스트 호환을 위해 null 허용. Spring 컨텍스트에서는 Bean 주입.
    // null 이면 커스텀 필드 검증을 수행하지 않는다(기존 테스트 backward-compat).
    private val customFieldDefinitionRepository: CustomFieldDefinitionRepository? = null,
    // 기본값은 Spring이 관리하지 않는 단위 테스트 컨텍스트 호환용 fallback이다 (pdfRenderer 패턴 동형).
    // prod 컨텍스트에서는 IdentityAccessIssueSecurityDirectory(@Profile("prod")) 또는
    // AlwaysAllowIssueSecurityDirectory(@Profile("!prod")) Bean이 타입으로 주입돼 이 기본값을 대체한다.
    private val securityDirectory: IssueSecurityDirectory = AlwaysAllowIssueSecurityDirectory(),
    private val clock: Clock = Clock.systemUTC(),
    // 기본값은 Spring이 관리하지 않는 단위 테스트 컨텍스트 호환용 fallback이다 (securityDirectory 패턴 동형).
    // prod 컨텍스트에서는 IdentityAccessFieldPermissionResolver(@Profile("prod")) 또는
    // AlwaysAllowFieldPermissionResolver(@Profile("!prod")) Bean이 타입으로 주입돼 이 기본값을 대체한다.
    private val fieldPermissionResolver: FieldPermissionResolver = AlwaysAllowFieldPermissionResolver(),
    private val historyRecorder: IssueHistoryRecorder,
    // 이슈 생성 시 description 안전망(옵션 C, FR-TM-01 Task 7). null 이면 템플릿 미적용(benign).
    // Spring 컨텍스트에서는 IssueTemplateRepository Bean 이 주입된다.
    // 기존 단위 테스트 호환을 위해 null 기본값 유지 (customFieldDefinitionRepository 패턴 동형).
    private val issueTemplateRepository: com.bts.issue.template.repository.IssueTemplateRepository? = null,
    // 전환 post-action 이벤트(plan.emitEvents)를 q_transition_events 큐에 enqueue 하는 어댑터.
    // prod 컨텍스트에서는 TransitionEventPublisher(@Component) Bean 이 주입된다.
    // null 이면 발행을 skip 한다(기존 단위 테스트 호환용 fallback — customFieldDefinitionRepository 패턴 동형).
    // 통합 테스트에서는 실 Bean 을 주입해 enqueue 경로 전체를 검증한다.
    private val transitionEventPublisher: TransitionEventPublisher? = null,
    // 자동 watcher 등록 저장소 (FR-WT-01). null 이면 자동 등록을 skip 한다
    // (기존 단위 테스트 호환용 fallback — transitionEventPublisher 패턴 동형).
    // Spring 컨텍스트에서는 IssueWatcherRepository Bean 이 주입된다.
    private val watcherRepository: com.bts.issue.watcher.repository.IssueWatcherRepository? = null,
    // 이슈 키 리다이렉트 저장소 (FR-MV-01). null 이면 redirect 조회를 skip 한다
    // (기존 단위 테스트 호환용 fallback — watcherRepository 패턴 동형).
    // Spring 컨텍스트에서는 IssueKeyRedirectRepository Bean 이 주입된다.
    private val keyRedirectRepository: com.bts.issue.repository.IssueKeyRedirectRepository? = null,
    // 프로젝트 아카이브 잠금 가드 (FR-PJ-04 PR-4 Task 8). null 이면 아카이브 검사를 skip 한다
    // (기존 단위 테스트 호환용 fallback — keyRedirectRepository 패턴 동형).
    // Spring 컨텍스트에서는 ProjectArchiveGuard(@Component) Bean 이 주입된다.
    //
    // ## 배치 원칙 (★쓰기 초크포인트, PJ4-4) — [assertPermission] 미참조
    // 쓰기 9종(createIssue/cloneIssue/updateIssue/transitionIssue/softDeleteIssue/changeAssignee/
    // changeComponents/changeAffectsVersions/changeFixVersions) 각각의 진입부에서 `assertPermission`
    // 직후에만 호출한다(D-ORDER — 미인가 actor 가 409 로 아카이브 상태를 알아내지 못하도록).
    // listIssues/listIssuesByCursor(BROWSE, `assertPermission` 공유 지점) 를 비롯한 읽기 경로에는
    // **절대 배치하지 않는다** — 그 공유 helper 안에 넣으면 목록조회까지 409 가 되어 EC-3(읽기 생존)이
    // 파괴된다(guard-handler-matrix-blindfold 회귀 방지, IssueApplicationServiceArchiveGuardTest 참조).
    private val projectArchiveGuard: ProjectArchiveGuard? = null,
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
     * 5. componentIds distinct 정규화 + [validateComponents] (프로젝트·활성 검증) — 위반 시 422.
     * 6. [resolveDefaultAssignee] — 컴포넌트 리드 중 이름 오름차순 첫 번째를 담당자로 결정.
     * 7. [WorkflowKeyResolver.resolveStart] 로 초기 상태 키 결정
     *    — WorkflowSchemeNoDefaultException 발생 시 [IssueWorkflowNotConfiguredException] 으로 변환 (BC 격리)
     * 8. [resolveDescription] — 옵션 C 안전망 (FR-TM-01 Task 7):
     *    request.description non-blank → 요청 값 사용, null/blank → 활성 템플릿 content 조회 (없으면 null).
     * 9. Issue.create (assigneeId + componentIds + description 포함)
     * 10. DB INSERT (issues)
     * 11. [IssueRepository.insertComponents] — issue_components batch INSERT (version bump 없음)
     * 12. IssueCreated 이벤트 발행
     *
     * @param actor 이슈를 생성하는 행위자.
     * @param request 생성 요청 DTO. typeId null 이면 task 타입으로 fallback.
     *   request.description non-blank 이면 그 값 사용, null/blank 이면 활성 템플릿 content 를 안전망으로 주입.
     * @return 삽입된 [Issue].
     * @throws IssueAccessDeniedException 권한 없을 때.
     * @throws IssueTypeNotFoundException request.typeId 가 non-null 이지만 활성 타입이 없을 때.
     * @throws IssueWorkflowNotConfiguredException 프로젝트에 기본 워크플로우 스킴이 없을 때.
     *
     * TooGenericExceptionCaught/ThrowsCount: BC 격리 — project-workflow 내부 예외를 직접 import 할 수 없으므로
     * javaClass.simpleName 으로 감지한다. RuntimeException catch 는 의도적인 설계 (DEVELOPMENT.md §1.1).
     */
    @Suppress("TooGenericExceptionCaught", "ThrowsCount", "LongMethod")
    fun createIssue(
        actor: ActorId,
        request: CreateIssueRequest,
    ): Issue {
        assertPermission(actor, IssuePermission.CREATE, IssueScope.Project(request.projectKey))
        projectArchiveGuard?.check(request.projectKey) // FR-PJ-04 Task 8 — 쓰기 초크포인트(D-ORDER)

        val seq = repo.incrementKeySequence(request.projectKey)
        val key = IssueKey.of(request.projectKey, seq)
        val projectId =
            repo.findProjectIdByKey(request.projectKey)
                ?: throw IssueProjectNotFoundException(request.projectKey)

        val resolvedTypeId = resolveTypeId(request.typeId)

        val normalizedComponentIds = request.componentIds.distinct()
        validateComponents(normalizedComponentIds, projectId)

        // 보안 등급 지정(FR-PM-06) — null 이면 무검증(공개). non-null 이면 SET_SECURITY 가드 + 스킴 소속 422.
        if (request.securityLevelId != null) {
            assertSecurityLevelAssignable(
                actor = actor,
                scope = IssueScope.Project(request.projectKey),
                projectKey = request.projectKey,
                levelId = request.securityLevelId,
            )
        }

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

        val resolvedAssignee = resolveCreateAssignee(request.assignee, projectId, normalizedComponentIds)

        // FR-IS-10: 커스텀 필드 검증 — 정의 로드 후 Validator 호출. null이면 빈 맵 처리.
        val customFieldValues = request.customFields ?: emptyMap()
        val definitionRepo = customFieldDefinitionRepository
        if (definitionRepo != null) {
            val definitions = definitionRepo.findActiveByProject(projectId)
            validateCustomFields(definitions, customFieldValues)
        }

        val resolvedDescription =
            resolveDescription(request.description, projectId, resolvedTypeId, request.reporterId, request.projectKey)

        // FR-BL-01 옵션 B: 신규 이슈 rank=NULL(lazy). 드래그(rerank) 시 BacklogRankService 가 부여한다.
        val issue =
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = key,
                projectId = projectId,
                typeId = resolvedTypeId,
                summary = request.summary,
                reporterId = request.reporterId,
                currentStateKey = startState.startStateKey,
                assigneeId = resolvedAssignee,
                componentIds = normalizedComponentIds,
                securityLevelId = request.securityLevelId,
                customFields = customFieldValues,
                description = resolvedDescription,
                // FR-UX-09 B1 — 생성 시 1회 제출로 확정. null 이면 도메인 기본값에 맡긴다.
                // Issue.kt 의 PRIORITY_DEFAULT 는 파일 private 이라 여기서 참조할 수 없어
                // 공개 출처인 IssuePriority.MEDIUM.number 를 쓴다(값 동일, 매직넘버 회피).
                priority = request.priority ?: IssuePriority.MEDIUM.number,
                labels = request.labels ?: emptyList(),
            )
        val saved = repo.insert(issue)
        autoWatch(saved.id.value, listOfNotNull(saved.reporterId.value, resolvedAssignee?.value))
        repo.insertComponents(saved.id.value, normalizedComponentIds)
        eventPublisher.publish(
            IssueCreated(
                issueKey = saved.key,
                projectKey = request.projectKey,
                summary = saved.summary,
                reporterId = saved.reporterId,
                actorId = actor,
                occurredAt = Instant.now(clock),
            ),
        )
        // FR-UX-09 B1 (ADR D-4 + D-5) — 담당자가 확정됐고 REST 생성 경로일 때만 발행한다.
        // 판정식은 「최종 assigneeId non-null AND notifyAssignment」 단일 술어다.
        // ★fail-safe 게이트. Import 는 이제 AssigneeIntent.None 을 넘겨 이 조건에 **도달하지 않는다**
        //   (2026-08-09). 기본 false 를 유지하는 근거는 「현존 2회 발행 방어」가 아니라 defense-in-depth 다 —
        //   앞으로 생길 새 생산자가 알림이 꺼진 채 태어나게 한다. 비-공허 증인은 CreateTest 의 2×2 행렬.
        if (request.notifyAssignment && resolvedAssignee != null) {
            eventPublisher.publish(
                IssueAssigned(issueKey = saved.key, actorId = actor, occurredAt = Instant.now(clock)),
            )
        }
        // FR-MN-03 — 생성 본문의 멘션. 비교 대상이 없으므로 diff 가 아니라 **전체**가 신규다.
        // cloneIssue 는 이 경로를 타지 않는다 — 원본에서 이미 알린 멘션을 복제마다 다시 알리면
        // 대량 복제가 알림 폭탄이 되므로 의도적으로 제외한다(스펙 E9).
        publishAndWatchMentions(
            key = saved.key,
            issueId = saved.id.value,
            actor = actor,
            resolved =
                MentionTargetResolver.resolve(
                    before = null,
                    after = resolvedDescription,
                    actor = actor.value,
                    userLookupPort = userLookupPort,
                ),
            sourceField = MentionSource.DESCRIPTION,
            commentId = null,
        )
        recordHistory(before = null, after = saved, actor = actor, projectId = projectId)
        log.info("issue_created key={} typeId={} actor={}", saved.key.value, resolvedTypeId.value, actor.value)
        return saved
    }

    /**
     * 기존 이슈를 복제하여 같은 프로젝트에 새 이슈를 생성한다 (FR-IS-06).
     *
     * 흐름.
     * 1. VIEW 권한 검증 (원본 Issue 범위, 미인가 시 [assertViewIssueOrNotFound] 가 404 로 존재 숨김)
     *    + CREATE 권한 검증 (대상 Project 범위, 미인가 시 403) — 둘 중 하나라도 없으면 부수효과 없이 거부
     * 2. 원본 조회 — 미존재/소프트삭제 시 IssueNotFoundException
     * 3. pg_advisory_xact_lock 으로 보호된 key_sequence 증가 → 새 IssueKey 발급
     * 4. [WorkflowKeyResolver.resolveStart] 로 초기 상태 키 결정 (원본 상태는 복사하지 않음)
     * 5. Issue.create — 복사 대상 필드는 원본에서, reporterId 는 actor, currentStateKey 는 초기상태로 새로 시작
     * 6. DB INSERT
     * 7. IssueCreated 이벤트 발행 (신규 이슈이므로 별도 클론 이벤트를 두지 않는다)
     *
     * ### 복사 vs 새로 시작 (ADR 2026-06-02-issue-clone-semantics)
     * - 복사: summary(옵션 override), description, typeId, priority, labels, environment, impact, assigneeId(옵션)
     * - 새로 시작: id, key, reporterId, currentStateKey, version=1, createdAt/updatedAt
     * - typeId 는 원본 값을 그대로 복사하며 활성 재검증을 하지 않는다 (기존 이슈의 타입 보존, EC-8).
     * - 첨부/Watcher/댓글은 미구현이므로 복사 대상이 아니다.
     *
     * @param actor 클론을 수행하는 행위자. 클론본의 reporterId 가 된다.
     * @param sourceKey 복제할 원본 이슈 키.
     * @param request 클론 옵션 (includeAssignee, summaryOverride).
     * @return 생성된 클론본 [Issue].
     * @throws IssueAccessDeniedException 대상 프로젝트 CREATE 권한이 없을 때(403).
     * @throws IssueNotFoundException 원본 이슈가 없거나 소프트 삭제된 경우, 또는 원본 VIEW 권한 미인가(존재 숨김 404).
     * @throws IssueWorkflowNotConfiguredException 프로젝트에 기본 워크플로우 스킴이 없을 때.
     */
    fun cloneIssue(
        actor: ActorId,
        sourceKey: IssueKey,
        request: CloneIssueRequest,
    ): Issue {
        val projectKey = sourceKey.projectPrefix
        assertViewIssueOrNotFound(actor, sourceKey)
        assertPermission(actor, IssuePermission.CREATE, IssueScope.Project(projectKey))
        projectArchiveGuard?.check(projectKey) // FR-PJ-04 Task 8 — 쓰기 초크포인트(D-ORDER)

        val source = repo.findByKey(sourceKey) ?: throw IssueNotFoundException(sourceKey)

        val seq = repo.incrementKeySequence(projectKey)
        val newKey = IssueKey.of(projectKey, seq)
        val startState = resolveWorkflowKey(newKey)

        // FR-BL-01 옵션 B: 클론본도 rank=NULL(lazy). 드래그(rerank) 시 BacklogRankService 가 부여한다.
        val clone =
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = newKey,
                projectId = source.projectId,
                typeId = source.typeId,
                summary = resolveCloneSummary(request, source),
                reporterId = actor,
                currentStateKey = startState.startStateKey,
                description = source.description,
                priority = source.priority,
                labels = source.labels,
                environment = source.environment,
                impact = source.impact,
                assigneeId = if (request.includeAssignee) source.assigneeId else null,
            )
        val saved = repo.insert(clone)
        eventPublisher.publish(
            IssueCreated(
                issueKey = saved.key,
                projectKey = projectKey,
                summary = saved.summary,
                reporterId = saved.reporterId,
                actorId = actor,
                occurredAt = Instant.now(clock),
            ),
        )
        // 클론본에 담당자가 확정됐으면 배정 알림을 발행한다 (TODOS 「cloneIssue 는 담당자를
        // 정해도 IssueAssigned 를 발행하지 않는다」 봉합). 판정식은 createIssue 와 같은
        // 단일 술어 — 「최종 assigneeId 가 non-null AND notifyAssignment」.
        //
        // ★issueKey 는 반드시 `saved.key`(클론본)다. 같은 스코프에 `sourceKey` 가 있어
        //   그걸 쓰면 **원본 담당자에게 잘못 알림이 가는 더 나쁜 결함**이 된다.
        if (request.notifyAssignment && saved.assigneeId != null) {
            eventPublisher.publish(
                IssueAssigned(issueKey = saved.key, actorId = actor, occurredAt = Instant.now(clock)),
            )
        }
        log.info("issue_cloned source={} clone={} actor={}", sourceKey.value, saved.key.value, actor.value)
        return saved
    }

    /**
     * 클론본의 제목을 결정한다. [CloneIssueRequest.summaryOverride] 가 공백이 아니면 그 값을, 아니면 원본 summary 를 사용한다.
     *
     * @param request 클론 옵션.
     * @param source 원본 이슈.
     * @return 클론본에 사용할 제목.
     */
    private fun resolveCloneSummary(
        request: CloneIssueRequest,
        source: Issue,
    ): String = request.summaryOverride?.takeIf { it.isNotBlank() } ?: source.summary

    /**
     * 이슈 생성 시 description 을 결정한다 (FR-TM-01 옵션 C 안전망 + FR-TM-02 변수 치환).
     *
     * - [requested] 가 non-blank 이면 요청 값을 그대로 사용한다. 템플릿 조회·치환 없음.
     * - null 또는 blank 이면 [issueTemplateRepository] 에서 (projectId, issueTypeId) 활성 템플릿 content 를 조회한다.
     *   - 템플릿이 없거나 [issueTemplateRepository] 가 null 이면 null 을 반환한다.
     *   - 템플릿이 있으면 [substituteTemplateVariables] 로 변수 치환 후 반환한다.
     *
     * @param requested 요청 DTO 의 description 값. null 허용.
     * @param projectId 이슈가 속할 프로젝트 UUID.
     * @param resolvedTypeId 이슈 타입 식별자 VO.
     * @param reporterId 이슈 작성자 ActorId — author 토큰 치환에 사용.
     * @param projectKey 프로젝트 키 문자열 — project 토큰 치환에 사용.
     * @return 최종 결정된 description 문자열. null 이면 이슈 생성 시 description 없음.
     */
    private fun resolveDescription(
        requested: String?,
        projectId: UUID,
        resolvedTypeId: IssueTypeId,
        reporterId: ActorId,
        projectKey: String,
    ): String? =
        requested?.takeIf { it.isNotBlank() }
            ?: issueTemplateRepository
                ?.findActiveContentByProjectAndType(projectId, resolvedTypeId.value)
                ?.let { substituteTemplateVariables(it, reporterId, projectKey) }

    /**
     * 템플릿 content 의 변수 토큰을 실제 값으로 치환한다 (FR-TM-02).
     *
     * author 토큰 최적화: content 에 [TemplateVariable.AUTHOR.token] 이 없으면 [userLookupPort] 를 호출하지 않는다.
     * author 조회 결과가 없으면 바인딩에 추가하지 않고 [TemplateVariableSubstitutor] 의 fail-safe 에 위임한다.
     *
     * @param content 활성 템플릿 본문 (non-null, non-blank).
     * @param reporterId author 토큰 치환용 사용자 ID.
     * @param projectKey project 토큰 치환값.
     * @return 변수 치환이 적용된 content. 미정의 토큰은 리터럴로 유지된다.
     */
    private fun substituteTemplateVariables(
        content: String,
        reporterId: ActorId,
        projectKey: String,
    ): String {
        val bindings =
            buildMap<TemplateVariable, String> {
                put(TemplateVariable.DATE, LocalDate.now(clock).format(DATE_FORMATTER))
                put(TemplateVariable.PROJECT, projectKey)
                if (content.contains(TemplateVariable.AUTHOR.token)) {
                    val displayName = userLookupPort.findDisplayNamesByIds(setOf(reporterId.value))[reporterId.value]
                    if (displayName != null) put(TemplateVariable.AUTHOR, displayName)
                }
            }
        return TemplateVariableSubstitutor.substitute(content, bindings)
    }

    /**
     * 이슈 단건을 조회한다.
     *
     * 단건 경로이므로 description 을 HTML 로 렌더하여 descriptionHtml 에 채운다 (C3).
     * 목록 경로([listIssues])는 N건 렌더 비용 방지를 위해 descriptionHtml=null 유지.
     *
     * 단건 VIEW 권한 미인가 시 [assertViewIssueOrNotFound] 가 존재를 숨겨 404 로 응답한다(403 아님).
     *
     * @param actor 조회 행위자.
     * @param key 조회할 이슈 키.
     * @return [IssueResponse] DTO. descriptionHtml 이 채워져 있다.
     * @throws IssueNotFoundException 이슈가 없거나 소프트 삭제된 경우, 또는 VIEW 권한 미인가(존재 숨김).
     * @throws IssueMovedException 이슈가 다른 프로젝트로 이동되어 옛 키가 redirect 체인에 존재할 때.
     *   308 Permanent Redirect 로 응답할 새 키를 [IssueMovedException.newKey] 에 담아 던진다 (DATA.md §2).
     */
    @Transactional(readOnly = true)
    fun findByKey(
        actor: ActorId,
        key: IssueKey,
    ): IssueResponse {
        assertViewIssueOrNotFound(actor, key)
        val response =
            repo.findByKeyWithType(key) ?: run {
                val currentKey = keyRedirectRepository?.findCurrentKey(key)
                if (currentKey != null) {
                    throw IssueMovedException(currentKey.value)
                }
                throw IssueNotFoundException(key)
            }
        val detailed = response.withSingleDetail()
        return maskFieldsForSingle(actor, key.projectPrefix, detailed)
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
    @Suppress("LongMethod", "ThrowsCount")
    fun updateIssue(
        actor: ActorId,
        key: IssueKey,
        request: UpdateIssueRequest,
    ): IssueResponse {
        assertPermission(actor, IssuePermission.UPDATE, IssueScope.Issue(key.value))
        projectArchiveGuard?.checkByIssue(key) // FR-PJ-04 Task 8 — 쓰기 초크포인트(D-ORDER)
        val existing = repo.findByKey(key) ?: throw IssueNotFoundException(key)

        // 보안 등급 변경(FR-PM-06) — Unchanged 외에는 SET_SECURITY 가드 + (Assign 시) 스킴 소속 422 를
        // 부수 효과(field update) 이전에 fail-fast 로 검증한다. 미보유 403, 미소속 422.
        assertSecurityLevelPatch(actor, key, request.securityLevel)

        val validated = validateAndNormalizeUpdateRequest(actor, key, existing, request)
        val normalizedLabels = validated.normalizedLabels
        val mergedCustomFields = validated.mergedCustomFields

        // 보안 등급 변경을 field update 보다 먼저 적용해 OCC version 체인을 단일화한다.
        // 변경이 적용되면 version 이 +1 되므로 후속 field update 는 갱신된 version 을 사용해야 한다.
        val versionAfterSecurity = applySecurityLevel(existing, request.securityLevel, request.expectedVersion)

        val securityChanged = versionAfterSecurity != request.expectedVersion
        val changedFields = buildChangedFields(existing, request, normalizedLabels, mergedCustomFields)
        if (changedFields.isEmpty()) {
            return handleCoreFieldsUnchanged(key, existing, actor, securityChanged)
        }
        val body = resolveBodyPatch(request)
        val updatedRows =
            repo.updateFields(
                key = key,
                patch =
                    IssueFieldPatch(
                        summary = request.summary,
                        typeId = request.typeId,
                        description = body.markdown,
                        descriptionHtml = body.html,
                        priority = request.priority,
                        labels = normalizedLabels,
                        environment = request.environment,
                        impact = request.impact,
                        customFields = mergedCustomFields,
                        startDate = request.startDate,
                        dueDate = request.dueDate,
                        targetDate = request.targetDate,
                        originalEstimate = request.originalEstimate,
                        remainingEstimate = request.remainingEstimate,
                    ),
                expectedVersion = versionAfterSecurity,
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
        val afterIssue = repo.findByKey(key)
        recordHistory(before = existing, after = afterIssue, actor = actor, projectId = existing.projectId)
        if ("description" in changedFields) {
            publishMentions(key, existing, request, actor)
        }
        log.info("issue_updated key={} fields={} actor={}", key.value, changedFields, actor.value)
        return (repo.findByKeyWithType(key) ?: throw IssueNotFoundException(key)).withSingleDetail()
    }

    /**
     * 이슈의 현재 상태에서 이동 가능한 전환 목록을 조회한다.
     *
     * 흐름.
     * 1. VIEW 권한 검증 (Issue 범위) — 미인가 시 [assertViewIssueOrNotFound] 가 존재를 숨겨 404 로 응답
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
     * @throws IssueNotFoundException 이슈가 없거나 소프트 삭제된 경우, 또는 VIEW 권한 미인가(존재 숨김).
     * @throws IssueWorkflowNotConfiguredException 프로젝트에 워크플로우 스킴이 미할당이거나
     *   워크플로우 row 가 없을 때. 부수 효과(DB 쓰기/이벤트)는 발생하지 않는다.
     */
    @Transactional(readOnly = true)
    fun availableTransitions(
        actor: ActorId,
        key: IssueKey,
    ): List<AvailableTransitionView> {
        assertViewIssueOrNotFound(actor, key)
        val issue = repo.findByKey(key) ?: throw IssueNotFoundException(key)
        val resolvedWorkflow = resolveWorkflowKeyReadOnly(key)
        // resolution 필드 포함 — EXECUTION phase RequiredField validator 입력 (B7 에서 활성화).
        val issueFieldsForAvailable = mapOf("summary" to issue.summary, "resolution" to issue.resolutionId?.toString())
        val req =
            AvailableTransitionsRequest(
                workflowKey = resolvedWorkflow.workflowKey,
                fromStateKey = issue.currentStateKey,
                issueKey = key.value,
                actorId = actor.value.toString(),
                actorRoles = emptySet(),
                issueFields = issueFieldsForAvailable,
            )
        return when (val result = workflowPort.availableTransitions(req)) {
            is AvailableTransitionsResult.Success -> result.transitions
            is AvailableTransitionsResult.WorkflowNotFound ->
                throw IssueWorkflowNotConfiguredException(key.projectPrefix, null)
        }
    }

    /**
     * 이슈 상태를 전환하고 resolution_id 를 영속한다 (WorkflowKeyResolver → workflowPort.plan() + 낙관락).
     *
     * 흐름 (FR-IS-07 B6 포함).
     * 1. TRANSITION 권한 검증 (Issue 범위)
     * 2. [Q3] resolutionId non-null 이면 [ResolutionRepository.findById] 로 존재성 검증 —
     *    없으면 [ResolutionNotFoundException](404). 영속 전에 수행하여 DB 오염을 차단한다.
     * 3. SELECT FOR UPDATE 로 이슈 조회 (비관락) — 미존재 시 IssueNotFoundException
     * 4. [WorkflowKeyResolver.resolveStart] 로 workflowKey 결정 (issueTypeKey = null, FR-IS-02 이전)
     *    — WorkflowSchemeNoDefaultException 발생 시 [IssueWorkflowNotConfiguredException] 으로 변환 (BC 격리)
     * 5. workflowPort.plan() 호출 — [TransitionResult] sealed 분기 처리.
     *    issueFields 에 resolution 포함 — EXECUTION phase RequiredField validator 입력 (B7 에서 활성화).
     * 6. [IssueRepository.applyTransition] 호출 — resolutionId 함께 UPDATE.
     *    null 이면 DB NULL(비DONE 재전환 clear), non-null 이면 지정값 SET.
     *    0 row 면 IssueVersionConflictException.
     * 7. IssueTransitioned 이벤트 발행 (q_issue_events 큐)
     * 8. [publishTransitionEvents] — plan.emitEvents 를 q_transition_events 큐에 발행 (FR-NT-05).
     *    [transitionEventPublisher] 가 null 이면 skip (단위 테스트 호환 fallback).
     *    상태 변경과 같은 트랜잭션 = outbox 정합 (DATA.md §7.2).
     *
     * 클래스 레벨 @Transactional(REQUIRED) 이 적용되므로 workflowKeyResolver.resolveStart (MANDATORY),
     * workflowPort.plan (MANDATORY) 호출 모두 만족한다.
     *
     * @param actor 전환 행위자.
     * @param key 전환할 이슈 키.
     * @param request 전환 요청 DTO. resolutionId=null 이면 resolution_id clear.
     * @return 전환된 이슈의 [IssueResponse].
     * @throws IssueAccessDeniedException 권한 없을 때.
     * @throws com.bts.issue.resolution.domain.ResolutionNotFoundException resolutionId non-null 이지만
     *   resolutions 테이블에 존재하지 않을 때 (404, 영속 전 검증).
     * @throws IssueNotFoundException 이슈가 없는 경우.
     * @throws IssueWorkflowNotConfiguredException 프로젝트에 기본 워크플로우 스킴이 없을 때.
     * @throws IssueTransitionNotAllowedException [TransitionResult.ValidatorFailure],
     *   [TransitionResult.WorkflowNotFound], [TransitionResult.ExpressionTimeout] 케이스에서 BC 경계 변환.
     * @throws IssueVersionConflictException 낙관락 충돌 시.
     *
     * `LongMethod` 억제 이유. `transitionId` 인자 1줄이 더해져 본문이 60줄(임계값)에 정확히 닿았다.
     * 이 클래스의 분리 리팩터는 별도 작업이므로 여기서는 **국소 억제**만 한다 —
     * 전역 detekt 임계값이나 `detekt-baseline.xml` 은 건드리지 않는다.
     */
    @Suppress("ThrowsCount", "TooGenericExceptionCaught", "LongMethod")
    fun transitionIssue(
        actor: ActorId,
        key: IssueKey,
        request: TransitionIssueRequest,
    ): IssueResponse {
        assertPermission(actor, IssuePermission.TRANSITION, IssueScope.Issue(key.value))
        projectArchiveGuard?.checkByIssue(key) // FR-PJ-04 Task 8 — 쓰기 초크포인트(D-ORDER)

        // [Q3] resolutionId 존재성 검증 — plan() 호출 전에 수행하여 영속 전에 거부한다.
        // non-null 인 경우에만 조회하며, 없으면 ResolutionNotFoundException (404).
        val reqResolutionId = request.resolutionId
        if (reqResolutionId != null) {
            resolutionRepository.findById(reqResolutionId) ?: throw ResolutionNotFoundException(reqResolutionId)
        }
        val validatedResolutionId = reqResolutionId

        val issue = repo.findByKeyForUpdate(key) ?: throw IssueNotFoundException(key)
        val resolvedWorkflow = resolveWorkflowKey(key)
        // resolution 필드 포함 — EXECUTION phase RequiredField validator 입력 (B7 에서 활성화).
        val issueFieldsForTransition =
            mapOf("summary" to issue.summary, "resolution" to validatedResolutionId?.toString())
        val transitionReq =
            TransitionRequest(
                workflowKey = resolvedWorkflow.workflowKey,
                issueKey = key.value,
                fromStateKey = issue.currentStateKey,
                toStateKey = request.toStateKey,
                actorId = actor.value.toString(),
                issueFields = issueFieldsForTransition,
                actorRoles = emptySet(),
                version = request.expectedVersion,
                // 후보 지목. null 이면 엔진이 (from, to) 로 후보를 찾아 1개일 때만 실행한다
                // (ADR 2026-08-18-workflow-transition-id-identity §D3).
                transitionId = request.transitionId,
            )
        val plan =
            resolveWorkflowResult(
                workflowPort.plan(transitionReq),
                key,
                issue.currentStateKey,
                request.toStateKey,
            )
        // resolution_id 영속: validatedResolutionId non-null 이면 SET, null 이면 NULL 로 clear.
        val updatedRows = repo.applyTransition(key, plan.toStateKey, request.expectedVersion, validatedResolutionId)
        if (updatedRows == 0) {
            throw IssueVersionConflictException(key, issue.version)
        }
        eventPublisher.publish(
            IssueTransitioned(
                issueKey = key,
                fromState = issue.currentStateKey,
                toState = plan.toStateKey,
                actorId = actor,
                occurredAt = Instant.now(clock),
            ),
        )
        publishTransitionEvents(plan)
        // after 는 전환 결과를 issue.copy 로 구성 — 재조회 대신 in-memory 구성하여 쿼리를 줄인다.
        val afterTransitioned =
            issue.copy(
                currentStateKey = plan.toStateKey,
                resolutionId = validatedResolutionId,
            )
        recordHistory(before = issue, after = afterTransitioned, actor = actor, projectId = issue.projectId)
        log.info(
            "issue_transitioned key={} from={} to={} actor={}",
            key.value,
            issue.currentStateKey,
            plan.toStateKey,
            actor.value,
        )
        return (repo.findByKeyWithType(key) ?: throw IssueNotFoundException(key)).withSingleDetail()
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
        projectArchiveGuard?.checkByIssue(key) // FR-PJ-04 Task 8 — 쓰기 초크포인트(D-ORDER)
        // 이력 기록을 위해 삭제 전 이슈 상태를 미리 조회한다.
        val existing = repo.findByKey(key)
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
        recordHistory(before = existing, after = null, actor = actor, projectId = existing?.projectId)
        log.info("issue_soft_deleted key={} actor={}", key.value, actor.value)
    }

    /**
     * 이슈 담당자를 변경하거나 해제한다 (낙관락).
     *
     * 흐름.
     * 1. UPDATE 권한 검증 (Issue 범위) — 기존 updateIssue 패턴과 동일.
     * 2. 이슈 조회 — 미존재 시 IssueNotFoundException.
     * 3. assigneeId non-null 이면 [userLookupPort.exists] 로 사용자 실재 검증.
     *    false 이면 [AssigneeNotFoundException]. null 이면 exists 호출 생략.
     * 4. 도메인 경유 — [Issue.assignTo] 또는 [Issue.unassign] 호출(불변식 일관성).
     * 5. [IssueRepository.updateAssignee] 호출 — 0 row 이면 [IssueVersionConflictException].
     * 6. [IssueAssigned] 이벤트 발행 (q_issue_events 큐) — FR-SL-02 할당 알림 파이프라인 트리거.
     *    no-op(요청값이 기존 담당자와 동일)이면 앞서 조기 반환되어 발행되지 않는다.
     * 7. 재조회 → [IssueResponse] 반환.
     *
     * @param actor 변경 행위자.
     * @param key 변경할 이슈 키.
     * @param request assigneeId(null=해제) + expectedVersion.
     * @return 변경된 이슈의 [IssueResponse].
     * @throws IssueAccessDeniedException 권한 없을 때.
     * @throws IssueNotFoundException 이슈가 없거나 소프트 삭제된 경우.
     * @throws AssigneeNotFoundException assigneeId non-null 이지만 사용자가 존재하지 않을 때.
     * @throws IssueVersionConflictException 낙관락 충돌 시.
     */
    @Suppress("ThrowsCount")
    fun changeAssignee(
        actor: ActorId,
        key: IssueKey,
        request: AppChangeAssigneeRequest,
    ): IssueResponse {
        assertPermission(actor, IssuePermission.UPDATE, IssueScope.Issue(key.value))
        projectArchiveGuard?.checkByIssue(key) // FR-PJ-04 Task 8 — 쓰기 초크포인트(D-ORDER)
        val existing = repo.findByKey(key) ?: throw IssueNotFoundException(key)

        val assigneeId = request.assigneeId

        // FR-PM-07 Task-8 — assigneeId 편집 게이트: 값이 실제로 바뀔 때만 게이트 적용.
        // no-op(기존값과 동일)이면 DB write 없이 현재 상태를 그대로 반환한다(불필요한 version bump 방지).
        // null(담당자 해제) 요청은 항상 처리한다 — 기존 담당자가 이미 null이어도 명시적 해제는 통과한다.
        val assigneeChanged = assigneeId == null || assigneeId != existing.assigneeId?.value
        if (!assigneeChanged) {
            return (repo.findByKeyWithType(key) ?: throw IssueNotFoundException(key)).withSingleDetail()
        }

        assertEditableOrForbidden(actor, key, setOf(FieldRef(FieldKind.CORE, "assigneeId")))

        val updated =
            if (assigneeId != null) {
                if (!userLookupPort.exists(assigneeId)) {
                    throw AssigneeNotFoundException(assigneeId)
                }
                existing.assignTo(ActorId(assigneeId))
            } else {
                existing.unassign()
            }

        // 영속 값은 도메인 Aggregate 산출물에서 가져온다 — assignTo/unassign 에 향후 불변식/정규화가
        // 추가돼도 repository 가 raw 입력을 독립적으로 써서 우회하지 않도록(메모리 patch-merge-도메인-우회).
        val updatedRows = repo.updateAssignee(key, updated.assigneeId?.value, request.expectedVersion)
        if (updatedRows == 0) {
            throw IssueVersionConflictException(key, existing.version)
        }
        if (assigneeId != null) {
            autoWatch(existing.id.value, listOf(assigneeId))
        }
        recordHistory(before = existing, after = updated, actor = actor, projectId = existing.projectId)
        eventPublisher.publish(IssueAssigned(issueKey = key, actorId = actor, occurredAt = Instant.now(clock)))
        log.info("issue_assignee_changed key={} assigneeId={} actor={}", key.value, assigneeId, actor.value)
        return (repo.findByKeyWithType(key) ?: throw IssueNotFoundException(key)).withSingleDetail()
    }

    /**
     * 이슈에 연결된 컴포넌트 목록을 교체한다 (FR-CM-02 + FR-CM-03 Task 5).
     *
     * 도메인 [Issue.assignComponents] 를 경유하여 distinct 정규화 후 영속한다.
     * repository 에 raw 입력을 직행시키지 않아 도메인 불변식 검증이 우회되지 않는다
     * (메모리 patch-merge-도메인-우회).
     *
     * ### 자동 담당자 배정 (FR-CM-03 Task 5)
     * 컴포넌트 교체 후 현재 담당자가 null 이면 [resolveDefaultAssignee] 로 후보를 결정한다.
     * 후보가 있으면 [IssueRepository.setAssignee] 로 영속한다.
     *
     * **이중 version bump 금지** — [replaceComponents] 가 이미 version+1 을 수행하므로
     * 담당자 자동 배정은 [IssueRepository.setAssignee](version bump·OCC 없음)를 사용한다.
     * [updateAssignee] 를 재사용하면 +2 가 되어 기존 FR-CM-02 version 단언이 회귀한다.
     *
     * @param actor 변경 행위자.
     * @param key 대상 이슈 키.
     * @param request 새 컴포넌트 UUID 목록 + expectedVersion.
     * @return 변경된 이슈의 [IssueResponse] (componentIds 채워짐).
     * @throws IssueAccessDeniedException 권한 없을 때.
     * @throws IssueNotFoundException 이슈가 없거나 소프트 삭제된 경우.
     * @throws IssueComponentNotFoundException 비활성 또는 타 프로젝트 컴포넌트 포함 시.
     * @throws IssueVersionConflictException 낙관락 충돌 시.
     */
    @Suppress("ThrowsCount")
    fun changeComponents(
        actor: ActorId,
        key: IssueKey,
        request: AppChangeComponentsRequest,
    ): IssueResponse {
        assertPermission(actor, IssuePermission.UPDATE, IssueScope.Issue(key.value))
        projectArchiveGuard?.checkByIssue(key) // FR-PJ-04 Task 8 — 쓰기 초크포인트(D-ORDER)
        val existing = repo.findByKey(key) ?: throw IssueNotFoundException(key)
        val normalized = existing.assignComponents(request.componentIds)
        validateComponents(normalized.componentIds, existing.projectId)
        val rows = repo.replaceComponents(key, existing.id.value, normalized.componentIds, request.expectedVersion)
        if (rows == 0) throw IssueVersionConflictException(key, existing.version)

        // 자동 담당자 배정 (FR-CM-03 Task 5) — assignee null 일 때만 발동.
        // replaceComponents 가 이미 version +1 했으므로 setAssignee(no-bump) 를 사용한다.
        // after 스냅샷: 자동배정된 assignee 까지 반영해야 components + assignee 두 item 이 모두 기록된다.
        var afterComponents: Issue = normalized
        if (existing.assigneeId == null) {
            val resolved = resolveDefaultAssignee(existing.projectId, normalized.componentIds, current = null)
            if (resolved != null) {
                val withAssignee = existing.assignTo(resolved)
                repo.setAssignee(existing.id.value, withAssignee.assigneeId?.value)
                autoWatch(existing.id.value, listOf(resolved.value))
                afterComponents = normalized.copy(assigneeId = resolved)
                // 자동 배정이 성사됐으면 배정 알림을 발행한다 (TODOS 「changeComponents 자동배정도
                // IssueAssigned 를 발행하지 않는다」 봉합). createIssue·cloneIssue 와 같은 단일 술어 —
                // 「배정이 실제로 일어났다 AND notifyAssignment」.
                //
                // ★이 블록 안이어야 한다. 밖으로 빼면 「기존 담당자 유지」까지 배정으로 세어
                //   컴포넌트만 바꿔도 매번 알림이 나간다. `existing.assigneeId == null` 가드가
                //   곧 「배정이 일어났다」의 정의다.
                if (request.notifyAssignment) {
                    eventPublisher.publish(
                        IssueAssigned(issueKey = key, actorId = actor, occurredAt = Instant.now(clock)),
                    )
                }
                log.info(
                    "issue_components_auto_assigned key={} assigneeId={} actor={}",
                    key.value,
                    resolved.value,
                    actor.value,
                )
            }
        }

        recordHistory(before = existing, after = afterComponents, actor = actor, projectId = existing.projectId)
        log.info(
            "issue_components_changed key={} count={} actor={}",
            key.value,
            normalized.componentIds.size,
            actor.value,
        )
        return (repo.findByKeyWithType(key) ?: throw IssueNotFoundException(key)).withSingleDetail()
    }

    /**
     * 이슈에 연결된 "영향받는 버전" 목록을 교체한다 (FR-VR-03).
     *
     * 도메인 [Issue.assignAffectsVersions] 를 경유하여 distinct 정규화 후 영속한다.
     * repository 에 raw 입력을 직행시키지 않아 도메인 불변식 검증이 우회되지 않는다
     * (메모리 patch-merge-도메인-우회).
     *
     * @param actor 변경 행위자.
     * @param key 대상 이슈 키.
     * @param request 새 버전 UUID 목록 + expectedVersion.
     * @return 변경된 이슈의 [IssueResponse].
     * @throws IssueAccessDeniedException 권한 없을 때.
     * @throws IssueNotFoundException 이슈가 없거나 소프트 삭제된 경우.
     * @throws IssueLinkedVersionNotFoundException 타 프로젝트/삭제 버전 포함 시.
     * @throws IssueVersionConflictException 낙관락 충돌 시.
     */
    @Suppress("ThrowsCount")
    fun changeAffectsVersions(
        actor: ActorId,
        key: IssueKey,
        request: AppChangeVersionsRequest,
    ): IssueResponse {
        assertPermission(actor, IssuePermission.UPDATE, IssueScope.Issue(key.value))
        projectArchiveGuard?.checkByIssue(key) // FR-PJ-04 Task 8 — 쓰기 초크포인트(D-ORDER)
        val existing = repo.findByKey(key) ?: throw IssueNotFoundException(key)
        val normalized = existing.assignAffectsVersions(request.versionIds)
        validateVersions(normalized.affectsVersionIds, existing.projectId)
        val rows =
            repo.replaceAffectsVersions(
                key,
                existing.id.value,
                normalized.affectsVersionIds,
                request.expectedVersion,
            )
        if (rows == 0) throw IssueVersionConflictException(key, existing.version)
        recordHistory(before = existing, after = normalized, actor = actor, projectId = existing.projectId)
        log.info(
            "issue_affects_versions_changed key={} count={} actor={}",
            key.value,
            normalized.affectsVersionIds.size,
            actor.value,
        )
        return (repo.findByKeyWithType(key) ?: throw IssueNotFoundException(key)).withSingleDetail()
    }

    /**
     * 이슈에 연결된 "수정 예정 버전" 목록을 교체한다 (FR-VR-03).
     *
     * [changeAffectsVersions] 와 완전 동형 — fix 버전 필드/메서드/테이블만 다르다.
     *
     * @param actor 변경 행위자.
     * @param key 대상 이슈 키.
     * @param request 새 버전 UUID 목록 + expectedVersion.
     * @return 변경된 이슈의 [IssueResponse].
     * @throws IssueAccessDeniedException 권한 없을 때.
     * @throws IssueNotFoundException 이슈가 없거나 소프트 삭제된 경우.
     * @throws IssueLinkedVersionNotFoundException 타 프로젝트/삭제 버전 포함 시.
     * @throws IssueVersionConflictException 낙관락 충돌 시.
     */
    @Suppress("ThrowsCount")
    fun changeFixVersions(
        actor: ActorId,
        key: IssueKey,
        request: AppChangeVersionsRequest,
    ): IssueResponse {
        assertPermission(actor, IssuePermission.UPDATE, IssueScope.Issue(key.value))
        projectArchiveGuard?.checkByIssue(key) // FR-PJ-04 Task 8 — 쓰기 초크포인트(D-ORDER)
        val existing = repo.findByKey(key) ?: throw IssueNotFoundException(key)
        val normalized = existing.assignFixVersions(request.versionIds)
        validateVersions(normalized.fixVersionIds, existing.projectId)
        val rows =
            repo.replaceFixVersions(
                key,
                existing.id.value,
                normalized.fixVersionIds,
                request.expectedVersion,
            )
        if (rows == 0) throw IssueVersionConflictException(key, existing.version)
        recordHistory(before = existing, after = normalized, actor = actor, projectId = existing.projectId)
        log.info(
            "issue_fix_versions_changed key={} count={} actor={}",
            key.value,
            normalized.fixVersionIds.size,
            actor.value,
        )
        return (repo.findByKeyWithType(key) ?: throw IssueNotFoundException(key)).withSingleDetail()
    }

    /**
     * 프로젝트의 활성 이슈 목록을 페이지로 조회한다.
     *
     * 단건 VIEW 와 달리 목록은 BROWSE 권한(Project 범위)을 검사하며, 미인가 시 403 을 유지한다
     * (존재 숨김 정책은 단건 경로에만 적용 — ADR 2026-06-05-issue-browse-view-permission).
     *
     * @param actor 조회 행위자.
     * @param projectKey 프로젝트 키.
     * @param pageable 페이지 정보. pageSize > 100 이면 거부.
     * @param filter 보드 카드 필터. 기본값 [BoardCardFilter.EMPTY](무필터). statusKeys/assigneeIds 등을 지정하면 SQL 수준에서 필터링된다.
     * @return [Page]<[IssueResponse]>.
     * @throws IssueAccessDeniedException BROWSE 권한 없을 때(403).
     * @throws IllegalArgumentException pageSize > 100 일 때.
     */
    @Transactional(readOnly = true)
    fun listIssues(
        actor: ActorId,
        projectKey: String,
        pageable: Pageable,
        filter: BoardCardFilter = BoardCardFilter.EMPTY,
    ): Page<IssueResponse> {
        require(pageable.pageSize <= 100) {
            "pageSize must be 100 or fewer, but was ${pageable.pageSize}"
        }
        assertPermission(actor, IssuePermission.BROWSE, IssueScope.Project(projectKey))
        // 목록당 1회 cross-BC 호출 — N+1 없음. unrestricted=true 이면 WHERE 술어 미적용(빠른경로).
        val access = securityDirectory.accessibleLevels(actor.value, projectKey)
        val page = repo.listWithType(projectKey, pageable, actor.value, access, filter)
        return maskFieldsForPage(actor, projectKey, page)
    }

    /**
     * 이슈 목록을 keyset cursor 방식으로 조회한다 (FR-API-01 Task 3).
     *
     * [listIssues] 와 동일한 BROWSE 권한·visibility 술어·필터를 재사용한다.
     * [IssueRepository.listWithTypeByCursor] 에 cursor 위치(createdAt, id) 를 분해해 전달한다.
     * [maskFieldsForPage] 를 [PageImpl] 래핑으로 재사용하여 필드 마스킹을 적용한다.
     * hasNext=true 이면 마지막 item 의 (createdAt, id) 를 [CursorCodec.encode] 로 next 토큰 생성.
     * createdAt 은 [Instant] → [java.time.OffsetDateTime](UTC) 로 변환 후 인코딩한다.
     *
     * 타임존 주의: [CursorCodec.encode] 는 [java.time.OffsetDateTime] 을 받는다.
     * [IssueResponse.createdAt] 은 [Instant] 이므로 [ZoneOffset.UTC] 로 변환해야
     * 인코딩과 Repository seek 비교가 동일한 절대 시점을 가리킨다.
     *
     * @param actor 조회 행위자.
     * @param projectKey 프로젝트 키.
     * @param cursor cursor 위치. null 이면 첫 페이지.
     * @param limit 반환 최대 건수. [MAX_CURSOR_LIMIT] 초과 시 예외(컨트롤러에서 400 변환).
     * @param filter 보드 카드 필터. 기본값 무필터.
     * @return [CursorPage] — items(필드 마스킹 적용) + next 토큰(마지막 페이지면 null).
     * @throws IssueAccessDeniedException BROWSE 권한 없을 때(403).
     * @throws IllegalArgumentException limit 이 [MAX_CURSOR_LIMIT] 초과 시(컨트롤러 400 변환).
     */
    @Transactional(readOnly = true)
    fun listIssuesByCursor(
        actor: ActorId,
        projectKey: String,
        cursor: CursorPosition?,
        limit: Int,
        filter: BoardCardFilter = BoardCardFilter.EMPTY,
    ): CursorPage<IssueResponse> {
        require(limit <= MAX_CURSOR_LIMIT) {
            "limit must be $MAX_CURSOR_LIMIT or fewer, but was $limit"
        }
        assertPermission(actor, IssuePermission.BROWSE, IssueScope.Project(projectKey))
        val access = securityDirectory.accessibleLevels(actor.value, projectKey)
        val result =
            repo.listWithTypeByCursor(
                projectKey = projectKey,
                seekCreatedAt = cursor?.createdAt,
                seekId = cursor?.id,
                limit = limit,
                actor = actor.value,
                access = access,
                filter = filter,
            )
        // maskFieldsForPage 재사용 — PageImpl 래핑으로 Page<IssueResponse> 전달 후 content 추출
        val tempPage =
            PageImpl(
                result.items,
                Pageable.unpaged(),
                result.items.size.toLong(),
            )
        val maskedItems = maskFieldsForPage(actor, projectKey, tempPage).content
        val next =
            if (result.hasNext && maskedItems.isNotEmpty()) {
                val last = maskedItems.last()
                val lastCreatedAt =
                    last.createdAt ?: error("목록 이슈의 createdAt 이 null 일 수 없습니다")
                CursorCodec.encode(lastCreatedAt.atOffset(ZoneOffset.UTC), last.id)
            } else {
                null
            }
        return CursorPage(items = maskedItems, next = next)
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
     * @param issueKey 전환 대상 이슈 키 — 예외 컨텍스트용.
     * @param fromStatus 전환 전 상태 키.
     * @param toStatus 전환 목표 상태 키.
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
     * @param mergedCustomFields 병합 완료된 커스텀 필드 최종 맵. null=무변경.
     * @return 변경된 필드 이름 집합. 비어있으면 no-op.
     */
    @Suppress("CyclomaticComplexMethod")
    private fun buildChangedFields(
        existing: Issue,
        request: UpdateIssueRequest,
        normalizedLabels: List<String>?,
        mergedCustomFields: Map<String, Any?>? = null,
    ): Set<String> {
        val fields = mutableSetOf<String>()
        if (request.summary != null && existing.summary != request.summary) fields.add("summary")
        if (request.typeId != null && existing.typeId != request.typeId) fields.add("typeId")

        // description: null=무변경, ""=클리어(기존 non-null 이면 변경), 값=설정(기존과 다를 때)
        // ★에디터 경로(descriptionHtml)도 같은 "description" 필드명으로 보고한다 — 변경 이력·
        //   알림·멘션 발행이 전부 이 이름에 걸려 있고, 사용자에게는 둘 다 「본문 변경」이다.
        //   HTML 경로에서 기존값과의 비교 기준이 없어(옛 행은 마크다운뿐) 항상 변경으로 본다.
        if (isTextFieldChanged(existing.description, request.description) ||
            request.descriptionHtml != null
        ) {
            fields.add("description")
        }

        // environment: null=무변경, ""=클리어(기존 non-null 이면 변경), 값=설정(기존과 다를 때)
        if (isTextFieldChanged(existing.environment, request.environment)) fields.add("environment")

        // labels: null=무변경, []=전체 제거(기존 비어있지 않으면 변경), 값=교체(기존 정규화값과 다를 때)
        // normalizedLabels 는 도메인 정규화(dedup/trim) 후의 최종값과 비교한다.
        if (normalizedLabels != null && existing.labels != normalizedLabels) fields.add("labels")

        // priority: null=무변경, 값=변경(기존과 다를 때)
        if (request.priority != null && existing.priority != request.priority) fields.add("priority")

        // impact: null=무변경, 값=변경(기존과 다를 때)
        if (request.impact != null && existing.impact != request.impact) fields.add("impact")

        // customFields: null=무변경, 병합맵=기존과 다를 때 변경
        if (mergedCustomFields != null && existing.customFields != mergedCustomFields) fields.add("customFields")

        // 날짜 필드 (FR-PL-01): DatePatch 3-state 감지
        if (isDatePatchChanged(existing.startDate, request.startDate)) fields.add("startDate")
        if (isDatePatchChanged(existing.dueDate, request.dueDate)) fields.add("dueDate")
        if (isDatePatchChanged(existing.targetDate, request.targetDate)) fields.add("targetDate")

        // 추정 필드 (FR-TT-01): EstimatePatch 3-state 감지
        if (isEstimatePatchChanged(existing.originalEstimateSeconds, request.originalEstimate)) {
            fields.add("originalEstimateSeconds")
        }
        if (isEstimatePatchChanged(existing.remainingEstimateSeconds, request.remainingEstimate)) {
            fields.add("remainingEstimateSeconds")
        }

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

    /**
     * 날짜 필드([DatePatch])의 3-state 변경 여부를 판정한다 (FR-PL-01).
     *
     * - [DatePatch.Unchanged] → 무변경 → false
     * - [DatePatch.Clear] → 기존값이 non-null 이면 true (기존 null 이면 no-op)
     * - [DatePatch.Set] → 기존값과 다르면 true
     */
    private fun isDatePatchChanged(
        existingValue: LocalDate?,
        patch: DatePatch,
    ): Boolean =
        when (patch) {
            is DatePatch.Unchanged -> false
            is DatePatch.Clear -> existingValue != null
            is DatePatch.Set -> existingValue != patch.value
        }

    /**
     * 추정 시간 필드([EstimatePatch])의 3-state 변경 여부를 판정한다 (FR-TT-01).
     *
     * - [EstimatePatch.Unchanged] → 무변경 → false
     * - [EstimatePatch.Clear] → 기존값이 non-null 이면 true (기존 null 이면 no-op)
     * - [EstimatePatch.Set] → 기존값과 다르면 true
     */
    private fun isEstimatePatchChanged(
        existingValue: Int?,
        patch: EstimatePatch,
    ): Boolean =
        when (patch) {
            is EstimatePatch.Unchanged -> false
            is EstimatePatch.Clear -> existingValue != null
            is EstimatePatch.Set -> existingValue != patch.value
        }

    /**
     * description 변경 시 새로 추가된 멘션을 해석해 [IssueMentioned] 발행 + 자동 watcher 등록 (FR-MN-01 · FR-MN-03).
     *
     * diff 기반 처리.
     * - 기존 description 에 이미 있던 멘션은 신규가 아니므로 제외한다.
     * - 자기 멘션(actor == 대상 userId) 은 제외한다. 알림 발행 시 자기 자신에게 알림을 보내지 않아야 하기 때문이다.
     * - 미존재 username(findIdsByUsernames 에서 드롭된 username) 은 자동 제외된다.
     * - 상한 초과 절단(알파벳 오름차순)·자기제외·정렬은 [MentionTargetResolver] 가 담당한다 —
     *   FR-MN-03 이 같은 규칙을 네 경로(이슈 생성·수정 · 댓글 작성·수정)에서 쓰게 되면서 뽑았다.
     *   드롭 수 WARN 로그는 [publishAndWatchMentions] 가 남긴다(순수 함수에 로거를 두지 않는다).
     * - 남은 대상이 없으면 이벤트를 발행하지 않는다.
     * - mentionedUserIds 는 UUID 오름차순 정렬로 결정적 직렬화를 보장한다 (IssueMentioned KDoc N3).
     *
     * 이 메서드는 호출자(updateIssue)의 클래스 레벨 @Transactional 트랜잭션 안에서 실행되므로
     * [IssueEventPublisher](Propagation.MANDATORY) 가 정상 동작한다.
     *
     * @param key 멘션이 발생한 이슈 키.
     * @param existing 변경 전 이슈 (description 이전 값 참조용).
     * @param request 수정 요청 DTO (description 새 값 참조용).
     * @param actor 멘션을 작성한 행위자.
     */
    private fun publishMentions(
        key: IssueKey,
        existing: Issue,
        request: UpdateIssueRequest,
        actor: ActorId,
    ) {
        val resolvedTargets =
            MentionTargetResolver.resolve(
                before = existing.description,
                after = request.description,
                actor = actor.value,
                userLookupPort = userLookupPort,
            )
        publishAndWatchMentions(
            key = key,
            issueId = existing.id.value,
            actor = actor,
            resolved = resolvedTargets,
            sourceField = MentionSource.DESCRIPTION,
            commentId = null,
        )
    }

    /**
     * 산출된 멘션 대상에게 이벤트를 발행하고 **같은 목록**을 watcher 로 등록한다 (FR-MN-03).
     *
     * ## 왜 한 함수인가 — E5 방어
     * 발행 대상과 watcher 대상이 갈리면 「알림은 왔는데 watcher 가 아니다」가 조용히 생긴다.
     * 두 목록이 서로를 검사하지 않으므로 테스트로만 막으면 새어나간다. 같은 `resolved.targets`
     * 를 두 곳에 넘기는 **한 곳**을 만들어 갈릴 자리 자체를 없앤다.
     *
     * 캡 절단 로그는 여기서 남긴다 — [MentionTargetResolver] 는 순수 함수라 로거를 갖지 않는다.
     */
    private fun publishAndWatchMentions(
        key: IssueKey,
        issueId: UUID,
        actor: ActorId,
        resolved: MentionTargets,
        sourceField: String,
        commentId: UUID?,
    ) {
        if (resolved.droppedByCap > 0) {
            log.warn(
                "mention_cap_exceeded key={} cap={} dropped={}",
                key.value,
                MentionTargetResolver.MAX_MENTIONS_PER_EVENT,
                resolved.droppedByCap,
            )
        }
        if (resolved.targets.isEmpty()) return

        eventPublisher.publish(
            IssueMentioned(
                issueKey = key,
                projectKey = key.projectPrefix,
                mentionedUserIds = resolved.targets,
                actorId = actor,
                sourceField = sourceField,
                occurredAt = Instant.now(clock),
                commentId = commentId,
            ),
        )
        autoWatch(issueId, resolved.targets)
    }

    /**
     * [TransitionPlan.emitEvents] 를 [TransitionEventPublisher] 로 발행한다.
     *
     * [transitionEventPublisher] 가 null 이면 발행 없이 조용히 종료한다(기존 단위 테스트 호환 fallback).
     * [transitionEventPublisher] 가 주입된 경우 각 [com.bts.shared.workflow.DomainEvent] 를 순서대로 발행한다.
     * 호출 시점은 [transitionIssue] 의 클래스 레벨 @Transactional(REQUIRED) 트랜잭션 안이므로
     * 상태 변경([IssueRepository.applyTransition])과 enqueue 가 원자적으로 커밋된다 (outbox 정합, DATA.md §7.2).
     *
     * @param plan [WorkflowTransitionPort.plan] 이 반환한 전환 실행 계획.
     */
    private fun publishTransitionEvents(plan: TransitionPlan) {
        transitionEventPublisher?.let { publisher ->
            plan.emitEvents.forEach { publisher.publish(it) }
        }
    }

    companion object {
        /** 템플릿 date 토큰 포맷 — ISO-8601 날짜(yyyy-MM-dd). */
        private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    }

    /**
     * 커스텀 필드 값을 검증한다 (FR-IS-10).
     *
     * 프로젝트의 활성 정의 목록을 기준으로 [CustomFieldValueValidator] 를 호출한다.
     * 정의 로드는 호출자가 미리 수행하여 불필요한 DB 조회를 줄인다.
     * [customFieldDefinitionRepository] 가 null 이면 검증을 수행하지 않는다(기존 테스트 backward-compat).
     *
     * @param definitions 프로젝트의 활성 필드 정의 목록.
     * @param values 저장할 커스텀 필드 값 맵. 빈 맵이면 required 없는 경우에만 통과한다.
     * @throws [com.bts.issue.customfield.domain.CustomFieldValidationException] 위반 시.
     */
    private fun validateCustomFields(
        definitions: List<com.bts.issue.customfield.domain.CustomFieldDefinition>,
        values: Map<String, Any?>,
    ) {
        CustomFieldValueValidator().validate(definitions, values)
    }

    /**
     * PATCH 커스텀 필드를 기존 값과 병합하고 최종 상태를 검증한다 (FR-IS-10, E11).
     *
     * - request.customFields=null → 무변경 → null 반환 (no-op 신호).
     * - request.customFields=맵 → 기존 맵에서 각 키를 병합.
     *   - 키 값이 non-null → 갱신.
     *   - 키 값이 null → 해당 키 제거.
     * - 병합 후 최종 맵에 대해 [validateCustomFields] 를 호출하여 required 검증 수행.
     * - [customFieldDefinitionRepository] 가 null 이면 검증 없이 병합만 수행(기존 테스트 backward-compat).
     *
     * @param existing 수정 전 이슈 Aggregate.
     * @param request 수정 요청 DTO.
     * @return 병합된 최종 맵. request.customFields=null 이면 null(무변경).
     * @throws [com.bts.issue.customfield.domain.CustomFieldValidationException] required 위반 시.
     */
    private fun mergeCustomFieldsAndValidate(
        existing: Issue,
        request: UpdateIssueRequest,
    ): Map<String, Any?>? {
        val patch = request.customFields ?: return null

        // 기존 값 복사 후 키 단위 병합 — null 값 키는 제거(E11)
        val merged = existing.customFields.toMutableMap()
        for ((fieldKey, value) in patch) {
            if (value == null) {
                merged.remove(fieldKey)
            } else {
                merged[fieldKey] = value
            }
        }

        // 병합 후 최종 상태 기준 required 검증 (patch-merge-domain-bypass 방지)
        val definitionRepo = customFieldDefinitionRepository
        if (definitionRepo != null) {
            val definitions = definitionRepo.findActiveByProject(existing.projectId)
            validateCustomFields(definitions, merged)
        }

        return merged.toMap()
    }

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
     * 보안 등급 지정 가능 여부를 검증한다 (FR-PM-06).
     *
     * 1. [IssuePermission.SET_SECURITY] 권한을 [scope] 범위로 검증 — 미보유 시 403.
     * 2. [IssueSecurityDirectory.levelBelongsToProjectScheme] 로 등급이 프로젝트 적용 스킴 소속인지 검증 —
     *    미소속 시 [IssueSecurityLevelNotInSchemeException] (422).
     *
     * 해제(Clear)는 등급 값이 없으므로 스킴 소속 검증 대상이 아니며, 호출자가 권한만 검증한다.
     *
     * @param actor 행위자.
     * @param scope 권한 평가 범위. 생성은 Project, 수정은 Issue.
     * @param projectKey 등급이 속해야 하는 프로젝트 키.
     * @param levelId 지정하려는 보안 등급 UUID.
     * @throws IssueAccessDeniedException SET_SECURITY 권한 미보유 시 (403).
     * @throws IssueSecurityLevelNotInSchemeException 등급이 적용 스킴 미소속일 때 (422).
     */
    private fun assertSecurityLevelAssignable(
        actor: ActorId,
        scope: IssueScope,
        projectKey: String,
        levelId: UUID,
    ) {
        assertPermission(actor, IssuePermission.SET_SECURITY, scope)
        if (!securityDirectory.levelBelongsToProjectScheme(levelId, projectKey)) {
            throw IssueSecurityLevelNotInSchemeException(levelId)
        }
    }

    /**
     * 보안 등급 PATCH 의도에 따라 권한을 사전 검증한다 (fail-fast, FR-PM-06).
     *
     * - [SecurityLevelPatch.Unchanged] — 검증 불필요.
     * - [SecurityLevelPatch.Clear] — SET_SECURITY 권한 검증만 수행.
     * - [SecurityLevelPatch.Assign] — SET_SECURITY 권한 + 스킴 소속 검증.
     *
     * @param actor 행위자.
     * @param key 대상 이슈 키.
     * @param patch 보안 등급 수정 의도.
     */
    private fun assertSecurityLevelPatch(
        actor: ActorId,
        key: IssueKey,
        patch: SecurityLevelPatch,
    ) {
        when (patch) {
            is SecurityLevelPatch.Assign -> {
                assertSecurityLevelAssignable(
                    actor = actor,
                    scope = IssueScope.Issue(key.value),
                    projectKey = key.projectPrefix,
                    levelId = patch.levelId,
                )
            }
            is SecurityLevelPatch.Clear ->
                assertPermission(actor, IssuePermission.SET_SECURITY, IssueScope.Issue(key.value))
            is SecurityLevelPatch.Unchanged -> Unit
        }
    }

    /**
     * 보안 등급 3-state 패치를 도메인 경유로 적용하고 적용 후 OCC version 을 반환한다 (FR-PM-06).
     *
     * - [SecurityLevelPatch.Unchanged] — 무변경. [expectedVersion] 을 그대로 반환한다.
     * - [SecurityLevelPatch.Clear] — 등급 해제(공개 복귀). [Issue.assignSecurityLevel](null) 경유.
     * - [SecurityLevelPatch.Assign] — 등급 지정. [Issue.assignSecurityLevel](levelId) 경유.
     *
     * 권한·스킴 검증은 호출 전 [updateIssue] 가 fail-fast 로 수행한다.
     * 영속 값은 도메인 [Issue.assignSecurityLevel] 산출물에서 가져와 repository 직행 우회를 차단한다
     * (patch-merge-domain-bypass 방지).
     *
     * @param existing 변경 전 이슈.
     * @param patch 보안 등급 수정 의도.
     * @param expectedVersion OCC 기준 버전.
     * @return 적용 후 버전. 변경이 일어났으면 [expectedVersion]+1, 무변경이면 [expectedVersion].
     * @throws IssueVersionConflictException 낙관락 충돌 시.
     */
    private fun applySecurityLevel(
        existing: Issue,
        patch: SecurityLevelPatch,
        expectedVersion: Long,
    ): Long {
        val targetLevelId =
            when (patch) {
                is SecurityLevelPatch.Unchanged -> return expectedVersion
                is SecurityLevelPatch.Clear -> null
                is SecurityLevelPatch.Assign -> patch.levelId
            }
        // 도메인 경유 — assignSecurityLevel 에 향후 불변식이 추가돼도 repository 가 우회하지 않도록 한다.
        val mutated = existing.assignSecurityLevel(targetLevelId)
        val rows = repo.updateSecurityLevel(existing.key, mutated.securityLevelId, expectedVersion)
        if (rows == 0) {
            throw IssueVersionConflictException(existing.key, existing.version)
        }
        log.info("issue_security_level_changed key={} levelId={}", existing.key.value, targetLevelId)
        return expectedVersion + 1
    }

    /**
     * 단건 이슈의 VIEW 권한을 검사하되, 미인가 시 존재 자체를 숨긴다 (404).
     *
     * 단건 경로(findByKey / availableTransitions / cloneIssue 소스)에서 VIEW 권한이 없을 때
     * 403(IssueAccessDeniedException) 대신 404(IssueNotFoundException)를 던진다. 이렇게 하면
     * 권한 없는 행위자가 403/404 응답 차이로 이슈의 존재 여부를 추론하는 것을 막는다(존재 숨김 정책).
     *
     * 목록 경로(listIssues)는 이 정책을 적용하지 않고 BROWSE 권한 미인가 시 403을 유지한다.
     *
     * ADR 2026-06-05-issue-browse-view-permission D2 (단건 VIEW 미인가 → 404 숨김), spec FR4 참조.
     *
     * @param actor 조회 행위자.
     * @param key 검사 대상 이슈 키.
     * @throws IssueNotFoundException VIEW 권한이 없을 때 (미존재와 동일 응답, 거부 사유 미포함).
     */
    private fun assertViewIssueOrNotFound(
        actor: ActorId,
        key: IssueKey,
    ) {
        if (!permissionResolver.hasPermission(actor.value, IssuePermission.VIEW, IssueScope.Issue(key.value))) {
            throw IssueNotFoundException(key)
        }
    }

    /**
     * 컴포넌트 UUID 목록이 모두 프로젝트 내 활성 컴포넌트인지 검증한다.
     *
     * 하나라도 null(비활성 또는 타 프로젝트) 이면 [IssueComponentNotFoundException] 을 던진다.
     *
     * @param componentIds 검증할 컴포넌트 UUID 목록 (distinct 정규화 완료 상태).
     * @param projectId 소속 프로젝트 UUID.
     * @throws IssueComponentNotFoundException 비활성 또는 타 프로젝트 컴포넌트가 포함된 경우.
     */
    private fun validateComponents(
        componentIds: List<UUID>,
        projectId: UUID,
    ) {
        componentIds.forEach { id ->
            componentRepository.findById(id, projectId) ?: throw IssueComponentNotFoundException(id)
        }
    }

    /**
     * 버전 UUID 목록이 모두 프로젝트 내 활성(deleted_at IS NULL) 버전인지 검증한다.
     *
     * 하나라도 null(타 프로젝트 또는 소프트 삭제) 이면 [IssueLinkedVersionNotFoundException] 을 던진다.
     * ARCHIVED 상태 버전은 deleted_at=null 이므로 이 검증을 통과한다.
     *
     * @param versionIds 검증할 버전 UUID 목록 (distinct 정규화 완료 상태).
     * @param projectId 소속 프로젝트 UUID.
     * @throws IssueLinkedVersionNotFoundException 타 프로젝트/삭제 버전이 포함된 경우.
     */
    private fun validateVersions(
        versionIds: List<UUID>,
        projectId: UUID,
    ) {
        versionIds.forEach { id ->
            versionRepository.findById(id, projectId) ?: throw IssueLinkedVersionNotFoundException(id)
        }
    }

    /**
     * 컴포넌트 후보에서 이슈 기본 담당자를 결정한다.
     *
     * 프로젝트의 활성 컴포넌트 중 [componentIds] 에 속하고 leadUserId 가 non-null 인 항목을
     * [DefaultAssigneeResolver.ComponentLead] 목록으로 구성한 뒤 [DefaultAssigneeResolver.resolve] 를 호출한다.
     *
     * [current] 가 non-null 이면 후보를 무시하고 [current] 를 그대로 반환한다 (덮어쓰기 금지).
     *
     * 담당자 결정 우선순위.
     * 1. [current] non-null → 그대로 반환 (덮어쓰기 금지).
     * 2. 컴포넌트 리드 → [componentIds] 에 속하고 leadUserId non-null 인 컴포넌트 중 이름 오름차순 첫 번째.
     * 3. 프로젝트 리드 → [ProjectLeadRepository.findLeadUserId] 조회 결과.
     * 4. 모두 없으면 null.
     *
     * [componentIds] 가 비어 있어도 프로젝트 리드 폴백을 시도한다.
     *
     * Task 5 (changeComponents) 에서도 동일 로직을 재사용한다.
     *
     * @param projectId 소속 프로젝트 UUID.
     * @param componentIds 이슈에 연결할 컴포넌트 UUID 목록 (distinct 정규화 완료 상태).
     * @param current 현재 이슈 담당자. 생성 경로에서는 null.
     * @return 결정된 담당자 [ActorId]. 없으면 null.
     */
    @Suppress("ReturnCount") // current 조기 반환 + 정상 반환 — guard clause 패턴
    private fun resolveDefaultAssignee(
        projectId: UUID,
        componentIds: List<UUID>,
        current: ActorId?,
    ): ActorId? {
        if (current != null) return current
        val componentIdSet = componentIds.toSet()
        val candidates =
            componentRepository.findByProject(projectId)
                .filter { c -> c.id != null && c.id in componentIdSet && c.leadUserId != null }
                .map { c ->
                    val cid = c.id ?: error("component.id must not be null after DB read")
                    ComponentLead(id = cid, name = c.name, leadUserId = c.leadUserId)
                }
        val projectLead = projectLeadRepository.findLeadUserId(projectId)
        return DefaultAssigneeResolver.resolve(current = null, candidates = candidates, projectLeadUserId = projectLead)
    }

    /**
     * 생성 시 담당자 지정 의도([AssigneeIntent]) 를 최종 담당자로 해석한다 (FR-UX-09 B1, ADR D-2).
     *
     * - [AssigneeIntent.Auto] — 기존 동작. [resolveDefaultAssignee] 자동 배정을 수행한다.
     * - [AssigneeIntent.None] — 자동 배정을 **수행하지 않고** 미할당으로 확정한다.
     *   `null` 을 반환하는 것과 「자동 배정을 돌렸는데 결과가 null」 은 관측상 같아 보이지만,
     *   전자는 컴포넌트/프로젝트 리드 조회 자체를 하지 않는다.
     * - [AssigneeIntent.User] — 자동 배정을 수행하지 않고 지정된 사용자를 담당자로 한다.
     *   **존재 검증을 수행한다** ([changeAssignee] 와 대칭). 자동 배정 결과는 컴포넌트/프로젝트 리드에서
     *   나와 이미 유효 사용자이므로 [AssigneeIntent.Auto] 경로에서는 조회하지 않는다.
     *
     * @param intent 담당자 지정 의도.
     * @param projectId 이슈가 속한 프로젝트 UUID.
     * @param componentIds 정규화된 컴포넌트 UUID 목록. 자동 배정 후보 산출에 쓰인다.
     * @return 최종 담당자. 미할당이면 null.
     * @throws AssigneeNotFoundException [AssigneeIntent.User] 의 사용자가 존재하지 않을 때 (422).
     */
    private fun resolveCreateAssignee(
        intent: AssigneeIntent,
        projectId: UUID,
        componentIds: List<UUID>,
    ): ActorId? =
        when (intent) {
            is AssigneeIntent.Auto -> resolveDefaultAssignee(projectId, componentIds, current = null)
            is AssigneeIntent.None -> null
            is AssigneeIntent.User -> {
                if (!userLookupPort.exists(intent.userId)) {
                    throw AssigneeNotFoundException(intent.userId)
                }
                ActorId(intent.userId)
            }
        }

    /**
     * 단건 조회 응답에 필드 수준 마스킹(열람) + 편집 불가 필드 표기를 적용한다 (FR-PM-07 Task-7 + Task-1).
     *
     * projectKey 로 projectId 를 조회한 뒤 [buildCandidates] 를 구성하고
     * [FieldPermissionResolver.visibleFields] 와 [FieldPermissionResolver.editableFields] 를 각 1회 호출한다.
     * projectId 를 찾지 못하면 원본 응답을 그대로 반환한다(방어적 처리).
     *
     * @param actor 조회 행위자.
     * @param projectKey 이슈가 속한 프로젝트 키.
     * @param response 마스킹 전 응답.
     * @return 마스킹 및 noneditableFields 가 적용된 응답.
     */
    private fun maskFieldsForSingle(
        actor: ActorId,
        projectKey: String,
        response: IssueResponse,
    ): IssueResponse {
        val projectId = repo.findProjectIdByKey(projectKey) ?: return response
        val candidates = buildCandidates(response)
        val visible = fieldPermissionResolver.visibleFields(actor.value, projectId, candidates)
        val editable = fieldPermissionResolver.editableFields(actor.value, projectId, visible)
        return response.maskInvisible(visible, editable)
    }

    /**
     * 목록 조회 응답 Page 전체에 필드 수준 마스킹(열람) + 편집 불가 필드 표기를 적용한다
     * (FR-PM-07 Task-7 + Task-1, EC14).
     *
     * 같은 프로젝트의 이슈 목록이므로 [FieldPermissionResolver.visibleFields] 와
     * [FieldPermissionResolver.editableFields] 를 페이지당 각 1회만 호출한다(N+1 회피).
     * candidates 는 페이지 내 모든 이슈의 커스텀 필드 키를 합집합으로 구성한다.
     * 빈 페이지이거나 projectId 를 찾지 못하면 원본 페이지를 그대로 반환한다(방어적 처리).
     *
     * @param actor 조회 행위자.
     * @param projectKey 목록이 속한 프로젝트 키.
     * @param page 마스킹 전 Page.
     * @return 마스킹 및 noneditableFields 가 적용된 Page.
     */
    @Suppress("ReturnCount") // empty guard + projectId miss guard 조기 반환 패턴 — 의도적 설계
    private fun maskFieldsForPage(
        actor: ActorId,
        projectKey: String,
        page: org.springframework.data.domain.Page<IssueResponse>,
    ): org.springframework.data.domain.Page<IssueResponse> {
        if (page.isEmpty) return page
        val projectId = repo.findProjectIdByKey(projectKey) ?: return page
        // 페이지 내 커스텀 필드 키 합집합 + 코어 마스킹 대상 후보 — 1회 호출로 배치 처리(EC14)
        val candidates =
            page.content
                .fold(buildCoreCandidates()) { acc, r ->
                    acc + r.customFields.keys.map { FieldRef(FieldKind.CUSTOM, it) }
                }.toSet()
        val visible = fieldPermissionResolver.visibleFields(actor.value, projectId, candidates)
        val editable = fieldPermissionResolver.editableFields(actor.value, projectId, visible)
        val maskedContent = page.content.map { it.maskInvisible(visible, editable) }
        return org.springframework.data.domain.PageImpl(maskedContent, page.pageable, page.totalElements)
    }

    /**
     * 단건 응답의 마스킹 candidate 집합을 구성한다.
     *
     * 코어 마스킹 대상 7종 + 이슈의 커스텀 필드 키 전체를 포함한다.
     *
     * @param response 대상 이슈 응답.
     * @return [FieldRef] candidate 집합.
     */
    private fun buildCandidates(response: IssueResponse): Set<FieldRef> =
        buildCoreCandidates() + response.customFields.keys.map { FieldRef(FieldKind.CUSTOM, it) }

    /**
     * 마스킹 대상 코어 필드 7종의 [FieldRef] 집합을 반환한다.
     *
     * summary·priority 는 non-null CORE 로 마스킹 대상이 아니므로 제외된다.
     */
    private fun buildCoreCandidates(): Set<FieldRef> =
        setOf(
            FieldRef(FieldKind.CORE, "description"),
            FieldRef(FieldKind.CORE, "environment"),
            FieldRef(FieldKind.CORE, "impact"),
            FieldRef(FieldKind.CORE, "assigneeId"),
            FieldRef(FieldKind.CORE, "labels"),
            FieldRef(FieldKind.CORE, "summary"),
            FieldRef(FieldKind.CORE, "priority"),
        )

    /**
     * updateIssue 요청에서 실제로 값이 변경되는 코어 필드의 [FieldRef] 집합을 반환한다 (FR-PM-07 Task-8).
     *
     * [buildChangedFields] 와 동일한 3-상태 sentinel 규칙을 따른다.
     * - summary/typeId: null=무변경, 기존값과 다른 경우만 포함.
     * - description/environment: null=무변경, ""=클리어(기존 non-null 이면 변경), 값=변경(기존과 다를 때).
     * - labels: null=무변경, []=전체 제거(기존 비어있지 않으면 변경), 값=교체(기존과 다를 때).
     * - priority/impact: null=무변경, 기존값과 다른 경우만 포함.
     *
     * 커스텀 필드는 [buildCustomChangedFieldRefs] 에서 별도로 계산한다.
     *
     * @param normalizedLabels [Issue.normalizeLabels] 를 거친 정규화 값. null=무변경.
     * @return 변경된 코어 필드의 [FieldRef] 집합.
     */
    @Suppress("CyclomaticComplexity")
    private fun buildCoreChangedFieldRefs(
        existing: Issue,
        request: UpdateIssueRequest,
        normalizedLabels: List<String>?,
    ): Set<FieldRef> {
        val refs = mutableSetOf<FieldRef>()
        if (request.summary != null && existing.summary != request.summary) {
            refs.add(FieldRef(FieldKind.CORE, "summary"))
        }
        if (request.typeId != null && existing.typeId != request.typeId) {
            refs.add(FieldRef(FieldKind.CORE, "typeId"))
        }
        if (isTextFieldChanged(existing.description, request.description)) {
            refs.add(FieldRef(FieldKind.CORE, "description"))
        }
        if (isTextFieldChanged(existing.environment, request.environment)) {
            refs.add(FieldRef(FieldKind.CORE, "environment"))
        }
        if (normalizedLabels != null && existing.labels != normalizedLabels) {
            refs.add(FieldRef(FieldKind.CORE, "labels"))
        }
        if (request.priority != null && existing.priority != request.priority) {
            refs.add(FieldRef(FieldKind.CORE, "priority"))
        }
        if (request.impact != null && existing.impact != request.impact) {
            refs.add(FieldRef(FieldKind.CORE, "impact"))
        }
        return refs
    }

    /**
     * updateIssue 요청에서 실제로 값이 변경되는 커스텀 필드의 [FieldRef] 집합을 반환한다 (FR-PM-07 Task-8).
     *
     * request.customFields=null 이면 무변경이므로 빈 집합을 반환한다.
     * 패치 맵의 각 키에 대해 기존값과 비교하여 달라지는 것만 포함한다.
     * (null 값 키 = 제거 의도이며, 기존에 해당 키가 있으면 변경으로 간주한다.)
     *
     * @return 변경된 커스텀 필드의 [FieldRef] 집합.
     */
    private fun buildCustomChangedFieldRefs(
        existing: Issue,
        request: UpdateIssueRequest,
    ): Set<FieldRef> {
        val patch = request.customFields ?: return emptySet()
        return patch.entries
            .filter { (fieldKey, newValue) -> existing.customFields[fieldKey] != newValue }
            .map { (fieldKey, _) -> FieldRef(FieldKind.CUSTOM, fieldKey) }
            .toSet()
    }

    /**
     * [changedCandidates] 중 actor 가 편집 불가한 필드가 있으면 403([ResponseStatusException])을 던진다
     * (FR-PM-07 Task-8).
     *
     * [changedCandidates] 가 비어있으면 editableFields 를 호출하지 않는다(no-op 최적화).
     * 프로젝트 ID 를 찾지 못하면 게이트를 스킵한다(방어적 처리).
     *
     * @param actor 편집 행위자.
     * @param key 편집 대상 이슈 키 (projectPrefix 추출용).
     * @param changedCandidates 실제로 변경되는 필드의 [FieldRef] 집합.
     * @throws org.springframework.web.server.ResponseStatusException (403) editable 에 없는 변경이 있을 때.
     */
    @Suppress("ReturnCount")
    private fun assertEditableOrForbidden(
        actor: ActorId,
        key: IssueKey,
        changedCandidates: Set<FieldRef>,
    ) {
        if (changedCandidates.isEmpty()) return
        val projectId = repo.findProjectIdByKey(key.projectPrefix) ?: return
        val editable = fieldPermissionResolver.editableFields(actor.value, projectId, changedCandidates)
        val blocked = changedCandidates - editable
        if (blocked.isNotEmpty()) {
            log.warn(
                "issue_edit_gate_denied key={} actor={} blockedFields={}",
                key.value,
                actor.value,
                blocked,
            )
            throw org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.FORBIDDEN,
                "편집 권한이 없는 필드가 포함되어 있습니다.",
            )
        }
    }

    /**
     * 코어 필드 변경이 없는 경우(changedFields.isEmpty())의 두 분기를 처리한다.
     *
     * - 보안 등급 단독 변경: updateFields/이벤트는 스킵, 이력만 기록(감사 누락 방지).
     * - 완전 noop: 로그만 남기고 현재 상태 반환.
     *
     * 호출자(updateIssue)의 @Transactional 안에서 실행되므로 별도 트랜잭션 어노테이션 불필요.
     */
    private fun handleCoreFieldsUnchanged(
        key: IssueKey,
        existing: Issue,
        actor: ActorId,
        securityChanged: Boolean,
    ): IssueResponse {
        if (securityChanged) {
            // 코어 필드 무변경 + 보안 등급 단독 변경 —
            // updateFields/이벤트는 스킵하되 이력은 기록(감사 누락 방지).
            val afterSecurityOnly = repo.findByKey(key) ?: throw IssueNotFoundException(key)
            recordHistory(
                before = existing,
                after = afterSecurityOnly,
                actor = actor,
                projectId = existing.projectId,
            )
            log.info("issue_security_level_only_updated key={} actor={}", key.value, actor.value)
        } else {
            log.info(
                "issue_update_noop key={} actor={} securityChanged={}",
                key.value,
                actor.value,
                securityChanged,
            )
        }
        return (repo.findByKeyWithType(key) ?: throw IssueNotFoundException(key)).withSingleDetail()
    }

    /**
     * 이슈 변경 이력을 기록하는 private 헬퍼.
     *
     * [IssueHistoryRecorder.record] 에 위임한다.
     * [projectId] 가 null 이면 기록하지 않는다 (이슈 미조회 경로에서의 방어).
     *
     * **self-invocation 금지** — 이 메서드는 같은 클래스 안에 있으므로 @Transactional 이 동작하지 않는다.
     * 트랜잭션은 historyRecorder(별도 @Service 빈)가 제공한다 (메모리 트랜잭션-self-invocation-REQUIRES_NEW).
     */
    private fun recordHistory(
        before: Issue?,
        after: Issue?,
        actor: ActorId?,
        projectId: UUID?,
    ) {
        if (projectId == null) {
            log.warn("history_record_skipped: projectId null (issueKey={})", before?.key?.value ?: after?.key?.value)
            return
        }
        historyRecorder.record(before = before, after = after, actor = actor, projectId = projectId)
    }

    /**
     * updateIssue 내 타입/우선순위/라벨/편집게이트/커스텀필드 검증·정규화 결과를 담는 내부 타입.
     * updateIssue 메서드 길이를 LongMethod 임계치(60줄) 이하로 유지하기 위해 분리.
     */
    private data class UpdateValidated(
        val normalizedLabels: List<String>?,
        val mergedCustomFields: Map<String, Any?>?,
    )

    /**
     * updateIssue 에서 타입 검증, 우선순위/영향도 범위 검증, 라벨 정규화,
     * 편집 게이트(FR-PM-07 Task-8), 커스텀 필드 병합·검증을 수행한다.
     *
     * 호출자(updateIssue)의 @Transactional 안에서 실행된다.
     */
    private fun validateAndNormalizeUpdateRequest(
        actor: ActorId,
        key: IssueKey,
        existing: Issue,
        request: UpdateIssueRequest,
    ): UpdateValidated {
        // typeId non-null 이면 활성 타입 존재 검증. null=변경없음 (resolveTypeId 의 null=fallback 과 다른 시맨틱).
        if (request.typeId != null) {
            issueTypeRepository.findById(request.typeId) ?: throw IssueTypeNotFoundException(request.typeId)
        }

        validatePriorityImpactRanges(request.priority, request.impact)

        // 라벨 도메인 검증 + 정규화 — null=무변경(스킵), non-null=도메인 권위 검증 필수.
        // BLOCKER 1: PATCH 경로에서 도메인 validateAndNormalizeLabels 를 우회하는 경로를 차단한다.
        val normalizedLabels: List<String>? = request.labels?.let { Issue.normalizeLabels(it) }

        // FR-PM-07 Task-8 — 편집 게이트: 실제로 값이 바뀌는 필드를 먼저 계산하고,
        // editableFields 에 없는 필드 변경이 있으면 403. mergeCustomFieldsAndValidate 전에 수행하여
        // 커스텀 필드 병합 비용을 차단하고 domain-bypass 를 방지한다.
        val coreChangedForGate = buildCoreChangedFieldRefs(existing, request, normalizedLabels)
        val customChangedForGate = buildCustomChangedFieldRefs(existing, request)
        assertEditableOrForbidden(actor, key, coreChangedForGate + customChangedForGate)

        // FR-IS-10 E11 커스텀 필드 필드단위 병합 — null=무변경, 맵 명시=키단위 병합, 키값 null=제거.
        // 병합 후 최종 상태를 기준으로 required 검증 수행 (patch-merge-domain-bypass 방지).
        val mergedCustomFields: Map<String, Any?>? = mergeCustomFieldsAndValidate(existing, request)

        return UpdateValidated(normalizedLabels = normalizedLabels, mergedCustomFields = mergedCustomFields)
    }

    /**
     * 단건 조회 응답에 descriptionHtml, resolution, componentIds 를 채운다
     * (C3 + FR-IS-07 B11 + FR-CM-02).
     *
     * - description 이 null 이면 descriptionHtml 도 null 유지.
     * - non-null 이면 [MarkdownRenderer.renderSafe] 로 렌더하여 채운다.
     * - resolutionId 가 non-null 이면 [ResolutionRepository.findById] 로 단건 조회하여
     *   [IssueResponse.ResolutionSummary] 를 생성한다. 단건 GET 이므로 추가 쿼리 1회 허용.
     * - componentIds 는 [IssueRepository.findActiveComponentIdsByIssue] 로 단건 경로에서만 채운다.
     *   목록 경로([listIssues])는 N건 비용 방지를 위해 이 함수를 호출하지 않는다.
     * - affectsVersionIds / fixVersionIds 는 [IssueRepository.findAffectsVersionIdsByIssue] /
     *   [IssueRepository.findFixVersionIdsByIssue] 로 단건 경로에서만 채운다 (FR-VR-03 T5).
     */
    private fun IssueResponse.withSingleDetail(): IssueResponse {
        val resolvedResolution =
            resolutionId?.let { resId ->
                resolutionRepository.findById(resId)?.let { r ->
                    IssueResponse.ResolutionSummary(
                        id = r.id ?: error("resolution.id must not be null after DB fetch"),
                        key = r.key,
                        name = r.name,
                    )
                }
            }
        return copy(
            // ★HTML 컬럼이 있으면 그대로, 없으면 마크다운을 렌더한다 (V039 읽기 fallback).
            //
            // V039 는 기존 행을 백필하지 않는다 — Flyway Java migration 이 조립 앱과 단독 테스트
            // 두 곳에 각각 등록돼야 해서, 한쪽을 빠뜨리면 백필이 조용히 건너뛰어진다. 대신 이
            // 한 줄이 옛 행을 흡수하고, 그 이슈가 편집되는 순간 HTML 컬럼이 채워져 이행이 끝난다.
            //
            // 렌더 생산 지점이 여기 하나뿐이라(2026-07-27 에 그렇게 굳혔다) fallback 도 한 줄이다.
            descriptionHtml = descriptionHtml ?: description?.let { MarkdownRenderer.renderSafe(it) },
            resolution = resolvedResolution,
            componentIds = repo.findActiveComponentIdsByIssue(this.id),
            affectsVersionIds = repo.findAffectsVersionIdsByIssue(this.id),
            fixVersionIds = repo.findFixVersionIdsByIssue(this.id),
        )
    }

    /**
     * 본문 수정 의도를 `description`(마크다운) + `description_html`(HTML) **양쪽 값**으로 푼다.
     *
     * ## 왜 둘을 항상 함께 쓰나
     *
     * V039 로 본문이 두 컬럼이 됐고, 검색용 `description_plain` 은 HTML 이 있으면 그것을 우선한다.
     * 한쪽만 갱신하면 원문과 HTML 이 서로 다른 내용을 가리키고 **검색이 옛 본문을 긁는다.**
     * 그래서 어느 입구로 들어오든 두 값을 같이 만들어 낸다.
     *
     * | 입구 | markdown 컬럼 | html 컬럼 |
     * |---|---|---|
     * | `description`(마크다운, CSV import·레거시) | 원문 그대로 | `renderSafe` 결과 |
     * | `descriptionHtml`(리치 에디터) | 빈 문자열(=클리어) | `sanitizeHtml` 결과 |
     * | 둘 다 null(무변경) | null | null |
     *
     * 에디터 경로가 마크다운 컬럼을 **비우는** 이유는, 남겨 두면 그것이 옛 내용을 담은 채
     * 영원히 굳기 때문이다. 읽기 fallback 은 HTML 이 있으면 마크다운을 보지 않으므로 무해하고,
     * 오히려 「어느 쪽이 진짜인가」가 컬럼 하나로 확정된다.
     *
     * REST 층([com.bts.issue.adapter.inbound.rest.UpdateIssueRequest.isBodyExclusive])이 두 필드의
     * 동시 전달을 400 으로 막으므로 여기서 우선순위를 다툴 일이 없다.
     *
     * @param request 수정 요청.
     * @return 두 컬럼에 그대로 실을 값 쌍. 무변경이면 둘 다 null.
     */
    private fun resolveBodyPatch(request: UpdateIssueRequest): BodyPatch =
        when {
            request.descriptionHtml != null ->
                BodyPatch(markdown = "", html = MarkdownRenderer.sanitizeHtml(request.descriptionHtml))
            request.description != null ->
                BodyPatch(markdown = request.description, html = renderOrClear(request.description))
            else -> BodyPatch(markdown = null, html = null)
        }

    /** 빈 본문(클리어 sentinel)은 렌더하지 않고 빈 문자열로 둔다 — 저장 시 두 컬럼 모두 NULL 이 된다. */
    private fun renderOrClear(markdown: String): String {
        if (markdown.isBlank()) return ""
        return MarkdownRenderer.renderSafe(markdown)
    }

    /** [resolveBodyPatch] 결과 — 두 본문 컬럼에 실을 값 쌍. null 이면 그 컬럼은 무변경이다. */
    private data class BodyPatch(val markdown: String?, val html: String?)

    /**
     * 자동 watcher 정책 FR-WT-01 — 시스템이 이슈 관련자를 자동으로 watcher 로 등록한다.
     *
     * reporter / assignee 배정 통로 전체(createIssue, changeAssignee, changeComponents 자동재배정)에서
     * 동일하게 호출되어 watcher 추가 정책을 일관되게 유지한다 (리뷰 CONCERN-1).
     *
     * - [userIds] 를 distinct 처리하여 중복 등록 요청을 제거한다.
     * - [IssueWatcherRepository.add] 의 `ON CONFLICT DO NOTHING` 으로 멱등성을 보장하므로
     *   이미 등록된 사용자는 조용히 무시된다.
     * - [watcherRepository] 가 null 이면(기존 단위 테스트 호환 fallback) 아무것도 수행하지 않는다.
     * - cloneIssue 는 watcher 복사 의사결정이 이연(ADR 2026-06-02-issue-clone-semantics)됐으므로 제외한다.
     *
     * @param issueId 대상 이슈 UUID.
     * @param userIds 자동 등록할 사용자 UUID 목록. 중복 포함 가능.
     */
    private fun autoWatch(
        issueId: UUID,
        userIds: List<UUID>,
    ) {
        val repo = watcherRepository ?: return
        val distinct = userIds.distinct()
        if (distinct.isEmpty()) return
        // ★1인당 INSERT 가 아니라 배치 1문장. 멘션 경로가 최대 50명을 넘기므로
        //   기존 「최대 2명」 전제가 더 이상 성립하지 않는다 (리뷰 R2).
        repo.addAll(issueId, distinct)
    }
}
