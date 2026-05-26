// WorkflowSchemeApplicationService 단위 테스트 — Mockito mock Repository + AlwaysAllow stub

package com.bts.workflow.scheme.application

import com.bts.workflow.port.outbound.ActorId
import com.bts.workflow.scheme.adapter.outbound.AlwaysAllowWorkflowSchemePermissionResolver
import com.bts.workflow.scheme.domain.WorkflowScheme
import com.bts.workflow.scheme.domain.WorkflowSchemeId
import com.bts.workflow.scheme.domain.WorkflowSchemeKey
import com.bts.workflow.scheme.exception.SchemeInUseException
import com.bts.workflow.scheme.exception.SchemeStandardFieldLockedException
import com.bts.workflow.scheme.exception.SchemeStandardNotDeletableException
import com.bts.workflow.scheme.exception.WorkflowSchemeNotFoundException
import com.bts.workflow.scheme.repository.ProjectWorkflowSchemeAssignmentRepository
import com.bts.workflow.scheme.repository.WorkflowSchemeRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import java.time.Instant

/**
 * [WorkflowSchemeApplicationService] 단위 테스트.
 *
 * 트랜잭션 AOP 가 없는 순수 단위 테스트.
 * [WorkflowSchemeRepository] / [ProjectWorkflowSchemeAssignmentRepository] 는 Mockito stub 으로 대체한다.
 * 권한 검증은 [AlwaysAllowWorkflowSchemePermissionResolver] stub 을 직접 주입해 항상 통과시킨다.
 */
@ExtendWith(MockitoExtension::class)
class WorkflowSchemeApplicationServiceTest {

    @Mock
    private lateinit var schemeRepo: WorkflowSchemeRepository

    @Mock
    private lateinit var assignmentRepo: ProjectWorkflowSchemeAssignmentRepository

    private val permissionResolver = AlwaysAllowWorkflowSchemePermissionResolver()

    private lateinit var service: WorkflowSchemeApplicationService

    private val actor = ActorId("test-user")

    @BeforeEach
    fun setUp() {
        service = WorkflowSchemeApplicationService(schemeRepo, assignmentRepo, permissionResolver)
    }

    // ── create ────────────────────────────────────────────────────────────────

    @Test
    fun `create — 정상 입력 시 save 호출 후 저장된 스킴을 반환한다`() {
        val key = WorkflowSchemeKey("software-scheme")
        val expected = buildScheme(key, isDefault = false)
        `when`(schemeRepo.save(org.mockito.ArgumentMatchers.any())).thenReturn(expected)

        val result = service.create(actor, key, "Software Scheme", null, isDefault = false)

        assertThat(result.key).isEqualTo(key)
        verify(schemeRepo).save(org.mockito.ArgumentMatchers.any())
    }

    // ── find ──────────────────────────────────────────────────────────────────

    @Test
    fun `find — 존재하는 key 조회 시 스킴을 반환한다`() {
        val key = WorkflowSchemeKey("software-scheme")
        val scheme = buildScheme(key, isDefault = false)
        `when`(schemeRepo.findByKey(key)).thenReturn(scheme)

        val result = service.find(key)

        assertThat(result.key).isEqualTo(key)
    }

    @Test
    fun `find — 존재하지 않는 key 조회 시 WorkflowSchemeNotFoundException 을 던진다`() {
        val key = WorkflowSchemeKey("unknown-key")
        `when`(schemeRepo.findByKey(key)).thenReturn(null)

        assertThatThrownBy { service.find(key) }
            .isInstanceOf(WorkflowSchemeNotFoundException::class.java)
    }

    // ── update ────────────────────────────────────────────────────────────────

    @Test
    fun `update — 일반 스킴의 name 변경 시 update 호출 후 변경된 스킴을 반환한다`() {
        val key = WorkflowSchemeKey("software-scheme")
        val existing = buildScheme(key, isDefault = false)
        val updated = buildScheme(key, name = "Updated Name", isDefault = false)
        `when`(schemeRepo.findByKey(key)).thenReturn(existing)
        `when`(schemeRepo.update(org.mockito.ArgumentMatchers.any())).thenReturn(updated)

        val result = service.update(actor, key, newName = "Updated Name", newDescription = null, newIsDefault = false)

        assertThat(result.name).isEqualTo("Updated Name")
        verify(schemeRepo).update(org.mockito.ArgumentMatchers.any())
    }

