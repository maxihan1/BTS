// WorklogAggregateService 단위 테스트 — MockK. 권한·버킷매핑·user레이블·정렬·C5 검증 (FR-TT-02 Task 2).

package com.bts.issue.worklog.aggregate.application

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.worklog.aggregate.domain.AggregateGranularity
import com.bts.issue.worklog.aggregate.domain.WorklogAggregateDimension
import com.bts.issue.worklog.aggregate.domain.WorklogAggregateRow
import com.bts.issue.worklog.aggregate.repository.WorklogAggregateRepository
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.user.UserLookupPort
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.util.UUID

/**
 * WorklogAggregateService 단위 테스트.
 *
 * repo / resolver / userLookup 을 MockK 로 stub.
 *
 * 검증 목록.
 * - 권한 없음(BROWSE, Project scope) → IssueAccessDeniedException (403)
 * - 권한 있음 → repo 호출 → 버킷 매핑
 * - by=user → UserLookupPort.findDisplayNamesByIds 로 label 채움, 미존재 시 빈 문자열(fail-safe). exactly 1회
 * - by=issue → label = groupKey, userLookup 0회 (C5)
 * - by=period → label = groupKey, userLookup 0회 (C5)
 * - totalTimeSpentSeconds = 버킷 합
 * - 정렬: issue/user DESC(동률 label ASC), period ASC
 * - from > to → 빈 결과 1건 박제(repo가 빈 리스트를 반환할 때 서비스는 그대로 전달)
 */
