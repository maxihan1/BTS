// 저장된 필터 공유 가시성 서비스 단위 테스트 — 4경로 가시성·403/404 분기·shares 배치 (FR-SR-03 PR2)

package com.bts.search.savedfilter.application

import com.bts.search.savedfilter.domain.SavedFilter
import com.bts.search.savedfilter.domain.SavedFilterShare
import com.bts.search.savedfilter.domain.ShareType
import com.bts.shared.membership.GroupMembershipPort
import com.bts.shared.membership.ProjectMembershipPort
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID

/**
 * 저장된 필터 공유 가시성 서비스 단위 테스트.
 *
 * MockK로 Repository·ShareRepository·두 멤버십 포트를 격리해 다음을 검증한다.
 * - 4경로 단건 가시성(owner / AUTHENTICATED / PROJECT / GROUP) + shares 동반.
 * - 비가시 단건 → 404(존재 은닉).
 * - 가시-비소유 update/delete → 403, 비가시 update/delete → 404.
 * - create/update shares 교체 시맨틱(null=유지 / []=전체제거 / [..]=교체).
 * - listOwnedWithShares / listSharedWith 의 단일 배치 shares 조합(N+1 차단).
 * - owner 단축경로(C6): owner get 시 멤버십 포트 미호출.
 */
class SavedFilterServiceVisibilityTest {
    private val repository: SavedFilterRepository = mockk()
    private val shareRepository: SavedFilterShareRepository = mockk()
    private val groupMembershipPort: GroupMembershipPort = mockk()
    private val projectMembershipPort: ProjectMembershipPort = mockk()
    private val service =
        SavedFilterService(repository, shareRepository, groupMembershipPort, projectMembershipPort)

    private val actorId: UUID = UUID.randomUUID()
    private val ownerId: UUID = UUID.randomUUID()

    /** 유효한 AQL 쿼리 — 렉서·파서 통과. */
    private val validAql = "status = Open"

    private fun aFilter(
        id: UUID = UUID.randomUUID(),
        owner: UUID = actorId,
    ): SavedFilter =
        SavedFilter(
            id = id,
            ownerId = owner,
            name = "필터",
            aqlQuery = validAql,
            projectKey = "PROJ",
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
            version = 0L,
        )

    // ── getVisibleById — 4경로 + 포트 미호출 ──────────────────────────────────

    @Test
    fun `getVisibleById - owner는 멤버십 포트 호출 없이 즉시 반환하고 shares 동반`() {
        val id = UUID.randomUUID()
        val filter = aFilter(id = id, owner = actorId)
        val shares = listOf(SavedFilterShare.create(ShareType.AUTHENTICATED, null))
        every { repository.findById(id) } returns filter
        every { shareRepository.findByFilterIds(setOf(id)) } returns mapOf(id to shares)

        val result = service.getVisibleById(id, actorId)

        assertEquals(filter, result.filter)
        assertEquals(shares, result.shares)
        verify(exactly = 0) { projectMembershipPort.projectKeysOf(any()) }
        verify(exactly = 0) { groupMembershipPort.groupIdsOf(any()) }
        verify(exactly = 0) { repository.findVisibleById(any(), any(), any(), any()) }
    }

    @Test
    fun `getVisibleById - AUTHENTICATED 공유 필터는 비소유자도 조회 가능`() {
        val id = UUID.randomUUID()
        val filter = aFilter(id = id, owner = ownerId)
        val shares = listOf(SavedFilterShare.create(ShareType.AUTHENTICATED, null))
        every { repository.findById(id) } returns filter
        every { projectMembershipPort.projectKeysOf(actorId) } returns emptySet()
        every { groupMembershipPort.groupIdsOf(actorId) } returns emptySet()
        every { repository.findVisibleById(id, actorId, emptySet(), emptySet()) } returns filter
        every { shareRepository.findByFilterIds(setOf(id)) } returns mapOf(id to shares)

        val result = service.getVisibleById(id, actorId)

        assertEquals(filter, result.filter)
        assertEquals(shares, result.shares)
        verify(exactly = 1) { repository.findVisibleById(id, actorId, emptySet(), emptySet()) }
    }

