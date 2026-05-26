// 워크플로우 스킴 도메인 예외 7종 인스턴스화 + message 포맷 검증

package com.bts.workflow.scheme.exception

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [WorkflowSchemeDomainException] sealed 계층 7 sub-class 검증.
 *
 * 검증 대상.
 * 1. [WorkflowSchemeNotFoundException] — key 필드 + 메시지 포맷
 * 2. [SchemeInUseException] — usedByProjects 필드 + count 메시지 포맷
 * 3. [SchemeStandardNotDeletableException] — key 필드 + 메시지 포맷
 * 4. [SchemeStandardFieldLockedException] — key / field 필드 + 메시지 포맷
 * 5. [MappingDuplicateException] — schemeKey / issueTypeKey 필드 + 메시지 포맷
 * 6. [MappingDefaultDuplicateException] — schemeKey 필드 + 메시지 포맷
 * 7. [WorkflowSchemeNoDefaultException] — schemeKey 필드 + 메시지 포맷
 */
class WorkflowSchemeExceptionsTest {
    // ── 공통 — sealed 상속 체인 ───────────────────────────────────────────────

    @Test
    fun `모든 예외는 WorkflowSchemeDomainException 상속`() {
        val exceptions: List<WorkflowSchemeDomainException> = listOf(
            WorkflowSchemeNotFoundException(key = "SCM-1"),
            SchemeInUseException(usedByProjects = listOf(1L, 2L, 3L)),
            SchemeStandardNotDeletableException(key = "DEFAULT"),
            SchemeStandardFieldLockedException(key = "DEFAULT", field = "name"),
            MappingDuplicateException(schemeKey = "SCM-1", issueTypeKey = "BUG"),
            MappingDefaultDuplicateException(schemeKey = "SCM-1"),
            WorkflowSchemeNoDefaultException(schemeKey = "SCM-1"),
        )
        exceptions.forEach { ex ->
            assertThat(ex).isInstanceOf(WorkflowSchemeDomainException::class.java)
            assertThat(ex).isInstanceOf(RuntimeException::class.java)
        }
    }

    // ── WorkflowSchemeNotFoundException ──────────────────────────────────────

    @Test
    fun `WorkflowSchemeNotFoundException key 필드 + 메시지에 key 포함`() {
        val ex = WorkflowSchemeNotFoundException(key = "SCM-MISSING")

        assertThat(ex.key).isEqualTo("SCM-MISSING")
        assertThat(ex.message).isEqualTo("WorkflowScheme not found: SCM-MISSING")
    }

    // ── SchemeInUseException ─────────────────────────────────────────────────

    @Test
    fun `SchemeInUseException usedByProjects 필드 + 메시지에 count 포함`() {
        val projects = listOf(10L, 20L, 30L)
        val ex = SchemeInUseException(usedByProjects = projects)

        assertThat(ex.usedByProjects).isEqualTo(projects)
        assertThat(ex.message).isEqualTo("Scheme in use by 3 projects")
    }

    @Test
    fun `SchemeInUseException 단일 project 1건`() {
        val ex = SchemeInUseException(usedByProjects = listOf(42L))

        assertThat(ex.message).isEqualTo("Scheme in use by 1 projects")
    }

    // ── SchemeStandardNotDeletableException ──────────────────────────────────

    @Test
    fun `SchemeStandardNotDeletableException key 필드 + 메시지 포맷`() {
        val ex = SchemeStandardNotDeletableException(key = "DEFAULT")

        assertThat(ex.key).isEqualTo("DEFAULT")
        assertThat(ex.message).isEqualTo("Standard scheme not deletable: DEFAULT")
    }

    // ── SchemeStandardFieldLockedException ───────────────────────────────────

    @Test
    fun `SchemeStandardFieldLockedException key, field 필드 + 메시지 포맷`() {
        val ex = SchemeStandardFieldLockedException(key = "DEFAULT", field = "name")

        assertThat(ex.key).isEqualTo("DEFAULT")
        assertThat(ex.field).isEqualTo("name")
        assertThat(ex.message).isEqualTo("Standard scheme field locked: DEFAULT.name")
    }

    @Test
    fun `SchemeStandardFieldLockedException is_default 필드도 lock 대상`() {
        val ex = SchemeStandardFieldLockedException(key = "DEFAULT", field = "is_default")

        assertThat(ex.message).isEqualTo("Standard scheme field locked: DEFAULT.is_default")
    }

    // ── MappingDuplicateException ────────────────────────────────────────────

    @Test
    fun `MappingDuplicateException schemeKey, issueTypeKey 필드 + 메시지 포맷`() {
        val ex = MappingDuplicateException(schemeKey = "SCM-1", issueTypeKey = "BUG")

        assertThat(ex.schemeKey).isEqualTo("SCM-1")
        assertThat(ex.issueTypeKey).isEqualTo("BUG")
        assertThat(ex.message).isEqualTo("Mapping duplicate: SCM-1/BUG")
    }

    // ── MappingDefaultDuplicateException ─────────────────────────────────────

    @Test
    fun `MappingDefaultDuplicateException schemeKey 필드 + 메시지 포맷`() {
        val ex = MappingDefaultDuplicateException(schemeKey = "SCM-1")

        assertThat(ex.schemeKey).isEqualTo("SCM-1")
        assertThat(ex.message).isEqualTo("Default mapping duplicate for: SCM-1")
    }

    // ── WorkflowSchemeNoDefaultException ─────────────────────────────────────

    @Test
    fun `WorkflowSchemeNoDefaultException schemeKey 필드 + 메시지 포맷`() {
        val ex = WorkflowSchemeNoDefaultException(schemeKey = "SCM-1")

        assertThat(ex.schemeKey).isEqualTo("SCM-1")
        assertThat(ex.message).isEqualTo("No default mapping for: SCM-1")
    }
}
