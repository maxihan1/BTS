// BoardQuickFilterService MockK 단위 테스트 — 검증(EC1~EC5) + 409 dual-path RED 명세 (FR-UX-01 Task 5)

package com.bts.agileplanning.application

import com.bts.agileplanning.domain.QuickFilter
import com.bts.agileplanning.repository.BoardQuickFilterRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.exception.IntegrityConstraintViolationException
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import java.sql.SQLException
import java.util.UUID

/**
 * BoardQuickFilterService 단위 테스트.
 *
 * [BoardQuickFilterRepository] 를 MockK 로 교체해 독립적으로 검증 분기를 확인한다.
 * mockk 는 분기별로 필요한 메서드만 명시 스텁한다 — `any()` 를 기본값으로 남발해
 * 다른 분기 호출을 가려버리는 가짜그린을 피한다(memory: mockk default+any 가짜그린).
 */
class BoardQuickFilterServiceTest {
    private val boardId: UUID = UUID.randomUUID()
    private val filterId: UUID = UUID.randomUUID()

    private fun existing(query: String = "assignee=" + UUID.randomUUID()) =
        QuickFilter(id = filterId, boardId = boardId, name = "기존필터", query = query)

    // ── create ────────────────────────────────────────────────────────────────

    @Test
    fun `create 정상 query면 정규화해 저장하고 반환한다`() {
        val repo = mockk<BoardQuickFilterRepository>()
        every { repo.countByBoardId(boardId) } returns 0
        val saved = slot<QuickFilter>()
        every { repo.insert(capture(saved)) } answers { firstArg() }

        val result =
            BoardQuickFilterService(repo).create(boardId, "내 버그", "label=zeta&label=alpha")

        // serialize 는 label 을 오름차순 정렬한다 (BoardFilterQueryParser 계약)
        assertThat(saved.captured.query).isEqualTo("label=alpha&label=zeta")
        assertThat(saved.captured.boardId).isEqualTo(boardId)
        assertThat(saved.captured.name).isEqualTo("내 버그")
        assertThat(result.query).isEqualTo("label=alpha&label=zeta")
        verify(exactly = 1) { repo.insert(any()) }
    }

    @Test
    fun `create 빈 query면 400을 던지고 repo를 호출하지 않는다 (EC1)`() {
        val repo = mockk<BoardQuickFilterRepository>()

        assertThatThrownBy {
            BoardQuickFilterService(repo).create(boardId, "내 버그", "")
        }.isInstanceOf(QuickFilterEmptyQueryException::class.java)
        verify(exactly = 0) { repo.countByBoardId(any()) }
        verify(exactly = 0) { repo.insert(any()) }
    }

    @Test
    fun `create query 파싱 실패면 400을 던지고 repo를 호출하지 않는다 (EC4)`() {
        val repo = mockk<BoardQuickFilterRepository>()

        assertThatThrownBy {
            BoardQuickFilterService(repo).create(boardId, "내 버그", "assignee=not-a-uuid")
        }.isInstanceOf(org.springframework.web.server.ResponseStatusException::class.java)
            .extracting("statusCode.value")
            .isEqualTo(400)
        verify(exactly = 0) { repo.countByBoardId(any()) }
        verify(exactly = 0) { repo.insert(any()) }
    }

    @Test
    fun `create 보드당 20건 이상이면 409를 던지고 insert를 호출하지 않는다 (EC3)`() {
        val repo = mockk<BoardQuickFilterRepository>()
        every { repo.countByBoardId(boardId) } returns 20

        assertThatThrownBy {
            BoardQuickFilterService(repo).create(boardId, "내 버그", "label=bug")
        }.isInstanceOf(QuickFilterLimitExceededException::class.java)
        verify(exactly = 0) { repo.insert(any()) }
    }

    @Test
    fun `create 이름 중복 시 Spring DataIntegrityViolationException을 409로 변환한다 (EC2)`() {
        val repo = mockk<BoardQuickFilterRepository>()
        every { repo.countByBoardId(boardId) } returns 0
        val cause = SQLException("unique_violation", "23505")
        every { repo.insert(any()) } throws DataIntegrityViolationException("dup", cause)

        assertThatThrownBy {
            BoardQuickFilterService(repo).create(boardId, "내 버그", "label=bug")
        }.isInstanceOf(QuickFilterNameConflictException::class.java)
    }

    @Test
    fun `create 이름 중복 시 jOOQ IntegrityConstraintViolationException을 409로 변환한다 (EC2)`() {
        val repo = mockk<BoardQuickFilterRepository>()
        every { repo.countByBoardId(boardId) } returns 0
        val sqlEx = SQLException("unique_violation", "23505")
        every { repo.insert(any()) } throws IntegrityConstraintViolationException("dup", sqlEx)

        assertThatThrownBy {
            BoardQuickFilterService(repo).create(boardId, "내 버그", "label=bug")
        }.isInstanceOf(QuickFilterNameConflictException::class.java)
    }

    // ── update ────────────────────────────────────────────────────────────────

    @Test
    fun `update 대상이 없으면 404를 던지고 그 외 repo를 호출하지 않는다 (EC5)`() {
        val repo = mockk<BoardQuickFilterRepository>()
        every { repo.findByIdAndBoardId(filterId, boardId) } returns null

        assertThatThrownBy {
            BoardQuickFilterService(repo).update(boardId, filterId, "새 이름", "label=bug")
        }.isInstanceOf(QuickFilterNotFoundException::class.java)
        verify(exactly = 0) { repo.countByBoardId(any()) }
        verify(exactly = 0) { repo.update(any()) }
    }