    @Test
    fun `getVisibleById - PROJECT 공유 필터는 멤버 프로젝트키로 조회`() {
        val id = UUID.randomUUID()
        val filter = aFilter(id = id, owner = ownerId)
        val keys = setOf("PROJ")
        val shares = listOf(SavedFilterShare.create(ShareType.PROJECT, "PROJ"))
        every { repository.findById(id) } returns filter
        every { projectMembershipPort.projectKeysOf(actorId) } returns keys
        every { groupMembershipPort.groupIdsOf(actorId) } returns emptySet()
        every { repository.findVisibleById(id, actorId, keys, emptySet()) } returns filter
        every { shareRepository.findByFilterIds(setOf(id)) } returns mapOf(id to shares)

        val result = service.getVisibleById(id, actorId)

        assertEquals(shares, result.shares)
        verify(exactly = 1) { projectMembershipPort.projectKeysOf(actorId) }
        verify(exactly = 1) { repository.findVisibleById(id, actorId, keys, emptySet()) }
    }

    @Test
    fun `getVisibleById - GROUP 공유 필터는 멤버 그룹ID로 조회`() {
        val id = UUID.randomUUID()
        val filter = aFilter(id = id, owner = ownerId)
        val groups = setOf("group-1")
        val shares = listOf(SavedFilterShare.create(ShareType.GROUP, "group-1"))
        every { repository.findById(id) } returns filter
        every { projectMembershipPort.projectKeysOf(actorId) } returns emptySet()
        every { groupMembershipPort.groupIdsOf(actorId) } returns groups
        every { repository.findVisibleById(id, actorId, emptySet(), groups) } returns filter
        every { shareRepository.findByFilterIds(setOf(id)) } returns mapOf(id to shares)

        val result = service.getVisibleById(id, actorId)

        assertEquals(shares, result.shares)
        verify(exactly = 1) { groupMembershipPort.groupIdsOf(actorId) }
        verify(exactly = 1) { repository.findVisibleById(id, actorId, emptySet(), groups) }
    }

    // ── C2 — 비소유 viewer 는 매칭 공유만 노출(정보 노출 차단) ───────────────────

    @Test
    fun `getVisibleById - C2 - owner는 다중 공유 전체 대상을 받는다`() {
        val id = UUID.randomUUID()
        val filter = aFilter(id = id, owner = actorId)
        val proj = SavedFilterShare.create(ShareType.PROJECT, "ATL")
        val group = SavedFilterShare.create(ShareType.GROUP, "execs")
        val auth = SavedFilterShare.create(ShareType.AUTHENTICATED, null)
        every { repository.findById(id) } returns filter
        every { shareRepository.findByFilterIds(setOf(id)) } returns mapOf(id to listOf(proj, group, auth))

        val result = service.getVisibleById(id, actorId)

        assertEquals(listOf(proj, group, auth), result.shares)
    }

    @Test
    fun `getVisibleById - C2 - 비소유 PROJECT 멤버는 매칭 공유만 받고 GROUP 공유는 제외`() {
        val id = UUID.randomUUID()
        val filter = aFilter(id = id, owner = ownerId)
        val keys = setOf("ATL")
        val proj = SavedFilterShare.create(ShareType.PROJECT, "ATL")
        val group = SavedFilterShare.create(ShareType.GROUP, "execs")
        val auth = SavedFilterShare.create(ShareType.AUTHENTICATED, null)
        every { repository.findById(id) } returns filter
        every { projectMembershipPort.projectKeysOf(actorId) } returns keys
        every { groupMembershipPort.groupIdsOf(actorId) } returns emptySet()
        every { repository.findVisibleById(id, actorId, keys, emptySet()) } returns filter
        every { shareRepository.findByFilterIds(setOf(id)) } returns mapOf(id to listOf(proj, group, auth))

        val result = service.getVisibleById(id, actorId)

        assertEquals(listOf(proj, auth), result.shares)
    }

