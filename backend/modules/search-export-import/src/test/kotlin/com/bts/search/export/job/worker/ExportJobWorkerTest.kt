// Export 작업 pgmq consumer 단위 테스트 — 폴링, CAS 클레임, processor 호출, delete (FR-EX-02 Task 9)

package com.bts.search.export.job.worker

import com.bts.search.export.job.application.ExportJobProcessor
import com.bts.search.export.job.domain.ExportJob
import com.bts.search.export.job.domain.ExportJobId
import com.bts.search.export.job.domain.ExportJobStatus
import com.bts.search.export.job.repository.ExportJobRepository
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.jooq.DSLContext
import java.time.Instant
import java.util.UUID

/**
 * FR-EX-02 Task 9 — ExportJobWorker 단위 테스트.
 *
 * 검증 범위:
 * (a) pgmq.read 메시지 → claimForRun true → findById → processor.process → pgmq.delete
 * (b) claimForRun false + 종단(COMPLETED/FAILED) → delete (무한재전달 차단)
 * (c) RUNNING not-stale → delete 안 함 (skip)
 * (d) poison(파싱불가/job없음) read_ct>MAX → pgmq.archive
 * (e) 예외 시 delete 생략 (at-least-once)
 * (f) @Transactional 없음 — 클래스 KDoc 에 의도 명시
 *
 * 단위 테스트 — DSLContext / ExportJobRepository / ExportJobProcessor 는 MockK 모의 객체.
 */
class ExportJobWorkerTest : DescribeSpec({

    val dsl = mockk<DSLContext>()
    val exportRepo = mockk<ExportJobRepository>()
    val processor = mockk<ExportJobProcessor>(relaxed = false)

    val worker = ExportJobWorker(dsl, exportRepo, processor)

    afterEach { clearMocks(dsl, exportRepo, processor) }

    describe("pollAndProcess") {

        context("큐에 메시지가 없을 때") {
            it("아무 처리도 하지 않는다") {
                every {
                    dsl.fetch(
                        any<String>(),
                        ExportJobWorker.QUEUE_NAME,
                        ExportJobWorker.VISIBILITY_TIMEOUT_SECONDS,
                        ExportJobWorker.POLL_BATCH_SIZE,
                    )
                } returns mockk(relaxed = true) { every { isEmpty() } returns true }

                worker.pollAndProcess()

                verify(exactly = 0) { exportRepo.claimForRun(any()) }
                verify(exactly = 0) { processor.process(any()) }
            }
        }

        context("큐에 메시지 1건, claimForRun 성공, processor.process 성공") {
            val jobId = ExportJobId(UUID.randomUUID())
            val msgId = 42L
            val job = makeJob(jobId)

            beforeEach {
                stubReadOneMessage(dsl, jobId, msgId)
                every { exportRepo.claimForRun(jobId) } returns true
                every { exportRepo.findById(jobId) } returns job
                justRun { processor.process(any()) }
                every { dsl.execute(any<String>(), ExportJobWorker.QUEUE_NAME, msgId) } returns 1
            }

            it("claimForRun → findById → processor.process → delete 순으로 실행한다") {
                worker.pollAndProcess()

                verifyOrder {
                    exportRepo.claimForRun(jobId)
                    exportRepo.findById(jobId)
                    processor.process(job)
                    dsl.execute(any<String>(), ExportJobWorker.QUEUE_NAME, msgId)
                }
            }
        }

        context("claimForRun false — COMPLETED 종단 상태") {
            val jobId = ExportJobId(UUID.randomUUID())
            val msgId = 99L

            beforeEach {
                stubReadOneMessage(dsl, jobId, msgId)
                every { exportRepo.claimForRun(jobId) } returns false
                every { exportRepo.findStatus(jobId) } returns ExportJobStatus.COMPLETED
                every { dsl.execute(any<String>(), ExportJobWorker.QUEUE_NAME, msgId) } returns 1
            }

            it("processor 를 호출하지 않고 메시지를 삭제한다 (무한 재전달 차단)") {
                worker.pollAndProcess()

                verify(exactly = 0) { processor.process(any()) }
                verify(exactly = 1) { dsl.execute(any<String>(), ExportJobWorker.QUEUE_NAME, msgId) }
            }
        }

        context("claimForRun false — FAILED 종단 상태") {
            val jobId = ExportJobId(UUID.randomUUID())
            val msgId = 100L

            beforeEach {
                stubReadOneMessage(dsl, jobId, msgId)
                every { exportRepo.claimForRun(jobId) } returns false
                every { exportRepo.findStatus(jobId) } returns ExportJobStatus.FAILED
                every { dsl.execute(any<String>(), ExportJobWorker.QUEUE_NAME, msgId) } returns 1
            }

            it("FAILED 종단 작업도 메시지를 삭제한다") {
                worker.pollAndProcess()

                verify(exactly = 0) { processor.process(any()) }
                verify(exactly = 1) { dsl.execute(any<String>(), ExportJobWorker.QUEUE_NAME, msgId) }
            }
        }

        context("claimForRun false — RUNNING (아직 stale 아님)") {
            val jobId = ExportJobId(UUID.randomUUID())
            val msgId = 101L

            beforeEach {
                stubReadOneMessage(dsl, jobId, msgId)
                every { exportRepo.claimForRun(jobId) } returns false
                every { exportRepo.findStatus(jobId) } returns ExportJobStatus.RUNNING
            }

            it("다른 워커가 처리 중이므로 메시지를 삭제하지 않는다 (정상 재전달 허용)") {
                worker.pollAndProcess()

                verify(exactly = 0) { processor.process(any()) }
                verify(exactly = 0) { dsl.execute(any<String>(), ExportJobWorker.QUEUE_NAME, msgId) }
            }
        }

        context("파싱 불가 메시지 (poison), read_ct > MAX") {
            val msgId = 200L

            beforeEach {
                stubReadPoisonMessage(dsl, msgId, readCt = ExportJobWorker.MAX_RECEIVE_COUNT + 1)
                every {
                    dsl.execute(
                        match<String> { it.contains("pgmq.archive") },
                        ExportJobWorker.QUEUE_NAME,
                        msgId,
                    )
                } returns 1
            }

            it("read_ct 초과 시 archive 를 호출한다 (dead-letter)") {
                worker.pollAndProcess()

                verify(exactly = 0) { processor.process(any()) }
                verify(exactly = 1) {
                    dsl.execute(
                        match<String> { it.contains("pgmq.archive") },
                        ExportJobWorker.QUEUE_NAME,
                        msgId,
                    )
                }
            }
        }

        context("파싱 불가 메시지 (poison), read_ct <= MAX") {
            val msgId = 201L

            beforeEach {
                stubReadPoisonMessage(dsl, msgId, readCt = 1)
            }

            it("read_ct 미초과 시 archive 를 호출하지 않는다 (재전달 대기)") {
                worker.pollAndProcess()

                verify(exactly = 0) { processor.process(any()) }
                verify(exactly = 0) {
                    dsl.execute(
                        match<String> { it.contains("pgmq.archive") },
                        any(),
                        any<Long>(),
                    )
                }
            }
        }

        context("processor.process 가 예외를 던질 때") {
            val jobId = ExportJobId(UUID.randomUUID())
            val msgId = 55L
            val job = makeJob(jobId)

            beforeEach {
                stubReadOneMessage(dsl, jobId, msgId)
                every { exportRepo.claimForRun(jobId) } returns true
                every { exportRepo.findById(jobId) } returns job
                every { processor.process(any()) } throws RuntimeException("처리 오류")
            }

            it("delete 를 호출하지 않는다 (vt 만료 후 재전달 허용 — at-least-once)") {
                worker.pollAndProcess()

                verify(exactly = 0) { dsl.execute(any<String>(), ExportJobWorker.QUEUE_NAME, msgId) }
            }
        }
    }
})

