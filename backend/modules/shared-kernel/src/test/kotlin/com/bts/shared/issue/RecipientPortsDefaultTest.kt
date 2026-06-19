// 수신자 포트(IssueRecipients 확장 · ProjectRecipientLookupPort · IssueVisibilityPort) 단위 테스트

package com.bts.shared.issue

import com.bts.shared.permission.IssueVisibilityPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Task 1 RED/GREEN 검증 — 수신자 포트 확장 단위 테스트.
 *
 * (a) [IssueRecipients] 가 watcherIds / componentLeadIds / previousAssigneeId 필드를 보유하고
 *     [IssueRecipients.empty] 가 빈 리스트/null 을 반환하는지 검증한다.
 * (b) [ProjectRecipientLookupPort] default 가 [ProjectRecipients.empty] 를 반환하는지 검증한다.
 * (c) [IssueVisibilityPort] 가 abstract method 임을 컴파일 타임에 강제하는지 확인한다
 *     (default 없음 — fail-closed 안전망).
 *
 * Spring 컨텍스트 없이 순수 단위 테스트로 실행한다.
 */
class RecipientPortsDefaultTest {
    // ── (a) IssueRecipients 필드 확장 ──────────────────────────────────────────

    @Test
    fun `IssueRecipients empty 는 watcherIds 가 빈 리스트다`() {
        val result = IssueRecipients.empty()
        assertThat(result.watcherIds).isEmpty()
    }

    @Test
    fun `IssueRecipients empty 는 componentLeadIds 가 빈 리스트다`() {
        val result = IssueRecipients.empty()
        assertThat(result.componentLeadIds).isEmpty()
    }

    @Test
    fun `IssueRecipients empty 는 previousAssigneeId 가 null 이다`() {
        val result = IssueRecipients.empty()
        assertThat(result.previousAssigneeId).isNull()
    }

    @Test
    fun `IssueRecipients 는 watcherIds 를 보유한다`() {
        val id = UUID.randomUUID()
        val recipients =
            IssueRecipients(
                reporterId = null,
                assigneeId = null,
                watcherIds = listOf(id),
                componentLeadIds = emptyList(),
                previousAssigneeId = null,
            )
        assertThat(recipients.watcherIds).containsExactly(id)
    }

    @Test
    fun `IssueRecipients 는 componentLeadIds 를 보유한다`() {
        val id = UUID.randomUUID()
        val recipients =
            IssueRecipients(
                reporterId = null,
                assigneeId = null,
                watcherIds = emptyList(),
                componentLeadIds = listOf(id),
                previousAssigneeId = null,
            )
        assertThat(recipients.componentLeadIds).containsExactly(id)
    }

    @Test
    fun `IssueRecipients 는 previousAssigneeId 를 보유한다`() {
        val id = UUID.randomUUID()
        val recipients =
            IssueRecipients(
                reporterId = null,
                assigneeId = null,
                watcherIds = emptyList(),
                componentLeadIds = emptyList(),
                previousAssigneeId = id,
            )
        assertThat(recipients.previousAssigneeId).isEqualTo(id)
    }

    // ── (b) ProjectRecipientLookupPort default ─────────────────────────────────

    @Test
    fun `ProjectRecipientLookupPort default 는 빈 ProjectRecipients 를 반환한다`() {
        val port = object : ProjectRecipientLookupPort {}
        val result = port.findProjectRecipients("PROJ")
        assertThat(result.memberIds).isEmpty()
        assertThat(result.adminIds).isEmpty()
    }

    @Test
    fun `ProjectRecipients empty 는 memberIds 가 빈 리스트다`() {
        val result = ProjectRecipients.empty()
        assertThat(result.memberIds).isEmpty()
    }

    @Test
    fun `ProjectRecipients empty 는 adminIds 가 빈 리스트다`() {
        val result = ProjectRecipients.empty()
        assertThat(result.adminIds).isEmpty()
    }

    // ── (c) IssueVisibilityPort — abstract 메서드로 구현 강제 ──────────────────

    @Test
    fun `IssueVisibilityPort 구현체는 filterVisibleUserIds 를 반드시 구현해야 한다`() {
        val candidateId = UUID.randomUUID()
        val port =
            object : IssueVisibilityPort {
                override fun filterVisibleUserIds(
                    issueKey: String,
                    candidateUserIds: Set<UUID>,
                ): Set<UUID> = candidateUserIds
            }
        val result = port.filterVisibleUserIds("PROJ-1", setOf(candidateId))
        assertThat(result).containsExactly(candidateId)
    }
}
