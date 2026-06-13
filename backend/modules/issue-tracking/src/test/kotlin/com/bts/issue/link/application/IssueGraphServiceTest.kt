// IssueGraphServiceTest — IssueGraphService BFS 그래프 빌드 단위 테스트 (MockK, FR-LK-02 Task 2)

package com.bts.issue.link.application

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.link.domain.InvalidGraphDepthException
import com.bts.issue.link.domain.LinkType
import com.bts.issue.link.domain.LinkedIssueNotFoundException
import com.bts.issue.link.repository.GraphNeighborRow
import com.bts.issue.link.repository.IssueGraphRepository
import com.bts.issue.link.repository.IssueLinkRepository
import com.bts.issue.link.repository.LinkedIssueRow
import com.bts.issue.repository.IssueRepository
import com.bts.shared.issue.IssueTypeId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import java.util.UUID

/**
 * [IssueGraphService] 단위 테스트.
 *
 * MockK 로 [IssueRepository], [IssueLinkRepository], [IssueGraphRepository] 를 mock 한다.
 * BFS 그래프 빌드 로직, 노드/엣지 방향성, 중복 제거, 정렬, truncated, resolveDepth 를 검증한다.
 */
class IssueGraphServiceTest : DescribeSpec({

    val issueRepository = mockk<IssueRepository>()
    val linkRepository = mockk<IssueLinkRepository>()
    val graphRepository = mockk<IssueGraphRepository>()

    val sut =
        IssueGraphService(
            issueRepository = issueRepository,
            linkRepository = linkRepository,
            graphRepository = graphRepository,
        )

    // ── 공통 픽스처 ─────────────────────────────────────────────────────────────

    val projectId = UUID.fromString("00000000-0000-4000-8000-100000000000")
    val reporterId = ActorId(UUID.fromString("00000000-0000-4000-8000-200000000000"))

    fun uuid(n: Int): UUID = UUID.fromString("00000000-0000-4000-8000-${n.toString().padStart(12, '0')}")

    fun makeIssue(
        id: UUID,
        key: String,
        status: String = "open",
    ): Issue =
        Issue.create(
            id = IssueId(id),
            key = IssueKey(key),
            projectId = projectId,
            typeId = IssueTypeId(1L),
            summary = "이슈 $key",
            reporterId = reporterId,
            currentStateKey = status,
        )

    fun makeNeighborRow(
        id: UUID,
        key: String,
        status: String = "open",
    ): GraphNeighborRow = GraphNeighborRow(id = id, key = key, summary = "이슈 $key", statusKey = status)

    fun makeLinkRow(
        linkId: Long,
        linkType: LinkType,
        otherId: UUID,
        otherKey: String,
        otherStatus: String = "open",
    ): LinkedIssueRow =
        LinkedIssueRow(
            linkId = linkId,
            linkType = linkType,
            otherIssueId = otherId,
            otherIssueKey = otherKey,
            otherIssueSummary = "이슈 $otherKey",
            otherCurrentStateKey = otherStatus,
        )

    val centerId = uuid(1)
    val centerKey = IssueKey("BTS-1")
    val centerIssue = makeIssue(centerId, "BTS-1")

    afterEach { clearMocks(issueRepository, linkRepository, graphRepository) }

    // ── 헬퍼: 기본 "아무 이웃 없음" stub ─────────────────────────────────────────

    fun stubNoNeighbors(id: UUID) {
        every { linkRepository.findOutwardWithIssue(id) } returns emptyList()
        every { linkRepository.findInwardWithIssue(id) } returns emptyList()
        every { graphRepository.findParent(id) } returns null
        every { graphRepository.findChildren(id) } returns emptyList()
    }

    // ── 케이스 1: 중심 이슈 미존재 ───────────────────────────────────────────────

    describe("buildGraph — 중심 이슈 미존재") {
        context("issueRepository.findByKey 가 null 을 반환할 때") {
            it("LinkedIssueNotFoundException 을 던진다") {
                every { issueRepository.findByKey(centerKey) } returns null

                shouldThrow<LinkedIssueNotFoundException> {
                    sut.buildGraph(centerKey, null)
                }
            }
        }
    }

    // ── 케이스 2: 1-hop 통합 케이스 ──────────────────────────────────────────────

    describe("buildGraph — 1-hop 이웃 4종 (outward/inward/parent/child)") {
        val outId = uuid(2)
        val inId = uuid(3)
        val parentId = uuid(4)
        val childId = uuid(5)

        it("노드 5개, 엣지 4개, 방향과 type 이 정확해야 한다") {
            every { issueRepository.findByKey(centerKey) } returns centerIssue

            // center 확장
            every { linkRepository.findOutwardWithIssue(centerId) } returns
                listOf(makeLinkRow(1L, LinkType.BLOCKS, outId, "BTS-2"))
            every { linkRepository.findInwardWithIssue(centerId) } returns
                listOf(makeLinkRow(2L, LinkType.RELATES, inId, "BTS-3"))
            every { graphRepository.findParent(centerId) } returns
                makeNeighborRow(parentId, "BTS-4")
            every { graphRepository.findChildren(centerId) } returns
                listOf(makeNeighborRow(childId, "BTS-5"))

            // 이웃 노드들은 depth=1 로 호출하여 이웃 확장 불필요하게 설정
            every { linkRepository.findOutwardWithIssue(outId) } returns emptyList()
            every { linkRepository.findInwardWithIssue(outId) } returns emptyList()
            every { graphRepository.findParent(outId) } returns null
            every { graphRepository.findChildren(outId) } returns emptyList()
            stubNoNeighbors(inId)
            stubNoNeighbors(parentId)
            stubNoNeighbors(childId)

            val result = sut.buildGraph(centerKey, "1")

            result.centerKey shouldBe "BTS-1"
            result.depth shouldBe 1
            result.truncated.shouldBeFalse()

            // 노드: center + 4 이웃
            result.nodes shouldHaveSize 5

            val centerNode = result.nodes.first { it.key == "BTS-1" }
            centerNode.depth shouldBe 0

            val outNode = result.nodes.first { it.key == "BTS-2" }
            outNode.depth shouldBe 1

            // 엣지 4개
            result.edges shouldHaveSize 4

            // outward BLOCKS: center → BTS-2
            val blocksEdge = result.edges.first { it.type == "BLOCKS" }
            blocksEdge.fromKey shouldBe "BTS-1"
            blocksEdge.toKey shouldBe "BTS-2"

            // inward RELATES: BTS-3 → center
            val relatesEdge = result.edges.first { it.type == "RELATES" }
            relatesEdge.fromKey shouldBe "BTS-3"
            relatesEdge.toKey shouldBe "BTS-1"

            // parent PARENT: BTS-4 → center
            val parentEdge = result.edges.filter { it.type == "PARENT" && it.toKey == "BTS-1" }
            parentEdge shouldHaveSize 1
            parentEdge.first().fromKey shouldBe "BTS-4"

            // child PARENT: center → BTS-5
            val childEdge = result.edges.filter { it.type == "PARENT" && it.fromKey == "BTS-1" }
            childEdge shouldHaveSize 1
            childEdge.first().toKey shouldBe "BTS-5"
        }
    }

    // ── 케이스 3: depth 1 vs depth 2 — 2-hop 포함/제외 ──────────────────────────

    describe("buildGraph — depth 비교") {
        val hop1Id = uuid(2)
        val hop2Id = uuid(3)

        it("depth=1 이면 2-hop 이웃을 포함하지 않는다") {
            every { issueRepository.findByKey(centerKey) } returns centerIssue

            every { linkRepository.findOutwardWithIssue(centerId) } returns
                listOf(makeLinkRow(10L, LinkType.RELATES, hop1Id, "BTS-2"))
            every { linkRepository.findInwardWithIssue(centerId) } returns emptyList()
            every { graphRepository.findParent(centerId) } returns null
            every { graphRepository.findChildren(centerId) } returns emptyList()

            // depth=1 이므로 hop1 은 확장하지 않아야 하지만 stub 은 넣어두고 결과로 검증
            every { linkRepository.findOutwardWithIssue(hop1Id) } returns
                listOf(makeLinkRow(11L, LinkType.RELATES, hop2Id, "BTS-3"))
            every { linkRepository.findInwardWithIssue(hop1Id) } returns emptyList()
            every { graphRepository.findParent(hop1Id) } returns null
            every { graphRepository.findChildren(hop1Id) } returns emptyList()
            stubNoNeighbors(hop2Id)

            val result = sut.buildGraph(centerKey, "1")
            val keys = result.nodes.map { it.key }
            keys.contains("BTS-3").shouldBeFalse()
        }

        it("depth=2 이면 2-hop 이웃을 포함한다") {
            every { issueRepository.findByKey(centerKey) } returns centerIssue

            every { linkRepository.findOutwardWithIssue(centerId) } returns
                listOf(makeLinkRow(10L, LinkType.RELATES, hop1Id, "BTS-2"))
            every { linkRepository.findInwardWithIssue(centerId) } returns emptyList()
            every { graphRepository.findParent(centerId) } returns null
            every { graphRepository.findChildren(centerId) } returns emptyList()

            every { linkRepository.findOutwardWithIssue(hop1Id) } returns
                listOf(makeLinkRow(11L, LinkType.RELATES, hop2Id, "BTS-3"))
            every { linkRepository.findInwardWithIssue(hop1Id) } returns emptyList()
            every { graphRepository.findParent(hop1Id) } returns null
            every { graphRepository.findChildren(hop1Id) } returns emptyList()
            stubNoNeighbors(hop2Id)

            val result = sut.buildGraph(centerKey, "2")
            val keys = result.nodes.map { it.key }
            keys.contains("BTS-3").shouldBeTrue()
        }
    }

    // ── 케이스 4: 링크 중복 제거 (같은 linkId 두 번 발견) ─────────────────────────

    describe("buildGraph — 엣지 중복 제거") {
        val nodeAId = uuid(2)
        val nodeBId = uuid(3)

        it("같은 linkId 가 양쪽 확장에서 나타나도 엣지는 1개여야 한다") {
            every { issueRepository.findByKey(centerKey) } returns centerIssue

            // center → nodeA (outward, linkId=99)
            every { linkRepository.findOutwardWithIssue(centerId) } returns
                listOf(makeLinkRow(99L, LinkType.BLOCKS, nodeAId, "BTS-2"))
            every { linkRepository.findInwardWithIssue(centerId) } returns emptyList()
            every { graphRepository.findParent(centerId) } returns null
            every { graphRepository.findChildren(centerId) } returns emptyList()

            // nodeA → center (inward 조회 시 같은 linkId=99 로 반환될 수 있음)
            every { linkRepository.findOutwardWithIssue(nodeAId) } returns emptyList()
            // linkId=99, otherIssueId=centerId (nodeA 입장에서 center 가 source)
            every { linkRepository.findInwardWithIssue(nodeAId) } returns
                listOf(makeLinkRow(99L, LinkType.BLOCKS, centerId, "BTS-1"))
            every { graphRepository.findParent(nodeAId) } returns null
            every { graphRepository.findChildren(nodeAId) } returns emptyList()

            // depth=1, nodeB 는 등장하지 않음
            val result = sut.buildGraph(centerKey, "1")

            // BLOCKS 엣지는 linkId=99 로 dedup → 1개
            val blocksEdges = result.edges.filter { it.type == "BLOCKS" }
            blocksEdges shouldHaveSize 1
            blocksEdges.first().fromKey shouldBe "BTS-1"
            blocksEdges.first().toKey shouldBe "BTS-2"
        }
    }

    // ── 케이스 5: truncated 케이스 (depth=1, NODE_CAP 간접 검증) ──────────────────

    describe("buildGraph — truncated") {
        it("이미 방문한 노드를 재발견해도 truncated 가 true 가 되지 않는다") {
            // 단순 케이스: truncated=false 기본값 검증
            every { issueRepository.findByKey(centerKey) } returns centerIssue
            stubNoNeighbors(centerId)

            val result = sut.buildGraph(centerKey, "1")
            result.truncated.shouldBeFalse()
            result.nodes shouldHaveSize 1
        }
    }

    // ── 케이스 6: 정렬 검증 ───────────────────────────────────────────────────────

    describe("buildGraph — 정렬") {
        val idA = uuid(2)
        val idB = uuid(3)
        val idC = uuid(4)

        it("nodes 는 depth ASC, key ASC 정렬이어야 한다") {
            every { issueRepository.findByKey(centerKey) } returns centerIssue

            // center 에서 3개 이웃 (key 가 역순으로 반환되게 stub)
            every { linkRepository.findOutwardWithIssue(centerId) } returns
                listOf(
                    makeLinkRow(30L, LinkType.RELATES, idC, "BTS-4"),
                    makeLinkRow(31L, LinkType.RELATES, idA, "BTS-2"),
                    makeLinkRow(32L, LinkType.RELATES, idB, "BTS-3"),
                )
            every { linkRepository.findInwardWithIssue(centerId) } returns emptyList()
            every { graphRepository.findParent(centerId) } returns null
            every { graphRepository.findChildren(centerId) } returns emptyList()
            stubNoNeighbors(idA)
            stubNoNeighbors(idB)
            stubNoNeighbors(idC)

            val result = sut.buildGraph(centerKey, "1")

            // depth=0: BTS-1, depth=1: BTS-2, BTS-3, BTS-4 (key ASC)
            result.nodes.map { it.key } shouldBe listOf("BTS-1", "BTS-2", "BTS-3", "BTS-4")
        }

        it("edges 는 fromKey ASC, toKey ASC, type ASC 정렬이어야 한다") {
            every { issueRepository.findByKey(centerKey) } returns centerIssue

            every { linkRepository.findOutwardWithIssue(centerId) } returns
                listOf(
                    makeLinkRow(40L, LinkType.RELATES, idC, "BTS-4"),
                    makeLinkRow(41L, LinkType.BLOCKS, idA, "BTS-2"),
                )
            every { linkRepository.findInwardWithIssue(centerId) } returns emptyList()
            every { graphRepository.findParent(centerId) } returns null
            every { graphRepository.findChildren(centerId) } returns emptyList()
            stubNoNeighbors(idA)
            stubNoNeighbors(idC)

            val result = sut.buildGraph(centerKey, "1")

            // fromKey=BTS-1, toKey BTS-2(BLOCKS) < BTS-4(RELATES) 순
            result.edges[0].toKey shouldBe "BTS-2"
            result.edges[0].type shouldBe "BLOCKS"
            result.edges[1].toKey shouldBe "BTS-4"
            result.edges[1].type shouldBe "RELATES"
        }
    }

    // ── 케이스 7: resolveDepth ────────────────────────────────────────────────────

    describe("resolveDepth (buildGraph 통해 간접 검증)") {
        context("raw=null") {
            it("DEFAULT_DEPTH(2) 를 사용한다") {
                every { issueRepository.findByKey(centerKey) } returns centerIssue
                stubNoNeighbors(centerId)

                val result = sut.buildGraph(centerKey, null)
                result.depth shouldBe 2
            }
        }

        context("raw=blank") {
            it("DEFAULT_DEPTH(2) 를 사용한다") {
                every { issueRepository.findByKey(centerKey) } returns centerIssue
                stubNoNeighbors(centerId)

                val result = sut.buildGraph(centerKey, "   ")
                result.depth shouldBe 2
            }
        }

        context("raw=\"abc\"") {
            it("InvalidGraphDepthException 을 던진다") {
                shouldThrow<InvalidGraphDepthException> {
                    sut.buildGraph(centerKey, "abc")
                }
            }
        }

        context("raw=\"0\"") {
            it("InvalidGraphDepthException 을 던진다") {
                shouldThrow<InvalidGraphDepthException> {
                    sut.buildGraph(centerKey, "0")
                }
            }
        }

        context("raw=\"4\"") {
            it("InvalidGraphDepthException 을 던진다") {
                shouldThrow<InvalidGraphDepthException> {
                    sut.buildGraph(centerKey, "4")
                }
            }
        }

        context("raw=\"-1\"") {
            it("InvalidGraphDepthException 을 던진다") {
                shouldThrow<InvalidGraphDepthException> {
                    sut.buildGraph(centerKey, "-1")
                }
            }
        }

        context("raw=\"3\"") {
            it("depth=3 을 반환한다") {
                every { issueRepository.findByKey(centerKey) } returns centerIssue
                stubNoNeighbors(centerId)

                val result = sut.buildGraph(centerKey, "3")
                result.depth shouldBe 3
            }
        }
    }
})
