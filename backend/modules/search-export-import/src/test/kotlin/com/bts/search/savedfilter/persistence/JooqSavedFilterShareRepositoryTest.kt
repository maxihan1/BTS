// JooqSavedFilterShareRepository + 가시성 메서드 통합테스트 — Testcontainers PG16, shares CRUD + 단일 술어(B3) 검증 (FR-SR-03 PR2)

package com.bts.search.savedfilter.persistence

import com.bts.search.savedfilter.domain.SavedFilter
import com.bts.search.savedfilter.domain.SavedFilterShare
import com.bts.search.savedfilter.domain.ShareType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * JooqSavedFilterShareRepository 통합테스트.
 *
 * Testcontainers (테스트용 DB를 도커로 자동 실행하는 라이브러리) PG16-alpine 위에서
 * Flyway V600~ 마이그레이션 체인 적용 후 두 Repository 의 동작을 검증한다.
 *
 * ## 검증 범위
 *
 * **(a) replaceShares delete-then-insert** —
 * 기존 shares 삭제 후 새 shares 교체, 빈 리스트로 전체 삭제.
 *
 * **(b) findByFilterIds 배치** —
 * 여러 필터 ID 한 번에 조회, 빈 Set 입력 시 빈 Map 반환(쿼리 스킵).
 *
 * **(c) CASCADE 삭제 (EC6)** —
 * 부모 필터 하드 삭제 시 shares 가 FK CASCADE 로 함께 삭제됨.
 *
 * **(d) UNIQUE NULLS NOT DISTINCT 안전망 (EC7)** —
 * 중복 PROJECT 공유 + AUTHENTICATED NULL target 중복 모두 DataAccessException.
 *
 * **(e) findSharedWith (EC14/C1)** —
 * owner 제외 · 4경로(AUTHENTICATED/PROJECT/GROUP/없음) · page-size 페이지네이션 ·
 * created_at ASC id ASC 정렬 · 빈 키/그룹 시 AUTHENTICATED 만.
 *
 * **(f) findVisibleById (B3)** —
 * owner → 반환 · 공유 매칭 비소유자 → 반환 · 매칭 없는 비소유자 → null.
 *
 * **(g) parity** —
 * 같은 시드에서 비소유 actor 기준 `findVisibleById≠null` ⟺ `findSharedWith` 결과에 포함.
 *
 * 테스트 DSLContext 는 Spring 없이 수동 구성되므로 JooqExceptionTranslator 가 등록되지 않는다.
 * 따라서 UNIQUE 위반은 jOOQ-native DataAccessException 으로 전파된다.
 */
class JooqSavedFilterShareRepositoryTest : SearchPersistenceTestBase() {
    private val filterRepo get() = JooqSavedFilterRepository(dsl)
    private val shareRepo get() = JooqSavedFilterShareRepository(dsl)

