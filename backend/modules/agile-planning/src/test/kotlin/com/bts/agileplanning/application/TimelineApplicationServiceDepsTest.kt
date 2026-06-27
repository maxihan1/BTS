// TimelineApplicationService.getDeps 단위 테스트 — BROWSE 게이트·정렬·truncated 전파 RED 명세 (FR-TL-02 Task 4)

package com.bts.agileplanning.application

import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.timeline.TimelineDepEdge
import com.bts.shared.timeline.TimelineDepsPage
import com.bts.shared.timeline.TimelineLookupPort
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * TimelineApplicationService.getDeps 단위 테스트.
 *
 * cross-BC 포트([IssuePermissionResolver], [TimelineLookupPort])를 MockK 로 교체해
 * BROWSE 게이트·정렬 결정성·truncated 전파 로직을 독립 검증한다.
 * Spring 컨텍스트를 부팅하지 않는다.
 *
 * ### 검증 케이스
 * - BROWSE 권한 없으면 403 을 던지고 포트를 호출하지 않는다.
 * - BROWSE 권한 있으면 포트 결과를 blockerKey ASC → blockedKey ASC 정렬해 반환한다.
 * - truncated 플래그가 포트 결과에서 그대로 전파된다.
 */
class TimelineApplicationServiceDepsTest {
    private val actorId: UUID = UUID.randomUUID()
    private val projectKey = "PROJ"

    private val permissionResolver: IssuePermissionResolver = mockk()
    private val timelineLookupPort: TimelineLookupPort = mockk()

    private val sut = TimelineApplicationService(permissionResolver, timelineLookupPort)

    // ── helper ────────────────────────────────────────────────────────────────

    /** permissionResolver 가 BROWSE 권한을 [result] 로 응답하도록 설정한다. */
    private fun stubBrowse(result: Boolean) {
        every {
            permissionResolver.hasPermission(
                actorId,
                IssuePermission.BROWSE,
                IssueScope.Project(projectKey),
            )
        } returns result
    }

    /** timelineLookupPort.listBlocksDepsByProject 가 [page] 를 반환하도록 설정한다. */
    private fun stubDepsPage(page: TimelineDepsPage) {
        every {
            timelineLookupPort.listBlocksDepsByProject(projectKey, actorId)
        } returns page
    }

    // ── BROWSE 게이트 거부 → 403 ──────────────────────────────────────────────

    @Test
    fun `getDeps denies without BROWSE - 403 ResponseStatusException 을 던지고 포트를 호출하지 않는다`() {
        stubBrowse(false)

        assertThatThrownBy { sut.getDeps(actorId, projectKey) }
            .isInstanceOf(ResponseStatusException::class.java)
            .satisfies({ ex ->
                assertThat((ex as ResponseStatusException).statusCode)
                    .isEqualTo(HttpStatus.FORBIDDEN)
            })

        verify(exactly = 0) { timelineLookupPort.listBlocksDepsByProject(any(), any()) }
    }

    // ── 정렬 + truncated 전파 ─────────────────────────────────────────────────

    @Test
    fun `getDeps returns sorted edges - blockerKey 후 blockedKey ASC 정렬 및 truncated 전파`() {
        stubBrowse(true)
        stubDepsPage(
            TimelineDepsPage(
                edges =
                    listOf(
                        TimelineDepEdge(blockerKey = "PROJ-3", blockedKey = "PROJ-4"),
                        TimelineDepEdge(blockerKey = "PROJ-1", blockedKey = "PROJ-2"),
                    ),
                truncated = true,
            ),
        )

        val result = sut.getDeps(actorId, projectKey)

        assertThat(result.edges).containsExactly(
            TimelineDepEdge(blockerKey = "PROJ-1", blockedKey = "PROJ-2"),
            TimelineDepEdge(blockerKey = "PROJ-3", blockedKey = "PROJ-4"),
        )
        assertThat(result.truncated).isTrue()
    }
}
