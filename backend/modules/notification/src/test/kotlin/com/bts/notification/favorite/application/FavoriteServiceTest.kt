// FavoriteService 단위 테스트 — FavoriteRepository 를 mockk 로 대체해 비즈니스 로직만 검증

package com.bts.notification.favorite.application

import com.bts.notification.favorite.domain.Favorite
import com.bts.notification.favorite.domain.FavoriteDomainException
import com.bts.notification.favorite.domain.FavoriteTargetType
import com.bts.notification.favorite.repository.FavoriteRepository
import com.bts.notification.favorite.repository.SaveResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

@DisplayName("FavoriteService 단위 테스트")
class FavoriteServiceTest {
    private val repository: FavoriteRepository = mockk()
    private lateinit var service: FavoriteService

    @BeforeEach
    fun setUp() {
        service = FavoriteService(repository)
    }

    // ── 테스트용 헬퍼 ─────────────────────────────────────────────────────────

    private fun stubFavorite(
        userId: UUID = UUID.randomUUID(),
        targetType: FavoriteTargetType = FavoriteTargetType.ISSUE,
        targetId: String = "PROJ-1",
    ) = Favorite(
        id = UUID.randomUUID(),
        userId = userId,
        targetType = targetType,
        targetId = targetId,
        createdAt = Instant.now(),
    )

    // ── addFavorite ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("addFavorite")
    inner class AddFavorite {
        @Test
        @DisplayName("유효한 입력이면 repository.save 를 호출하고 SaveResult 를 반환한다")
        fun `addFavorite - valid input - saves and returns result`() {
            val actorId = UUID.randomUUID()
            val targetId = "PROJ-42"
            val favorite = stubFavorite(userId = actorId, targetType = FavoriteTargetType.ISSUE, targetId = targetId)
            val expected = SaveResult(favorite = favorite, created = true)

            every { repository.save(any()) } returns expected

            val result = service.addFavorite(actorId, "ISSUE", targetId)

            assertThat(result.created).isTrue()
            assertThat(result.favorite.userId).isEqualTo(actorId)
            assertThat(result.favorite.targetType).isEqualTo(FavoriteTargetType.ISSUE)
            assertThat(result.favorite.targetId).isEqualTo(targetId)
            verify(exactly = 1) {
                repository.save(
                    match {
                        it.userId == actorId &&
                            it.targetType == FavoriteTargetType.ISSUE &&
                            it.targetId == targetId
                    },
                )
            }
        }

        @Test
        @DisplayName("이미 존재하는 즐겨찾기이면 created=false 인 SaveResult 를 반환한다")
        fun `addFavorite - already exists - returns created=false`() {
            val actorId = UUID.randomUUID()
            val favorite = stubFavorite(userId = actorId, targetType = FavoriteTargetType.PROJECT, targetId = "PRJ-1")
            val expected = SaveResult(favorite = favorite, created = false)

            every { repository.save(any()) } returns expected

            val result = service.addFavorite(actorId, "PROJECT", "PRJ-1")

            assertThat(result.created).isFalse()
            verify(exactly = 1) { repository.save(any()) }
        }

        @Test
        @DisplayName("targetTypeRaw 가 무효 문자열이면 FavoriteDomainException 을 던진다")
        fun `addFavorite - invalid targetTypeRaw - throws FavoriteDomainException`() {
            val actorId = UUID.randomUUID()

            assertThatThrownBy { service.addFavorite(actorId, "UNKNOWN", "PROJ-1") }
                .isInstanceOf(FavoriteDomainException::class.java)

            verify(exactly = 0) { repository.save(any()) }
        }

        @Test
        @DisplayName("targetTypeRaw 가 빈 문자열이면 FavoriteDomainException 을 던진다")
        fun `addFavorite - blank targetTypeRaw - throws FavoriteDomainException`() {
            val actorId = UUID.randomUUID()

            assertThatThrownBy { service.addFavorite(actorId, "", "PROJ-1") }
                .isInstanceOf(FavoriteDomainException::class.java)

            verify(exactly = 0) { repository.save(any()) }
        }

        @Test
        @DisplayName("targetId 가 공백이면 도메인 불변식 위반으로 FavoriteDomainException 을 던진다")
        fun `addFavorite - blank targetId - throws FavoriteDomainException`() {
            val actorId = UUID.randomUUID()

            assertThatThrownBy { service.addFavorite(actorId, "ISSUE", "   ") }
                .isInstanceOf(FavoriteDomainException::class.java)

            verify(exactly = 0) { repository.save(any()) }
        }

        @Test
        @DisplayName("targetId 가 255자 초과이면 FavoriteDomainException 을 던진다")
        fun `addFavorite - targetId over 255 chars - throws FavoriteDomainException`() {
            val actorId = UUID.randomUUID()
            val longTargetId = "A".repeat(256)

            assertThatThrownBy { service.addFavorite(actorId, "ISSUE", longTargetId) }
                .isInstanceOf(FavoriteDomainException::class.java)

            verify(exactly = 0) { repository.save(any()) }
        }

        @Test
        @DisplayName("DASHBOARD 타입도 정상 처리된다")
        fun `addFavorite - DASHBOARD type - succeeds`() {
            val actorId = UUID.randomUUID()
            val favorite =
                stubFavorite(
                    userId = actorId,
                    targetType = FavoriteTargetType.DASHBOARD,
                    targetId = "dash-uuid",
                )
            val expected = SaveResult(favorite = favorite, created = true)

            every { repository.save(any()) } returns expected

            val result = service.addFavorite(actorId, "DASHBOARD", "dash-uuid")

            assertThat(result.favorite.targetType).isEqualTo(FavoriteTargetType.DASHBOARD)
        }
    }