    @Test
    fun `update — 표준 스킴의 name 변경 시 SchemeStandardFieldLockedException 을 던진다`() {
        val key = WorkflowSchemeKey("default-scheme")
        val standardScheme = buildScheme(key, isDefault = true)
        `when`(schemeRepo.findByKey(key)).thenReturn(standardScheme)

        assertThatThrownBy {
            service.update(actor, key, newName = "Hacked Name", newDescription = null, newIsDefault = true)
        }.isInstanceOf(SchemeStandardFieldLockedException::class.java)
    }

    @Test
    fun `update — 표준 스킴의 isDefault 변경 시 SchemeStandardFieldLockedException 을 던진다`() {
        val key = WorkflowSchemeKey("default-scheme")
        val standardScheme = buildScheme(key, isDefault = true)
        `when`(schemeRepo.findByKey(key)).thenReturn(standardScheme)

        assertThatThrownBy {
            service.update(actor, key, newName = "Default Scheme", newDescription = null, newIsDefault = false)
        }.isInstanceOf(SchemeStandardFieldLockedException::class.java)
    }

    // ── softDelete ────────────────────────────────────────────────────────────

    @Test
    fun `softDelete — 사용 중이지 않은 일반 스킴 삭제 시 softDelete 호출된다`() {
        val key = WorkflowSchemeKey("software-scheme")
        val scheme = buildScheme(key, isDefault = false, id = WorkflowSchemeId(1L))
        `when`(schemeRepo.findByKey(key)).thenReturn(scheme)
        `when`(assignmentRepo.existsBySchemeId(WorkflowSchemeId(1L))).thenReturn(false)

        service.softDelete(actor, key)

        verify(schemeRepo).softDelete(WorkflowSchemeId(1L))
    }

    @Test
    fun `S6 — softDelete 표준 스킴 시 SchemeStandardNotDeletableException 을 던진다`() {
        val key = WorkflowSchemeKey("default-scheme")
        val standardScheme = buildScheme(key, isDefault = true, id = WorkflowSchemeId(1L))
        `when`(schemeRepo.findByKey(key)).thenReturn(standardScheme)

        assertThatThrownBy { service.softDelete(actor, key) }
            .isInstanceOf(SchemeStandardNotDeletableException::class.java)
    }

    @Test
    fun `S7 — softDelete 사용 중인 스킴 시 SchemeInUseException 을 던진다`() {
        val key = WorkflowSchemeKey("software-scheme")
        val scheme = buildScheme(key, isDefault = false, id = WorkflowSchemeId(2L))
        `when`(schemeRepo.findByKey(key)).thenReturn(scheme)
        `when`(assignmentRepo.existsBySchemeId(WorkflowSchemeId(2L))).thenReturn(true)

        assertThatThrownBy { service.softDelete(actor, key) }
            .isInstanceOf(SchemeInUseException::class.java)
    }

    // ── list ──────────────────────────────────────────────────────────────────

    @Test
    fun `list — findAll 결과를 그대로 반환한다`() {
        val schemes = listOf(
            buildScheme(WorkflowSchemeKey("software-scheme"), isDefault = false),
            buildScheme(WorkflowSchemeKey("service-desk"), isDefault = false),
        )
        `when`(schemeRepo.findAll()).thenReturn(schemes)

        val result = service.list()

        assertThat(result).hasSize(2)
        assertThat(result.map { it.key.value }).containsExactly("software-scheme", "service-desk")
        verify(schemeRepo).findAll()
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private fun buildScheme(
        key: WorkflowSchemeKey,
        name: String = key.value,
        isDefault: Boolean = false,
        id: WorkflowSchemeId? = null,
    ): WorkflowScheme {
        val now = Instant.now()
        return WorkflowScheme.reconstruct(
            id = id ?: WorkflowSchemeId(1L),
            key = key,
            name = name,
            description = null,
            isDefault = isDefault,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
        )
    }
}
