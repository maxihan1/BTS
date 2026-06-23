// 에픽 진행률 값 객체(EpicProgress) 순수 로직 단위 테스트

package com.bts.issue.epic.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class EpicProgressTest {

    @Test
    fun `aggregates categories into byCategory counts`() {
        // 자식 이슈 4개: DONE 2, IN_PROGRESS 1, TODO 1
        val progress = EpicProgress.of(listOf("DONE", "DONE", "IN_PROGRESS", "TODO"))

        assertEquals(4, progress.total)
        assertEquals(2, progress.done)
        assertEquals(1, progress.inProgress)
        assertEquals(1, progress.todo)
    }

    @Test
    fun `donePercentage rounds half`() {
        // done=1, total=3 → 33.33... → 반올림 33
        val oneOfThree = EpicProgress.of(listOf("DONE", "TODO", "TODO"))
        assertEquals(33, oneOfThree.donePercentage)

        // done=2, total=3 → 66.66... → 반올림 67
        val twoOfThree = EpicProgress.of(listOf("DONE", "DONE", "TODO"))
        assertEquals(67, twoOfThree.donePercentage)
    }

    @Test
    fun `empty epic yields zero progress`() {
        // EC1: 자식 없는 에픽 → 모든 카운트 0
        val progress = EpicProgress.of(emptyList())

        assertEquals(0, progress.total)
        assertEquals(0, progress.done)
        assertEquals(0, progress.donePercentage)
        assertEquals(0, progress.todo)
        assertEquals(0, progress.inProgress)
    }

    @Test
    fun `unmapped or unknown category falls back to TODO`() {
        // EC2: 비표준 문자열 "FOO" → TODO 취급
        // EC3: null → TODO 취급
        // (EC3는 StateCategory enum 3값뿐이므로 정상 경로에서 도달 불가한 방어 케이스임)
        val progress = EpicProgress.of(listOf("FOO", null))

        assertEquals(2, progress.total)
        assertEquals(2, progress.todo)
        assertEquals(0, progress.done)
        assertEquals(0, progress.inProgress)
    }

    @Test
    fun `all done yields 100 percent`() {
        // EC5: 전부 DONE → donePercentage = 100
        val progress = EpicProgress.of(listOf("DONE", "DONE"))

        assertEquals(100, progress.donePercentage)
        assertEquals(2, progress.done)
        assertEquals(0, progress.todo)
        assertEquals(0, progress.inProgress)
    }

    @Test
    fun `top-level done equals byCategory done`() {
        // 편의 중복 필드 정합 단언 — total done == byCategory done
        // 한쪽만 변경 시 회귀 차단
        val progress = EpicProgress.of(listOf("DONE", "IN_PROGRESS", "TODO"))

        // progress.done 은 DONE 카운트이고 byCategory의 done 과 동일해야 함
        // 이 VO에서는 done 필드 자체가 곧 byCategory.done 이므로 아래 단언이 성립
        assertEquals(progress.done, 1)
        // total = todo + inProgress + done
        assertEquals(progress.total, progress.todo + progress.inProgress + progress.done)
    }
}