class WorklogAggregateServiceTest : DescribeSpec({

    val repo = mockk<WorklogAggregateRepository>()
    val resolver = mockk<IssuePermissionResolver>()
    val userLookup = mockk<UserLookupPort>()

    val sut = WorklogAggregateService(
        repo = repo,
        permissionResolver = resolver,
        userLookup = userLookup,
    )

    val actor = ActorId(UUID.randomUUID())
    val projectKey = "TPRJ"
    val projectScope = IssueScope.Project(projectKey)

    fun stubAllowed() {
        every { resolver.hasPermission(actor.value, IssuePermission.BROWSE, projectScope) } returns true
    }

    fun stubDenied() {
        every { resolver.hasPermission(actor.value, IssuePermission.BROWSE, projectScope) } returns false
    }

    fun stubRepo(rows: List<WorklogAggregateRow>) {
        every {
            repo.aggregate(
                projectKey = projectKey,
                dimension = any(),
                granularity = any(),
                from = any(),
                to = any(),
            )
        } returns rows
    }

    beforeEach {
        clearMocks(repo, resolver, userLookup)
    }

    // ── 권한 검증 ─────────────────────────────────────────────────────────────

    describe("권한 검증") {
        it("BROWSE 권한 없음 → IssueAccessDeniedException") {
            stubDenied()

            shouldThrow<IssueAccessDeniedException> {
                sut.aggregate(
                    actorId = actor,
                    projectKey = projectKey,
                    dimension = WorklogAggregateDimension.ISSUE,
                    granularity = null,
                    from = null,
                    to = null,
                )
            }
        }

        it("권한 거부 시 repo 조회 미수행 (존재 probe 차단)") {
            stubDenied()

            shouldThrow<IssueAccessDeniedException> {
                sut.aggregate(
                    actorId = actor,
                    projectKey = projectKey,
                    dimension = WorklogAggregateDimension.ISSUE,
                    granularity = null,
                    from = null,
                    to = null,
                )
            }

            verify(exactly = 0) { repo.aggregate(any(), any(), any(), any(), any()) }
        }
    }

    // ── by=issue ──────────────────────────────────────────────────────────────

    describe("by=issue") {
        it("버킷 매핑 — key=groupKey, label=groupKey, timeSpentSeconds, worklogCount") {
            stubAllowed()
            stubRepo(
                listOf(
                    WorklogAggregateRow(groupKey = "TPRJ-2", timeSpentSeconds = 7200L, worklogCount = 3),
                    WorklogAggregateRow(groupKey = "TPRJ-1", timeSpentSeconds = 3600L, worklogCount = 1),
                ),
            )

            val result = sut.aggregate(
                actorId = actor,
                projectKey = projectKey,
                dimension = WorklogAggregateDimension.ISSUE,
                granularity = null,
                from = null,
                to = null,
            )

            result.buckets shouldHaveSize 2
            result.buckets[0].key shouldBe "TPRJ-2"
            result.buckets[0].label shouldBe "TPRJ-2"
        }

        it("C5 — userLookup.findDisplayNamesByIds 호출 0회") {
            stubAllowed()
            stubRepo(
                listOf(
                    WorklogAggregateRow(groupKey = "TPRJ-1", timeSpentSeconds = 100L, worklogCount = 1),
                ),
            )

            sut.aggregate(
                actorId = actor,
                projectKey = projectKey,
                dimension = WorklogAggregateDimension.ISSUE,
                granularity = null,
                from = null,
                to = null,
            )

            verify(exactly = 0) { userLookup.findDisplayNamesByIds(any()) }
        }

        it("정렬: DESC (timeSpentSeconds 기준), 동률 label ASC") {
            stubAllowed()
            // TPRJ-1과 TPRJ-2 동률, TPRJ-3 최대
            stubRepo(
                listOf(
                    WorklogAggregateRow(groupKey = "TPRJ-1", timeSpentSeconds = 1000L, worklogCount = 2),
                    WorklogAggregateRow(groupKey = "TPRJ-3", timeSpentSeconds = 5000L, worklogCount = 1),
                    WorklogAggregateRow(groupKey = "TPRJ-2", timeSpentSeconds = 1000L, worklogCount = 1),
                ),
            )

            val result = sut.aggregate(
                actorId = actor,
                projectKey = projectKey,
                dimension = WorklogAggregateDimension.ISSUE,
                granularity = null,
                from = null,
                to = null,
            )

            result.buckets.map { it.key } shouldBe listOf("TPRJ-3", "TPRJ-1", "TPRJ-2")
        }

        it("totalTimeSpentSeconds = 버킷 합") {
            stubAllowed()
            stubRepo(
                listOf(
                    WorklogAggregateRow(groupKey = "TPRJ-1", timeSpentSeconds = 3600L, worklogCount = 1),
                    WorklogAggregateRow(groupKey = "TPRJ-2", timeSpentSeconds = 7200L, worklogCount = 2),
                ),
            )

            val result = sut.aggregate(
                actorId = actor,
                projectKey = projectKey,
                dimension = WorklogAggregateDimension.ISSUE,
                granularity = null,
                from = null,
                to = null,
            )

            result.totalTimeSpentSeconds shouldBe 10800L
        }

        it("from > to → repo 빈 결과 그대로 전달 (검증은 컨트롤러 책임)") {
            stubAllowed()
            val futureFrom = Instant.parse("2026-12-31T00:00:00Z")
            val pastTo = Instant.parse("2026-01-01T00:00:00Z")
            every {
                repo.aggregate(
                    projectKey = projectKey,
                    dimension = WorklogAggregateDimension.ISSUE,
                    granularity = null,
                    from = futureFrom,
                    to = pastTo,
                )
            } returns emptyList()

            val result = sut.aggregate(
                actorId = actor,
                projectKey = projectKey,
                dimension = WorklogAggregateDimension.ISSUE,
                granularity = null,
                from = futureFrom,
                to = pastTo,
            )

            result.buckets.shouldBeEmpty()
            result.totalTimeSpentSeconds shouldBe 0L
        }
    }

    // ── by=user ───────────────────────────────────────────────────────────────

    describe("by=user") {
        val userId1 = UUID.randomUUID()
        val userId2 = UUID.randomUUID()

        it("findDisplayNamesByIds 로 label 채움 — 정확히 1회 호출") {
            stubAllowed()
            stubRepo(
                listOf(
                    WorklogAggregateRow(groupKey = userId1.toString(), timeSpentSeconds = 3600L, worklogCount = 2),
                    WorklogAggregateRow(groupKey = userId2.toString(), timeSpentSeconds = 1800L, worklogCount = 1),
                ),
            )
            every {
                userLookup.findDisplayNamesByIds(setOf(userId1, userId2))
            } returns mapOf(userId1 to "Alice", userId2 to "Bob")

            val result = sut.aggregate(
                actorId = actor,
                projectKey = projectKey,
                dimension = WorklogAggregateDimension.USER,
                granularity = null,
                from = null,
                to = null,
            )

            result.buckets shouldHaveSize 2
            result.buckets.find { it.key == userId1.toString() }?.label shouldBe "Alice"
            result.buckets.find { it.key == userId2.toString() }?.label shouldBe "Bob"

            verify(exactly = 1) { userLookup.findDisplayNamesByIds(any()) }
        }

        it("미존재 userId → label = 빈 문자열(fail-safe)") {
            val unknownUserId = UUID.randomUUID()
            stubAllowed()
            stubRepo(
                listOf(
                    WorklogAggregateRow(groupKey = unknownUserId.toString(), timeSpentSeconds = 1000L, worklogCount = 1),
                ),
            )
            every { userLookup.findDisplayNamesByIds(setOf(unknownUserId)) } returns emptyMap()

            val result = sut.aggregate(
                actorId = actor,
                projectKey = projectKey,
                dimension = WorklogAggregateDimension.USER,
                granularity = null,
                from = null,
                to = null,
            )

            result.buckets[0].label shouldBe ""
        }

        it("정렬: DESC (timeSpentSeconds 기준), 동률 label ASC") {
            val userA = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000000")
            val userB = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000000")
            stubAllowed()
            stubRepo(
                listOf(
                    WorklogAggregateRow(groupKey = userA.toString(), timeSpentSeconds = 500L, worklogCount = 1),
                    WorklogAggregateRow(groupKey = userB.toString(), timeSpentSeconds = 500L, worklogCount = 1),
                ),
            )
            every { userLookup.findDisplayNamesByIds(any()) } returns mapOf(userA to "Charlie", userB to "Alice")

            val result = sut.aggregate(
                actorId = actor,
                projectKey = projectKey,
                dimension = WorklogAggregateDimension.USER,
                granularity = null,
                from = null,
                to = null,
            )

            // 동률: label ASC → Alice(userB) 먼저, Charlie(userA) 다음
            result.buckets[0].label shouldBe "Alice"
            result.buckets[1].label shouldBe "Charlie"
        }
    }

    // ── by=period ─────────────────────────────────────────────────────────────

    describe("by=period") {
        it("버킷 매핑 — label = groupKey(날짜 문자열)") {
            stubAllowed()
            every {
                repo.aggregate(
                    projectKey = projectKey,
                    dimension = WorklogAggregateDimension.PERIOD,
                    granularity = AggregateGranularity.DAY,
                    from = null,
                    to = null,
                )
            } returns listOf(
                WorklogAggregateRow(groupKey = "2026-06-01", timeSpentSeconds = 3600L, worklogCount = 1),
                WorklogAggregateRow(groupKey = "2026-06-03", timeSpentSeconds = 7200L, worklogCount = 2),
            )

            val result = sut.aggregate(
                actorId = actor,
                projectKey = projectKey,
                dimension = WorklogAggregateDimension.PERIOD,
                granularity = AggregateGranularity.DAY,
                from = null,
                to = null,
            )

            result.buckets.find { it.key == "2026-06-01" }?.label shouldBe "2026-06-01"
            result.buckets.find { it.key == "2026-06-03" }?.label shouldBe "2026-06-03"
        }

        it("C5 — userLookup.findDisplayNamesByIds 호출 0회") {
            stubAllowed()
            every {
                repo.aggregate(
                    projectKey = projectKey,
                    dimension = WorklogAggregateDimension.PERIOD,
                    granularity = AggregateGranularity.WEEK,
                    from = null,
                    to = null,
                )
            } returns listOf(
                WorklogAggregateRow(groupKey = "2026-06-01", timeSpentSeconds = 1000L, worklogCount = 1),
            )

            sut.aggregate(
                actorId = actor,
                projectKey = projectKey,
                dimension = WorklogAggregateDimension.PERIOD,
                granularity = AggregateGranularity.WEEK,
                from = null,
                to = null,
            )

            verify(exactly = 0) { userLookup.findDisplayNamesByIds(any()) }
        }

        it("정렬: ASC (period는 시간순)") {
            stubAllowed()
            every {
                repo.aggregate(
                    projectKey = projectKey,
                    dimension = WorklogAggregateDimension.PERIOD,
                    granularity = AggregateGranularity.MONTH,
                    from = null,
                    to = null,
                )
            } returns listOf(
                WorklogAggregateRow(groupKey = "2026-03-01", timeSpentSeconds = 1000L, worklogCount = 1),
                WorklogAggregateRow(groupKey = "2026-01-01", timeSpentSeconds = 5000L, worklogCount = 2),
                WorklogAggregateRow(groupKey = "2026-02-01", timeSpentSeconds = 2000L, worklogCount = 1),
            )

            val result = sut.aggregate(
                actorId = actor,
                projectKey = projectKey,
                dimension = WorklogAggregateDimension.PERIOD,
                granularity = AggregateGranularity.MONTH,
                from = null,
                to = null,
            )

            result.buckets.map { it.key } shouldBe listOf("2026-01-01", "2026-02-01", "2026-03-01")
        }
    }
})