    // ── removeFavorite ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("removeFavorite")
    inner class RemoveFavorite {
        @Test
        @DisplayName("존재하는 즐겨찾기를 삭제하면 repository.deleteByTarget 을 호출한다")
        fun `removeFavorite - existing - calls deleteByTarget`() {
            val actorId = UUID.randomUUID()

            every { repository.deleteByTarget(actorId, FavoriteTargetType.ISSUE, "PROJ-1") } returns true

            service.removeFavorite(actorId, "ISSUE", "PROJ-1")

            verify(exactly = 1) { repository.deleteByTarget(actorId, FavoriteTargetType.ISSUE, "PROJ-1") }
        }

        @Test
        @DisplayName("존재하지 않는 즐겨찾기 삭제는 멱등으로 정상 처리된다")
        fun `removeFavorite - not existing - idempotent no exception`() {
            val actorId = UUID.randomUUID()

            every { repository.deleteByTarget(actorId, FavoriteTargetType.ISSUE, "PROJ-99") } returns false

            // 예외 없이 정상 완료 (멱등)
            service.removeFavorite(actorId, "ISSUE", "PROJ-99")

            verify(exactly = 1) { repository.deleteByTarget(actorId, FavoriteTargetType.ISSUE, "PROJ-99") }
        }

        @Test
        @DisplayName("targetTypeRaw 가 무효이면 FavoriteDomainException 을 던진다")
        fun `removeFavorite - invalid targetTypeRaw - throws FavoriteDomainException`() {
            val actorId = UUID.randomUUID()

            assertThatThrownBy { service.removeFavorite(actorId, "INVALID_TYPE", "PROJ-1") }
                .isInstanceOf(FavoriteDomainException::class.java)

            verify(exactly = 0) { repository.deleteByTarget(any(), any(), any()) }
        }
    }

    // ── listFavorites ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("listFavorites")
    inner class ListFavorites {
        @Test
        @DisplayName("targetTypeRaw 가 null 이면 전체 목록을 반환한다")
        fun `listFavorites - null targetType - returns all`() {
            val actorId = UUID.randomUUID()
            val favorites =
                listOf(
                    stubFavorite(userId = actorId, targetType = FavoriteTargetType.ISSUE),
                    stubFavorite(userId = actorId, targetType = FavoriteTargetType.PROJECT),
                )

            every { repository.findByUser(actorId, null) } returns favorites

            val result = service.listFavorites(actorId, null)

            assertThat(result).hasSize(2)
            verify(exactly = 1) { repository.findByUser(actorId, null) }
        }

        @Test
        @DisplayName("targetTypeRaw 를 지정하면 파싱 후 해당 타입만 필터링해 반환한다")
        fun `listFavorites - with targetType - filters by type`() {
            val actorId = UUID.randomUUID()
            val favorites = listOf(stubFavorite(userId = actorId, targetType = FavoriteTargetType.ISSUE))

            every { repository.findByUser(actorId, FavoriteTargetType.ISSUE) } returns favorites

            val result = service.listFavorites(actorId, "ISSUE")

            assertThat(result).hasSize(1)
            assertThat(result[0].targetType).isEqualTo(FavoriteTargetType.ISSUE)
            verify(exactly = 1) { repository.findByUser(actorId, FavoriteTargetType.ISSUE) }
        }

        @Test
        @DisplayName("결과가 없으면 빈 목록을 반환한다")
        fun `listFavorites - empty result - returns empty list`() {
            val actorId = UUID.randomUUID()

            every { repository.findByUser(actorId, FavoriteTargetType.FILTER) } returns emptyList()

            val result = service.listFavorites(actorId, "FILTER")

            assertThat(result).hasSize(0)
            verify(exactly = 1) { repository.findByUser(actorId, FavoriteTargetType.FILTER) }
        }

        @Test
        @DisplayName("targetTypeRaw 가 무효이면 FavoriteDomainException 을 던진다")
        fun `listFavorites - invalid targetTypeRaw - throws FavoriteDomainException`() {
            val actorId = UUID.randomUUID()

            assertThatThrownBy { service.listFavorites(actorId, "NOT_A_TYPE") }
                .isInstanceOf(FavoriteDomainException::class.java)

            verify(exactly = 0) { repository.findByUser(any(), any()) }
        }
    }
}
