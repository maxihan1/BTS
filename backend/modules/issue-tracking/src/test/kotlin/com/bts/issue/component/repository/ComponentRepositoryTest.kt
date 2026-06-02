// ComponentRepository Testcontainers 통합테스트 — insert/find/softDelete/유니크 위반/삭제 후 재생성
package com.bts.issue.component.repository

import com.bts.issue.component.domain.Component
import com.bts.issue.repository.IssueTestcontainersBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.util.UUID

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ComponentRepositoryTest : IssueTestcontainersBase() {

    private lateinit var componentRepository: ComponentRepository

    @BeforeEach
    fun setUpComponentRepository() {
        componentRepository = ComponentRepository(dsl)
        // 각 테스트마다 components 초기화
        dsl.execute("DELETE FROM components")
    }

    // ── T1. insert + findById(소속+활성 조건) ─────────────────────────────────

    @Test
    @Order(1)
    fun `insert 후 findById 로 조회되어야 한다`() {
        val component = Component.create(
            projectId = testProjectId,
            name = "Auth",
            description = "인증 모듈",
            leadUserId = UUID.randomUUID(),
        )

        val saved = componentRepository.insert(component)

        assertThat(saved.id).isNotNull()
        assertThat(saved.name).isEqualTo("Auth")
        assertThat(saved.description).isEqualTo("인증 모듈")
        assertThat(saved.projectId).isEqualTo(testProjectId)
        assertThat(saved.deletedAt).isNull()

        val found = componentRepository.findById(saved.id!!, testProjectId)
        assertThat(found).isNotNull()
        assertThat(found!!.id).isEqualTo(saved.id)
        assertThat(found.name).isEqualTo("Auth")
    }

    @Test
    @Order(2)
    fun `다른 프로젝트 id 로 findById 하면 null 을 반환해야 한다`() {
        val component = Component.create(projectId = testProjectId, name = "UI")
        val saved = componentRepository.insert(component)

        val otherProjectId = UUID.randomUUID()
        val found = componentRepository.findById(saved.id!!, otherProjectId)

        assertThat(found).isNull()
    }

    // ── T2. findByProject — 활성, name 정렬 ───────────────────────────────────

    @Test
    @Order(3)
    fun `findByProject 는 활성 컴포넌트를 name 오름차순으로 반환해야 한다`() {
        componentRepository.insert(Component.create(projectId = testProjectId, name = "Zeta"))
        componentRepository.insert(Component.create(projectId = testProjectId, name = "Alpha"))
        componentRepository.insert(Component.create(projectId = testProjectId, name = "Beta"))

        val result = componentRepository.findByProject(testProjectId)

        assertThat(result).hasSize(3)
        assertThat(result.map { it.name }).containsExactly("Alpha", "Beta", "Zeta")
    }

    @Test
    @Order(4)
    fun `findByProject 는 다른 프로젝트의 컴포넌트를 포함하지 않아야 한다`() {
        val otherProjectId = UUID.randomUUID()
        componentRepository.insert(Component.create(projectId = testProjectId, name = "Mine"))
        // 다른 프로젝트 직접 삽입 (FK 없으므로 UUID만 다르게 사용)
        dsl.execute(
            "INSERT INTO components (project_id, name) VALUES (?, ?)",
            otherProjectId,
            "Other",
        )

        val result = componentRepository.findByProject(testProjectId)

        assertThat(result).hasSize(1)
        assertThat(result[0].name).isEqualTo("Mine")
    }

    // ── T3. softDelete ────────────────────────────────────────────────────────

    @Test
    @Order(5)
    fun `softDelete 후 findById 는 null 을 반환해야 한다`() {
        val saved = componentRepository.insert(Component.create(projectId = testProjectId, name = "ToDelete"))

        componentRepository.softDelete(saved.id!!, testProjectId)

        val found = componentRepository.findById(saved.id!!, testProjectId)
        assertThat(found).isNull()
    }

    @Test
    @Order(6)
    fun `softDelete 후 findByProject 에서 제외되어야 한다`() {
        val active = componentRepository.insert(Component.create(projectId = testProjectId, name = "Active"))
        val toDelete = componentRepository.insert(Component.create(projectId = testProjectId, name = "Deleted"))

        componentRepository.softDelete(toDelete.id!!, testProjectId)

        val result = componentRepository.findByProject(testProjectId)
        assertThat(result).hasSize(1)
        assertThat(result[0].id).isEqualTo(active.id)
    }

    // ── T4. 활성 동명 유니크 위반 ─────────────────────────────────────────────

    @Test
    @Order(7)
    fun `같은 프로젝트에서 동명 활성 컴포넌트를 삽입하면 DataAccessException 이 발생해야 한다`() {
        componentRepository.insert(Component.create(projectId = testProjectId, name = "Duplicate"))

        assertThatThrownBy {
            componentRepository.insert(Component.create(projectId = testProjectId, name = "Duplicate"))
        }.isInstanceOf(DataAccessException::class.java)
    }

    // ── T5. 삭제 후 동명 재생성 허용 ─────────────────────────────────────────

    @Test
    @Order(8)
    fun `softDelete 후 같은 이름으로 재삽입이 허용되어야 한다`() {
        val first = componentRepository.insert(Component.create(projectId = testProjectId, name = "Reborn"))
        componentRepository.softDelete(first.id!!, testProjectId)

        val second = componentRepository.insert(Component.create(projectId = testProjectId, name = "Reborn"))

        assertThat(second.id).isNotNull()
        assertThat(second.id).isNotEqualTo(first.id)
        assertThat(second.name).isEqualTo("Reborn")
        assertThat(second.deletedAt).isNull()
    }
}
