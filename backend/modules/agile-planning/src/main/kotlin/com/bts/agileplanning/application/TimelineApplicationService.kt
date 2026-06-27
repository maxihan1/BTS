// 간트 차트 타임라인 조회 애플리케이션 서비스 — 권한 게이트·날짜 정렬·의존 엣지 정렬 (FR-TL-01/02 Task 4)

package com.bts.agileplanning.application

import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.timeline.TimelineDepEdge
import com.bts.shared.timeline.TimelineItemView
import com.bts.shared.timeline.TimelineLookupPort
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * 타임라인 조회 서비스 결과 VO.
 *
 * @property items 정렬된 타임라인 아이템 목록.
 *   startDate ASC NULLS LAST → dueDate ASC NULLS LAST → key ASC.
 * @property truncated 조회 건수가 LIMIT 를 초과해 일부 누락됐으면 true.
 */
data class TimelineResult(
    val items: List<TimelineItemView>,
    val truncated: Boolean,
)

/**
 * 타임라인 의존 엣지 조회 서비스 결과 VO.
 *
 * [TimelineApplicationService.getDeps] 가 반환하는 읽기 전용 값 객체.
 * 간트 차트 오버레이에서 `blocks` 화살표를 렌더링하기 위해 사용한다(FR-TL-02).
 *
 * 정렬 기준 — blockerKey ASC → blockedKey ASC (결정적 순서, FR6).
 *
 * @property edges 정렬된 의존 엣지 목록.
 * @property truncated 조회 건수가 LIMIT 를 초과해 엣지 일부가 누락됐으면 true.
 */
data class TimelineDepsResult(
    val edges: List<TimelineDepEdge>,
    val truncated: Boolean,
)

/**
 * 간트 차트 타임라인 조회 애플리케이션 서비스.
 *
 * 프로젝트의 가시 이슈를 [TimelineLookupPort] 를 통해 조회한 뒤
 * 날짜 기준으로 정렬해 반환한다.
 *
 * cross-BC 통신은 shared-kernel 포트([TimelineLookupPort], [IssuePermissionResolver])만 사용한다.
 * issue-tracking 내부를 직접 import 하지 않는다(BC 격리).
 *
 * ## 처리 순서
 * 1. BROWSE 권한 판정 — 거부 시 403(fail-closed).
 * 2. [TimelineLookupPort.listTimelineItemsByProject] 로 가시 이슈 목록 조회.
 * 3. 날짜 정렬 적용 — [timelineComparator] 참조.
 * 4. [TimelineResult] 반환.
 *
 * ## 정렬 규칙
 * startDate ASC NULLS LAST → dueDate ASC NULLS LAST → key ASC.
 *
 * ## BC 격리 사유
 * ```
 * agile-planning ──(port)──▶ shared-kernel ◀──(impl)──  issue-tracking
 * ```
 * agile-planning 은 shared-kernel 포트([TimelineLookupPort])만 의존한다.
 * issue-tracking 내부 클래스 직접 import 는 BC 경계 위반으로 차단된다.
 *
 * @param permissionResolver cross-BC 권한 판정 포트(fail-closed, non-null 주입).
 * @param timelineLookupPort 프로젝트 가시 이슈 조회 포트(issue-tracking 구현).
 */
