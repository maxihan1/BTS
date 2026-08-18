// ExportJob 도메인 단위 테스트 — 상태 전환 규칙, progressPercent 계산, downloadReady 조건

package com.bts.search.export.job.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.UUID

/**
 * [ExportJob] 도메인 단위 테스트.
 *
 * 검증 범위.
 * - [ExportJobStatus] 허용/거부 전환 규칙
 * - [ExportJob.progressPercent] 계산 (total=0 → 100, 그 외 (processed*100/total) 내림)
 * - [ExportJob.downloadReady] 조건 (status==COMPLETED && resultObjectKey != null)
 */
class ExportJobTest : DescribeSpec({

    fun sampleJob(
        status: ExportJobStatus = ExportJobStatus.PENDING,
        resultObjectKey: String? = null,
    ) = ExportJob(
        id = ExportJobId(UUID.randomUUID()),
        projectKey = "ATLAS",
        query = "status = OPEN",
        format = "CSV",
        columns = listOf("KEY", "SUMMARY"),
        requesterUserId = UUID.randomUUID(),
        status = status,
        progress = 0,
        rowCount = null,
        resultObjectKey = resultObjectKey,
        errorCode = null,
        expiresAt = null,
        createdAt = Instant.now(),
        startedAt = null,
        completedAt = null,
    )

    // ── (a) 상태 전환 규칙 ──────────────────────────────────────────────────────

    describe("ExportJobStatus 전환 규칙") {
        it("PENDING → RUNNING 허용") {
            ExportJobStatus.PENDING.transitionTo(ExportJobStatus.RUNNING) shouldBe ExportJobStatus.RUNNING
        }

        it("RUNNING → COMPLETED 허용") {
            ExportJobStatus.RUNNING.transitionTo(ExportJobStatus.COMPLETED) shouldBe ExportJobStatus.COMPLETED
        }

        it("PENDING → FAILED 허용") {
            ExportJobStatus.PENDING.transitionTo(ExportJobStatus.FAILED) shouldBe ExportJobStatus.FAILED
        }

        it("RUNNING → FAILED 허용") {
            ExportJobStatus.RUNNING.transitionTo(ExportJobStatus.FAILED) shouldBe ExportJobStatus.FAILED
        }

        it("PENDING → COMPLETED 거부 — 직접 완료 불가") {
            shouldThrow<IllegalStateException> {
                ExportJobStatus.PENDING.transitionTo(ExportJobStatus.COMPLETED)
            }
        }

        it("COMPLETED → RUNNING 거부 — 종단 상태 재전환 불가") {
            shouldThrow<IllegalStateException> {
                ExportJobStatus.COMPLETED.transitionTo(ExportJobStatus.RUNNING)
            }
        }

        it("COMPLETED → FAILED 거부 — 종단 상태 재전환 불가") {
            shouldThrow<IllegalStateException> {
                ExportJobStatus.COMPLETED.transitionTo(ExportJobStatus.FAILED)
            }
        }

        it("FAILED → RUNNING 거부 — 종단 상태 재전환 불가") {
            shouldThrow<IllegalStateException> {
                ExportJobStatus.FAILED.transitionTo(ExportJobStatus.RUNNING)
            }
        }

        it("FAILED → COMPLETED 거부 — 종단 상태 재전환 불가") {
            shouldThrow<IllegalStateException> {
                ExportJobStatus.FAILED.transitionTo(ExportJobStatus.COMPLETED)
            }
        }
    }

    // ── (b) progressPercent 계산 ────────────────────────────────────────────────

    describe("ExportJob.progressPercent") {
        it("total=0 이면 100 반환") {
            sampleJob().progressPercent(processed = 0L, total = 0L) shouldBe 100
        }

        it("total=0 이고 processed>0 이어도 100 반환") {
            sampleJob().progressPercent(processed = 50L, total = 0L) shouldBe 100
        }

        it("processed=0, total=100 → 0") {
            sampleJob().progressPercent(processed = 0L, total = 100L) shouldBe 0
        }

        it("processed=50, total=100 → 50") {
            sampleJob().progressPercent(processed = 50L, total = 100L) shouldBe 50
        }

        it("processed=100, total=100 → 100") {
            sampleJob().progressPercent(processed = 100L, total = 100L) shouldBe 100
        }

        it("processed=1, total=3 → 33 (내림 확인)") {
            sampleJob().progressPercent(processed = 1L, total = 3L) shouldBe 33
        }

        it("processed=99_999, total=100_000 → 99") {
            sampleJob().progressPercent(processed = 99_999L, total = 100_000L) shouldBe 99
        }
    }

    // ── (c) downloadReady 조건 ──────────────────────────────────────────────────

    describe("ExportJob.downloadReady") {
        it("COMPLETED + resultObjectKey 있음 → true") {
            sampleJob(status = ExportJobStatus.COMPLETED, resultObjectKey = "ATLAS/job-1.csv")
                .downloadReady shouldBe true
        }

        it("COMPLETED + resultObjectKey null → false") {
            sampleJob(status = ExportJobStatus.COMPLETED, resultObjectKey = null)
                .downloadReady shouldBe false
        }

        it("RUNNING + resultObjectKey 있음 → false") {
            sampleJob(status = ExportJobStatus.RUNNING, resultObjectKey = "ATLAS/job-1.csv")
                .downloadReady shouldBe false
        }

        it("PENDING + resultObjectKey null → false") {
            sampleJob(status = ExportJobStatus.PENDING, resultObjectKey = null)
                .downloadReady shouldBe false
        }

        it("FAILED + resultObjectKey 있음 → false") {
            sampleJob(status = ExportJobStatus.FAILED, resultObjectKey = "ATLAS/job-1.csv")
                .downloadReady shouldBe false
        }
    }

    // ── MAX_ROWS 상수 ───────────────────────────────────────────────────────────

    describe("ExportJob.MAX_ROWS 상수") {
        it("MAX_ROWS = 100_000") {
            ExportJob.MAX_ROWS shouldBe 100_000L
        }
    }
})
