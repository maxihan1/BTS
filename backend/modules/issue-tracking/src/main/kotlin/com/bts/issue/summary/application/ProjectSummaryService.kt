// 프로젝트 요약·활동 조회 유스케이스 — 권한 검증 · 원천 조회 · 카테고리 해석 · 집계 조립

package com.bts.issue.summary.application

import com.bts.issue.adapter.outbound.velocity.IsolatedWorkflowStateLookup
import com.bts.issue.application.IssueChangeItemMasker
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.history.IssueChangeItem
import com.bts.issue.repository.IssueRepository
import com.bts.issue.repository.ProjectActivityRow
import com.bts.issue.repository.SummaryIssueRow
import com.bts.issue.statushistory.StatusCategory
import com.bts.issue.statushistory.repository.StatusChangeRow
import com.bts.issue.summary.domain.AssigneeSlice
import com.bts.issue.summary.domain.PrioritySlice
import com.bts.issue.summary.domain.ProjectActivityEntry
import com.bts.issue.summary.domain.ProjectSummary
import com.bts.issue.summary.domain.RecentCounts
import com.bts.issue.summary.domain.StatusSlice
import com.bts.issue.summary.domain.SummaryWindows
import com.bts.issue.summary.domain.TypeSlice
import com.bts.issue.summary.domain.UpcomingCounts
import com.bts.issue.summary.domain.WindowCount
import com.bts.issue.type.domain.IssueType
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.issue.IssueTypeKey
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.permission.IssueSecurityAccess
import com.bts.shared.permission.IssueSecurityDirectory
import com.bts.shared.user.UserLookupPort
import com.bts.shared.workflow.ProjectKey
import com.bts.shared.workflow.WorkflowStateView
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * 프로젝트 요약 화면과 활동 피드 조회 유스케이스 (Jira 패리티 캠페인 PR ③).
 *
 * ## 오케스트레이션 순서
 * 1. [IssuePermission.BROWSE] 검증 — repo 조회보다 **먼저**. 순서가 뒤집히면 권한 없는 사용자가
 *    403 대신 404 를 받아 프로젝트 존재를 probe 할 수 있다([com.bts.issue.cfd.application.CfdService] 선례).
 * 2. [IssueSecurityDirectory.accessibleLevels] 로 뷰어 접근 가능 보안 등급을 조회.
 * 3. [IssueRepository.fetchActiveVisibleIssuesForSummary] 로 활성·가시 이슈 원천을 한 번에 가져온다.
 * 4. 가시 이슈가 0건이면 이력·워크플로우·사용자 조회 없이 빈 요약을 즉시 반환한다.
 * 5. `issue_types.id → IssueType` 역매핑을 1쿼리로 로드하고, 등장 타입별로
 *    [IsolatedWorkflowStateLookup.listStates] 를 1회씩 캐싱한다(N+1 차단).
 * 6. [IssueRepository.fetchStatusChangesSinceForProject] 로 2주치 상태 이력을 한 번 읽어
 *    이슈별 **DONE 진입 시각**을 뽑는다.
 * 7. 카드·분포를 조립한다.
 *
 * ## 트랜잭션
 * `listStates` 가 `REQUIRES_NEW` 로 격리되어 있으나 이 서비스 자신은 활성 트랜잭션 안에서 돌아야
 * 하므로 `@Transactional(readOnly = true)` 를 붙인다([com.bts.issue.cfd.application.CfdService] 동형).
 *
 * @param permissionResolver 이슈 권한 판정 포트.
 * @param issueRepository 요약 원천·이력·활동 조회 리포지토리.
 * @param securityDirectory 이슈 보안 등급 조회 포트.
 * @param issueTypeRepository 이슈 타입 조회 리포지토리(`id → key/name` 역매핑용).
 * @param workflowStateLookup 워크플로우 상태 목록 격리 조회 Bean(velocity 가 정의한 기존 Bean 재사용).
 * @param userLookupPort 사용자 표시명 일괄 조회 포트. 실패해도 조회를 막지 않는다.
 * @param masker 변경 이력 항목 마스킹 협력자. 단건 이력 경로와 **같은 판정**을 쓴다
 *               ([IssueChangeItemMasker] — FR-PM-07 필드 권한 + FR-CO-02 삭제 댓글).
 * @param clock 창 경계 결정을 위한 시계. 모듈에 전역 [Clock] 빈이 없어 기본값을 둔다
 *              (`NoSuchBeanDefinitionException` 방지 — [com.bts.issue.cfd.web.CfdController] 선례).
 */