    @Test
    fun `getVisibleById - C2 - 비소유 GROUP 소속은 매칭 공유만 받고 PROJECT 공유는 제외`() {
        val id = UUID.randomUUID()
        val filter = aFilter(id = id, owner = ownerId)
        val groups = setOf("execs")
        val proj = SavedFilterShare.create(ShareType.PROJECT, "ATL")
        val group = SavedFilterShare.create(ShareType.GROUP, "execs")
        val auth = SavedFilterShare.create(ShareType.AUTHENTICATED, null)
        every { repository.findById(id) } returns filter
        every { projectMembershipPort.projectKeysOf(actorId) } returns emptySet()
        every { groupMembershipPort.groupIdsOf(actorId) } returns groups
        every { repository.findVisibleById(id, actorId, emptySet(), groups) } returns filter
        every { shareRepository.findByFilterIds(setOf(id)) } returns mapOf(id to listOf(proj, group, auth))

        val result = service.getVisibleById(id, actorId)

        assertEquals(listOf(group, auth), result.shares)
    }

    @Test
    fun `listSharedWith - C2 - 비소유 결과는 viewer 매칭 공유만 포함`() {
        val id1 = UUID.randomUUID()
        val keys = setOf("ATL")
        val f1 = aFilter(id = id1, owner = ownerId)
        val proj = SavedFilterShare.create(ShareType.PROJECT, "ATL")
        val group = SavedFilterShare.create(ShareType.GROUP, "execs")
        val auth = SavedFilterShare.create(ShareType.AUTHENTICATED, null)
        every { projectMembershipPort.projectKeysOf(actorId) } returns keys
        every { groupMembershipPort.groupIdsOf(actorId) } returns emptySet()
        every { repository.findSharedWith(actorId, keys, emptySet(), 0, 20) } returns listOf(f1)
        every { shareRepository.findByFilterIds(setOf(id1)) } returns mapOf(id1 to listOf(proj, group, auth))

        val result = service.listSharedWith(actorId, 0, 20)

        assertEquals(listOf(proj, auth), result[0].shares)
    }

    // ── getVisibleById — 비가시 404 ───────────────────────────────────────────

    @Test
    fun `getVisibleById - 존재하지 않으면 404`() {
        val id = UUID.randomUUID()
        every { repository.findById(id) } returns null

        assertThrows<SavedFilterNotFoundException> { service.getVisibleById(id, actorId) }
    }

    @Test
    fun `getVisibleById - 비가시 비소유 필터는 404 존재은닉`() {
        val id = UUID.randomUUID()
        val filter = aFilter(id = id, owner = ownerId)
        every { repository.findById(id) } returns filter
        every { projectMembershipPort.projectKeysOf(actorId) } returns emptySet()
        every { groupMembershipPort.groupIdsOf(actorId) } returns emptySet()
        every { repository.findVisibleById(id, actorId, emptySet(), emptySet()) } returns null

        assertThrows<SavedFilterNotFoundException> { service.getVisibleById(id, actorId) }
        verify(exactly = 0) { shareRepository.findByFilterIds(any()) }
    }

    // ── update / delete — 403(가시-비소유) vs 404(비가시) ──────────────────────

    @Test
    fun `update - 가시 비소유 필터는 403 Forbidden`() {
        val id = UUID.randomUUID()
        val filter = aFilter(id = id, owner = ownerId)
        val groups = setOf("group-1")
        every { repository.findById(id) } returns filter
        every { projectMembershipPort.projectKeysOf(actorId) } returns emptySet()
        every { groupMembershipPort.groupIdsOf(actorId) } returns groups
        every { repository.findVisibleById(id, actorId, emptySet(), groups) } returns filter

        assertThrows<SavedFilterForbiddenException> {
            service.update(id, actorId, "새 이름", validAql, 0L)
        }
        verify(exactly = 0) { repository.update(any()) }
    }

    @Test
    fun `update - 비가시 비소유 필터는 404 존재은닉`() {
        val id = UUID.randomUUID()
        val filter = aFilter(id = id, owner = ownerId)
        every { repository.findById(id) } returns filter
        every { projectMembershipPort.projectKeysOf(actorId) } returns emptySet()
        every { groupMembershipPort.groupIdsOf(actorId) } returns emptySet()
        every { repository.findVisibleById(id, actorId, emptySet(), emptySet()) } returns null

        assertThrows<SavedFilterNotFoundException> {
            service.update(id, actorId, "새 이름", validAql, 0L)
        }
        verify(exactly = 0) { repository.update(any()) }
    }

