// DashboardService MockK 단위 테스트 — 권한 판정·OCC·정규화·소프트삭제·멱등성 시나리오

package com.bts.notification.dashboard.application

import com.bts.notification.dashboard.domain.Dashboard
import com.bts.notification.dashboard.domain.DashboardVisibility
import com.bts.notification.dashboard.repository.DashboardPage
import com.bts.notification.dashboard.repository.DashboardRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * DashboardService 권한 판정·정규화·OCC·소프트삭제·멱등성 시나리오 단위 테스트.
 *
 * DashboardRepository 는 MockK stub 으로 대체한다.
 * Clock 은 고정 Instant 로 주입해 시각 결정성을 보장한다.
 */
class DashboardServiceTest {
    private val repository: DashboardRepository = mockk()
    private val fixedNow: Instant = Instant.parse("2026-06-22T12:00:00Z")
    private val clock: Clock = Clock.fixed(fixedNow, ZoneOffset.UTC)
    private lateinit var service: DashboardService

    private val ownerId: UUID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001")
    private val otherId: UUID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002")
    private val sharedId: UUID = UUID.fromString("cccccccc-0000-0000-0000-000000000003")

    @BeforeEach
    fun setUp() {
        service = DashboardService(repository, clock)
    }

    // ── 생성 ──────────────────────────────────────────────────────────────────

    /** CREATE-1. create — 성공 시 owner = actorId 로 저장. */
    @Test
    fun `create — owner 는 actorId 로 설정된다`() {
        val captured = mutableListOf<Dashboard>()
        every { repository.insert(capture(captured)) } answers { captured.last() }
        every { repository.replaceShares(any(), any()) } returns Unit

        service.create(
            actorId = ownerId,
            name = "내 대시보드",
            description = null,
            visibility = DashboardVisibility.PRIVATE,
            layout = "[]",
            sharedUserIds = emptySet(),
        )

        assertThat(captured).hasSize(1)
        assertThat(captured[0].ownerId).isEqualTo(ownerId)
    }

    // ── 단건 조회 권한 ─────────────────────────────────────────────────────────

    /** GET-1. PRIVATE 대시보드 — 소유자가 아닌 사용자는 404. */
    @Test
    fun `get — PRIVATE 대시보드를 비소유자가 조회하면 DashboardNotFoundException 발생`() {
        val dashboard = buildDashboard(ownerId = ownerId, visibility = DashboardVisibility.PRIVATE)
        every { repository.findById(dashboard.id) } returns dashboard
        every { repository.findSharesByDashboardId(dashboard.id) } returns emptyList()

        assertThatThrownBy { service.get(actorId = otherId, id = dashboard.id) }
            .isInstanceOf(DashboardNotFoundException::class.java)
    }

    /** GET-2. PRIVATE 대시보드 — 소유자는 200. */
    @Test
    fun `get — PRIVATE 대시보드를 소유자가 조회하면 성공`() {
        val dashboard = buildDashboard(ownerId = ownerId, visibility = DashboardVisibility.PRIVATE)
        every { repository.findById(dashboard.id) } returns dashboard
        every { repository.findSharesByDashboardId(dashboard.id) } returns emptyList()

        val result = service.get(actorId = ownerId, id = dashboard.id)
        assertThat(result.id).isEqualTo(dashboard.id)
    }

    /** GET-3. TEAM 대시보드 — 공유 대상 사용자는 200. */
    @Test
    fun `get — TEAM 대시보드를 공유 대상이 조회하면 성공`() {
        val dashboard = buildDashboard(ownerId = ownerId, visibility = DashboardVisibility.TEAM)
        every { repository.findById(dashboard.id) } returns dashboard
        every { repository.findSharesByDashboardId(dashboard.id) } returns listOf(sharedId)

        val result = service.get(actorId = sharedId, id = dashboard.id)
        assertThat(result.id).isEqualTo(dashboard.id)
    }

    /** GET-4. TEAM 대시보드 — 공유 대상이 아닌 사용자는 404. */
    @Test
    fun `get — TEAM 대시보드를 공유 대상이 아닌 사용자가 조회하면 DashboardNotFoundException 발생`() {
        val dashboard = buildDashboard(ownerId = ownerId, visibility = DashboardVisibility.TEAM)
        every { repository.findById(dashboard.id) } returns dashboard
        every { repository.findSharesByDashboardId(dashboard.id) } returns listOf(sharedId)

        assertThatThrownBy { service.get(actorId = otherId, id = dashboard.id) }
            .isInstanceOf(DashboardNotFoundException::class.java)
    }