// ── test helpers ───────────────────────────────────────────────────────────────

/**
 * 테스트용 [ExportJob] 인스턴스를 생성한다.
 * status=RUNNING — claimForRun 성공 후 findById 가 반환하는 상태.
 */
private fun makeJob(id: ExportJobId): ExportJob =
    ExportJob(
        id = id,
        projectKey = "PROJ",
        query = "status = OPEN",
        format = "CSV",
        columns = emptyList(),
        requesterUserId = UUID.randomUUID(),
        status = ExportJobStatus.RUNNING,
        progress = 0,
        rowCount = null,
        resultObjectKey = null,
        errorCode = null,
        expiresAt = null,
        createdAt = Instant.now(),
        startedAt = Instant.now(),
        completedAt = null,
    )

/**
 * dsl.fetch("SELECT * FROM pgmq.read(…)") 가 단일 메시지 1건을 반환하도록 스텁한다.
 * 메시지 JSON 형식: `{"exportJobId":"<UUID>"}`.
 */
private fun stubReadOneMessage(
    dsl: DSLContext,
    jobId: ExportJobId,
    msgId: Long,
    readCt: Int = 1,
) {
    val row =
        mockk<org.jooq.Record>(relaxed = true) {
            every { get("msg_id", Long::class.java) } returns msgId
            every { get("message", String::class.java) } returns """{"exportJobId":"${jobId.value}"}"""
            every { get("read_ct", Int::class.java) } returns readCt
        }
    val result =
        mockk<org.jooq.Result<org.jooq.Record>>(relaxed = true) {
            every { isEmpty() } returns false
            every { iterator() } answers { mutableListOf(row).iterator() }
        }
    every {
        dsl.fetch(
            any<String>(),
            ExportJobWorker.QUEUE_NAME,
            ExportJobWorker.VISIBILITY_TIMEOUT_SECONDS,
            ExportJobWorker.POLL_BATCH_SIZE,
        )
    } returns result
}

/**
 * exportJobId 필드가 없는 poison 메시지를 반환하도록 스텁한다.
 * parseJobId 가 null 을 반환하게 해 poison 경로를 검증한다.
 */
private fun stubReadPoisonMessage(
    dsl: DSLContext,
    msgId: Long,
    readCt: Int = 1,
) {
    val row =
        mockk<org.jooq.Record>(relaxed = true) {
            every { get("msg_id", Long::class.java) } returns msgId
            every { get("message", String::class.java) } returns """{"not_an_export":"garbage"}"""
            every { get("read_ct", Int::class.java) } returns readCt
        }
    val result =
        mockk<org.jooq.Result<org.jooq.Record>>(relaxed = true) {
            every { isEmpty() } returns false
            every { iterator() } answers { mutableListOf(row).iterator() }
        }
    every {
        dsl.fetch(
            any<String>(),
            ExportJobWorker.QUEUE_NAME,
            ExportJobWorker.VISIBILITY_TIMEOUT_SECONDS,
            ExportJobWorker.POLL_BATCH_SIZE,
        )
    } returns result
}
