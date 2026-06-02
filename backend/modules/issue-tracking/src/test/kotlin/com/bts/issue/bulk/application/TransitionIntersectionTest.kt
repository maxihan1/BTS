// 여러 이슈의 가용 전이 교집합 로직을 검증하는 단위 테스트

package com.bts.issue.bulk.application

import com.bts.shared.workflow.AvailableTransitionView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("TransitionIntersection.intersect")
class TransitionIntersectionTest {

    /** 테스트용 뷰 생성 헬퍼. */
    private fun view(from: String, to: String, name: String = "전이-$to") =
        AvailableTransitionView(fromStateKey = from, toStateKey = to, name = name)

    // ────────────────────────────────────────────────
    // 공통 있음
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("두 이슈에 공통 toStateKey 가 존재하면 해당 전이를 반환한다")
    fun `두 이슈 공통 toStateKey 반환`() {
        val issue1 = listOf(view("open", "in-progress"), view("open", "done"))
        val issue2 = listOf(view("open", "in-progress"), view("open", "closed"))

        val result: List<AvailableTransitionView> = TransitionIntersection.intersect(listOf(issue1, issue2))

        assertEquals(1, result.size)
        assertEquals("in-progress", result[0].toStateKey)
    }

    // ────────────────────────────────────────────────
    // 공통 없음
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("공통 toStateKey 가 없으면 빈 목록을 반환한다")
    fun `공통 없으면 빈 목록`() {
        val issue1 = listOf(view("open", "done"))
        val issue2 = listOf(view("open", "closed"))

        val result: List<AvailableTransitionView> = TransitionIntersection.intersect(listOf(issue1, issue2))

        assertEquals(emptyList<AvailableTransitionView>(), result)
    }

    // ────────────────────────────────────────────────
    // 한 이슈 빈 목록
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("이슈 중 하나가 빈 전이 목록을 가지면 교집합은 빈 목록이다")
    fun `한 이슈 빈 목록이면 빈 결과`() {
        val issue1 = listOf(view("open", "done"))
        val issue2 = emptyList<AvailableTransitionView>()

        val result: List<AvailableTransitionView> = TransitionIntersection.intersect(listOf(issue1, issue2))

        assertEquals(emptyList<AvailableTransitionView>(), result)
    }

    // ────────────────────────────────────────────────
    // 단일 이슈 (dedup 적용)
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("이슈가 하나이면 dedup 을 적용한 자기 자신 목록을 반환한다")
    fun `단일 이슈 dedup 적용`() {
        val issue1 = listOf(
            view("open", "done", "완료"),
            view("open", "done", "중복완료"),  // 동일 toStateKey — 첫 번째만 남아야 함
            view("open", "in-progress"),
        )

        val result: List<AvailableTransitionView> = TransitionIntersection.intersect(listOf(issue1))

        assertEquals(2, result.size)
        assertEquals("done", result[0].toStateKey)
        assertEquals("완료", result[0].name)           // 첫 이슈의 name 채택
        assertEquals("in-progress", result[1].toStateKey)
    }

    // ────────────────────────────────────────────────
    // 빈 입력
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("perIssue 가 빈 리스트이면 빈 목록을 반환한다")
    fun `빈 입력이면 빈 목록`() {
        val result: List<AvailableTransitionView> = TransitionIntersection.intersect(emptyList())

        assertEquals(emptyList<AvailableTransitionView>(), result)
    }

    // ────────────────────────────────────────────────
    // C4 — 동일 toStateKey 인데 이슈마다 name 이 다를 때 첫 이슈 채택
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("동일 toStateKey 에 대해 이슈마다 name 이 달라도 첫 이슈의 name/fromStateKey 를 채택한다")
    fun `C4 첫 이슈 name 채택`() {
        val issue1 = listOf(view("open", "done", "완료처리"))
        val issue2 = listOf(view("reviewing", "done", "검토완료"))

        val result: List<AvailableTransitionView> = TransitionIntersection.intersect(listOf(issue1, issue2))

        assertEquals(1, result.size)
        assertEquals("done", result[0].toStateKey)
        assertEquals("open", result[0].fromStateKey)   // 첫 이슈 기준
        assertEquals("완료처리", result[0].name)         // 첫 이슈 기준
    }

    // ────────────────────────────────────────────────
    // 결과 순서 = 첫 이슈 등장 순서
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("교집합 결과 순서는 첫 이슈에 등장한 순서를 따른다")
    fun `결과 순서는 첫 이슈 순서`() {
        val issue1 = listOf(
            view("open", "done"),
            view("open", "in-progress"),
            view("open", "closed"),
        )
        val issue2 = listOf(
            view("open", "closed"),
            view("open", "in-progress"),
            view("open", "done"),
        )

        val result: List<AvailableTransitionView> = TransitionIntersection.intersect(listOf(issue1, issue2))

        assertEquals(listOf("done", "in-progress", "closed"), result.map { it.toStateKey })
    }
}