    /** GET-5. ORG 대시보드 — 임의 인증 사용자 조회 성공. */
    @Test
    fun `get — ORG 대시보드는 임의 인증 사용자도 조회 가능`() {
        val dashboard = buildDashboard(ownerId = ownerId, visibility = DashboardVisibility.ORG)
        every { repository.findById(dashboard.id) } returns dashboard
        every { repository.findSharesByDashboardId(dashboard.id) } returns emptyList()

        val result = service.get(actorId = otherId, id = dashboard.id)
        assertThat(result.id).isEqualTo(dashboard.id)
    }

    /** GET-6. 존재하지 않는 id → 404. */
    @Test
    fun `get — 존재하지 않는 id 는 DashboardNotFoundException 발생`() {
        val id = UUID.randomUUID()
        every { repository.findById(id) } returns null

        assertThatThrownBy { service.get(actorId = ownerId, id = id) }
            .isInstanceOf(DashboardNotFoundException::class.java)
    }

    // ── 목록 ──────────────────────────────────────────────────────────────────

    /** LIST-1. list — repository.findPage 에 actorId, limit, offset 위임. */
    @Test
    fun `list — repository findPage 를 위임 호출한다`() {
        every { repository.findPage(ownerId, 50, 0) } returns DashboardPage(emptyList(), 0)

        service.list(actorId = ownerId, limit = 50, offset = 0)

        verify(exactly = 1) { repository.findPage(ownerId, 50, 0) }
    }

    /** LIST-2. list — limit 상한 100 초과 시 100 으로 클램프. */
    @Test
    fun `list — limit 이 100 초과이면 100 으로 클램프한다`() {
        every { repository.findPage(ownerId, 100, 0) } returns DashboardPage(emptyList(), 0)

        service.list(actorId = ownerId, limit = 200, offset = 0)

        verify(exactly = 1) { repository.findPage(ownerId, 100, 0) }
    }

    /** LIST-3. C3 — limit 음수(-1) 시 1 로 클램프, 500 아님. */
    @Test
    fun `list — limit 이 음수이면 1 로 클램프한다`() {
        every { repository.findPage(ownerId, 1, 0) } returns DashboardPage(emptyList(), 0)

        service.list(actorId = ownerId, limit = -1, offset = 0)

        verify(exactly = 1) { repository.findPage(ownerId, 1, 0) }
    }

    /** LIST-4. C3 — offset 음수(-1) 시 0 으로 클램프, 500 아님. */
    @Test
    fun `list — offset 이 음수이면 0 으로 클램프한다`() {
        every { repository.findPage(ownerId, 50, 0) } returns DashboardPage(emptyList(), 0)

        service.list(actorId = ownerId, limit = 50, offset = -1)

        verify(exactly = 1) { repository.findPage(ownerId, 50, 0) }
    }

    // ── 수정 (OCC, 권한) ──────────────────────────────────────────────────────

    /** UPDATE-1. update — 조회 불가(null) 시 404. */
    @Test
    fun `update — 존재하지 않는 id 는 DashboardNotFoundException 발생`() {
        val id = UUID.randomUUID()
        every { repository.findById(id) } returns null

        assertThatThrownBy {
            service.update(
                actorId = ownerId,
                id = id,
                name = null,
                description = null,
                visibility = null,
                layout = null,
                sharedUserIds = null,
                version = 0L,
            )
        }.isInstanceOf(DashboardNotFoundException::class.java)
    }

    /** UPDATE-2. update — 비소유자 수정 시도 → 403. */
    @Test
    fun `update — 비소유자가 수정하면 DashboardForbiddenException 발생`() {
        val dashboard = buildDashboard(ownerId = ownerId, visibility = DashboardVisibility.ORG)
        every { repository.findById(dashboard.id) } returns dashboard

        assertThatThrownBy {
            service.update(
                actorId = otherId,
                id = dashboard.id,
                name = "새 이름",
                description = null,
                visibility = null,
                layout = null,
                sharedUserIds = null,
                version = 0L,
            )
        }.isInstanceOf(DashboardForbiddenException::class.java)
    }

    /** UPDATE-3. update — OCC version 불일치 → 409. */
    @Test
    fun `update — version 불일치 시 DashboardConflictException 발생`() {
        val dashboard = buildDashboard(ownerId = ownerId, version = 2L)
        every { repository.findById(dashboard.id) } returns dashboard
        // applyPatch 후 version = 3, repository.update 는 WHERE version=2 → 실제 DB 에 version=5 있어 0 반환
        every { repository.update(any()) } returns 0
        every { repository.replaceShares(any(), any()) } returns Unit

        assertThatThrownBy {
            service.update(
                actorId = ownerId,
                id = dashboard.id,
                name = "새 이름",
                description = null,
                visibility = null,
                layout = null,
                sharedUserIds = null,
                version = 2L,
            )
        }.isInstanceOf(DashboardConflictException::class.java)
    }

