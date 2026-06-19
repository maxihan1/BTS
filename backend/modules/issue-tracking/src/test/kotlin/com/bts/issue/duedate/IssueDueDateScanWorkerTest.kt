// IssueDueDateScanWorker 단위 테스트 — 마감 임박/지연 이슈 스캔, 결함격리, 빈 목록, occurredAt 검증

package com.bts.issue.duedate

import com.bts.issue.repository.IssueDueScanItem
import com.bts.issue.repository.IssueRepository
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * IssueDueDateScanWorker 단위 테스트.
 *
 * 검증 범위.
 * - 임박 item → emitter.emitDueSoon 호출 (today+1 인자 확인)
 * - 지연 item → emitter.emitOverdue 호출 (today 인자 확인)
 * - repo 에 today+1 (임박) · today (지연) 날짜 정확 전달 (Clock.fixed 핀)
 * - emitter 한 호출이 예외를 던져도 나머지 item 이 처리된다 (결함격리)
 * - 빈 결과 → emitter 미호출
 * - occurredAt = today.atStartOfDay(UTC) 정확
 *
 * Clock.fixed 로 기준 날짜를 고정해 time-bomb 방지.
 */
class IssueDueDateScanWorkerTest : DescribeSpec({

    val repo = mockk<IssueRepository>()
    val emitter = mockk<IssueDueEventEmitter>()

    /**
     * 고정 기준 시각: 2026-06-19T00:00:00Z
     * KST 기준 today = 2026-06-19 (UTC 자정 = KST 오전 9시)
     */
    val fixedInstant: Instant = Instant.parse("2026-06-19T00:00:00Z")
    val fixedClock: Clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)

    /** KST(Asia/Seoul) 기준 today */
    val today: LocalDate = LocalDate.of(2026, 6, 19)

    /** occurredAt = today.atStartOfDay(UTC) */
    val expectedOccurredAt: Instant = today.atStartOfDay(ZoneOffset.UTC).toInstant()

    val worker = IssueDueDateScanWorker(repo, emitter, fixedClock)

    afterEach { clearMocks(repo, emitter) }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    fun item(issueKey: String, projectKey: String = "PROJ"): IssueDueScanItem =
        IssueDueScanItem(issueKey = issueKey, projectKey = projectKey)

    // ── 테스트 ─────────────────────────────────────────────────────────────────

    describe("scan") {

        context("임박 item 이 있을 때") {
            it("repo.findOpenIssuesDueOn 을 today+1 로 호출하고 emitter.emitDueSoon 을 호출한다") {
                val dueSoonItem = item("PROJ-1")
                every { repo.findOpenIssuesDueOn(today.plusDays(1)) } returns listOf(dueSoonItem)
                every { repo.findOpenOverdueIssues(today) } returns emptyList()
                justRun { emitter.emitDueSoon(dueSoonItem, expectedOccurredAt) }

                worker.scan()

                verify(exactly = 1) { repo.findOpenIssuesDueOn(today.plusDays(1)) }
                verify(exactly = 1) { emitter.emitDueSoon(dueSoonItem, expectedOccurredAt) }
            }
        }

        context("지연 item 이 있을 때") {
            it("repo.findOpenOverdueIssues 를 today 로 호출하고 emitter.emitOverdue 를 호출한다") {
                val overdueItem = item("PROJ-2")
                every { repo.findOpenIssuesDueOn(today.plusDays(1)) } returns emptyList()
                every { repo.findOpenOverdueIssues(today) } returns listOf(overdueItem)
                justRun { emitter.emitOverdue(overdueItem, expectedOccurredAt) }

                worker.scan()

                verify(exactly = 1) { repo.findOpenOverdueIssues(today) }
                verify(exactly = 1) { emitter.emitOverdue(overdueItem, expectedOccurredAt) }
            }
        }

        context("occurredAt 검증") {
            it("occurredAt 은 today.atStartOfDay(UTC) 와 정확히 일치한다") {
                val dueSoonItem = item("PROJ-3")
                every { repo.findOpenIssuesDueOn(today.plusDays(1)) } returns listOf(dueSoonItem)
                every { repo.findOpenOverdueIssues(today) } returns emptyList()
                justRun { emitter.emitDueSoon(dueSoonItem, expectedOccurredAt) }

                worker.scan()

                // verify 의 인자 매칭으로 occurredAt 정확성 보장
                verify(exactly = 1) { emitter.emitDueSoon(dueSoonItem, expectedOccurredAt) }
            }
        }

        context("결함격리 — emitter 한 호출이 예외를 던질 때") {
            it("실패한 item 다음 item 도 계속 처리한다") {
                val item1 = item("PROJ-1")
                val item2 = item("PROJ-2")
                every { repo.findOpenIssuesDueOn(today.plusDays(1)) } returns listOf(item1, item2)
                every { repo.findOpenOverdueIssues(today) } returns emptyList()
                every { emitter.emitDueSoon(item1, expectedOccurredAt) } throws RuntimeException("pg error")
                justRun { emitter.emitDueSoon(item2, expectedOccurredAt) }

                // 예외가 scan() 밖으로 전파되지 않아야 한다
                worker.scan()

                // item2 는 정상 처리되어야 한다
                verify(exactly = 1) { emitter.emitDueSoon(item2, expectedOccurredAt) }
            }

            it("overdue 목록에서 한 item 이 실패해도 나머지를 처리한다") {
                val item1 = item("PROJ-3")
                val item2 = item("PROJ-4")
                every { repo.findOpenIssuesDueOn(today.plusDays(1)) } returns emptyList()
                every { repo.findOpenOverdueIssues(today) } returns listOf(item1, item2)
                every { emitter.emitOverdue(item1, expectedOccurredAt) } throws RuntimeException("tx error")
                justRun { emitter.emitOverdue(item2, expectedOccurredAt) }

                worker.scan()

                verify(exactly = 1) { emitter.emitOverdue(item2, expectedOccurredAt) }
            }
        }

        context("빈 결과일 때") {
            it("emitter 를 호출하지 않는다") {
                every { repo.findOpenIssuesDueOn(any()) } returns emptyList()
                every { repo.findOpenOverdueIssues(any()) } returns emptyList()

                worker.scan()

                verify(exactly = 0) { emitter.emitDueSoon(any(), any()) }
                verify(exactly = 0) { emitter.emitOverdue(any(), any()) }
            }
        }
    }
})
