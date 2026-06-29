// Export 작업 TTL 정리 워커 단위 테스트 — 만료 작업 MinIO 삭제 + DB 삭제, Clock.fixed 시각 핀 (FR-EX-02 Task 9)

package com.bts.search.export.job.worker

import com.bts.search.export.job.domain.ExportJob
import com.bts.search.export.job.domain.ExportJobId
import com.bts.search.export.job.domain.ExportJobStatus
import com.bts.search.export.job.repository.ExportJobRepository
import com.bts.search.export.job.storage.ExportObjectStoragePort
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * FR-EX-02 Task 9 — ExportJobCleanupWorker 단위 테스트.
 *
 * 검증 범위:
 * - 만료 작업 없음 → storage.remove / deleteById 호출 없음
 * - 만료 작업 2건 (objectKey 있음) → storage.remove → deleteById 순 실행
 * - objectKey null → storage.remove 건너뛰고 deleteById 만 호출
 * - storage.remove 예외 시 best-effort → deleteById 계속 호출
 * - Clock.fixed 로 기준 시각 핀 → time-bomb 방지 (authcontroller-revokesession-timebomb)
 *
 * 단위 테스트 — ExportJobRepository / ExportObjectStoragePort 는 MockK 모의 객체.
 */
class ExportJobCleanupWorkerTest : DescribeSpec({

    val exportRepo = mockk<ExportJobRepository>()
    val storage = mockk<ExportObjectStoragePort>()

    /** 고정 현재 시각: 2026-06-29T12:00:00Z */
    val fixedNow: Instant = Instant.parse("2026-06-29T12:00:00Z")
    val fixedClock: Clock = Clock.fixed(fixedNow, ZoneOffset.UTC)

    val worker = ExportJobCleanupWorker(exportRepo, storage, fixedClock)

    afterEach { clearMocks(exportRepo, storage) }

    describe("cleanupExpired") {

        context("만료 작업이 없을 때") {
            it("storage.remove 와 deleteById 를 호출하지 않는다") {
                every { exportRepo.findExpired(fixedNow) } returns emptyList()

                worker.cleanupExpired()

                verify(exactly = 0) { storage.remove(any()) }
                verify(exactly = 0) { exportRepo.deleteById(any()) }
            }
        }

        context("만료 작업 2건 (objectKey 있음)") {
            val job1 = makeExpiredJob(ExportJobId(UUID.randomUUID()), objectKey = "PROJ/job1.csv")
            val job2 = makeExpiredJob(ExportJobId(UUID.randomUUID()), objectKey = "PROJ/job2.xlsx")

            beforeEach {
                every { exportRepo.findExpired(fixedNow) } returns listOf(job1, job2)
                justRun { storage.remove(any()) }
                justRun { exportRepo.deleteById(any()) }
            }

            it("각 작업에 대해 storage.remove → deleteById 순으로 실행한다") {
                worker.cleanupExpired()

                verifyOrder {
                    storage.remove(job1.resultObjectKey!!)
                    exportRepo.deleteById(job1.id)
                    storage.remove(job2.resultObjectKey!!)
                    exportRepo.deleteById(job2.id)
                }
            }
        }

        context("만료 작업의 resultObjectKey 가 null 인 경우") {
            val job = makeExpiredJob(ExportJobId(UUID.randomUUID()), objectKey = null)

            it("storage.remove 를 건너뛰고 deleteById 만 호출한다") {
                every { exportRepo.findExpired(fixedNow) } returns listOf(job)
                justRun { exportRepo.deleteById(any()) }

                worker.cleanupExpired()

                verify(exactly = 0) { storage.remove(any()) }
                verify(exactly = 1) { exportRepo.deleteById(job.id) }
            }
        }

        context("storage.remove 가 예외를 던질 때 (best-effort)") {
            val job = makeExpiredJob(ExportJobId(UUID.randomUUID()), objectKey = "PROJ/fail.csv")

            it("deleteById 는 계속 호출한다") {
                every { exportRepo.findExpired(fixedNow) } returns listOf(job)
                every { storage.remove(any()) } throws RuntimeException("MinIO 연결 오류")
                justRun { exportRepo.deleteById(any()) }

                worker.cleanupExpired()

                verify(exactly = 1) { exportRepo.deleteById(job.id) }
            }
        }

        context("Clock.fixed 로 기준 시각 핀 — time-bomb 방지") {
            it("findExpired 를 fixedNow 로 호출한다") {
                val capturedInstant = slot<Instant>()
                every { exportRepo.findExpired(capture(capturedInstant)) } returns emptyList()

                worker.cleanupExpired()

                assertThat(capturedInstant.captured).isEqualTo(fixedNow)
            }
        }
    }
})

// ── test helpers ───────────────────────────────────────────────────────────────

/**
 * 테스트용 만료 [ExportJob] 인스턴스를 생성한다.
 * status=COMPLETED, expires_at = 1초 전.
 */
private fun makeExpiredJob(
    id: ExportJobId,
    objectKey: String?,
): ExportJob =
    ExportJob(
        id = id,
        projectKey = "PROJ",
        query = "status = OPEN",
        format = "CSV",
        columns = emptyList(),
        requesterUserId = UUID.randomUUID(),
        status = ExportJobStatus.COMPLETED,
        progress = 100,
        rowCount = 100L,
        resultObjectKey = objectKey,
        errorCode = null,
        expiresAt = Instant.now().minusSeconds(1),
        createdAt = Instant.now().minusSeconds(90_000),
        startedAt = Instant.now().minusSeconds(89_000),
        completedAt = Instant.now().minusSeconds(88_000),
    )
