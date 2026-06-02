// VersionRepository Testcontainers 통합테스트 — insert/find/update/softDelete/유니크 위반/삭제 후 재생성
package com.bts.issue.version.repository

import com.bts.issue.repository.IssueTestcontainersBase
import com.bts.issue.version.domain.Version
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.time.LocalDate
import java.util.UUID

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class VersionRepositoryTest : IssueTestcontainersBase() {
    private lateinit var versionRepository: VersionRepository

    @BeforeEach
    fun setUpVersionRepository() {
        versionRepository = VersionRepository(dsl)
        // 각 테스트마다 versions 초기화
        dsl.execute("DELETE FROM versions")
    }

    // ── T1. insert + findById(소속+활성 조건) ─────────────────────────────────

    @Test
    @Order(1)
    fun `insert 후 findById 로 조회되어야 한다`() {
        val version =
            Version.create(
                projectId = testProjectId,
                name = "v1.0.0",
                description = "첫 릴리스",
                startDate = LocalDate.of(2024, 1, 1),
                releaseDate = LocalDate.of(2024, 3, 31),
            )

        val saved = versionRepository.insert(version)

        assertThat(saved.id).isNotNull()
        assertThat(saved.name).isEqualTo("v1.0.0")
        assertThat(saved.description).isEqualTo("첫 릴리스")
        assertThat(saved.projectId).isEqualTo(testProjectId)
        assertThat(saved.startDate).isEqualTo(LocalDate.of(2024, 1, 1))
        assertThat(saved.releaseDate).isEqualTo(LocalDate.of(2024, 3, 31))
        assertThat(saved.deletedAt).isNull()

        val found = versionRepository.findById(saved.id!!, testProjectId)
        assertThat(found).isNotNull()
        assertThat(found!!.id).isEqualTo(saved.id)
        assertThat(found.name).isEqualTo("v1.0.0")
    }

    @Test
    @Order(2)
    fun `날짜 없이 insert 후 findById 로 날짜 null 로 조회되어야 한다`() {
        val version = Version.create(projectId = testProjectId, name = "v2.0.0")

        val saved = versionRepository.insert(version)

        assertThat(saved.startDate).isNull()
        assertThat(saved.releaseDate).isNull()

        val found = versionRepository.findById(saved.id!!, testProjectId)
        assertThat(found).isNotNull()
        assertThat(found!!.startDate).isNull()
        assertThat(found.releaseDate).isNull()
    }

    @Test
    @Order(3)
    fun `다른 프로젝트 id 로 findById 하면 null 을 반환해야 한다`() {
        val version = Version.create(projectId = testProjectId, name = "v3.0.0")
        val saved = versionRepository.insert(version)

        val otherProjectId = UUID.randomUUID()
        val found = versionRepository.findById(saved.id!!, otherProjectId)

        assertThat(found).isNull()
    }

    // ── T2. findByProject — 활성, name 오름차순 정렬 ─────────────────────────

    @Test
    @Order(4)
    fun `findByProject 는 활성 버전을 name 오름차순으로 반환해야 한다`() {
        versionRepository.insert(Version.create(projectId = testProjectId, name = "v3.0.0"))
        versionRepository.insert(Version.create(projectId = testProjectId, name = "v1.0.0"))
        versionRepository.insert(Version.create(projectId = testProjectId, name = "v2.0.0"))

        val result = versionRepository.findByProject(testProjectId)

        assertThat(result).hasSize(3)
        assertThat(result.map { it.name }).containsExactly("v1.0.0", "v2.0.0", "v3.0.0")
    }

    @Test
    @Order(5)
    fun `findByProject 는 다른 프로젝트의 버전을 포함하지 않아야 한다`() {
        // 두 번째 프로젝트를 DB에 먼저 삽입해야 FK 제약을 만족한다
        val upsertSql =
            "INSERT INTO projects (key, name) VALUES (?, ?)" +
                " ON CONFLICT (key) DO UPDATE SET name = EXCLUDED.name RETURNING id"
        val otherProjectId: UUID =
            dsl.resultQuery(upsertSql, "TVRPRJ2", "Test Version Project 2")
                .fetchOne()
                ?.get(0) as UUID

        versionRepository.insert(Version.create(projectId = testProjectId, name = "Mine"))
        versionRepository.insert(Version.create(projectId = otherProjectId, name = "Other"))

        val result = versionRepository.findByProject(testProjectId)

        assertThat(result).hasSize(1)
        assertThat(result[0].name).isEqualTo("Mine")
    }

    // ── T3. update — name/description/dates 갱신 ─────────────────────────────

    @Test
    @Order(6)
    fun `update 후 findById 로 변경된 값이 조회되어야 한다`() {
        val saved =
            versionRepository.insert(
                Version.create(
                    projectId = testProjectId,
                    name = "v1.0.0-SNAPSHOT",
                    description = "스냅샷",
                    startDate = LocalDate.of(2024, 1, 1),
                    releaseDate = null,
                ),
            )

        val updated =
            saved.copy(
                id = saved.id,
                name = "v1.0.0",
                description = "정식 릴리스",
                startDate = LocalDate.of(2024, 1, 1),
                releaseDate = LocalDate.of(2024, 6, 30),
            )
        val result = versionRepository.update(updated)

        assertThat(result.name).isEqualTo("v1.0.0")
        assertThat(result.description).isEqualTo("정식 릴리스")
        assertThat(result.releaseDate).isEqualTo(LocalDate.of(2024, 6, 30))

        val found = versionRepository.findById(saved.id!!, testProjectId)
        assertThat(found).isNotNull()
        assertThat(found!!.name).isEqualTo("v1.0.0")
        assertThat(found.releaseDate).isEqualTo(LocalDate.of(2024, 6, 30))
    }

    @Test
    @Order(7)
    fun `update 에서 날짜를 역순 또는 null 로 변경해도 허용되어야 한다`() {
        val saved =
            versionRepository.insert(
                Version.create(
                    projectId = testProjectId,
                    name = "v4.0.0",
                    startDate = LocalDate.of(2024, 6, 1),
                    releaseDate = LocalDate.of(2024, 3, 1), // 의도적 역순 — 도메인은 강제 안 함
                ),
            )

        val updatedToNull = saved.copy(startDate = null, releaseDate = null)
        versionRepository.update(updatedToNull)

        val found = versionRepository.findById(saved.id!!, testProjectId)
        assertThat(found).isNotNull()
        assertThat(found!!.startDate).isNull()
        assertThat(found.releaseDate).isNull()
    }

    // ── T4. softDelete ────────────────────────────────────────────────────────

    @Test
    @Order(8)
    fun `softDelete 후 findById 는 null 을 반환해야 한다`() {
        val saved = versionRepository.insert(Version.create(projectId = testProjectId, name = "ToDelete"))

        versionRepository.softDelete(saved.id!!, testProjectId)

        val found = versionRepository.findById(saved.id!!, testProjectId)
        assertThat(found).isNull()
    }

    @Test
    @Order(9)
    fun `softDelete 후 findByProject 에서 제외되어야 한다`() {
        val active = versionRepository.insert(Version.create(projectId = testProjectId, name = "Active"))
        val toDelete = versionRepository.insert(Version.create(projectId = testProjectId, name = "Deleted"))

        versionRepository.softDelete(toDelete.id!!, testProjectId)

        val result = versionRepository.findByProject(testProjectId)
        assertThat(result).hasSize(1)
        assertThat(result[0].id).isEqualTo(active.id)
    }

    // ── T5. 활성 동명 유니크 위반 ─────────────────────────────────────────────

    @Test
    @Order(10)
    fun `같은 프로젝트에서 동명 활성 버전을 삽입하면 DataAccessException 이 발생해야 한다`() {
        versionRepository.insert(Version.create(projectId = testProjectId, name = "Duplicate"))

        assertThatThrownBy {
            versionRepository.insert(Version.create(projectId = testProjectId, name = "Duplicate"))
        }.isInstanceOf(DataAccessException::class.java)
    }

    // ── T6. 삭제 후 동명 재생성 허용 ─────────────────────────────────────────

    @Test
    @Order(11)
    fun `softDelete 후 같은 이름으로 재삽입이 허용되어야 한다`() {
        val first = versionRepository.insert(Version.create(projectId = testProjectId, name = "Reborn"))
        versionRepository.softDelete(first.id!!, testProjectId)

        val second = versionRepository.insert(Version.create(projectId = testProjectId, name = "Reborn"))

        assertThat(second.id).isNotNull()
        assertThat(second.id).isNotEqualTo(first.id)
        assertThat(second.name).isEqualTo("Reborn")
        assertThat(second.deletedAt as Any?).isNull()
    }
}
