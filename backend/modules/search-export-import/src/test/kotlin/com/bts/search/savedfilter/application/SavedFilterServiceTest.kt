// 저장된 필터 서비스 단위 테스트 — MockK로 Repository 격리 (FR-SR-03)

package com.bts.search.savedfilter.application

import com.bts.search.savedfilter.domain.SavedFilter
import com.bts.shared.membership.GroupMembershipPort
import com.bts.shared.membership.ProjectMembershipPort
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.dao.DuplicateKeyException
import java.sql.SQLException
import java.time.Instant
import java.util.UUID

class SavedFilterServiceTest {
    private val repository: SavedFilterRepository = mockk()
    private val shareRepository: SavedFilterShareRepository = mockk()
    private val groupMembershipPort: GroupMembershipPort = mockk()
    private val projectMembershipPort: ProjectMembershipPort = mockk()
    private val service =
        SavedFilterService(repository, shareRepository, groupMembershipPort, projectMembershipPort)

    private val actorId: UUID = UUID.randomUUID()
    private val otherId: UUID = UUID.randomUUID()

    /** 유효한 AQL 쿼리 — 렉서·파서 통과. */
    private val validAql = "status = Open"

    /** 무효한 AQL 쿼리 — '?' 는 인식 불가 문자 → AqlLexException. */
    private val invalidAql = "???"

    private fun aFilter(ownerId: UUID = actorId): SavedFilter =
        SavedFilter(
            id = UUID.randomUUID(),
            ownerId = ownerId,
            name = "내 필터",
            aqlQuery = validAql,
            projectKey = "PROJ",
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
            version = 0L,
        )

    // ── create ───────────────────────────────────────────────────────────────

    @Test
    fun `create - AQL 구문 오류 시 SavedFilterValidationException`() {
        assertThrows<SavedFilterValidationException> {
            service.create(actorId, "내 필터", invalidAql, "PROJ")
        }
    }

    @Test
    fun `create - 정상 요청 시 repo save 호출 후 결과 반환`() {
        val saved = aFilter()
        every { repository.save(any()) } returns saved

        val result = service.create(actorId, "내 필터", validAql, "PROJ")

        verify { repository.save(any()) }
        assertEquals(saved, result)
    }

    @Test
    fun `create - Spring DuplicateKeyException 시 SavedFilterDuplicateNameException`() {
        every { repository.save(any()) } throws DuplicateKeyException("unique constraint")

        assertThrows<SavedFilterDuplicateNameException> {
            service.create(actorId, "내 필터", validAql, "PROJ")
        }
    }

    @Test
    fun `create - jOOQ DataAccessException SQLState 23505 시 SavedFilterDuplicateNameException`() {
        val cause = SQLException("unique constraint violation", "23505")
        val ex = DataAccessException("duplicate key value violates unique constraint", cause)
        every { repository.save(any()) } throws ex

        assertThrows<SavedFilterDuplicateNameException> {
            service.create(actorId, "내 필터", validAql, "PROJ")
        }
    }

    // ── update ───────────────────────────────────────────────────────────────

    @Test
    fun `update - 비owner 비가시면 SavedFilterNotFoundException (존재은닉)`() {
        val othersFilter = aFilter(ownerId = otherId)
        every { repository.findById(othersFilter.id!!) } returns othersFilter
        every { projectMembershipPort.projectKeysOf(actorId) } returns emptySet()
        every { groupMembershipPort.groupIdsOf(actorId) } returns emptySet()
        every {
            repository.findVisibleById(othersFilter.id!!, actorId, emptySet(), emptySet())
        } returns null

        assertThrows<SavedFilterNotFoundException> {
            service.update(othersFilter.id!!, actorId, "새 이름", validAql, 0L)
        }
    }

    @Test
    fun `update - AQL 구문 오류 시 SavedFilterValidationException`() {
        val myFilter = aFilter()
        every { repository.findById(myFilter.id!!) } returns myFilter

        assertThrows<SavedFilterValidationException> {
            service.update(myFilter.id!!, actorId, "새 이름", invalidAql, myFilter.version)
        }
    }

    @Test
    fun `update - repo update가 null 반환 시 SavedFilterConflictException OCC`() {
        val myFilter = aFilter()
        every { repository.findById(myFilter.id!!) } returns myFilter
        every { repository.update(any()) } returns null

        assertThrows<SavedFilterConflictException> {
            service.update(myFilter.id!!, actorId, "새 이름", validAql, myFilter.version)
        }
    }

    @Test
    fun `update - projectKey 변경 무시 기존 projectKey 유지`() {
        val myFilter = aFilter() // projectKey = "PROJ"
        val updated = myFilter.copy(name = "새 이름", version = 1L)
        every { repository.findById(myFilter.id!!) } returns myFilter
        every { repository.update(any()) } returns updated

        service.update(myFilter.id!!, actorId, "새 이름", validAql, myFilter.version)

        verify { repository.update(match { it.projectKey == "PROJ" }) }
    }

    @Test
    fun `update - 정상 요청 시 갱신된 필터 반환`() {
        val myFilter = aFilter()
        val updated = myFilter.copy(name = "새 이름", version = 1L)
        every { repository.findById(myFilter.id!!) } returns myFilter
        every { repository.update(any()) } returns updated

        val result = service.update(myFilter.id!!, actorId, "새 이름", validAql, myFilter.version)

        assertEquals(updated, result)
    }

    // ── delete ───────────────────────────────────────────────────────────────

    @Test
    fun `delete - 존재하지 않으면 SavedFilterNotFoundException`() {
        every { repository.findById(any()) } returns null

        assertThrows<SavedFilterNotFoundException> {
            service.delete(UUID.randomUUID(), actorId)
        }
    }

    @Test
    fun `delete - 비owner 비가시면 SavedFilterNotFoundException (존재은닉)`() {
        val othersFilter = aFilter(ownerId = otherId)
        every { repository.findById(othersFilter.id!!) } returns othersFilter
        every { projectMembershipPort.projectKeysOf(actorId) } returns emptySet()
        every { groupMembershipPort.groupIdsOf(actorId) } returns emptySet()
        every {
            repository.findVisibleById(othersFilter.id!!, actorId, emptySet(), emptySet())
        } returns null

        assertThrows<SavedFilterNotFoundException> {
            service.delete(othersFilter.id!!, actorId)
        }
    }

    @Test
    fun `delete - 본인 필터 삭제 시 repo deleteById 호출`() {
        val myFilter = aFilter()
        every { repository.findById(myFilter.id!!) } returns myFilter
        every { repository.deleteById(myFilter.id!!) } returns true

        service.delete(myFilter.id!!, actorId)

        verify { repository.deleteById(myFilter.id!!) }
    }
}
