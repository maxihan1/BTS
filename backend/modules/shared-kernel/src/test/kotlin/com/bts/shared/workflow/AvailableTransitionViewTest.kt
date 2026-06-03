// AvailableTransitionView toCategory nullable additive 필드 검증 테스트

package com.bts.shared.workflow

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * [AvailableTransitionView] published language 표면 계약 테스트.
 *
 * toCategory 필드가 nullable additive 로 추가됨을 검증한다.
 * 기존 3-인자 생성 지점(WorkflowEngine 등)이 무수정 컴파일되어야 하며,
 * 명시 생성 시 카테고리 값이 올바르게 보관되어야 한다.
 */
class AvailableTransitionViewTest {

    @Test
    fun `toCategory 명시 생성 — 전달한 카테고리 문자열을 보관한다`() {
        val view = AvailableTransitionView(
            fromStateKey = "open",
            toStateKey = "done",
            name = "완료로 전환",
            toCategory = "DONE",
        )

        assertEquals("DONE", view.toCategory)
    }

    @Test
    fun `toCategory 기본값 — 3-인자 생성 시 null 이다`() {
        // 기존 생성 지점(WorkflowEngine.kt:212 등)이 무수정 컴파일되는 계약 검증
        val view = AvailableTransitionView(
            fromStateKey = "open",
            toStateKey = "in-progress",
            name = "진행 중으로 전환",
        )

        assertNull(view.toCategory)
    }

    @Test
    fun `toCategory 다양한 카테고리 문자열 — IN_PROGRESS 저장`() {
        val view = AvailableTransitionView(
            fromStateKey = "open",
            toStateKey = "in-progress",
            name = "진행 중으로 전환",
            toCategory = "IN_PROGRESS",
        )

        assertEquals("IN_PROGRESS", view.toCategory)
    }

    @Test
    fun `toCategory 다양한 카테고리 문자열 — TODO 저장`() {
        val view = AvailableTransitionView(
            fromStateKey = "in-progress",
            toStateKey = "open",
            name = "열림으로 되돌리기",
            toCategory = "TODO",
        )

        assertEquals("TODO", view.toCategory)
    }
}
