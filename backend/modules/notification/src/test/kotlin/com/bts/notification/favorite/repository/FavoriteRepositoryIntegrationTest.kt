// FavoriteRepository Testcontainers 통합 테스트 — save 멱등·deleteByTarget·findByUser 검증

package com.bts.notification.favorite.repository

import com.bts.notification.favorite.domain.Favorite
import com.bts.notification.favorite.domain.FavoriteTargetType
import com.bts.notification.support.NotificationTestcontainersBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.util.UUID

/**
 * FavoriteRepository 통합 테스트.
 *
 * NotificationTestcontainersBase 를 상속해 Flyway V406(favorites 테이블) 이 적용된 PG16 를 사용한다.
 * 테스트 격리는 @BeforeEach 에서 favorites 테이블 전체 삭제로 보장한다.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class FavoriteRepositoryIntegrationTest : NotificationTestcontainersBase() {
    private lateinit var repository: FavoriteRepository

    private val userId1: UUID = UUID.fromString("20000000-0000-0000-0000-000000000001")
    private val userId2: UUID = UUID.fromString("20000000-0000-0000-0000-000000000002")

    @BeforeEach
    fun setUp() {
        repository = FavoriteRepository(dsl)
        // 각 테스트 독립성 보장
        dsl.execute("DELETE FROM favorites")
    }

    // ── save 멱등 ───────────────────────────────────────────────────────────────

    @Test
    fun `save 첫 호출 시 created true 를 반환하고 DB 에 1건이 존재한다`() {
        val favorite = buildFavorite(userId = userId1, targetType = FavoriteTargetType.ISSUE, targetId = "PROJ-1")

        val result = repository.save(favorite)

        assertThat(result.created).isTrue()
        assertThat(result.favorite.userId).isEqualTo(userId1)
        assertThat(result.favorite.targetType).isEqualTo(FavoriteTargetType.ISSUE)
        assertThat(result.favorite.targetId).isEqualTo("PROJ-1")

        val count = dsl.fetchCount(dsl.selectFrom("favorites"))
        assertThat(count).isEqualTo(1)
    }

    @Test
    fun `save 같은 userId targetType targetId 로 두 번 호출 시 DB 행은 1건이고 두 번째는 created false 를 반환한다`() {
        val favorite = buildFavorite(userId = userId1, targetType = FavoriteTargetType.ISSUE, targetId = "PROJ-1")

        val first = repository.save(favorite)
        // 두 번째 save — 같은 복합 유니크 키 (userId, targetType, targetId)
        val second = repository.save(buildFavorite(userId = userId1, targetType = FavoriteTargetType.ISSUE, targetId = "PROJ-1"))

        assertThat(first.created).isTrue()
        assertThat(second.created).isFalse()

        // DB 행은 정확히 1건
        val count = dsl.fetchCount(dsl.selectFrom("favorites"))
        assertThat(count).isEqualTo(1)
    }

    @Test
    fun `save 두 번째 호출 시 반환된 favorite 의 id 는 첫 번째와 동일하다`() {
        val fav = buildFavorite(userId = userId1, targetType = FavoriteTargetType.DASHBOARD, targetId = "dash-001")

        val first = repository.save(fav)
        val second = repository.save(buildFavorite(userId = userId1, targetType = FavoriteTargetType.DASHBOARD, targetId = "dash-001"))

        assertThat(second.favorite.id).isEqualTo(first.favorite.id)
    }

    @Test
    fun `save 다른 targetType 이면 별도 행으로 저장된다`() {
        repository.save(buildFavorite(userId = userId1, targetType = FavoriteTargetType.ISSUE, targetId = "PROJ-1"))
        repository.save(buildFavorite(userId = userId1, targetType = FavoriteTargetType.PROJECT, targetId = "PROJ-1"))

        val count = dsl.fetchCount(dsl.selectFrom("favorites"))
        assertThat(count).isEqualTo(2)
    }

    // ── deleteByTarget ──────────────────────────────────────────────────────────

    @Test
    fun `deleteByTarget 존재하는 즐겨찾기를 삭제하면 true 를 반환하고 DB 에서 제거된다`() {
        repository.save(buildFavorite(userId = userId1, targetType = FavoriteTargetType.ISSUE, targetId = "PROJ-1"))

        val deleted = repository.deleteByTarget(userId1, FavoriteTargetType.ISSUE, "PROJ-1")

        assertThat(deleted).isTrue()
        val count = dsl.fetchCount(dsl.selectFrom("favorites"))
        assertThat(count).isEqualTo(0)
    }

    @Test
    fun `deleteByTarget 존재하지 않는 즐겨찾기를 삭제하면 false 를 반환하고 오류가 없다`() {
        val deleted = repository.deleteByTarget(userId1, FavoriteTargetType.ISSUE, "PROJ-NOTEXIST")

        assertThat(deleted).isFalse()
    }

    @Test
    fun `deleteByTarget 은 본인 것만 삭제한다 — 다른 사용자 행은 유지된다`() {
        repository.save(buildFavorite(userId = userId2, targetType = FavoriteTargetType.ISSUE, targetId = "PROJ-1"))

        // userId1 으로 삭제 시도 — userId2 의 즐겨찾기는 영향 없음
        val deleted = repository.deleteByTarget(userId1, FavoriteTargetType.ISSUE, "PROJ-1")

        assertThat(deleted).isFalse()
        val count = dsl.fetchCount(dsl.selectFrom("favorites"))
        assertThat(count).isEqualTo(1)
    }

    // ── findByUser ──────────────────────────────────────────────────────────────

    @Test
    fun `findByUser targetType null 이면 해당 사용자의 전체 즐겨찾기를 반환한다`() {
        repository.save(buildFavorite(userId = userId1, targetType = FavoriteTargetType.ISSUE, targetId = "PROJ-1"))
        repository.save(buildFavorite(userId = userId1, targetType = FavoriteTargetType.PROJECT, targetId = "PROJ"))
        // 다른 사용자 행 — 누출 방지 확인
        repository.save(buildFavorite(userId = userId2, targetType = FavoriteTargetType.ISSUE, targetId = "PROJ-2"))

        val results = repository.findByUser(userId1, null)

        assertThat(results).hasSize(2)
        assertThat(results.map { it.userId }.distinct()).containsExactly(userId1)
    }

    @Test
    fun `findByUser targetType 주면 해당 종류만 반환한다`() {
        repository.save(buildFavorite(userId = userId1, targetType = FavoriteTargetType.ISSUE, targetId = "PROJ-1"))
        repository.save(buildFavorite(userId = userId1, targetType = FavoriteTargetType.ISSUE, targetId = "PROJ-2"))
        repository.save(buildFavorite(userId = userId1, targetType = FavoriteTargetType.PROJECT, targetId = "PROJ"))

        val results = repository.findByUser(userId1, FavoriteTargetType.ISSUE)

        assertThat(results).hasSize(2)
        assertThat(results.map { it.targetType }.distinct()).containsExactly(FavoriteTargetType.ISSUE)
    }

    @Test
    fun `findByUser 결과는 created_at DESC 정렬이다`() {
        // 3건을 순서대로 삽입 — DB default now() 타임스탬프 순
        repository.save(buildFavorite(userId = userId1, targetType = FavoriteTargetType.ISSUE, targetId = "PROJ-1"))
        Thread.sleep(10) // created_at 차이 보장
        repository.save(buildFavorite(userId = userId1, targetType = FavoriteTargetType.ISSUE, targetId = "PROJ-2"))
        Thread.sleep(10)
        repository.save(buildFavorite(userId = userId1, targetType = FavoriteTargetType.ISSUE, targetId = "PROJ-3"))

        val results = repository.findByUser(userId1, null)

        // DESC 정렬 — 마지막 삽입이 첫 번째
        assertThat(results[0].targetId).isEqualTo("PROJ-3")
        assertThat(results[1].targetId).isEqualTo("PROJ-2")
        assertThat(results[2].targetId).isEqualTo("PROJ-1")
    }

    @Test
    fun `findByUser 다른 사용자 즐겨찾기는 누출되지 않는다`() {
        repository.save(buildFavorite(userId = userId2, targetType = FavoriteTargetType.ISSUE, targetId = "PROJ-99"))

        val results = repository.findByUser(userId1, null)

        assertThat(results).hasSize(0)
    }

    @Test
    fun `findByUser 즐겨찾기가 없으면 빈 목록을 반환한다`() {
        val results = repository.findByUser(userId1, null)

        assertThat(results).hasSize(0)
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────────

    /**
     * 테스트용 Favorite 인스턴스를 생성한다.
     * Favorite.create 는 Instant.now() 를 사용하므로 순서 의존 테스트에서는 Thread.sleep 을 사용한다.
     */
    private fun buildFavorite(
        userId: UUID,
        targetType: FavoriteTargetType,
        targetId: String,
    ): Favorite = Favorite.create(userId = userId, targetType = targetType, targetId = targetId)
}