    /** UPDATE-4. update — [C6] applyPatch 를 통해 도메인 정규화를 거친 뒤 repository.update 호출. */
    @Test
    fun `update — PRIVATE 으로 변경 시 sharedUserIds 가 빈 집합으로 정규화된다`() {
        val dashboard =
            buildDashboard(
                ownerId = ownerId,
                visibility = DashboardVisibility.TEAM,
                sharedUserIds = setOf(sharedId),
                version = 0L,
            )
        every { repository.findById(dashboard.id) } returns dashboard
        val updated = mutableListOf<Dashboard>()
        every { repository.update(capture(updated)) } returns 1
        every { repository.replaceShares(any(), any()) } returns Unit

        service.update(
            actorId = ownerId,
            id = dashboard.id,
            name = null,
            description = null,
            visibility = DashboardVisibility.PRIVATE,
            layout = null,
            sharedUserIds = null,
            version = 0L,
        )

        // PRIVATE 로 변경 후 도메인 정규화 → sharedUserIds 가 빈 집합
        assertThat(updated).hasSize(1)
        assertThat(updated[0].sharedUserIds).isEmpty()
        assertThat(updated[0].visibility).isEqualTo(DashboardVisibility.PRIVATE)
    }

    // ── 삭제 (C1 — findById → owner 비교 → softDelete 순서) ─────────────────

    /** DELETE-1. delete — 소유자가 삭제하면 softDelete 호출. */
    @Test
    fun `delete — 소유자가 삭제하면 softDelete 를 호출한다`() {
        val dashboard = buildDashboard(ownerId = ownerId, visibility = DashboardVisibility.PRIVATE)
        every { repository.findById(dashboard.id) } returns dashboard
        every { repository.softDelete(dashboard.id) } returns 1

        service.delete(actorId = ownerId, id = dashboard.id)

        verify(exactly = 1) { repository.softDelete(dashboard.id) }
    }

    /** DELETE-2. delete — 존재하지 않는(null) 대시보드 → 404. */
    @Test
    fun `delete — 존재하지 않는 대시보드 는 DashboardNotFoundException 발생`() {
        val id = UUID.randomUUID()
        every { repository.findById(id) } returns null

        assertThatThrownBy { service.delete(actorId = ownerId, id = id) }
            .isInstanceOf(DashboardNotFoundException::class.java)
    }

    /** DELETE-3. [C1] delete — 비소유자가 삭제 시도 → 403. rowcount 단일 판정 금지. */
    @Test
    fun `delete — 비소유자가 삭제 시도하면 DashboardForbiddenException 발생`() {
        val dashboard = buildDashboard(ownerId = ownerId, visibility = DashboardVisibility.ORG)
        every { repository.findById(dashboard.id) } returns dashboard

        assertThatThrownBy { service.delete(actorId = otherId, id = dashboard.id) }
            .isInstanceOf(DashboardForbiddenException::class.java)

        // softDelete 는 호출되지 않아야 한다
        verify(exactly = 0) { repository.softDelete(any()) }
    }

    /** DELETE-4. [C1] delete — 이미 삭제된(deleted_at != null) 대시보드 → 404 (멱등). */
    @Test
    fun `delete — 이미 삭제된 대시보드 는 DashboardNotFoundException 발생 — 멱등`() {
        val id = UUID.randomUUID()
        // deleted_at IS NULL 필터 → findById 가 null 반환
        every { repository.findById(id) } returns null

        assertThatThrownBy { service.delete(actorId = ownerId, id = id) }
            .isInstanceOf(DashboardNotFoundException::class.java)
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private fun buildDashboard(
        ownerId: UUID = this.ownerId,
        visibility: DashboardVisibility = DashboardVisibility.PRIVATE,
        sharedUserIds: Set<UUID> = emptySet(),
        version: Long = 0L,
    ): Dashboard =
        Dashboard(
            id = UUID.randomUUID(),
            ownerId = ownerId,
            name = "테스트 대시보드",
            description = null,
            visibility = visibility,
            layout = "[]",
            sharedUserIds = sharedUserIds,
            createdAt = fixedNow,
            updatedAt = fixedNow,
            deletedAt = null,
            version = version,
        )
}
