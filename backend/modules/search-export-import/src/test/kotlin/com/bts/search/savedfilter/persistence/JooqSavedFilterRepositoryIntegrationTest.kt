// JooqSavedFilterRepository 통합테스트 — Testcontainers + Flyway V600 + OCC + 유니크 위반 검증 (FR-SR-03)

package com.bts.search.savedfilter.persistence

import com.bts.search.savedfilter.domain.SavedFilter
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * JooqSavedFilterRepository 통합테스트.
 *
 * Testcontainers (테스트용 DB 를 도커로 자동 실행하는 라이브러리) PG16-alpine 위에서
 * Flyway V600 마이그레이션 체인 적용 후 Repository 동작을 검증한다.
 *
 * 검증 범위.
 * - save → findById 라운드트립 (id/타임스탬프 채워짐, version=0)
 * - findByOwner 목록 (타 owner 행 제외)
 * - update 시 version+1 및 updated_at 갱신
 * - OCC: stale version 으로 update 시 null 반환
 * - deleteById 후 findById = null
 * - unique(owner_id, name) 위반 시 DataAccessException 발생
 *
 * 테스트 DSLContext 는 Spring 없이 수동 구성되므로 JooqExceptionTranslator 가 등록되지 않는다.
 * 따라서 유니크 위반은 jOOQ-native DataAccessException 으로 전파된다.
 * 운영 환경에서는 JooqAutoConfiguration 이 translator 를 자동 등록해 DuplicateKeyException 으로 변환된다.
 * (메모리 jooq-exception-translator-409-dependency)
 */
class JooqSavedFilterRepositoryIntegrationTest : SearchPersistenceTestBase() {
    /** 테스트마다 새로 생성 — DSLContext 는 bootstrap() 이후 확정된다. */
    private val repo get() = JooqSavedFilterRepository(dsl)

    @AfterEach
    fun cleanSavedFilters() {
        dsl.execute("DELETE FROM saved_filters")
    }

    private fun buildFilter(
        ownerId: UUID = UUID.randomUUID(),
        name: String = "테스트 필터",
        aqlQuery: String = "status = OPEN",
        projectKey: String = "ATLAS",
    ): SavedFilter = SavedFilter.create(ownerId = ownerId, name = name, aqlQuery = aqlQuery, projectKey = projectKey)

    // ── save → findById 라운드트립 ────────────────────────────────────────────

    @Test
    fun `save 후 findById 로 라운드트립 — id와 타임스탬프가 채워지고 version은 0`() {
        val filter = buildFilter()

        val saved = repo.save(filter)

        assertThat(saved.id).isNotNull()
        assertThat(saved.createdAt).isNotNull()
        assertThat(saved.updatedAt).isNotNull()
        assertThat(saved.version).isEqualTo(0L)
        assertThat(saved.name).isEqualTo(filter.name)
        assertThat(saved.aqlQuery).isEqualTo(filter.aqlQuery)
        assertThat(saved.projectKey).isEqualTo(filter.projectKey)
        assertThat(saved.ownerId).isEqualTo(filter.ownerId)

        val found = repo.findById(saved.id!!)
        assertThat(found).isNotNull()
        assertThat(found!!.id).isEqualTo(saved.id)
        assertThat(found.name).isEqualTo(saved.name)
        assertThat(found.aqlQuery).isEqualTo(saved.aqlQuery)
        assertThat(found.projectKey).isEqualTo(saved.projectKey)
        assertThat(found.ownerId).isEqualTo(saved.ownerId)
        assertThat(found.version).isEqualTo(0L)
    }

    @Test
    fun `findById — 존재하지 않는 id 는 null`() {
        val result = repo.findById(UUID.randomUUID())
        assertThat(result).isNull()
    }

    // ── findByOwner 목록 격리 ─────────────────────────────────────────────────

    @Test
    fun `findByOwner — 본인 필터만 반환하고 타 owner 행은 제외`() {
        val alice = UUID.randomUUID()
        val bob = UUID.randomUUID()

        val aliceFilter1 = repo.save(buildFilter(ownerId = alice, name = "앨리스 필터1"))
        val aliceFilter2 = repo.save(buildFilter(ownerId = alice, name = "앨리스 필터2"))
        repo.save(buildFilter(ownerId = bob, name = "밥 필터"))

        val aliceFilters = repo.findByOwner(alice)
        assertThat(aliceFilters).hasSize(2)
        assertThat(aliceFilters.map { it.id }).containsExactlyInAnyOrder(aliceFilter1.id, aliceFilter2.id)
        assertThat(aliceFilters).allMatch { it.ownerId == alice }
    }

    // ── update OCC ────────────────────────────────────────────────────────────

    @Test
    fun `update — version+1 및 updated_at 갱신`() {
        val saved = repo.save(buildFilter())
        val updatedName = "수정된 이름"
        val updatedAql = "status = CLOSED"

        val updated = repo.update(saved.copy(name = updatedName, aqlQuery = updatedAql))

        assertThat(updated).isNotNull()
        assertThat(updated!!.version).isEqualTo(1L)
        assertThat(updated.name).isEqualTo(updatedName)
        assertThat(updated.aqlQuery).isEqualTo(updatedAql)
        assertThat(updated.updatedAt).isNotNull()
    }

    @Test
    fun `update OCC — stale version 으로 update 시 null 반환`() {
        val saved = repo.save(buildFilter())
        // version=0 으로 1차 업데이트 → DB version 이 1 이 됨
        repo.update(saved.copy(name = "1차 수정"))

        // saved 는 여전히 version=0 → WHERE version=0 은 0행 → null
        val result = repo.update(saved.copy(name = "stale 수정"))
        assertThat(result).isNull()
    }

    // ── deleteById ────────────────────────────────────────────────────────────

    @Test
    fun `deleteById 후 findById 는 null`() {
        val saved = repo.save(buildFilter())

        val deleted = repo.deleteById(saved.id!!)

        assertThat(deleted).isTrue()
        assertThat(repo.findById(saved.id!!)).isNull()
    }

    @Test
    fun `deleteById — 존재하지 않는 id 는 false`() {
        val result = repo.deleteById(UUID.randomUUID())
        assertThat(result).isFalse()
    }

    // ── unique(owner_id, name) 위반 ───────────────────────────────────────────

    @Test
    fun `같은 owner_id와 name 조합 중복 save 시 DataAccessException 발생`() {
        val ownerId = UUID.randomUUID()
        repo.save(buildFilter(ownerId = ownerId, name = "중복 이름"))

        // 테스트 DSLContext 는 JooqExceptionTranslator 미등록 → jOOQ-native DataAccessException 전파
        assertThatThrownBy {
            repo.save(buildFilter(ownerId = ownerId, name = "중복 이름"))
        }.isInstanceOf(DataAccessException::class.java)
    }

    @Test
    fun `같은 name 이라도 owner_id 가 다르면 각자 저장 허용`() {
        repo.save(buildFilter(ownerId = UUID.randomUUID(), name = "공통 이름"))
        repo.save(buildFilter(ownerId = UUID.randomUUID(), name = "공통 이름"))
        // 예외 없이 통과해야 한다
    }
}
