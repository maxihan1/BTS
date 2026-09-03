// 보드 카드 필터 VO 단위 테스트 — isEmpty 논리 검증

package com.bts.shared.board

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [BoardCardFilter] 단위 테스트.
 *
 * Spring 컨텍스트 없이 순수 단위 테스트로 실행한다.
 *
 * 검증 항목.
 * - [BoardCardFilter.EMPTY] 의 [BoardCardFilter.isEmpty] 가 true 임.
 * - 각 필드에 값이 있으면 [BoardCardFilter.isEmpty] 가 false 임.
 */
class BoardCardFilterTest {
    @Test
    fun `EMPTY 싱글턴은 isEmpty 가 true 다`() {
        assertThat(BoardCardFilter.EMPTY.isEmpty()).isTrue()
    }

    @Test
    fun `기본 생성자(모든 기본값)는 isEmpty 가 true 다`() {
        assertThat(BoardCardFilter().isEmpty()).isTrue()
    }

    @Test
    fun `assigneeIds 가 있으면 isEmpty 가 false 다`() {
        val filter = BoardCardFilter(assigneeIds = listOf(UUID.randomUUID()))
        assertThat(filter.isEmpty()).isFalse()
    }

    @Test
    fun `includeUnassigned 가 true 이면 isEmpty 가 false 다`() {
        val filter = BoardCardFilter(includeUnassigned = true)
        assertThat(filter.isEmpty()).isFalse()
    }

    @Test
    fun `labels 가 있으면 isEmpty 가 false 다`() {
        val filter = BoardCardFilter(labels = listOf("bug"))
        assertThat(filter.isEmpty()).isFalse()
    }

    @Test
    fun `componentIds 가 있으면 isEmpty 가 false 다`() {
        val filter = BoardCardFilter(componentIds = listOf(UUID.randomUUID()))
        assertThat(filter.isEmpty()).isFalse()
    }

    @Test
    fun `statusKeys 가 비어있지 않으면 isEmpty 는 false`() {
        val filter = BoardCardFilter(statusKeys = listOf("in_progress"))
        assertThat(filter.isEmpty()).isFalse()
    }

    @Test
    fun `statusKeys 만 있어도(다른 필드 빈 상태) isEmpty 는 false`() {
        val filter =
            BoardCardFilter(
                assigneeIds = emptyList(),
                includeUnassigned = false,
                labels = emptyList(),
                componentIds = emptyList(),
                statusKeys = listOf("done"),
            )
        assertThat(filter.isEmpty()).isFalse()
    }

    @Test
    fun `EMPTY 는 statusKeys 도 빈 목록`() {
        assertThat(BoardCardFilter.EMPTY.statusKeys).isEmpty()
    }

    @Test
    fun `issueKeys 가 비어있지 않으면 isEmpty 는 false`() {
        val filter = BoardCardFilter(issueKeys = listOf("ATLAS-1"))
        assertThat(filter.isEmpty()).isFalse()
    }

    /**
     * ★이 케이스가 `IssueRepository.buildFilterCondition` 의 조기 return 에 대한 **유일한 방어**다.
     *
     * 그 함수는 `if (filter.isEmpty()) return null` 로 시작한다. `isEmpty()` 확장을 빠뜨리면
     * `issueKeys` 만 실은 필터가 「빈 필터」로 판정되어 **술어가 통째로 드롭되고**, 스크럼 보드는
     * 전량 조회로 되돌아간다 — 조회는 성공하므로 어느 테스트도 red 가 되지 않는다.
     * 다른 필드를 명시적으로 비워 두는 것이 이 케이스의 요점이다(`statusKeys` 쌍둥이와 같은 이유).
     */
    @Test
    fun `issueKeys 만 있어도(다른 필드 빈 상태) isEmpty 는 false`() {
        val filter =
            BoardCardFilter(
                assigneeIds = emptyList(),
                includeUnassigned = false,
                labels = emptyList(),
                componentIds = emptyList(),
                statusKeys = emptyList(),
                issueKeys = listOf("ATLAS-42"),
            )
        assertThat(filter.isEmpty()).isFalse()
    }

    @Test
    fun `EMPTY 는 issueKeys 도 빈 목록`() {
        assertThat(BoardCardFilter.EMPTY.issueKeys).isEmpty()
    }
}
