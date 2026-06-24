// 백로그 조회 애플리케이션 서비스 — 미할당/스프린트별 그룹핑 + 정렬 (FR-BL-01/02 Task 3)

package com.bts.agileplanning.application

import com.bts.agileplanning.domain.SprintStatus
import com.bts.agileplanning.repository.SprintRepository
import com.bts.agileplanning.web.dto.BacklogIssueResponse
import com.bts.agileplanning.web.dto.SprintMetaResponse
import com.bts.shared.board.BoardIssueLookupPort
import com.bts.shared.board.BoardIssueView
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * 백로그 조회 서비스 결과 VO — 스프린트별 이슈 묶음.
 *
 * @property sprint 스프린트 메타 응답.
 * @property issues 해당 스프린트에 할당된 가시 이슈 목록.
 */
data class SprintWithIssues(
    val sprint: SprintMetaResponse,
    val issues: List<BacklogIssueResponse>,
)

/**
 * 백로그 조회 서비스 결과 VO.
 *
 * @property backlog 미할당 이슈 목록. rank ASC NULLS LAST → key ASC.
 * @property sprints 스프린트별 이슈 묶음. ACTIVE → PLANNED → COMPLETED 그다음 startDate.
 * @property truncated 조회 건수 LIMIT 초과 여부.
 */
data class BacklogResult(
    val backlog: List<BacklogIssueResponse>,
    val sprints: List<SprintWithIssues>,
    val truncated: Boolean,
)

/**
 * 백로그 조회 애플리케이션 서비스.
 *
 * 프로젝트의 가시 이슈를 미할당(백로그)과 스프린트별로 그룹핑해 정렬 후 반환한다.
 * cross-BC 통신은 shared-kernel 포트([BoardIssueLookupPort], [IssuePermissionResolver])만 사용한다.
 * issue-tracking 내부를 직접 import 하지 않는다(BC 격리).
 *
 * ## 처리 순서
 * 1. BROWSE 권한 판정 — 거부 시 403.
 * 2. [BoardIssueLookupPort.listVisibleIssuesByProject] 로 가시 이슈 목록 조회.
 * 3. [SprintRepository.findIssueKeysByProject] 로 전체 스프린트의 이슈 키를 단일 쿼리로 조회(N+1 차단).
 * 4. 가시 이슈 중 어느 스프린트에도 없는 것 → backlog.
 *    스프린트에 있는 것(단, 가시 이슈 중에서만) → 해당 스프린트.
 * 5. 정렬 적용.
 *
 * ## 정렬 규칙
 * - 이슈(백로그/각 스프린트 내): rank ASC NULLS LAST → key ASC.
 * - 스프린트: status(ACTIVE=0, PLANNED=1, COMPLETED=2) ASC → startDate ASC NULLS LAST.
 *
 * ## 가시성 보장
 * [BoardIssueLookupPort.listVisibleIssuesByProject] 결과만 사용한다.
 * sprint_issues 에 있어도 lookup 결과에 없는 이슈는 응답에 포함되지 않는다(비가시 이슈 누출 차단).
 *
 * @param permissionResolver cross-BC 권한 판정 포트(fail-closed, non-null 주입)
 * @param sprintRepository sprints / sprint_issues jOOQ repository
 * @param boardIssueLookupPort 프로젝트 이슈 목록 조회 포트(issue-tracking 구현)
 */