    @Test
    fun `delete - 가시 비소유 필터는 403 Forbidden`() {
        val id = UUID.randomUUID()
        val filter = aFilter(id = id, owner = ownerId)
        val keys = setOf("PROJ")
        every { repository.findById(id) } returns filter
        every { projectMembershipPort.projectKeysOf(actorId) } returns keys
        every { groupMembershipPort.groupIdsOf(actorId) } returns emptySet()
        every { repository.findVisibleById(id, actorId, keys, emptySet()) } returns filter

        assertThrows<SavedFilterForbiddenException> { service.delete(id, actorId) }
        verify(exactly = 0) { repository.deleteById(any()) }
    }

    @Test
    fun `delete - 비가시 비소유 필터는 404 존재은닉`() {
        val id = UUID.randomUUID()
        val filter = aFilter(id = id, owner = ownerId)
        every { repository.findById(id) } returns filter
        every { projectMembershipPort.projectKeysOf(actorId) } returns emptySet()
        every { groupMembershipPort.groupIdsOf(actorId) } returns emptySet()
        every { repository.findVisibleById(id, actorId, emptySet(), emptySet()) } returns null

        assertThrows<SavedFilterNotFoundException> { service.delete(id, actorId) }
        verify(exactly = 0) { repository.deleteById(any()) }
    }

    // ── create / update — shares 교체 시맨틱 ───────────────────────────────────

    @Test
    fun `create - shares 제공 시 정규화 후 replaceShares 교체`() {
        val id = UUID.randomUUID()
        val saved = aFilter(id = id, owner = actorId)
        val shares = listOf(SavedFilterShare.create(ShareType.AUTHENTICATED, null))
        every { repository.save(any()) } returns saved
        justRun { shareRepository.replaceShares(id, any()) }

        service.create(actorId, "필터", validAql, "PROJ", shares)

        verify(exactly = 1) { shareRepository.replaceShares(id, shares) }
    }

    @Test
    fun `create - shares null이면 replaceShares 미호출`() {
        val saved = aFilter(owner = actorId)
        every { repository.save(any()) } returns saved

        service.create(actorId, "필터", validAql, "PROJ", null)

        verify(exactly = 0) { shareRepository.replaceShares(any(), any()) }
    }

    @Test
    fun `update - shares 빈 리스트면 replaceShares에 빈 리스트 전달 전체제거`() {
        val id = UUID.randomUUID()
        val filter = aFilter(id = id, owner = actorId)
        val updated = filter.copy(name = "새 이름", version = 1L)
        every { repository.findById(id) } returns filter
        every { repository.update(any()) } returns updated
        justRun { shareRepository.replaceShares(id, any()) }
        // update 가 SavedFilterWithShares 를 반환 — GREEN 에서 findByFilterIds 호출됨 — 미리 mock.
        every { shareRepository.findByFilterIds(setOf(id)) } returns emptyMap()

        service.update(id, actorId, "새 이름", validAql, 0L, emptyList())

        verify(exactly = 1) { shareRepository.replaceShares(id, emptyList()) }
    }

    @Test
    fun `update - shares null이면 replaceShares 미호출 유지`() {
        val id = UUID.randomUUID()
        val filter = aFilter(id = id, owner = actorId)
        val updated = filter.copy(name = "새 이름", version = 1L)
        every { repository.findById(id) } returns filter
        every { repository.update(any()) } returns updated
        // update 가 SavedFilterWithShares 를 반환 — GREEN 에서 findByFilterIds 호출됨 — 미리 mock.
        every { shareRepository.findByFilterIds(setOf(id)) } returns emptyMap()

        service.update(id, actorId, "새 이름", validAql, 0L, null)

        verify(exactly = 0) { shareRepository.replaceShares(any(), any()) }
    }

    @Test
    fun `update - shares 제공 시 정규화 후 replaceShares 교체`() {
        val id = UUID.randomUUID()
        val filter = aFilter(id = id, owner = actorId)
        val updated = filter.copy(name = "새 이름", version = 1L)
        val shares = listOf(SavedFilterShare.create(ShareType.PROJECT, "PROJ"))
        every { repository.findById(id) } returns filter
        every { repository.update(any()) } returns updated
        justRun { shareRepository.replaceShares(id, any()) }
        // update 가 SavedFilterWithShares 를 반환 — GREEN 에서 findByFilterIds 호출됨 — 미리 mock.
        every { shareRepository.findByFilterIds(setOf(id)) } returns emptyMap()

        service.update(id, actorId, "새 이름", validAql, 0L, shares)

        verify(exactly = 1) { shareRepository.replaceShares(id, shares) }
    }

