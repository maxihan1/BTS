// DashboardShareTokenRepository Testcontainers 통합 테스트 — insert·활성조회·삭제·목록·카운트·교차격리 검증

package com.bts.notification.dashboard.repository

import com.bts.notification.dashboard.domain.Dashboard
import com.bts.notification.dashboard.domain.DashboardShareToken
import com.bts.notification.dashboard.domain.DashboardVisibility
import com.bts.notification.support.NotificationTestcontainersBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.time.Instant
import java.util.UUID

/**
 * DashboardShareTokenRepository insert + findActiveByTokenHash + deleteById
 * + listByDashboard + countByDashboard 통합 테스트.
 *
 * NotificationTestcontainersBase 를 상속해 Flyway V400~V408 마이그레이션이 적용된 PG16 를 사용한다.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DashboardShareTokenRepositoryTest : NotificationTestcontainersBase() {
    private lateinit var dashboardRepository: DashboardRepository
    private lateinit var repository: DashboardShareTokenRepository

    private val ownerId: UUID = UUID.fromString("20000000-0000-0000-0000-000000000001")
    private val now: Instant = Instant.parse("2026-07-02T00:00:00Z")

    @BeforeEach
    fun setUp() {
        dashboardRepository = DashboardRepository(dsl)
        repository = DashboardShareTokenRepository(dsl)
        // 각 테스트 독립성 보장 — 이전 테스트가 삽입한 행 전체 삭제 (자식부터 삭제)
        dsl.execute("DELETE FROM dashboard_share_tokens")
        dsl.execute("DELETE FROM dashboard_shares")
        dsl.execute("DELETE FROM dashboards")
    }

    private fun insertDashboard(
        name: String = "공유 테스트 대시보드",
    ): Dashboard {
        val dashboard =
            Dashboard.create(
                ownerId = ownerId,
                name = name,
                description = null,
                visibility = DashboardVisibility.PRIVATE,
                layout = "[]",
                sharedUserIds = emptySet(),
                now = now,
            )
        return dashboardRepository.insert(dashboard)
    }

    private fun buildToken(
        dashboardId: UUID,
        tokenHash: String = UUID.randomUUID().toString().replace("-", "").repeat(2).take(64),
        createdAt: Instant = now,
        expiresAt: Instant? = null,
    ): DashboardShareToken =
        DashboardShareToken(
            id = UUID.randomUUID(),
            dashboardId = dashboardId,
            tokenHash = tokenHash,
            createdBy = ownerId,
            createdAt = createdAt,
            expiresAt = expiresAt,
        )

    // ── insert + findActiveByTokenHash ───────────────────────────────────────────

    @Test
    fun `insert 후 findActiveByTokenHash 로 같은 토큰을 조회할 수 있다`() {
        val dashboard = insertDashboard()
        val token = buildToken(dashboard.id, tokenHash = "a".repeat(64))
        repository.insert(token)

        val found = repository.findActiveByTokenHash("a".repeat(64))

        assertThat(found).isNotNull
        assertThat(found!!.id).isEqualTo(token.id)
        assertThat(found.dashboardId).isEqualTo(dashboard.id)
        assertThat(found.tokenHash).isEqualTo("a".repeat(64))
        assertThat(found.createdBy).isEqualTo(ownerId)
        assertThat(found.expiresAt).isNull()
    }

    @Test
    fun `부모 대시보드가 soft-delete 되면 findActiveByTokenHash 는 null 을 반환한다`() {
        val dashboard = insertDashboard()
        val token = buildToken(dashboard.id, tokenHash = "b".repeat(64))
        repository.insert(token)

        dashboardRepository.softDelete(dashboard.id)

        val found = repository.findActiveByTokenHash("b".repeat(64))
        assertThat(found).isNull()
    }

    @Test
    fun `존재하지 않는 해시로 조회하면 null 을 반환한다`() {
        val found = repository.findActiveByTokenHash("c".repeat(64))
        assertThat(found).isNull()
    }

    // ── deleteById ────────────────────────────────────────────────────────────────

    @Test
    fun `deleteById 후 findActiveByTokenHash 는 null 을 반환한다`() {
        val dashboard = insertDashboard()
        val token = buildToken(dashboard.id, tokenHash = "d".repeat(64))
        repository.insert(token)

        val deleted = repository.deleteById(token.id, dashboard.id)
        assertThat(deleted).isEqualTo(1)

        val found = repository.findActiveByTokenHash("d".repeat(64))
        assertThat(found).isNull()
    }

    @Test
    fun `deleteById 는 다른 대시보드 소유 토큰을 삭제하지 못한다`() {
        val dashboard = insertDashboard()
        val otherDashboard = insertDashboard(name = "다른 대시보드")
        val token = buildToken(dashboard.id, tokenHash = "e".repeat(64))
        repository.insert(token)

        // 잘못된 dashboardId 스코프로 삭제 시도 — 영향받은 행 없어야 함
        val deleted = repository.deleteById(token.id, otherDashboard.id)
        assertThat(deleted).isEqualTo(0)

        val found = repository.findActiveByTokenHash("e".repeat(64))
        assertThat(found).isNotNull
    }

    // ── listByDashboard ───────────────────────────────────────────────────────────

    @Test
    fun `listByDashboard 는 발급된 토큰 메타 목록을 created_at 오름차순으로 반환한다`() {
        val dashboard = insertDashboard()
        val earlier = buildToken(dashboard.id, tokenHash = "f".repeat(64), createdAt = now)
        val later = buildToken(dashboard.id, tokenHash = "0".repeat(64), createdAt = now.plusSeconds(60))
        repository.insert(later)
        repository.insert(earlier)

        val tokens = repository.listByDashboard(dashboard.id)

        assertThat(tokens).hasSize(2)
        assertThat(tokens.map { it.id }).containsExactly(earlier.id, later.id)
    }

    @Test
    fun `listByDashboard 는 토큰이 없으면 빈 목록을 반환한다`() {
        val dashboard = insertDashboard()
        val tokens = repository.listByDashboard(dashboard.id)
        assertThat(tokens).isEmpty()
    }

    // ── countByDashboard ──────────────────────────────────────────────────────────

    @Test
    fun `countByDashboard 는 발급된 토큰 수를 정확히 반환한다`() {
        val dashboard = insertDashboard()
        repository.insert(buildToken(dashboard.id, tokenHash = "1".repeat(64)))
        repository.insert(buildToken(dashboard.id, tokenHash = "2".repeat(64)))
        repository.insert(buildToken(dashboard.id, tokenHash = "3".repeat(64)))

        val count = repository.countByDashboard(dashboard.id)
        assertThat(count).isEqualTo(3)
    }

    @Test
    fun `countByDashboard 는 토큰이 없으면 0 을 반환한다`() {
        val dashboard = insertDashboard()
        val count = repository.countByDashboard(dashboard.id)
        assertThat(count).isEqualTo(0)
    }

    // ── 교차 격리 — 다른 대시보드 토큰이 섞이지 않음 ─────────────────────────────

    @Test
    fun `listByDashboard 와 countByDashboard 는 다른 대시보드의 토큰과 섞이지 않는다`() {
        val dashboard = insertDashboard(name = "대상 대시보드")
        val otherDashboard = insertDashboard(name = "다른 대시보드")

        repository.insert(buildToken(dashboard.id, tokenHash = "4".repeat(64)))
        repository.insert(buildToken(otherDashboard.id, tokenHash = "5".repeat(64)))
        repository.insert(buildToken(otherDashboard.id, tokenHash = "6".repeat(64)))

        val tokens = repository.listByDashboard(dashboard.id)
        val count = repository.countByDashboard(dashboard.id)

        assertThat(tokens).hasSize(1)
        assertThat(tokens[0].dashboardId).isEqualTo(dashboard.id)
        assertThat(count).isEqualTo(1)

        val otherCount = repository.countByDashboard(otherDashboard.id)
        assertThat(otherCount).isEqualTo(2)
    }
}
