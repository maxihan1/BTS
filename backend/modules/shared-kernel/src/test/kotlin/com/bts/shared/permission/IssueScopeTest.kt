// IssuePermission enum + IssueScope sealed interface 단위 테스트 — enum 6종, 3 scope 인스턴스/equality 검증

package com.bts.shared.permission

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [IssuePermission] 및 [IssueScope] 단위 테스트.
 *
 * Spring 컨텍스트 없이 순수 단위 테스트로 실행한다.
 * 테스트 케이스.
 * - global_singleton — [IssueScope.Global] 은 data object 이므로 참조 동일성이 보장된다.
 * - project_equality — [IssueScope.Project] 동일 key 이면 equals true.
 * - issue_equality — [IssueScope.Issue] 동일 key 이면 equals true.
 * - permission_entries_size — [IssuePermission] 항목 수 6개 (VIEW, CREATE, UPDATE, TRANSITION, SOFT_DELETE, HARD_DELETE).
 */
class IssueScopeTest {
    @Test
    fun `global_singleton — IssueScope Global 은 같은 인스턴스다`() {
        assertThat(IssueScope.Global).isSameAs(IssueScope.Global)
        assertThat(IssueScope.Global).isEqualTo(IssueScope.Global)
    }

    @Test
    fun `project_equality — 같은 key 의 Project 는 equals true 다`() {
        val a = IssueScope.Project("ATLAS")
        val b = IssueScope.Project("ATLAS")

        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    @Test
    fun `issue_equality — 같은 key 의 Issue 는 equals true 다`() {
        val a = IssueScope.Issue("ATLAS-1")
        val b = IssueScope.Issue("ATLAS-1")

        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    @Test
    fun `permission_entries_size — IssuePermission 항목 수는 6이다`() {
        assertThat(IssuePermission.entries).hasSize(6)
    }
}