    @Test
    fun `update 정상 query면 정규화해 갱신하고 반환한다`() {
        val repo = mockk<BoardQuickFilterRepository>()
        every { repo.findByIdAndBoardId(filterId, boardId) } returns existing()
        every { repo.countByBoardId(boardId) } returns 1
        val saved = slot<QuickFilter>()
        every { repo.update(capture(saved)) } answers { firstArg() }

        val result =
            BoardQuickFilterService(repo).update(boardId, filterId, "새 이름", "label=zeta&label=alpha")

        assertThat(saved.captured.id).isEqualTo(filterId)
        assertThat(saved.captured.query).isEqualTo("label=alpha&label=zeta")
        assertThat(result.name).isEqualTo("새 이름")
        verify(exactly = 1) { repo.update(any()) }
    }

    @Test
    fun `update 빈 query면 400을 던지고 update를 호출하지 않는다 (EC1)`() {
        val repo = mockk<BoardQuickFilterRepository>()
        every { repo.findByIdAndBoardId(filterId, boardId) } returns existing()

        assertThatThrownBy {
            BoardQuickFilterService(repo).update(boardId, filterId, "새 이름", "")
        }.isInstanceOf(QuickFilterEmptyQueryException::class.java)
        verify(exactly = 0) { repo.update(any()) }
    }

    @Test
    fun `update query 파싱 실패면 400을 던지고 update를 호출하지 않는다 (EC4)`() {
        val repo = mockk<BoardQuickFilterRepository>()
        every { repo.findByIdAndBoardId(filterId, boardId) } returns existing()

        assertThatThrownBy {
            BoardQuickFilterService(repo).update(boardId, filterId, "새 이름", "component=not-a-uuid")
        }.isInstanceOf(org.springframework.web.server.ResponseStatusException::class.java)
            .extracting("statusCode.value")
            .isEqualTo(400)
        verify(exactly = 0) { repo.update(any()) }
    }

    @Test
    fun `update 보드당 20건 이상이면 409를 던지고 update를 호출하지 않는다 (EC3)`() {
        val repo = mockk<BoardQuickFilterRepository>()
        every { repo.findByIdAndBoardId(filterId, boardId) } returns existing()
        every { repo.countByBoardId(boardId) } returns 21

        assertThatThrownBy {
            BoardQuickFilterService(repo).update(boardId, filterId, "새 이름", "label=bug")
        }.isInstanceOf(QuickFilterLimitExceededException::class.java)
        verify(exactly = 0) { repo.update(any()) }
    }

    @Test
    fun `update 이름 중복 시 Spring DataIntegrityViolationException을 409로 변환한다 (EC2)`() {
        val repo = mockk<BoardQuickFilterRepository>()
        every { repo.findByIdAndBoardId(filterId, boardId) } returns existing()
        every { repo.countByBoardId(boardId) } returns 1
        val cause = SQLException("unique_violation", "23505")
        every { repo.update(any()) } throws DataIntegrityViolationException("dup", cause)

        assertThatThrownBy {
            BoardQuickFilterService(repo).update(boardId, filterId, "새 이름", "label=bug")
        }.isInstanceOf(QuickFilterNameConflictException::class.java)
    }

    @Test
    fun `update 이름 중복 시 jOOQ IntegrityConstraintViolationException을 409로 변환한다 (EC2)`() {
        val repo = mockk<BoardQuickFilterRepository>()
        every { repo.findByIdAndBoardId(filterId, boardId) } returns existing()
        every { repo.countByBoardId(boardId) } returns 1
        val sqlEx = SQLException("unique_violation", "23505")
        every { repo.update(any()) } throws IntegrityConstraintViolationException("dup", sqlEx)

        assertThatThrownBy {
            BoardQuickFilterService(repo).update(boardId, filterId, "새 이름", "label=bug")
        }.isInstanceOf(QuickFilterNameConflictException::class.java)
    }

    // ── delete ────────────────────────────────────────────────────────────────

    @Test
    fun `delete 대상이 없으면 404를 던지고 delete를 호출하지 않는다 (EC5)`() {
        val repo = mockk<BoardQuickFilterRepository>()
        every { repo.findByIdAndBoardId(filterId, boardId) } returns null

        assertThatThrownBy {
            BoardQuickFilterService(repo).delete(boardId, filterId)
        }.isInstanceOf(QuickFilterNotFoundException::class.java)
        verify(exactly = 0) { repo.delete(any(), any()) }
    }

    @Test
    fun `delete 대상이 있으면 repository delete를 호출한다`() {
        val repo = mockk<BoardQuickFilterRepository>()
        every { repo.findByIdAndBoardId(filterId, boardId) } returns existing()
        every { repo.delete(filterId, boardId) } returns true

        BoardQuickFilterService(repo).delete(boardId, filterId)

        verify(exactly = 1) { repo.delete(filterId, boardId) }
    }

    // ── list ──────────────────────────────────────────────────────────────────

    @Test
    fun `list는 repository findByBoardId 결과를 그대로 반환한다`() {
        val repo = mockk<BoardQuickFilterRepository>()
        val filters = listOf(existing())
        every { repo.findByBoardId(boardId) } returns filters

        val result = BoardQuickFilterService(repo).list(boardId)

        assertThat(result).isEqualTo(filters)
    }
}
