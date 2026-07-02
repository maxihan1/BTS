// ImportJob 도메인 단위 테스트 — progressPercent 계산, errorLogReady 조건 (FR-IM-01 PR1 Task 2)

package com.bts.search.imports.job.domain

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.UUID

/**
 * [ImportJob] 도메인 단위 테스트.
 *
 * 검증 범위.
 * - [ImportJob.progressPercent] 계산 (total=0 이면 100, 그 외 processed 곱하기 100 나누기 total 을 내림)
 * - [ImportJob.errorLogReady] 조건 (status==COMPLETED 이고 errorLogObjectKey 가 null 이 아닐 때만 true)
 *
 * `export` BC [com.bts.search.export.job.domain.ExportJobTest] 를 1:1 미러한다.
 */
class ImportJobTest : DescribeSpec({

    fun sampleJob(
        status: ImportJobStatus = ImportJobStatus.PENDING,
        errorLogObjectKey: String? = null,
    ) = ImportJob(
        id = ImportJobId(UUID.randomUUID()),
        projectKey = "ATLAS",
        format = "CSV",
        sourceObjectKey = "imports/atlas/source.csv",
        dryRun = false,
        requesterUserId = UUID.randomUUID(),
        status = status,
        progress = 0,
        totalRows = null,
        succeededRows = 0L,
        failedRows = 0L,
        errorCode = null,
        errorLogObjectKey = errorLogObjectKey,
        expiresAt = null,
        createdAt = Instant.now(),
        startedAt = null,
        completedAt = null,
    )

    // ── (a) progressPercent 계산 ────────────────────────────────────────────────

    describe("ImportJob.progressPercent") {
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

    // ── (b) errorLogReady 조건 ──────────────────────────────────────────────────

    describe("ImportJob.errorLogReady") {
        it("COMPLETED + errorLogObjectKey 있음 → true") {
            sampleJob(status = ImportJobStatus.COMPLETED, errorLogObjectKey = "ATLAS/job-1-errors.csv")
                .errorLogReady shouldBe true
        }

        it("COMPLETED + errorLogObjectKey null → false") {
            sampleJob(status = ImportJobStatus.COMPLETED, errorLogObjectKey = null)
                .errorLogReady shouldBe false
        }

        it("RUNNING + errorLogObjectKey 있음 → false") {
            sampleJob(status = ImportJobStatus.RUNNING, errorLogObjectKey = "ATLAS/job-1-errors.csv")
                .errorLogReady shouldBe false
        }

        it("PENDING + errorLogObjectKey null → false") {
            sampleJob(status = ImportJobStatus.PENDING, errorLogObjectKey = null)
                .errorLogReady shouldBe false
        }

        it("FAILED + errorLogObjectKey 있음 → false") {
            sampleJob(status = ImportJobStatus.FAILED, errorLogObjectKey = "ATLAS/job-1-errors.csv")
                .errorLogReady shouldBe false
        }
    }

    // ── MAX_ROWS 상수 ───────────────────────────────────────────────────────────

    describe("ImportJob.MAX_ROWS 상수") {
        it("MAX_ROWS = 100_000") {
            ImportJob.MAX_ROWS shouldBe 100_000L
        }
    }
})