@Service
@Transactional(readOnly = true)
class BacklogApplicationService(
    private val permissionResolver: IssuePermissionResolver,
    private val sprintRepository: SprintRepository,
    private val boardIssueLookupPort: BoardIssueLookupPort,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** 스프린트 상태 정렬 우선순위 — 낮을수록 앞에 온다. */
        private val STATUS_ORDER = mapOf(
            SprintStatus.ACTIVE to 0,
            SprintStatus.PLANNED to 1,
            SprintStatus.COMPLETED to 2,
        )
    }

    /**
     * 프로젝트의 가시 이슈를 백로그(미할당)와 스프린트별로 그룹핑해 반환한다.
     *
     * 처리 순서 및 상세는 클래스 KDoc 참조.
     *
     * @param actorId 조회 행위자 UUID.
     * @param projectKey 조회할 프로젝트 키.
     * @return [BacklogResult] — 그룹핑·정렬된 이슈 목록 + truncated.
     * @throws ResponseStatusException 403 — BROWSE 권한 미충족.
     */
    @Transactional(readOnly = true)
    fun getBacklog(
        actorId: UUID,
        projectKey: String,
    ): BacklogResult {
        log.debug("백로그 조회 시작 — projectKey={}, actorId={}", projectKey, actorId)

        if (!permissionResolver.hasPermission(actorId, IssuePermission.BROWSE, IssueScope.Project(projectKey))) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "접근 권한이 없습니다.")
        }

        // 1. 가시 이슈 전체 조회
        val page = boardIssueLookupPort.listVisibleIssuesByProject(projectKey, actorId)
        val visibleIssues: List<BoardIssueView> = page.issues
        val visibleKeys: Set<String> = visibleIssues.map { it.key }.toHashSet()

        // 2. 프로젝트 전체 스프린트 + 스프린트별 이슈 키 일괄 조회(N+1 차단, C4)
        val sprints = sprintRepository.findByProject(projectKey)
        val sprintIssueKeys: Map<UUID, List<String>> = sprintRepository.findIssueKeysByProject(projectKey)

        // 3. 스프린트에 할당된 이슈 키(가시 이슈 교집합만) → Set
        val assignedKeys: Set<String> = sprintIssueKeys.values
            .flatten()
            .filter { it in visibleKeys }
            .toHashSet()

        // 4. 이슈 Map(key → view) 생성
        val issueByKey: Map<String, BoardIssueView> = visibleIssues.associateBy { it.key }

        // 5. 미할당 이슈 = 가시 이슈 − 할당된 이슈, rank 정렬
        val backlog = visibleIssues
            .filter { it.key !in assignedKeys }
            .sortedWith(issueComparator())
            .map { BacklogIssueResponse.from(it) }

        // 6. 스프린트별 그룹핑 + 정렬
        val sprintWithIssuesList = sprints
            .sortedWith(sprintComparator())
            .map { sprint ->
                val keys = sprintIssueKeys[sprint.id] ?: emptyList()
                // 가시 이슈 중에서만 — 비가시 이슈 누출 차단
                val issues = keys
                    .mapNotNull { issueByKey[it] }
                    .sortedWith(issueComparator())
                    .map { BacklogIssueResponse.from(it) }
                SprintWithIssues(
                    sprint = SprintMetaResponse(
                        sprintId = sprint.id,
                        name = sprint.name,
                        goal = sprint.goal,
                        status = sprint.status.name,
                        startDate = sprint.startDate,
                        endDate = sprint.endDate,
                        version = sprint.version,
                    ),
                    issues = issues,
                )
            }

        log.debug(
            "백로그 조회 완료 — projectKey={}, backlog={}, sprints={}, truncated={}",
            projectKey, backlog.size, sprintWithIssuesList.size, page.truncated,
        )

        return BacklogResult(
            backlog = backlog,
            sprints = sprintWithIssuesList,
            truncated = page.truncated,
        )
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * 이슈 정렬 비교자 — rank ASC NULLS LAST → key ASC.
     *
     * rank 가 null 인 이슈는 rank 가 있는 이슈보다 뒤에 온다.
     * rank 가 같거나 둘 다 null 이면 key 의 사전 순으로 보조 정렬한다.
     */
    private fun issueComparator(): Comparator<BoardIssueView> =
        Comparator { a, b ->
            val rankA: String? = a.rank
            val rankB: String? = b.rank
            when {
                rankA == null && rankB == null -> a.key.compareTo(b.key)
                rankA == null -> 1  // null 은 뒤로
                rankB == null -> -1
                else -> {
                    val cmp = rankA.compareTo(rankB)
                    if (cmp != 0) cmp else a.key.compareTo(b.key)
                }
            }
        }

    /**
     * 스프린트 정렬 비교자 — status(ACTIVE=0,PLANNED=1,COMPLETED=2) ASC → startDate ASC NULLS LAST.
     *
     * status 우선순위가 같으면 startDate 로 보조 정렬한다. startDate 가 null 이면 뒤로.
     */
    private fun sprintComparator(): Comparator<com.bts.agileplanning.domain.Sprint> =
        Comparator { a, b ->
            val statusCmp = (STATUS_ORDER[a.status] ?: Int.MAX_VALUE)
                .compareTo(STATUS_ORDER[b.status] ?: Int.MAX_VALUE)
            if (statusCmp != 0) return@Comparator statusCmp
            when {
                a.startDate == null && b.startDate == null -> 0
                a.startDate == null -> 1
                b.startDate == null -> -1
                else -> a.startDate.compareTo(b.startDate)
            }
        }
}
