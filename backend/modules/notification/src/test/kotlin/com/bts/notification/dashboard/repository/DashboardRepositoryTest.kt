// DashboardRepository Testcontainers 통합 테스트 — CRUD·소프트삭제·shares·목록 UNION 페이지네이션 검증

package com.bts.notification.dashboard.repository

import com.bts.notification.dashboard.domain.Dashboard
import com.bts.notification.dashboard.domain.DashboardVisibility
import com.bts.notification.support.NotificationTestcontainersBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.MethodOrderer
import java.time.Instant
import java.util.UUID

/**
 * DashboardRepository CRUD + 소프트삭제 + shares + 목록 UNION DISTINCT 페이지네이션 통합 테스트.
 *
 * NotificationTestcontainersBase 를 상속해 Flyway V400~V405 마이그레이션이 적용된 PG16 를 사용한다.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class DashboardRepositoryTest : NotificationTestcontainersBase() {

    private lateinit var repository: DashboardRepository

    private val ownerId: UUID = UUID.fromString("10000000-0000-0000-0000-000000000001")
    private val userId2: UUID = UUID.fromString("10000000-0000-0000-0000-000000000002")
    private val userId3: UUID = UUID.fromString("10000000-0000-0000-0000-000000000003")
    private val now: Instant = Instant.parse("2026-06-22T00:00:00Z")

    @BeforeEach
    fun setUp() {
        repository = DashboardRepository(dsl)
        // 각 테스트 독립성 보장 — 이전 테스트가 삽입한 대시보드 전체 삭제
        dsl.execute("DELETE FROM dashboard_shares")
        dsl.execute("DELETE FROM dashboards")
    }

    private fun buildAndInsert(
        ownerId: UUID = this.ownerId,
        name: String = "내 대시보드",
        visibility: DashboardVisibility = DashboardVisibility.PRIVATE,
        sharedUserIds: Set<UUID> = emptySet(),
    ): Dashboard {
        val dashboard = Dashboard.create(
            ownerId = ownerId,
            name = name,
            description = null,
            visibility = visibility,
            layout = "[]",
            sharedUserIds = sharedUserIds,
            now = now,
        )
        return repository.insert(dashboard)
    }

    // ── insert + findById 라운드트립 ─────────────────────────────────────────────

    @Test
    fun `insert 후 findById 로 같은 대시보드를 조회할 수 있다`() {
        val inserted = buildAndInsert(name = "테스트 대시보드")
        val found = repository.findById(inserted.id)

        assertThat(found).isNotNull
        assertThat(found!!.id).isEqualTo(inserted.id)
        assertThat(found.name).isEqualTo("테스트 대시보드")
        assertThat(found.ownerId).isEqualTo(ownerId)
        assertThat(found.visibility).isEqualTo(DashboardVisibility.PRIVATE)
        assertThat(found.deletedAt).isNull()
        assertThat(found.version).isEqualTo(0L)
    }

    @Test
    fun `존재하지 않는 id 로 findById 호출 시 null 을 반환한다`() {
        val result = repository.findById(UUID.randomUUID())
        assertThat(result).isNull()
    }

    // ── update (OCC version bump) ────────────────────────────────────────────────

    @Test
    fun `update 성공 시 version 이 1 증가한다`() {
        val inserted = buildAndInsert()
        val patched = inserted.applyPatch(
            name = "바뀐 이름",
            description = null,
            visibility = null,
            layout = null,
            sharedUserIds = null,
            now = now.plusSeconds(10),
        )
        val rowCount = repository.update(patched)
        assertThat(rowCount).isEqualTo(1)

        val found = repository.findById(inserted.id)
        assertThat(found!!.name).isEqualTo("바뀐 이름")
        assertThat(found.version).isEqualTo(1L)
    }

    @Test
    fun `update 시 version 불일치이면 rowCount 0 을 반환한다 (OCC 충돌 신호)`() {
        val inserted = buildAndInsert()
        // version=1 로 조작된 도메인 객체 — 실제 DB 의 version=0 과 불일치
        val stale = inserted.copy(name = "충돌", version = 99L)
        val rowCount = repository.update(stale)
        assertThat(rowCount).isEqualTo(0)
    }

    @Test
    fun `update 시 deleted_at IS NOT NULL 이면 rowCount 0 을 반환한다`() {
        val inserted = buildAndInsert()
        repository.softDelete(inserted.id)

        val patched = inserted.applyPatch(
            name = null,
            description = null,
            visibility = null,
            layout = null,
            sharedUserIds = null,
            now = now.plusSeconds(5),
        )
        val rowCount = repository.update(patched)
        assertThat(rowCount).isEqualTo(0)
    }

    // ── softDelete ────────────────────────────────────────────────────────────────

    @Test
    fun `softDelete 후 findById 로 조회 시 null 을 반환한다`() {
        val inserted = buildAndInsert()
        repository.softDelete(inserted.id)

        val found = repository.findById(inserted.id)
        assertThat(found).isNull()
    }

    @Test
    fun `이미 삭제된 대시보드 softDelete 는 멱등 — rowCount 0`() {
        val inserted = buildAndInsert()
        val first = repository.softDelete(inserted.id)
        val second = repository.softDelete(inserted.id)

        assertThat(first).isEqualTo(1)
        assertThat(second).isEqualTo(0)
    }

    // ── shares insert/replace ────────────────────────────────────────────────────

    @Test
    fun `replaceShares 로 공유 사용자 목록을 교체할 수 있다`() {
        val inserted = buildAndInsert(visibility = DashboardVisibility.TEAM)
        repository.replaceShares(inserted.id, setOf(userId2))

        val shares = repository.findSharesByDashboardId(inserted.id)
        assertThat(shares).containsExactly(userId2)
    }

    @Test
    fun `replaceShares 빈 집합으로 호출 시 기존 shares 가 전부 제거된다`() {
        val inserted = buildAndInsert(visibility = DashboardVisibility.TEAM)
        repository.replaceShares(inserted.id, setOf(userId2, userId3))
        repository.replaceShares(inserted.id, emptySet())

        val shares = repository.findSharesByDashboardId(inserted.id)
        assertThat(shares).isEmpty()
    }

    @Test
    fun `softDelete 후 shares 는 부모 JOIN 필터로 가려진다`() {
        val inserted = buildAndInsert(visibility = DashboardVisibility.TEAM)
        repository.replaceShares(inserted.id, setOf(userId2))
        repository.softDelete(inserted.id)

        // 부모가 삭제되면 shares 는 더 이상 노출되지 않아야 함
        val shares = repository.findSharesByDashboardId(inserted.id)
        assertThat(shares).isEmpty()
    }

    // ── 목록 — owned ──────────────────────────────────────────────────────────────

    @Test
    fun `findPage 는 owner 소유 대시보드를 포함한다`() {
        buildAndInsert(ownerId = ownerId, visibility = DashboardVisibility.PRIVATE)
        buildAndInsert(ownerId = userId2, visibility = DashboardVisibility.PRIVATE)

        val page = repository.findPage(actorId = ownerId, limit = 10, offset = 0)
        assertThat(page.items).hasSize(1)
        assertThat(page.items[0].ownerId).isEqualTo(ownerId)
    }

    // ── 목록 — shared-to-me ──────────────────────────────────────────────────────

    @Test
    fun `findPage 는 TEAM 공유 대상인 대시보드를 포함한다`() {
        val teamDashboard = buildAndInsert(ownerId = userId2, visibility = DashboardVisibility.TEAM)
        repository.replaceShares(teamDashboard.id, setOf(ownerId))

        val page = repository.findPage(actorId = ownerId, limit = 10, offset = 0)
        val ids = page.items.map { it.id }
        assertThat(ids).contains(teamDashboard.id)
    }

    // ── 목록 — ORG ────────────────────────────────────────────────────────────────

    @Test
    fun `findPage 는 ORG visibility 대시보드를 포함한다`() {
        buildAndInsert(ownerId = userId2, visibility = DashboardVisibility.ORG)

        val page = repository.findPage(actorId = ownerId, limit = 10, offset = 0)
        assertThat(page.items).hasSize(1)
        assertThat(page.items[0].visibility).isEqualTo(DashboardVisibility.ORG)
    }

    // ── UNION DISTINCT — owned 이면서 ORG 는 1건 ─────────────────────────────────

    @Test
    fun `findPage UNION DISTINCT — owned 이면서 ORG 인 경우 1건으로 중복 제거된다`() {
        buildAndInsert(ownerId = ownerId, visibility = DashboardVisibility.ORG)

        val page = repository.findPage(actorId = ownerId, limit = 10, offset = 0)
        // owned ∪ ORG 합산이 중복 없이 1건이어야 함
        assertThat(page.items).hasSize(1)
    }

    // ── total count 별도 서브쿼리 ────────────────────────────────────────────────

    @Test
    fun `findPage total count 는 실제 접근 가능한 대시보드 수를 반환한다`() {
        buildAndInsert(ownerId = ownerId, visibility = DashboardVisibility.PRIVATE)
        buildAndInsert(ownerId = userId2, visibility = DashboardVisibility.ORG)

        val page = repository.findPage(actorId = ownerId, limit = 10, offset = 0)
        assertThat(page.total).isEqualTo(2)
    }

    // ── 페이지네이션 — limit/offset ──────────────────────────────────────────────

    @Test
    fun `findPage limit 으로 결과를 잘라낸다`() {
        repeat(5) { buildAndInsert(ownerId = ownerId, name = "대시보드 $it") }

        val page = repository.findPage(actorId = ownerId, limit = 3, offset = 0)
        assertThat(page.items).hasSize(3)
        assertThat(page.total).isEqualTo(5)
    }

    @Test
    fun `findPage offset 으로 건너뛴다`() {
        repeat(5) { buildAndInsert(ownerId = ownerId, name = "대시보드 $it") }

        val page = repository.findPage(actorId = ownerId, limit = 10, offset = 3)
        assertThat(page.items).hasSize(2)
        assertThat(page.total).isEqualTo(5)
    }

    @Test
    fun `빈 목록이면 items 는 비어 있고 total 은 0 이다`() {
        val page = repository.findPage(actorId = ownerId, limit = 10, offset = 0)
        assertThat(page.items).isEmpty()
        assertThat(page.total).isEqualTo(0)
    }

    // ── 소프트 삭제된 항목은 목록에서 제외 ─────────────────────────────────────

    @Test
    fun `소프트 삭제된 대시보드는 findPage 목록에 포함되지 않는다`() {
        val inserted = buildAndInsert(ownerId = ownerId, visibility = DashboardVisibility.PRIVATE)
        repository.softDelete(inserted.id)

        val page = repository.findPage(actorId = ownerId, limit = 10, offset = 0)
        assertThat(page.items).isEmpty()
        assertThat(page.total).isEqualTo(0)
    }
}