    @AfterEach
    fun clean() {
        // saved_filters 삭제 → FK CASCADE 로 saved_filter_shares 자식도 정리
        dsl.execute("DELETE FROM saved_filters")
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private fun saveFilter(
        ownerId: UUID = UUID.randomUUID(),
        name: String = "필터-${UUID.randomUUID()}",
        projectKey: String = "ATLAS",
    ): SavedFilter =
        filterRepo.save(
            SavedFilter.create(
                ownerId = ownerId,
                name = name,
                aqlQuery = "status = OPEN",
                projectKey = projectKey,
            ),
        )

    private fun authenticated() = SavedFilterShare.create(ShareType.AUTHENTICATED, null)

    private fun projectShare(key: String) = SavedFilterShare.create(ShareType.PROJECT, key)

    private fun groupShare(gid: String) = SavedFilterShare.create(ShareType.GROUP, gid)

    /** 단건 필터의 공유 목록을 조회하는 편의 헬퍼. */
    private fun sharesOf(filterId: UUID): List<SavedFilterShare> =
        shareRepo.findByFilterIds(setOf(filterId))[filterId] ?: emptyList()

    // ── (a) replaceShares delete-then-insert ──────────────────────────────────

    @Test
    fun `replaceShares - 기존 공유를 삭제하고 새 공유로 교체한다`() {
        val filter = saveFilter()
        shareRepo.replaceShares(filter.id!!, listOf(authenticated()))

        // PROJECT 로 교체
        shareRepo.replaceShares(filter.id!!, listOf(projectShare("ATLAS")))

        val shares = sharesOf(filter.id!!)
        assertThat(shares).hasSize(1)
        assertThat(shares[0].shareType).isEqualTo(ShareType.PROJECT)
        assertThat(shares[0].targetId).isEqualTo("ATLAS")
    }

    @Test
    fun `replaceShares - 빈 리스트로 교체하면 모든 공유가 삭제된다`() {
        val filter = saveFilter()
        shareRepo.replaceShares(filter.id!!, listOf(authenticated()))

        shareRepo.replaceShares(filter.id!!, emptyList())

        assertThat(sharesOf(filter.id!!)).isEmpty()
    }

    @Test
    fun `replaceShares - 여러 공유를 한 번에 등록할 수 있다`() {
        val filter = saveFilter()

        shareRepo.replaceShares(
            filter.id!!,
            listOf(authenticated(), projectShare("ATLAS"), groupShare("grp1")),
        )

        val shares = sharesOf(filter.id!!)
        assertThat(shares).hasSize(3)
        assertThat(shares.map { it.shareType })
            .containsExactlyInAnyOrder(ShareType.AUTHENTICATED, ShareType.PROJECT, ShareType.GROUP)
    }

    // ── (b) findByFilterIds 배치 ──────────────────────────────────────────────

    @Test
    fun `findByFilterIds - 여러 필터 ID를 한 번에 조회한다`() {
        val f1 = saveFilter()
        val f2 = saveFilter()
        shareRepo.replaceShares(f1.id!!, listOf(authenticated()))
        shareRepo.replaceShares(f2.id!!, listOf(projectShare("ATLAS"), groupShare("grp1")))

        val result = shareRepo.findByFilterIds(setOf(f1.id!!, f2.id!!))

        assertThat(result[f1.id!!]).hasSize(1)
        assertThat(result[f1.id!!]!![0].shareType).isEqualTo(ShareType.AUTHENTICATED)
        assertThat(result[f2.id!!]).hasSize(2)
        assertThat(result[f2.id!!]!!.map { it.shareType })
            .containsExactlyInAnyOrder(ShareType.PROJECT, ShareType.GROUP)
    }

    @Test
    fun `findByFilterIds - 빈 Set 입력 시 쿼리 없이 빈 Map 반환`() {
        val result = shareRepo.findByFilterIds(emptySet())

        assertThat(result).isEmpty()
    }

    @Test
    fun `findByFilterIds - 공유 없는 필터는 Map에 포함되지 않는다`() {
        val f1 = saveFilter()
        val f2 = saveFilter()
        shareRepo.replaceShares(f1.id!!, listOf(authenticated()))
        // f2 는 공유 없음

        val result = shareRepo.findByFilterIds(setOf(f1.id!!, f2.id!!))

        assertThat(result).containsKey(f1.id!!)
        assertThat(result).doesNotContainKey(f2.id!!)
    }

    // ── (c) CASCADE 삭제 (EC6) ────────────────────────────────────────────────

    @Test
    fun `부모 필터 하드 삭제 시 shares가 FK CASCADE로 함께 삭제된다 (EC6)`() {
        val filter = saveFilter()
        shareRepo.replaceShares(filter.id!!, listOf(authenticated(), projectShare("ATLAS")))

        filterRepo.deleteById(filter.id!!)

        val result = shareRepo.findByFilterIds(setOf(filter.id!!))
        assertThat(result).isEmpty()
    }

    // ── (d) UNIQUE NULLS NOT DISTINCT 안전망 (EC7) ────────────────────────────

    @Test
    fun `replaceShares - 중복 PROJECT 공유 INSERT 시 UNIQUE 위반 DataAccessException (EC7)`() {
        val filter = saveFilter()

        assertThatThrownBy {
            shareRepo.replaceShares(
                filter.id!!,
                listOf(projectShare("ATLAS"), projectShare("ATLAS")),
            )
        }.isInstanceOf(DataAccessException::class.java)
    }

    @Test
    fun `replaceShares - AUTHENTICATED target NULL 중복 시 UNIQUE NULLS NOT DISTINCT 위반 (EC7)`() {
        val filter = saveFilter()

        assertThatThrownBy {
            shareRepo.replaceShares(
                filter.id!!,
                listOf(authenticated(), authenticated()),
            )
        }.isInstanceOf(DataAccessException::class.java)
    }

    // ── (e) findSharedWith ────────────────────────────────────────────────────

    @Test
    fun `findSharedWith - AUTHENTICATED 공유 필터는 모든 비소유자에게 반환된다`() {
        val owner = UUID.randomUUID()
        val authFilter = saveFilter(ownerId = owner)
        shareRepo.replaceShares(authFilter.id!!, listOf(authenticated()))

        val actor = UUID.randomUUID()
        val result = filterRepo.findSharedWith(actor, emptySet(), emptySet(), 0, 20)

        assertThat(result).hasSize(1)
        assertThat(result[0].id).isEqualTo(authFilter.id)
    }

    @Test
    fun `findSharedWith - owner 자신의 필터는 owner_id != actor 조건으로 제외된다`() {
        val alice = UUID.randomUUID()
        val authFilter = saveFilter(ownerId = alice)
        shareRepo.replaceShares(authFilter.id!!, listOf(authenticated()))

        // Alice 자신으로 조회 → 소유 필터라 제외
        val result = filterRepo.findSharedWith(alice, emptySet(), emptySet(), 0, 20)

        assertThat(result).isEmpty()
    }

    @Test
    fun `findSharedWith - PROJECT 키 매칭 공유 필터만 반환한다`() {
        val owner = UUID.randomUUID()
        val atlasFilter = saveFilter(ownerId = owner, name = "atlas-filter")
        val otherFilter = saveFilter(ownerId = owner, name = "other-filter")
        shareRepo.replaceShares(atlasFilter.id!!, listOf(projectShare("ATLAS")))
        shareRepo.replaceShares(otherFilter.id!!, listOf(projectShare("OTHER")))

        val actor = UUID.randomUUID()
        val result = filterRepo.findSharedWith(actor, setOf("ATLAS"), emptySet(), 0, 20)

        assertThat(result).hasSize(1)
        assertThat(result[0].id).isEqualTo(atlasFilter.id)
    }

    @Test
    fun `findSharedWith - GROUP 매칭 공유 필터만 반환한다`() {
        val owner = UUID.randomUUID()
        val grp1Filter = saveFilter(ownerId = owner, name = "grp1-filter")
        val grp2Filter = saveFilter(ownerId = owner, name = "grp2-filter")
        shareRepo.replaceShares(grp1Filter.id!!, listOf(groupShare("grp1")))
        shareRepo.replaceShares(grp2Filter.id!!, listOf(groupShare("grp2")))

        val actor = UUID.randomUUID()
        val result = filterRepo.findSharedWith(actor, emptySet(), setOf("grp1"), 0, 20)

        assertThat(result).hasSize(1)
        assertThat(result[0].id).isEqualTo(grp1Filter.id)
    }

    @Test
    fun `findSharedWith - 공유 없는 필터는 제외된다`() {
        val owner = UUID.randomUUID()
        saveFilter(ownerId = owner) // 공유 없음

        val actor = UUID.randomUUID()
        val result = filterRepo.findSharedWith(actor, setOf("ATLAS"), setOf("grp1"), 0, 20)

        assertThat(result).isEmpty()
    }

    @Test
    fun `findSharedWith - 빈 projectKeys와 groupIds 입력 시 AUTHENTICATED 매칭만 반환한다`() {
        val owner = UUID.randomUUID()
        val authFilter = saveFilter(ownerId = owner, name = "auth-filter")
        val projFilter = saveFilter(ownerId = owner, name = "proj-filter")
        val grpFilter = saveFilter(ownerId = owner, name = "grp-filter")
        shareRepo.replaceShares(authFilter.id!!, listOf(authenticated()))
        shareRepo.replaceShares(projFilter.id!!, listOf(projectShare("ATLAS")))
        shareRepo.replaceShares(grpFilter.id!!, listOf(groupShare("grp1")))

        val actor = UUID.randomUUID()
        val result = filterRepo.findSharedWith(actor, emptySet(), emptySet(), 0, 20)

        // 빈 projectKeys/groupIds → PROJECT·GROUP 매칭 없음, AUTHENTICATED 만 반환
        assertThat(result).hasSize(1)
        assertThat(result[0].id).isEqualTo(authFilter.id)
    }

    @Test
    fun `findSharedWith - created_at ASC id ASC 정렬 후 page-size 페이지네이션 적용`() {
        val owner = UUID.randomUUID()
        val f1 = saveFilter(ownerId = owner, name = "f1")
        val f2 = saveFilter(ownerId = owner, name = "f2")
        val f3 = saveFilter(ownerId = owner, name = "f3")
        listOf(f1, f2, f3).forEach { shareRepo.replaceShares(it.id!!, listOf(authenticated())) }

        val actor = UUID.randomUUID()
        val page0 = filterRepo.findSharedWith(actor, emptySet(), emptySet(), 0, 2)
        val page1 = filterRepo.findSharedWith(actor, emptySet(), emptySet(), 1, 2)

        assertThat(page0).hasSize(2)
        assertThat(page1).hasSize(1)
        // 두 페이지 합집합 = 3개 필터 전체
        val allIds = (page0 + page1).map { it.id }.toSet()
        assertThat(allIds).containsExactlyInAnyOrder(f1.id, f2.id, f3.id)
    }

    @Test
    fun `findSharedWith - AUTHENTICATED과 PROJECT 매칭이 같은 필터에 있어도 중복 없이 단건 반환`() {
        val owner = UUID.randomUUID()
        val filter = saveFilter(ownerId = owner)
        shareRepo.replaceShares(filter.id!!, listOf(authenticated(), projectShare("ATLAS")))

        val actor = UUID.randomUUID()
        val result = filterRepo.findSharedWith(actor, setOf("ATLAS"), emptySet(), 0, 20)

        // EXISTS 술어는 중복을 만들지 않는다
        assertThat(result).hasSize(1)
        assertThat(result[0].id).isEqualTo(filter.id)
    }

    // ── (f) findVisibleById ───────────────────────────────────────────────────

    @Test
    fun `findVisibleById - owner는 공유 없이도 자신의 필터를 조회할 수 있다`() {
        val alice = UUID.randomUUID()
        val filter = saveFilter(ownerId = alice)
        // 공유 없음

        val result = filterRepo.findVisibleById(filter.id!!, alice, emptySet(), emptySet())

        assertThat(result).isNotNull()
        assertThat(result!!.id).isEqualTo(filter.id)
    }

    @Test
    fun `findVisibleById - AUTHENTICATED 공유 매칭 시 비소유자도 조회 가능하다 (B3)`() {
        val owner = UUID.randomUUID()
        val filter = saveFilter(ownerId = owner)
        shareRepo.replaceShares(filter.id!!, listOf(authenticated()))

        val actor = UUID.randomUUID()
        val result = filterRepo.findVisibleById(filter.id!!, actor, emptySet(), emptySet())

        assertThat(result).isNotNull()
        assertThat(result!!.id).isEqualTo(filter.id)
    }

    @Test
    fun `findVisibleById - PROJECT 공유 매칭 시 비소유자도 조회 가능하다 (B3)`() {
        val owner = UUID.randomUUID()
        val filter = saveFilter(ownerId = owner)
        shareRepo.replaceShares(filter.id!!, listOf(projectShare("ATLAS")))

        val actor = UUID.randomUUID()
        val result = filterRepo.findVisibleById(filter.id!!, actor, setOf("ATLAS"), emptySet())

        assertThat(result).isNotNull()
        assertThat(result!!.id).isEqualTo(filter.id)
    }

    @Test
    fun `findVisibleById - GROUP 공유 매칭 시 비소유자도 조회 가능하다 (B3)`() {
        val owner = UUID.randomUUID()
        val filter = saveFilter(ownerId = owner)
        shareRepo.replaceShares(filter.id!!, listOf(groupShare("grp1")))

        val actor = UUID.randomUUID()
        val result = filterRepo.findVisibleById(filter.id!!, actor, emptySet(), setOf("grp1"))

        assertThat(result).isNotNull()
        assertThat(result!!.id).isEqualTo(filter.id)
    }

    @Test
    fun `findVisibleById - 공유 없으면 비소유자는 null 반환 (존재 은닉)`() {
        val owner = UUID.randomUUID()
        val filter = saveFilter(ownerId = owner)
        // 공유 없음

        val actor = UUID.randomUUID()
        val result = filterRepo.findVisibleById(filter.id!!, actor, setOf("ATLAS"), setOf("grp1"))

        assertThat(result).isNull()
    }

    @Test
    fun `findVisibleById - projectKeys 불일치 시 비소유자는 null 반환`() {
        val owner = UUID.randomUUID()
        val filter = saveFilter(ownerId = owner)
        shareRepo.replaceShares(filter.id!!, listOf(projectShare("OTHER")))

        val actor = UUID.randomUUID()
        val result = filterRepo.findVisibleById(filter.id!!, actor, setOf("ATLAS"), emptySet())

        assertThat(result).isNull()
    }

    // ── (g) parity — findVisibleById non-null ⟺ findSharedWith 결과에 포함 ─────

    @Test
    fun `parity - 비소유자 기준 findVisibleById non-null이면 findSharedWith에도 포함된다`() {
        val owner = UUID.randomUUID()
        val visibleFilter = saveFilter(ownerId = owner, name = "visible")
        val hiddenFilter = saveFilter(ownerId = owner, name = "hidden")
        shareRepo.replaceShares(visibleFilter.id!!, listOf(authenticated()))
        // hiddenFilter 는 공유 없음

        val actor = UUID.randomUUID()
        val projectKeys = setOf("ATLAS")
        val groupIds = emptySet<String>()

        val sharedList = filterRepo.findSharedWith(actor, projectKeys, groupIds, 0, 20)
        val sharedIds = sharedList.map { it.id }.toSet()

        // visible: findVisibleById != null, sharedIds 에 포함
        val visibleResult = filterRepo.findVisibleById(visibleFilter.id!!, actor, projectKeys, groupIds)
        assertThat(visibleResult).isNotNull()
        assertThat(sharedIds).contains(visibleFilter.id)

        // hidden: findVisibleById == null, sharedIds 에 미포함
        val hiddenResult = filterRepo.findVisibleById(hiddenFilter.id!!, actor, projectKeys, groupIds)
        assertThat(hiddenResult).isNull()
        assertThat(sharedIds).doesNotContain(hiddenFilter.id)
    }

    @Test
    fun `parity - 여러 필터 혼합 시드에서도 parity가 성립한다`() {
        val owner = UUID.randomUUID()
        val f1 = saveFilter(ownerId = owner, name = "auth-share")
        val f2 = saveFilter(ownerId = owner, name = "proj-share")
        val f3 = saveFilter(ownerId = owner, name = "no-share")
        shareRepo.replaceShares(f1.id!!, listOf(authenticated()))
        shareRepo.replaceShares(f2.id!!, listOf(projectShare("ATLAS")))
        // f3 는 공유 없음

        val actor = UUID.randomUUID()
        val projectKeys = setOf("ATLAS")
        val groupIds = emptySet<String>()

        val sharedList = filterRepo.findSharedWith(actor, projectKeys, groupIds, 0, 20)
        val sharedIds = sharedList.map { it.id }.toSet()

        // f1(AUTHENTICATED), f2(PROJECT ATLAS) 가 보여야 한다
        assertThat(sharedIds).containsExactlyInAnyOrder(f1.id, f2.id)

        // findVisibleById 일관성 검증
        assertThat(filterRepo.findVisibleById(f1.id!!, actor, projectKeys, groupIds)).isNotNull()
        assertThat(filterRepo.findVisibleById(f2.id!!, actor, projectKeys, groupIds)).isNotNull()
        assertThat(filterRepo.findVisibleById(f3.id!!, actor, projectKeys, groupIds)).isNull()
    }
}
