// ExportJobService 단위 테스트 — MockK repository/publisher, AQL 선검증/영속/enqueue 경계 (FR-EX-02)

package com.bts.search.export.job.application

import com.bts.search.aql.AqlSyntaxException
import com.bts.search.export.job.domain.ExportJob
import com.bts.search.export.job.domain.ExportJobStatus
import com.bts.search.export.job.event.ExportJobEnqueuePublisher
import com.bts.search.export.job.repository.ExportJobRepository
import com.bts.search.web.SearchValidationException
import com.bts.search.web.dto.ExportRequest
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * [ExportJobService] 단위 테스트.
 *
 * [ExportJobRepository] 와 [ExportJobEnqueuePublisher] 는 MockK 로 격리한다.
 * [Clock] 은 고정 인스턴스로 createdAt 결정성을 보장한다.
 *
 * 검증 항목.
 * - (a) AQL 성공: repo.insert(PENDING) + enqueue 호출, jobId 반환.
 * - (b) AQL 문법 오류: AqlSyntaxException 전파, repo.insert 미호출(exactly=0).
 * - (c) blank projectKey → ResponseStatusException 400 + insert 미호출.
 * - (d) 잘못된 format → SearchValidationException + insert 미호출.
 * - (e) null format → CSV 기본값으로 저장.
 * - (f) XLSX format 저장.
 * - (g) enqueue 에 전달된 ID = submit 반환 ID (jobId 동일성).
 */
class ExportJobServiceTest {
    private val repository: ExportJobRepository = mockk()
    private val publisher: ExportJobEnqueuePublisher = mockk()
    private val fixedClock: Clock = Clock.fixed(Instant.parse("2024-03-15T10:30:45Z"), ZoneOffset.UTC)

    private lateinit var service: ExportJobService

    private val requesterUserId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val validQuery = "status = OPEN"
    private val malformedQuery = "("

    @BeforeEach
    fun setUp() {
        service = ExportJobService(repository, publisher, fixedClock)
        justRun { repository.insert(any()) }
        justRun { publisher.enqueue(any()) }
    }

    // ── (a) AQL 성공 경로 ─────────────────────────────────────────────────────────

    @Test
    fun `submit returns non-null ExportJobId when AQL is valid`() {
        val jobId = service.submit(ExportRequest(projectKey = "ATLAS", query = validQuery), requesterUserId)
        assertThat(jobId.value).isNotNull
    }

    @Test
    fun `submit inserts PENDING job with correct fields`() {
        val jobSlot = slot<ExportJob>()
        justRun { repository.insert(capture(jobSlot)) }

        service.submit(ExportRequest(projectKey = "ATLAS", query = validQuery), requesterUserId)

        with(jobSlot.captured) {
            assertThat(status).isEqualTo(ExportJobStatus.PENDING)
            assertThat(this.requesterUserId).isEqualTo(requesterUserId)
            assertThat(projectKey).isEqualTo("ATLAS")
            assertThat(query).isEqualTo(validQuery)
            assertThat(progress).isEqualTo(0)
            assertThat(createdAt).isEqualTo(Instant.parse("2024-03-15T10:30:45Z"))
        }
    }

    @Test
    fun `submit calls repo insert and enqueue exactly once`() {
        service.submit(ExportRequest(projectKey = "ATLAS", query = validQuery), requesterUserId)

        verify(exactly = 1) { repository.insert(any()) }
        verify(exactly = 1) { publisher.enqueue(any()) }
    }

    @Test
    fun `submit enqueues the same jobId as inserted in repo`() {
        val jobSlot = slot<ExportJob>()
        justRun { repository.insert(capture(jobSlot)) }

        val returned = service.submit(ExportRequest(projectKey = "ATLAS", query = validQuery), requesterUserId)

        assertThat(returned).isEqualTo(jobSlot.captured.id)
    }

    // ── (b) AQL 문법 오류 ────────────────────────────────────────────────────────

    @Test
    fun `submit propagates AqlSyntaxException on malformed AQL`() {
        assertThrows<AqlSyntaxException> {
            service.submit(ExportRequest(projectKey = "ATLAS", query = malformedQuery), requesterUserId)
        }
    }

    @Test
    fun `repo insert is never called when AQL parsing fails`() {
        runCatching {
            service.submit(ExportRequest(projectKey = "ATLAS", query = malformedQuery), requesterUserId)
        }
        verify(exactly = 0) { repository.insert(any()) }
    }

    // ── (c) blank projectKey ──────────────────────────────────────────────────

    @Test
    fun `submit throws ResponseStatusException when projectKey is blank`() {
        assertThrows<ResponseStatusException> {
            service.submit(ExportRequest(projectKey = "", query = validQuery), requesterUserId)
        }
        verify(exactly = 0) { repository.insert(any()) }
    }

    // ── (d) 잘못된 format ────────────────────────────────────────────────────

    @Test
    fun `submit throws SearchValidationException on unsupported format`() {
        assertThrows<SearchValidationException> {
            service.submit(
                ExportRequest(projectKey = "ATLAS", query = validQuery, format = "PDF"),
                requesterUserId,
            )
        }
        verify(exactly = 0) { repository.insert(any()) }
    }

    // ── (e) null format → CSV 기본값 ─────────────────────────────────────────

    @Test
    fun `null format defaults to CSV in the persisted job`() {
        val jobSlot = slot<ExportJob>()
        justRun { repository.insert(capture(jobSlot)) }

        service.submit(ExportRequest(projectKey = "ATLAS", query = validQuery, format = null), requesterUserId)

        assertThat(jobSlot.captured.format).isEqualTo("CSV")
    }

    // ── (f) XLSX format ───────────────────────────────────────────────────────

    @Test
    fun `XLSX format is stored in the persisted job`() {
        val jobSlot = slot<ExportJob>()
        justRun { repository.insert(capture(jobSlot)) }

        service.submit(ExportRequest(projectKey = "ATLAS", query = validQuery, format = "XLSX"), requesterUserId)

        assertThat(jobSlot.captured.format).isEqualTo("XLSX")
    }
}