    /**
     * C1 RED 테스트 — update(shares=null) 반환 SavedFilterWithShares 의 shares 가 영속 공유와 일치해야 한다.
     *
     * 현재 구현은 `shares ?: emptyList()` 를 반환하므로 null 전달 시 빈 리스트가 돌아온다 → FAIL.
     * GREEN 에서 withShares(updated) 로 교체하면 shareRepository 가 재조회되어 PASS 한다.
     */
    @Test
    fun `update - shares null이면 반환 SavedFilterWithShares의 shares가 기존 공유와 일치`() {
        val id = UUID.randomUUID()
        val filter = aFilter(id = id, owner = actorId)
        val updated = filter.copy(name = "새 이름", version = 1L)
        val existingShares = listOf(SavedFilterShare.create(ShareType.PROJECT, "PROJ"))
        every { repository.findById(id) } returns filter
        every { repository.update(any()) } returns updated
        every { shareRepository.findByFilterIds(setOf(id)) } returns mapOf(id to existingShares)

        val result = service.update(id, actorId, "새 이름", validAql, 0L, null)

        // 기존 공유가 유지(replaceShares 미호출)되었으므로 응답에도 반영되어야 한다.
        assertEquals(existingShares, result.shares)
        verify(exactly = 0) { shareRepository.replaceShares(any(), any()) }
    }

    // ── listOwnedWithShares / listSharedWith — 배치 조합(N+1 차단) ─────────────

    @Test
    fun `listOwnedWithShares - 소유 필터 목록에 shares 단일 배치 조합`() {
        val id1 = UUID.randomUUID()
        val id2 = UUID.randomUUID()
        val f1 = aFilter(id = id1, owner = actorId)
        val f2 = aFilter(id = id2, owner = actorId)
        val shares1 = listOf(SavedFilterShare.create(ShareType.AUTHENTICATED, null))
        every { repository.findByOwner(actorId) } returns listOf(f1, f2)
        every { shareRepository.findByFilterIds(setOf(id1, id2)) } returns mapOf(id1 to shares1)

        val result = service.listOwnedWithShares(actorId)

        assertEquals(2, result.size)
        assertEquals(shares1, result[0].shares)
        assertEquals(emptyList<SavedFilterShare>(), result[1].shares)
        verify(exactly = 1) { shareRepository.findByFilterIds(setOf(id1, id2)) }
    }

    @Test
    fun `listOwnedWithShares - 소유 필터 없으면 빈 목록`() {
        every { repository.findByOwner(actorId) } returns emptyList()
        every { shareRepository.findByFilterIds(emptySet()) } returns emptyMap()

        val result = service.listOwnedWithShares(actorId)

        assertEquals(emptyList<SavedFilterWithShares>(), result)
    }

    @Test
    fun `listSharedWith - 공유받은 필터 목록에 shares 단일 배치 조합`() {
        val id1 = UUID.randomUUID()
        val keys = setOf("PROJ")
        val f1 = aFilter(id = id1, owner = ownerId)
        val shares1 = listOf(SavedFilterShare.create(ShareType.PROJECT, "PROJ"))
        every { projectMembershipPort.projectKeysOf(actorId) } returns keys
        every { groupMembershipPort.groupIdsOf(actorId) } returns emptySet()
        every { repository.findSharedWith(actorId, keys, emptySet(), 0, 20) } returns listOf(f1)
        every { shareRepository.findByFilterIds(setOf(id1)) } returns mapOf(id1 to shares1)

        val result = service.listSharedWith(actorId, 0, 20)

        assertEquals(1, result.size)
        assertEquals(shares1, result[0].shares)
        verify(exactly = 1) { repository.findSharedWith(actorId, keys, emptySet(), 0, 20) }
        verify(exactly = 1) { shareRepository.findByFilterIds(setOf(id1)) }
    }
}
