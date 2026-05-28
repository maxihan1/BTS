// WorkflowSchemeId VO 단위 테스트
package com.bts.workflow.scheme.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class WorkflowSchemeIdTest {
    @Test
    fun `WorkflowSchemeId 인스턴스화 및 value 접근`() {
        val id = WorkflowSchemeId(42L)
        assertEquals(42L, id.value)
    }

    @Test
    fun `동일한 Long 값은 equals 가 true`() {
        val a = WorkflowSchemeId(1L)
        val b = WorkflowSchemeId(1L)
        assertEquals(a, b)
    }

    @Test
    fun `다른 Long 값은 equals 가 false`() {
        val a = WorkflowSchemeId(1L)
        val b = WorkflowSchemeId(2L)
        assertNotEquals(a, b)
    }

    @Test
    fun `동일한 Long 값은 hashCode 가 동일`() {
        val a = WorkflowSchemeId(99L)
        val b = WorkflowSchemeId(99L)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