@Service
@Transactional(readOnly = true)
class TimelineApplicationService(
    private val permissionResolver: IssuePermissionResolver,
    private val timelineLookupPort: TimelineLookupPort,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 프로젝트의 가시 이슈를 날짜 정렬해 반환한다.
     *
     * 처리 순서 및 상세는 클래스 KDoc 참조.
     *
     * @param actorId 조회 행위자 UUID.
     * @param projectKey 조회할 프로젝트 키. 예: `"ATLAS"`.
     * @return [TimelineResult] — 정렬된 타임라인 아이템 목록 + truncated.
     * @throws ResponseStatusException 403 — BROWSE 권한 미충족(fail-closed).
     */
    @Transactional(readOnly = true)
    fun getTimeline(
        actorId: UUID,
        projectKey: String,
    ): TimelineResult {
        log.debug("타임라인 조회 시작 — projectKey={}, actorId={}", projectKey, actorId)

        if (!permissionResolver.hasPermission(actorId, IssuePermission.BROWSE, IssueScope.Project(projectKey))) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "접근 권한이 없습니다.")
        }

        val page = timelineLookupPort.listTimelineItemsByProject(projectKey, actorId)
        val sorted = page.items.sortedWith(timelineComparator())

        log.debug(
            "타임라인 조회 완료 — projectKey={}, items={}, truncated={}",
            projectKey,
            sorted.size,
            page.truncated,
        )

        return TimelineResult(items = sorted, truncated = page.truncated)
    }

    /**
     * 프로젝트의 `blocks` 의존 엣지를 결정적 순서로 정렬해 반환한다.
     *
     * [getTimeline] 과 동일한 BROWSE 게이트를 재사용한다(fail-closed 403).
     * 새로운 권한 경로를 신설하지 않고 기존 게이트를 그대로 적용한다.
     *
     * 정렬 기준 — blockerKey ASC → blockedKey ASC (FR6 결정적 순서).
     * 클라이언트가 동일한 요청에 항상 동일한 순서를 받도록 보장한다.
     *
     * @param actorId 조회 행위자 UUID.
     * @param projectKey 조회할 프로젝트 키. 예: `"ATLAS"`.
     * @return [TimelineDepsResult] — 정렬된 의존 엣지 목록 + truncated.
     * @throws ResponseStatusException 403 — BROWSE 권한 미충족(fail-closed).
     */
    @Transactional(readOnly = true)
    fun getDeps(
        actorId: UUID,
        projectKey: String,
    ): TimelineDepsResult {
        log.debug("타임라인 의존 엣지 조회 시작 — projectKey={}, actorId={}", projectKey, actorId)

        if (!permissionResolver.hasPermission(actorId, IssuePermission.BROWSE, IssueScope.Project(projectKey))) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "접근 권한이 없습니다.")
        }

        val page = timelineLookupPort.listBlocksDepsByProject(projectKey, actorId)
        val sorted = page.edges.sortedWith(compareBy({ it.blockerKey }, { it.blockedKey }))

        log.debug(
            "타임라인 의존 엣지 조회 완료 — projectKey={}, edges={}, truncated={}",
            projectKey,
            sorted.size,
            page.truncated,
        )

        return TimelineDepsResult(edges = sorted, truncated = page.truncated)
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * 타임라인 아이템 정렬 비교자.
     *
     * 정렬 기준 (우선순위 순).
     * 1. startDate ASC NULLS LAST — 시작일이 빠른 이슈를 앞에. 미설정(null)은 뒤로.
     * 2. dueDate ASC NULLS LAST — 시작일이 같으면 마감일이 빠른 이슈를 앞에. 미설정(null)은 뒤로.
     * 3. key ASC — 날짜가 모두 같으면 이슈 키 사전 순으로 보조 정렬.
     *
     * null 날짜는 항상 non-null 날짜보다 뒤에 배치되어(NULLS LAST) 날짜가 정해진 이슈가
     * 간트 차트 상단에 표시된다.
     */
    private fun timelineComparator(): Comparator<TimelineItemView> =
        Comparator { a, b ->
            val startCmp = compareNullsLast(a.startDate, b.startDate)
            if (startCmp != 0) return@Comparator startCmp

            val dueCmp = compareNullsLast(a.dueDate, b.dueDate)
            if (dueCmp != 0) return@Comparator dueCmp

            a.key.compareTo(b.key)
        }

    /**
     * null 을 뒤로 보내는 Comparable 비교 헬퍼.
     *
     * 두 피연산자 모두 null 이면 0(동등). 한쪽만 null 이면 null 이 뒤(+1/-1).
     * 둘 다 non-null 이면 [Comparable.compareTo] 결과를 그대로 반환한다.
     *
     * @param a 왼쪽 피연산자.
     * @param b 오른쪽 피연산자.
     * @return 음수(a 앞), 0(동등), 양수(b 앞).
     */
    private fun <T : Comparable<T>> compareNullsLast(
        a: T?,
        b: T?,
    ): Int =
        when {
            a == null && b == null -> 0
            a == null -> 1
            b == null -> -1
            else -> a.compareTo(b)
        }
}
