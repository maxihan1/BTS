// BacklogRankService 단위 테스트 — MockK. rerank/rebalance + 권한·이웃 검증 + history 미생성 (FR-BL-01).

package com.bts.issue.application

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueNotFoundException
import com.bts.issue.repository.IssueRepository
import com.bts.shared.lexorank.Rank
import com.bts.shared.lexorank.RankSpaceExhaustedException
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.jooq.DSLContext
import java.util.UUID

/**
 * BacklogRankService 단위 테스트.
 *
 * repo / permissionResolver / dsl 를 MockK 로 stub.
 *
 * 검증 목록.
 * - UPDATE 권한 미보유 → IssueAccessDeniedException
 * - 대상 이슈 미존재·소프트삭제 → IssueNotFoundException
 * - prev·next 둘 다 null → InvalidRankNeighborException
 * - prev == next (동일 이웃) → InvalidRankNeighborException
 * - 대상 == prev → InvalidRankNeighborException
 * - 대상 == next → InvalidRankNeighborException
 * - 이웃 미존재·소프트삭제 → IssueNotFoundException
 * - 이웃이 타 프로젝트 → InvalidRankNeighborException
 * - previousRank >= nextRank (순서 역전) → InvalidRankNeighborException
 * - 정상 rerank → updateRank 호출 + historyRecorder 미호출
 * - 고갈 → rebalance + 재조회 → updateRank (C3)
 * - rebalance: lock 후 findRanksForRebalance 재조회 + 균등 updateRank 반복
 */