@Service
// TooManyFunctions — 공개 메서드는 getSummary/getActivity 둘뿐이고 나머지는 카드·분포를 하나씩 만드는
// private 헬퍼다. 한 화면 한 벌을 조립하는 단일 책임이라 쪼개면 단일 사용처 추상화만 늘어난다.
@Suppress("LongParameterList", "TooManyFunctions")
class ProjectSummaryService(
    private val permissionResolver: IssuePermissionResolver,
    private val issueRepository: IssueRepository,
    private val securityDirectory: IssueSecurityDirectory,
    private val issueTypeRepository: IssueTypeRepository,
    private val workflowStateLookup: IsolatedWorkflowStateLookup,
    private val userLookupPort: UserLookupPort,
    private val masker: IssueChangeItemMasker,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 프로젝트 요약 한 벌을 조회한다.
     *
     * @param actorId 조회를 요청하는 행위자.
     * @param projectKey 대상 프로젝트 키.
     * @return 카드 4종 + 분포 4종이 채워진 [ProjectSummary].
     * @throws IssueAccessDeniedException [IssuePermission.BROWSE] 권한 미보유 시.
     */
    @Transactional(readOnly = true)
    fun getSummary(
        actorId: ActorId,
        projectKey: String,
    ): ProjectSummary {
        checkBrowsePermission(actorId, projectKey)

        val windows = SummaryWindows.of(clock)
        val access = accessOf(actorId, projectKey)
        val issues = issueRepository.fetchActiveVisibleIssuesForSummary(projectKey, actorId.value, access)
        if (issues.isEmpty()) {
            log.debug("getSummary: no visible issues, projectKey={}", projectKey)
            return emptySummary(projectKey)
        }

        val typeById = issueTypeRepository.findAll().mapNotNull { t -> t.id?.let { it.value to t } }.toMap()
        val stateCache = buildStateCache(issues, typeById, projectKey)
        val categoryOf = { row: SummaryIssueRow -> categoryOf(row.currentStateKey, row.typeId, typeById, stateCache) }

        val doneEntries =
            collectDoneEntryTimes(
                issueRepository.fetchStatusChangesSinceForProject(
                    projectKey,
                    actorId.value,
                    access,
                    windows.historySince,
                ),
                issues,
                typeById,
                stateCache,
            )

        return ProjectSummary(
            projectKey = projectKey,
            recent = buildRecentCounts(issues, doneEntries, windows),
            upcoming = buildUpcomingCounts(issues, windows, categoryOf),
            statusOverview = buildStatusOverview(issues, doneEntries, typeById, stateCache),
            priorityBreakdown =
                issues.groupingBy { it.priority }.eachCount()
                    .map { (priority, count) -> PrioritySlice(priority, count.toLong()) }
                    .sortedBy { it.priority },
            typesOfWork = buildTypesOfWork(issues, typeById),
            teamWorkload = buildTeamWorkload(issues),
        )
    }

    /**
     * 프로젝트 활동 피드를 최신순으로 조회한다.
     *
     * ## 게이트가 두 겹인 이유
     * 프로젝트 진입은 [IssuePermission.BROWSE] 로 막지만, 피드는 **이슈 단위 내용**(변경 전후 값·라벨)을
     * 내보내므로 항목마다 [IssuePermission.VIEW] 를 다시 묻는다. 두 권한은 `BROWSE_PROJECT`/`VIEW_ISSUE`
     * 라는 **독립 매트릭스 권한**에 각각 위임되며 포함 관계가 아니다(FR-PM-05). 집계만 내보내는
     * [getSummary] 가 BROWSE 로 충분한 것과 대비된다.
     *
     * VIEW 를 통과하지 못한 이슈의 항목은 값만 가리지 않고 **제거**한다 — 단건 경로가 VIEW 미보유를
     * `IssueNotFoundException`(404)으로 돌려 존재 자체를 숨기는 것과 의미를 맞춘다.
     * 그래서 반환 항목 수는 [limit] 보다 적을 수 있다.
     *
     * ## 남은 항목은 단건 이력과 같은 마스킹을 받는다
     * VIEW 를 통과했다고 모든 값을 볼 수 있는 건 아니다. [IssueChangeItemMasker] 가 필드 수준
     * 권한(FR-PM-07)으로 가려진 필드와 삭제된 댓글의 본문(FR-CO-02)을 값·라벨 4종만 null 로
     * 치환한다 — `GET /issues/{key}/changelog` 와 **같은 협력자·같은 판정**이다. 여기서 판정을
     * 복사하면 한쪽에 마스킹 대상이 추가될 때 다른 쪽이 조용히 샌다.
     *
     * @param actorId 조회를 요청하는 행위자.
     * @param projectKey 대상 프로젝트 키.
     * @param limit 조회할 최대 변경 그룹 수. VIEW 게이트로 제거된 만큼 결과는 이보다 적을 수 있다.
     * @return 최신순 [ProjectActivityEntry] 목록.
     * @throws IssueAccessDeniedException [IssuePermission.BROWSE] 권한 미보유 시.
     */
    @Transactional(readOnly = true)
    fun getActivity(
        actorId: ActorId,
        projectKey: String,
        limit: Int,
    ): List<ProjectActivityEntry> {
        checkBrowsePermission(actorId, projectKey)

        val access = accessOf(actorId, projectKey)
        val fetched = issueRepository.fetchProjectActivity(projectKey, actorId.value, access, limit)
        val rows = retainViewableRows(actorId, projectKey, fetched)
        if (rows.isEmpty()) return emptyList()

        val actorNames = resolveActorNames(rows.mapNotNull { it.actorId }.toSet())
        // 조회 1벌 — 피드 전체 항목을 한 번에 넘겨 필드 권한 1회 + 활성 댓글 1회로 판정을 끝낸다.
        val mask = masker.planFor(actorId, projectKey, rows.groupBy({ it.issueId }, { it.toChangeItem() }))

        // repository 가 created_at DESC, group id DESC 로 정렬해 반환하므로 groupBy 가 순서를 보존한다.
        return rows.groupBy { it.groupId }.map { (_, groupRows) ->
            val head = groupRows.first()
            ProjectActivityEntry(
                issueKey = head.issueKey,
                actorId = head.actorId,
                actorName = head.actorId?.let { actorNames[it] },
                createdAt = head.createdAt,
                items = groupRows.map { mask.apply(it.issueId, it.toChangeItem()) },
            )
        }
    }

    // ── 권한·접근 ─────────────────────────────────────────────────────────────

    /**
     * [IssuePermission.BROWSE] 권한을 프로젝트 범위로 검증한다.
     *
     * 존재 probe 방지를 위해 repo 조회보다 먼저 호출해야 한다.
     *
     * @throws IssueAccessDeniedException 권한 미보유 시.
     */
    private fun checkBrowsePermission(
        actorId: ActorId,
        projectKey: String,
    ) {
        val scope = IssueScope.Project(projectKey)
        if (!permissionResolver.hasPermission(actorId.value, IssuePermission.BROWSE, scope)) {
            throw IssueAccessDeniedException(actorId, IssuePermission.BROWSE, scope)
        }
    }

    private fun accessOf(
        actorId: ActorId,
        projectKey: String,
    ): IssueSecurityAccess = securityDirectory.accessibleLevels(actorId.value, projectKey)

    /**
     * 활동 피드 행 중 [IssuePermission.VIEW] 를 통과한 이슈의 것만 남긴다.
     *
     * **왜 이슈 단위인가.** `BROWSE_PROJECT` 와 `VIEW_ISSUE` 는 독립 매트릭스 권한이라(FR-PM-05)
     * 프로젝트를 둘러볼 수 있어도 이슈 내용을 볼 권한은 없을 수 있다. 한 변경 그룹의 행은 모두 같은
     * 이슈에 속하므로 그룹이 반쪽만 남는 일은 없다.
     *
     * **N+1.** [IssuePermissionResolver] 에 배치 API 가 없어 이슈마다 개별 호출이지만, 피드는 최대
     * `limit`(컨트롤러 상한 50)개 그룹이라 distinct 이슈도 그 이하다. 여기에 이슈 키 캐시를 더해
     * 같은 이슈가 여러 그룹으로 등장해도 판정은 1회다.
     */
    private fun retainViewableRows(
        actorId: ActorId,
        projectKey: String,
        rows: List<ProjectActivityRow>,
    ): List<ProjectActivityRow> {
        if (rows.isEmpty()) return rows
        val decided = mutableMapOf<String, Boolean>()
        return rows.filter { row ->
            decided.getOrPut(row.issueKey) { canViewIssue(actorId, projectKey, row.issueKey) }
        }
    }

    /**
     * [issueKey] 이슈의 [IssuePermission.VIEW] 보유 여부를 판정한다. 불명은 거부다.
     *
     * 이슈 키 접두사는 소속 프로젝트 키와 같다는 불변식이 있고(DATA.md §1.1 이슈 키 영속성),
     * resolver 도 그 접두사로 프로젝트를 해석한다. 접두사가 조회 대상 프로젝트와 어긋나면 판정이
     * **다른 프로젝트 기준**으로 나가므로, 그 상황에서는 묻지 않고 거부한다(fail-closed).
     */
    private fun canViewIssue(
        actorId: ActorId,
        projectKey: String,
        issueKey: String,
    ): Boolean {
        if (issueKey.substringBefore(ISSUE_KEY_SEPARATOR) != projectKey) {
            log.warn(
                "getActivity: issueKey prefix mismatch issueKey={} projectKey={} — VIEW 판정 불능이므로 거부",
                issueKey,
                projectKey,
            )
            return false
        }
        return permissionResolver.hasPermission(actorId.value, IssuePermission.VIEW, IssueScope.Issue(issueKey))
    }

    // ── 워크플로우 카테고리 해석 ───────────────────────────────────────────────

    /**
     * [issues] 에 등장하는 distinct 타입마다 상태 키 → [WorkflowStateView] 맵을 1회씩 캐싱한다(N+1 차단).
     */
    private fun buildStateCache(
        issues: List<SummaryIssueRow>,
        typeById: Map<Long, IssueType>,
        projectKey: String,
    ): Map<IssueTypeKey, Map<String, WorkflowStateView>> =
        issues.mapNotNull { typeById[it.typeId]?.key }
            .toSet()
            .associateWith { typeKey -> resolveStates(projectKey, typeKey) }

    /**
     * [projectKey] + [typeKey] 의 상태 키 → [WorkflowStateView] 맵을 반환한다.
     *
     * `WorkflowSchemeNoDefaultException` 이면 빈 맵으로 폴백해 운영 500 을 차단한다
     * ([com.bts.issue.cfd.application.CfdService.resolveStateCategories] 동형).
     *
     * TooGenericExceptionCaught suppress 근거. project-workflow BC 내부 예외를 직접 import 할 수 없어
     * RuntimeException 을 받아 simpleName 으로 식별한다.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun resolveStates(
        projectKey: String,
        typeKey: IssueTypeKey,
    ): Map<String, WorkflowStateView> =
        try {
            workflowStateLookup.listStates(ProjectKey.of(projectKey), typeKey).associateBy { it.key }
        } catch (e: RuntimeException) {
            if (e.javaClass.simpleName == WORKFLOW_SCHEME_NO_DEFAULT_EXCEPTION) {
                log.warn(
                    "getSummary: no workflow scheme for typeKey={} projectKey={} — defaulting to empty map",
                    typeKey.value,
                    projectKey,
                )
                emptyMap()
            } else {
                throw e
            }
        }

    private fun stateOf(
        stateKey: String?,
        typeId: Long,
        typeById: Map<Long, IssueType>,
        stateCache: Map<IssueTypeKey, Map<String, WorkflowStateView>>,
    ): WorkflowStateView? = stateKey?.let { typeById[typeId]?.key?.let { key -> stateCache[key]?.get(stateKey) } }

    private fun categoryOf(
        stateKey: String?,
        typeId: Long,
        typeById: Map<Long, IssueType>,
        stateCache: Map<IssueTypeKey, Map<String, WorkflowStateView>>,
    ): StatusCategory = StatusCategory.fromCategoryString(stateOf(stateKey, typeId, typeById, stateCache)?.category)

    // ── 완료 판정 (D1 — 상태 이력 기반) ────────────────────────────────────────

    /**
     * 이슈별 **DONE 진입 시각** 목록을 뽑는다.
     *
     * 「진입」은 전환 전 카테고리가 DONE 이 **아니고** 전환 후가 DONE 인 경우만 센다.
     * DONE 안에서의 상태 이동(예: 완료 → 보류완료)을 완료로 두 번 세지 않기 위해서다.
     *
     * @param changes 2주치 status 전환 이력(오름차순).
     * @param issues 가시 이슈 원천 — 이슈의 타입을 알아야 카테고리를 해석할 수 있다.
     * @return `issueId → DONE 진입 시각 목록`.
     */
    private fun collectDoneEntryTimes(
        changes: List<StatusChangeRow>,
        issues: List<SummaryIssueRow>,
        typeById: Map<Long, IssueType>,
        stateCache: Map<IssueTypeKey, Map<String, WorkflowStateView>>,
    ): Map<UUID, List<Instant>> {
        val typeIdByIssue = issues.associate { it.issueId to it.typeId }
        return changes
            .mapNotNull { change ->
                val typeId = typeIdByIssue[change.issueId] ?: return@mapNotNull null
                val to = categoryOf(change.toValue, typeId, typeById, stateCache)
                val from = categoryOf(change.fromValue, typeId, typeById, stateCache)
                if (to == StatusCategory.DONE && from != StatusCategory.DONE) {
                    change.issueId to change.changedAt
                } else {
                    null
                }
            }
            .groupBy({ it.first }, { it.second })
    }

    // ── 카드·분포 조립 ────────────────────────────────────────────────────────

    private fun buildRecentCounts(
        issues: List<SummaryIssueRow>,
        doneEntries: Map<UUID, List<Instant>>,
        windows: SummaryWindows,
    ): RecentCounts {
        // 창은 [from, to) — 시작 inclusive, 끝 exclusive. 두 창이 인접하므로 경계값이 양쪽에 세지면 안 된다.
        val recentStart = windows.recentFrom.toInstant()
        val recentEnd = windows.recentTo.toInstant()
        val previousStart = windows.previousFrom.toInstant()
        val previousEnd = windows.previousTo.toInstant()

        fun inRecent(t: Instant) = !t.isBefore(recentStart) && t.isBefore(recentEnd)

        fun inPrevious(t: Instant) = !t.isBefore(previousStart) && t.isBefore(previousEnd)

        return RecentCounts(
            windowDays = SummaryWindows.RECENT_WINDOW_DAYS.toInt(),
            // 완료는 **이슈 수**다 — 같은 이슈가 창 안에서 두 번 완료돼도 1로 센다.
            completed =
                WindowCount(
                    current = doneEntries.count { (_, times) -> times.any(::inRecent) }.toLong(),
                    previous = doneEntries.count { (_, times) -> times.any(::inPrevious) }.toLong(),
                ),
            updated =
                WindowCount(
                    current = issues.count { inRecent(it.updatedAt) }.toLong(),
                    previous = issues.count { inPrevious(it.updatedAt) }.toLong(),
                ),
            created =
                WindowCount(
                    current = issues.count { inRecent(it.createdAt) }.toLong(),
                    previous = issues.count { inPrevious(it.createdAt) }.toLong(),
                ),
        )
    }

    /**
     * 마감 예정·지연 카드를 만든다. 둘 다 **미완료 이슈만** 센다 — 끝난 일은 마감을 앞두지도, 늦지도 않았다.
     */
    private fun buildUpcomingCounts(
        issues: List<SummaryIssueRow>,
        windows: SummaryWindows,
        resolveCategory: (SummaryIssueRow) -> StatusCategory,
    ): UpcomingCounts {
        // 마감일을 여기서 뽑아내 이후로는 non-null 만 다룬다 — !! 를 쓰지 않는다.
        val openDueDates =
            issues.mapNotNull { row ->
                row.dueDate?.takeIf { resolveCategory(row) != StatusCategory.DONE }
            }
        return UpcomingCounts(
            windowDays = SummaryWindows.RECENT_WINDOW_DAYS.toInt(),
            due = openDueDates.count { it >= windows.dueFrom && it <= windows.dueTo }.toLong(),
            overdue = openDueDates.count { it < windows.dueFrom }.toLong(),
        )
    }

    /**
     * 상태 개요를 만든다.
     *
     * **DONE 카테고리 상태만 최근 2주 특례**가 걸린다 — 2주 안에 DONE 으로 진입한 이력이 없는 이슈는
     * 지금 DONE 상태여도 세지 않는다. Jira 클라우드 원문 "Only items that have been completed in
     * the last two weeks will appear in Done" 를 그대로 옮긴 것이다. 다른 카테고리와 나머지 분포
     * 3종에는 적용하지 않는다.
     *
     * [doneEntries] 자체가 `historySince`(=2주 전) 이후만 담고 있으므로 추가 시각 비교가 필요 없다.
     */
    private fun buildStatusOverview(
        issues: List<SummaryIssueRow>,
        doneEntries: Map<UUID, List<Instant>>,
        typeById: Map<Long, IssueType>,
        stateCache: Map<IssueTypeKey, Map<String, WorkflowStateView>>,
    ): List<StatusSlice> =
        issues
            .filter { row ->
                val category = categoryOf(row.currentStateKey, row.typeId, typeById, stateCache)
                category != StatusCategory.DONE || doneEntries[row.issueId]?.isNotEmpty() == true
            }
            .groupBy { it.currentStateKey }
            .map { (statusKey, rows) ->
                val head = rows.first()
                val state = stateOf(statusKey, head.typeId, typeById, stateCache)
                StatusSlice(
                    statusKey = statusKey,
                    statusName = state?.name,
                    category = StatusCategory.fromCategoryString(state?.category),
                    count = rows.size.toLong(),
                )
            }
            .sortedWith(compareBy({ it.category.ordinal }, { it.statusKey }))

    private fun buildTypesOfWork(
        issues: List<SummaryIssueRow>,
        typeById: Map<Long, IssueType>,
    ): List<TypeSlice> =
        issues
            .groupingBy { it.typeId }.eachCount()
            .mapNotNull { (typeId, count) ->
                typeById[typeId]?.let { TypeSlice(it.key.value, it.name, count.toLong()) }
            }
            .sortedWith(compareByDescending<TypeSlice> { it.count }.thenBy { it.typeKey })

    private fun buildTeamWorkload(issues: List<SummaryIssueRow>): List<AssigneeSlice> {
        val counts = issues.groupingBy { it.assigneeId }.eachCount()
        val names = resolveActorNames(counts.keys.filterNotNull().toSet())
        return counts
            .map { (assigneeId, count) ->
                AssigneeSlice(assigneeId, assigneeId?.let { names[it] }, count.toLong())
            }
            // 미할당 버킷은 항상 마지막 — 이름이 없어 이름순 정렬에 끼워 넣을 수 없다.
            .sortedWith(
                compareBy<AssigneeSlice> { it.assigneeId == null }
                    .thenByDescending { it.count }
                    .thenBy { it.assigneeId?.toString().orEmpty() },
            )
    }

    /**
     * 사용자 표시명을 일괄 해석한다. 조회가 실패해도 요약·활동 조회를 막지 않는다.
     *
     * identity-access 장애로 이름을 못 얻는 것과 화면 전체가 500 이 되는 것은 전혀 다른 사고다
     * ([com.bts.issue.application.IssueChangelogService] 선례).
     */
    @Suppress("TooGenericExceptionCaught")
    private fun resolveActorNames(ids: Set<UUID>): Map<UUID, String> {
        if (ids.isEmpty()) return emptyMap()
        return try {
            userLookupPort.findDisplayNamesByIds(ids)
        } catch (e: RuntimeException) {
            log.warn("UserLookupPort.findDisplayNamesByIds failed for ids={} — degrade to null names", ids, e)
            emptyMap()
        }
    }

    private fun ProjectActivityRow.toChangeItem(): IssueChangeItem =
        IssueChangeItem(
            field = field,
            fromValue = fromValue,
            toValue = toValue,
            fromLabel = fromLabel,
            toLabel = toLabel,
        )

    private fun emptySummary(projectKey: String): ProjectSummary {
        val zero = WindowCount(0, 0)
        val days = SummaryWindows.RECENT_WINDOW_DAYS.toInt()
        return ProjectSummary(
            projectKey = projectKey,
            recent = RecentCounts(days, zero, zero, zero),
            upcoming = UpcomingCounts(days, 0, 0),
            statusOverview = emptyList(),
            priorityBreakdown = emptyList(),
            typesOfWork = emptyList(),
            teamWorkload = emptyList(),
        )
    }

    private companion object {
        /** project-workflow BC 내부 예외 simpleName — 직접 import 불가하므로 문자열로 식별. */
        const val WORKFLOW_SCHEME_NO_DEFAULT_EXCEPTION = "WorkflowSchemeNoDefaultException"

        /** 이슈 키의 프로젝트 접두사 구분자(`BTS-1` → `BTS`). */
        const val ISSUE_KEY_SEPARATOR = '-'
    }
}
