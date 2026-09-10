// 소유 프로젝트 → 권한 스코프 변환 판정 — 전역·프로젝트·fail-closed 세 갈래 (FR-WF-08)

package com.bts.workflow.scheme.application

import com.bts.shared.permission.WorkflowScope
import com.bts.workflow.repository.WorkflowRepository
import com.bts.workflow.scheme.domain.ProjectKey
import com.bts.workflow.scheme.domain.WorkflowScheme
import com.bts.workflow.scheme.domain.WorkflowSchemeId
import com.bts.workflow.scheme.domain.WorkflowSchemeKey
import com.bts.workflow.scheme.port.outbound.ProjectLookupPort
import com.bts.workflow.scheme.repository.WorkflowSchemeRepository
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * [WorkflowOwnershipScopeResolver] 판정 테스트.
 *
 * 이 클래스가 틀리면 **모든 스킴 CRUD 의 권한 스코프가 한꺼번에 틀린다** — 13곳이 여기 하나를
 * 부르기 때문이다. 그래서 「전역이면 Global」 하나로는 부족하고, 소유가 있을 때 Project 로 **바뀌는지**
 * 와 소유를 못 되짚을 때 Global 로 **떨어지는지**(fail-closed)를 함께 잰다.
 *
 * fail-closed 가 왜 판정 대상인가 — 반대로 떨어뜨리면(프로젝트 스코프로) 소유가 불명확한 스킴을
 * 아무 프로젝트 관리자나 만지게 된다. 전역은 SYSTEM_ADMIN 만 통과하므로 가장 좁은 쪽이다.
 */
class WorkflowOwnershipScopeResolverTest {
    private val schemeRepo: WorkflowSchemeRepository = mockk()
    private val workflowRepo: WorkflowRepository = mockk()
    private val projectLookupPort: ProjectLookupPort = mockk()

    private val resolver = WorkflowOwnershipScopeResolver(schemeRepo, workflowRepo, projectLookupPort)

    private val ownerId: UUID = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001")

    // ── 스킴 소유 ──────────────────────────────────────────────────────────────

    @Test
    fun `ofScheme — 전역 스킴은 Global 이다`() {
        val key = WorkflowSchemeKey("software-scheme")
        every { schemeRepo.findByKey(key) } returns scheme(key, projectId = null)

        assertThat(resolver.ofScheme("software-scheme")).isEqualTo(WorkflowScope.Global)
    }

    @Test
    fun `ofScheme — 프로젝트 소유 스킴은 그 프로젝트 스코프다`() {
        val key = WorkflowSchemeKey("atlas-scheme")
        every { schemeRepo.findByKey(key) } returns scheme(key, projectId = ownerId)
        every { projectLookupPort.findKeyById(ownerId) } returns ProjectKey("ATLAS")

        assertThat(resolver.ofScheme("atlas-scheme")).isEqualTo(WorkflowScope.Project("ATLAS"))
    }

    @Test
    fun `ofScheme — 없는 스킴은 Global 이다 (fail-closed)`() {
        val key = WorkflowSchemeKey("ghost-scheme")
        every { schemeRepo.findByKey(key) } returns null

        assertThat(resolver.ofScheme("ghost-scheme")).isEqualTo(WorkflowScope.Global)
    }

    @Test
    fun `ofScheme — 키 형식이 어긋나면 저장소를 묻지 않고 Global 이다`() {
        // 예외를 던지면 권한 판정 전에 400 이 나가 순서 계약이 깨진다. schemeRepo 는 스텁이 없으므로
        // 이 판정이 저장소를 부르면 MockKException 으로 죽는다 — 「묻지 않는다」까지 재는 셈이다.
        assertThat(resolver.ofScheme("Invalid Key!")).isEqualTo(WorkflowScope.Global)
    }

    @Test
    fun `ofScheme — 소유 프로젝트 행을 못 되짚으면 Global 이다 (fail-closed)`() {
        val key = WorkflowSchemeKey("orphan-scheme")
        every { schemeRepo.findByKey(key) } returns scheme(key, projectId = ownerId)
        every { projectLookupPort.findKeyById(ownerId) } returns null

        assertThat(resolver.ofScheme("orphan-scheme")).isEqualTo(WorkflowScope.Global)
    }

    // ── 워크플로우 소유 ────────────────────────────────────────────────────────

    @Test
    fun `ofWorkflow — 전역 워크플로우는 Global 이다`() {
        every { workflowRepo.findProjectIdByKey("software-default") } returns null

        assertThat(resolver.ofWorkflow("software-default")).isEqualTo(WorkflowScope.Global)
    }

    @Test
    fun `ofWorkflow — 프로젝트 소유 워크플로우는 그 프로젝트 스코프다`() {
        every { workflowRepo.findProjectIdByKey("atlas-flow") } returns ownerId
        every { projectLookupPort.findKeyById(ownerId) } returns ProjectKey("ATLAS")

        assertThat(resolver.ofWorkflow("atlas-flow")).isEqualTo(WorkflowScope.Project("ATLAS"))
    }

    // ── 생성 경로 ──────────────────────────────────────────────────────────────

    @Test
    fun `ofProjectKey — 요청이 프로젝트를 지목하면 그 스코프다`() {
        assertThat(resolver.ofProjectKey("ATLAS")).isEqualTo(WorkflowScope.Project("ATLAS"))
    }

    @Test
    fun `ofProjectKey — 지목이 없으면 전역 생성이다`() {
        assertThat(resolver.ofProjectKey(null)).isEqualTo(WorkflowScope.Global)
    }

    private fun scheme(
        key: WorkflowSchemeKey,
        projectId: UUID?,
    ): WorkflowScheme {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        return WorkflowScheme.reconstruct(
            id = WorkflowSchemeId(1L),
            key = key,
            name = key.value,
            description = null,
            isDefault = false,
            projectId = projectId,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
        )
    }
}