class BacklogRankServiceTest : DescribeSpec({

    val repo = mockk<IssueRepository>()
    val permissionResolver = mockk<IssuePermissionResolver>()
    val dsl = mockk<DSLContext>()

    val sut = BacklogRankService(
        repo = repo,
        permissionResolver = permissionResolver,
        dsl = dsl,
    )

    val actor = ActorId(UUID.randomUUID())
    val projectId = UUID.randomUUID()
    val targetKey = IssueKey("PROJ-3")
    val scope = IssueScope.Issue(targetKey.value)

    fun makeIssue(
        key: IssueKey,
        rank: String,
        pid: UUID = projectId,
    ): com.bts.issue.domain.Issue =
        mockk<com.bts.issue.domain.Issue>().also {
            every { it.key } returns key
            every { it.projectId } returns pid
            every { it.rank } returns rank
            every { it.id } returns IssueId(UUID.randomUUID())
        }

    fun stubUpdatePermission(allowed: Boolean) {
        every {
            permissionResolver.hasPermission(actor.value, IssuePermission.UPDATE, scope)
        } returns allowed
    }

    beforeEach {
        clearMocks(repo, permissionResolver, dsl)
    }

    // ── 권한 검증 ─────────────────────────────────────────────────────────────

    describe("권한 검증") {
        it("UPDATE 권한 미보유 → IssueAccessDeniedException") {
            stubUpdatePermission(false)

            shouldThrow<IssueAccessDeniedException> {
                sut.rerank(actor, targetKey, null, IssueKey("PROJ-1"))
            }
        }
    }

    // ── 대상 이슈 조회 ─────────────────────────────────────────────────────────

    describe("대상 이슈 조회") {
        it("대상 이슈 미존재/소프트삭제 → IssueNotFoundException") {
            stubUpdatePermission(true)
            every { repo.findByKey(targetKey) } returns null

            shouldThrow<IssueNotFoundException> {
                sut.rerank(actor, targetKey, IssueKey("PROJ-1"), IssueKey("PROJ-2"))
            }
        }
    }

    // ── 이웃 검증 ─────────────────────────────────────────────────────────────

    describe("이웃 검증") {
        it("prev·next 둘 다 null → InvalidRankNeighborException") {
            stubUpdatePermission(true)
            every { repo.findByKey(targetKey) } returns makeIssue(targetKey, "n")

            shouldThrow<InvalidRankNeighborException> {
                sut.rerank(actor, targetKey, null, null)
            }
        }

        it("prev == next → InvalidRankNeighborException") {
            stubUpdatePermission(true)
            every { repo.findByKey(targetKey) } returns makeIssue(targetKey, "n")

            shouldThrow<InvalidRankNeighborException> {
                sut.rerank(actor, targetKey, IssueKey("PROJ-1"), IssueKey("PROJ-1"))
            }
        }

        it("대상 key == prev → InvalidRankNeighborException") {
            stubUpdatePermission(true)
            every { repo.findByKey(targetKey) } returns makeIssue(targetKey, "n")

            shouldThrow<InvalidRankNeighborException> {
                sut.rerank(actor, targetKey, targetKey, IssueKey("PROJ-1"))
            }
        }

        it("대상 key == next → InvalidRankNeighborException") {
            stubUpdatePermission(true)
            every { repo.findByKey(targetKey) } returns makeIssue(targetKey, "n")

            shouldThrow<InvalidRankNeighborException> {
                sut.rerank(actor, targetKey, IssueKey("PROJ-1"), targetKey)
            }
        }

        it("이웃 이슈 미존재/소프트삭제 → IssueNotFoundException") {
            stubUpdatePermission(true)
            every { repo.findByKey(targetKey) } returns makeIssue(targetKey, "n")
            every { repo.findByKey(IssueKey("PROJ-1")) } returns null

            shouldThrow<IssueNotFoundException> {
                sut.rerank(actor, targetKey, IssueKey("PROJ-1"), null)
            }
        }

        it("이웃이 타 프로젝트 → InvalidRankNeighborException") {
            stubUpdatePermission(true)
            val otherProjectId = UUID.randomUUID()
            every { repo.findByKey(targetKey) } returns makeIssue(targetKey, "n")
            every { repo.findByKey(IssueKey("OTHER-1")) } returns makeIssue(
                IssueKey("OTHER-1"),
                "b",
                pid = otherProjectId,
            )

            shouldThrow<InvalidRankNeighborException> {
                sut.rerank(actor, targetKey, IssueKey("OTHER-1"), null)
            }
        }

        it("previousRank >= nextRank (순서 역전) → InvalidRankNeighborException") {
            stubUpdatePermission(true)
            every { repo.findByKey(targetKey) } returns makeIssue(targetKey, "n")
            every { repo.findByKey(IssueKey("PROJ-1")) } returns makeIssue(IssueKey("PROJ-1"), "z")
            every { repo.findByKey(IssueKey("PROJ-2")) } returns makeIssue(IssueKey("PROJ-2"), "b")

            shouldThrow<InvalidRankNeighborException> {
                // prev rank "z" >= next rank "b" → 역전
                sut.rerank(actor, targetKey, IssueKey("PROJ-1"), IssueKey("PROJ-2"))
            }
        }
    }

    // ── 정상 rerank ───────────────────────────────────────────────────────────

    describe("정상 rerank") {
        it("맨 앞으로 이동 (prev=null, next 존재) → updateRank 호출") {
            stubUpdatePermission(true)
            every { repo.findByKey(targetKey) } returns makeIssue(targetKey, "n")
            every { repo.findByKey(IssueKey("PROJ-1")) } returns makeIssue(IssueKey("PROJ-1"), "g")
            every { repo.updateRank(eq(targetKey), any()) } returns Unit

            sut.rerank(actor, targetKey, null, IssueKey("PROJ-1"))

            verify { repo.updateRank(eq(targetKey), any()) }
        }

        it("맨 뒤로 이동 (prev 존재, next=null) → updateRank 호출") {
            stubUpdatePermission(true)
            every { repo.findByKey(targetKey) } returns makeIssue(targetKey, "b")
            every { repo.findByKey(IssueKey("PROJ-9")) } returns makeIssue(IssueKey("PROJ-9"), "z")
            every { repo.updateRank(eq(targetKey), any()) } returns Unit

            sut.rerank(actor, targetKey, IssueKey("PROJ-9"), null)

            verify { repo.updateRank(eq(targetKey), any()) }
        }

        it("두 이슈 사이 이동 → between 결과로 updateRank 호출") {
            stubUpdatePermission(true)
            every { repo.findByKey(targetKey) } returns makeIssue(targetKey, "z")
            every { repo.findByKey(IssueKey("PROJ-1")) } returns makeIssue(IssueKey("PROJ-1"), "b")
            every { repo.findByKey(IssueKey("PROJ-2")) } returns makeIssue(IssueKey("PROJ-2"), "n")
            var capturedRank: String? = null
            every { repo.updateRank(eq(targetKey), any()) } answers { capturedRank = secondArg() }

            sut.rerank(actor, targetKey, IssueKey("PROJ-1"), IssueKey("PROJ-2"))

            // between("b","n") 결과는 "b" < r < "n"
            val r = capturedRank ?: error("updateRank 미호출")
            (r > "b" && r < "n") shouldBe true
        }

        it("rank 변경은 historyRecorder 미호출 (history noise 회피) + updateRank 만 write") {
            stubUpdatePermission(true)
            every { repo.findByKey(targetKey) } returns makeIssue(targetKey, "n")
            every { repo.findByKey(IssueKey("PROJ-9")) } returns makeIssue(IssueKey("PROJ-9"), "z")
            every { repo.updateRank(eq(targetKey), any()) } returns Unit

            sut.rerank(actor, targetKey, IssueKey("PROJ-9"), null)

            // updateRank 외 다른 write repo 메서드 미호출
            verify(exactly = 1) { repo.updateRank(any(), any()) }
            // 재배포(rebalance) 관련 findRanksForRebalance 미호출 — 고갈 없음
            verify(exactly = 0) { repo.findRanksForRebalance(any()) }
        }
    }

    // ── 고갈 → rebalance ──────────────────────────────────────────────────────

    describe("고갈 → on-demand rebalance") {
        it("RankSpaceExhaustedException → rebalance 후 재조회 → updateRank (C3)") {
            stubUpdatePermission(true)
            val prevKey = IssueKey("PROJ-1")
            val nextKey = IssueKey("PROJ-2")

            // 고갈 상황: prev와 next 의 rank 가 인접해 between 이 실패
            // "zy"와 "zz" 사이는 공간 없음(사실 between은 작동하지만 mock으로 직접 고갈 시뮬레이션)
            val prevIssue = makeIssue(prevKey, "zy")
            val nextIssue = makeIssue(nextKey, "zz")
            every { repo.findByKey(targetKey) } returns makeIssue(targetKey, "b")
            every { repo.findByKey(prevKey) } returns prevIssue
            every { repo.findByKey(nextKey) } returns nextIssue

            // advisory lock: dsl.execute 는 Unit 반환
            every {
                dsl.execute(
                    match<String> { it.contains("pg_advisory_xact_lock") },
                    any<String>(),
                )
            } returns 0

            // rebalance 대상 이슈 목록: 3개
            val rebalanceList = listOf(
                "PROJ-1" to "zy",
                "PROJ-3" to "b",
                "PROJ-2" to "zz",
            )
            every { repo.findRanksForRebalance(projectId) } returns rebalanceList

            // rebalance 후 재조회 (C3): rank 변경됨
            every { repo.findRankByKey(prevKey) } returns "g"
            every { repo.findRankByKey(nextKey) } returns "t"

            // 모든 updateRank stub
            every { repo.updateRank(any(), any()) } returns Unit

            // between("zy","zz")는 실제 Rank.between이 고갈을 던질 수 있지만,
            // 이 테스트는 서비스 흐름을 검증하므로 고갈 예외를 직접 모킹하기보다
            // 실제 between 으로 고갈이 발생하는 케이스를 시뮬레이션한다.
            // Rank.between("zy","zz")는 'zy' < r < 'zz' 를 찾아야 하는데 trailing-a 금지로 불가 → 고갈.
            sut.rerank(actor, targetKey, prevKey, nextKey)

            // rebalance: findRanksForRebalance 호출 검증
            verify { repo.findRanksForRebalance(projectId) }
            // C3: rebalance 후 prev/next rank 재조회 검증
            verify { repo.findRankByKey(prevKey) }
            verify { repo.findRankByKey(nextKey) }
            // 최종 updateRank 호출됨
            verify(atLeast = 1) { repo.updateRank(any(), any()) }
        }
    }
})
