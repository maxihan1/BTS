// SlashIssueSearchPort default(fail-safe) 동작 단위 테스트

package com.bts.shared.search

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [SlashIssueSearchPort] default 구현을 검증하는 단위 테스트.
 *
 * 어댑터(search-export-import BC 구현체)가 등록되지 않은 환경에서
 * default 구현이 데이터 누출 없이 빈 페이지를 담은 [SlashSearchOutcome.Success] 를
 * 반환하는지 확인한다.
 */
class SlashIssueSearchPortTest {
    @Test
    fun `default implementation returns Success with empty page without data leakage`() {
        // 어댑터 미등록 상황을 재현 — 인터페이스의 default 메서드만 사용
        val port: SlashIssueSearchPort = object : SlashIssueSearchPort {}
        val query =
            SlashSearchQuery(
                rawAql = "status = open",
                projectKey = "PROJ",
                viewerUserId = UUID.randomUUID(),
                page = 0,
                size = 50,
            )

        val outcome = port.search(query)

        assertThat(outcome).isInstanceOf(SlashSearchOutcome.Success::class.java)
        val success = outcome as SlashSearchOutcome.Success
        assertThat(success.page.items).isEmpty()
        assertThat(success.page.total).isEqualTo(0L)
        assertThat(success.page.page).isEqualTo(query.page)
        assertThat(success.page.size).isEqualTo(query.size)
    }

    @Test
    fun `default implementation preserves page and size from query`() {
        val port: SlashIssueSearchPort = object : SlashIssueSearchPort {}
        val query =
            SlashSearchQuery(
                rawAql = "priority = 1",
                projectKey = "PROJ",
                viewerUserId = UUID.randomUUID(),
                page = 2,
                size = 20,
            )

        val outcome = port.search(query)

        val success = outcome as SlashSearchOutcome.Success
        assertThat(success.page.page).isEqualTo(2)
        assertThat(success.page.size).isEqualTo(20)
    }
}
