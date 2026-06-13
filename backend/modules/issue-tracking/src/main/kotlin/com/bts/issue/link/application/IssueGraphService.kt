// IssueGraphService — 이슈 링크 그래프 BFS 빌드 서비스 (FR-LK-02 Task 2)

package com.bts.issue.link.application

import com.bts.issue.domain.IssueKey
import com.bts.issue.link.domain.InvalidGraphDepthException
import com.bts.issue.link.domain.LinkedIssueNotFoundException
import com.bts.issue.link.repository.GraphNeighborRow
import com.bts.issue.link.repository.IssueGraphRepository
import com.bts.issue.link.repository.IssueLinkRepository
import com.bts.issue.repository.IssueRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.LinkedList
import java.util.UUID

// ── 읽기 모델 ─────────────────────────────────────────────────────────────────

/**
 * 그래프의 단일 이슈 노드.
 *
 * @property key 이슈 키 (예: "BTS-1").
 * @property summary 이슈 제목.
 * @property statusKey 현재 워크플로우 상태 키.
 * @property depth 중심 이슈로부터의 홉 거리. 중심=0.
 */
data class GraphNodeModel(
    val key: String,
    val summary: String,
    val statusKey: String,
    val depth: Int,
)

/**
 * 그래프의 단일 엣지.
 *
 * @property fromKey 엣지 출발 노드 이슈 키.
 * @property toKey 엣지 도착 노드 이슈 키.
 * @property type 링크 타입 대문자 문자열 (예: "BLOCKS", "PARENT").
 */
data class GraphEdgeModel(
    val fromKey: String,
    val toKey: String,
    val type: String,
)

/**
 * [IssueGraphService.buildGraph] 결과.
 *
 * @property centerKey 중심 이슈 키.
 * @property depth 요청된 최대 홉 거리.
 * @property nodes 수집된 이슈 노드 목록 (depth ASC, key ASC 정렬).
 * @property edges 수집된 엣지 목록 (fromKey ASC, toKey ASC, type ASC 정렬).
 * @property truncated 노드 상한([IssueGraphService.NODE_CAP])에 의해 일부 이웃이 잘렸으면 true.
 */
data class IssueGraphResult(
    val centerKey: String,
    val depth: Int,
    val nodes: List<GraphNodeModel>,
    val edges: List<GraphEdgeModel>,
    val truncated: Boolean,
)

// ── BFS 내부 큐 항목 ──────────────────────────────────────────────────────────

private data class QueueItem(
    val id: UUID,
    val key: String,
    val summary: String,
    val statusKey: String,
    val depth: Int,
)

// ── 엣지 후보 (dedup 전) ──────────────────────────────────────────────────────

private data class EdgeCandidate(
    val fromKey: String,
    val toKey: String,
    val type: String,
    val fromId: UUID,
    val toId: UUID,
    val dedupKey: String,
)

// ── 서비스 ────────────────────────────────────────────────────────────────────

/**
 * 이슈 링크 그래프를 BFS 로 빌드하는 Application Service (FR-LK-02).
 *
 * ## 알고리즘 (BFS)
 * 중심 이슈에서 시작해 최대 [MAX_DEPTH] 홉까지 이웃을 너비 우선 탐색한다.
 * - outward/inward issue_links 와 parent/child 관계를 모두 확장한다.
 * - 방문한 노드는 재추가하지 않는다 (visited 셋 관리).
 * - [NODE_CAP] 초과 시 해당 이웃을 건너뛰고 [IssueGraphResult.truncated]=true 로 표시한다.
 * - 엣지는 [EdgeCandidate.dedupKey] 기반으로 중복 제거한다.
 *
 * ## 트랜잭션 경계 (DATA.md §6)
 * [buildGraph] — `@Transactional(readOnly = true)`.
 *
 * ## BC 격리
 * issue-tracking BC 내 레포지토리만 직접 의존. 타 BC 호출 없음.
 */
@Service
class IssueGraphService(
    private val issueRepository: IssueRepository,
    private val linkRepository: IssueLinkRepository,
    private val graphRepository: IssueGraphRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** depth 파라미터 미입력 시 기본값. */
        const val DEFAULT_DEPTH = 2

        /** 허용되는 최대 depth. */
        const val MAX_DEPTH = 3

        /** BFS 노드 수 상한. 초과 시 truncated=true. */
        const val NODE_CAP = 100
    }

    /**
     * 중심 이슈를 기점으로 BFS 그래프를 빌드해 반환한다.
     *
     * @param centerKey 중심 이슈 키.
     * @param rawDepth 클라이언트가 전달한 depth 문자열. null 또는 blank 이면 [DEFAULT_DEPTH] 사용.
     * @return [IssueGraphResult] — 노드/엣지/truncated 포함.
     * @throws LinkedIssueNotFoundException 중심 이슈가 없거나 소프트삭제된 경우.
     * @throws InvalidGraphDepthException rawDepth 가 1~[MAX_DEPTH] 범위의 정수가 아닌 경우.
     */
    @Transactional(readOnly = true)
    fun buildGraph(
        centerKey: IssueKey,
        rawDepth: String?,
    ): IssueGraphResult {
        val depth = resolveDepth(rawDepth)
        log.debug("buildGraph centerKey={} depth={}", centerKey.value, depth)

        val center =
            issueRepository.findByKey(centerKey)
                ?: throw LinkedIssueNotFoundException(centerKey)

        val centerId = center.id.value
        val visited = mutableSetOf(centerId)
        val nodes =
            mutableListOf(
                QueueItem(
                    id = centerId,
                    key = center.key.value,
                    summary = center.summary,
                    statusKey = center.currentStateKey,
                    depth = 0,
                ),
            )
        val queue: LinkedList<QueueItem> = LinkedList<QueueItem>().also { it.add(nodes.first()) }
        val edgeCandidates = mutableListOf<EdgeCandidate>()
        var truncated = false

        while (queue.isNotEmpty()) {
            val current = queue.poll()
            if (current.depth >= depth) continue

            truncated =
                expandNode(
                    current = current,
                    visited = visited,
                    nodes = nodes,
                    queue = queue,
                    edgeCandidates = edgeCandidates,
                    truncated = truncated,
                    depth = depth,
                )
        }

        val visitedSet = visited
        val finalEdges =
            edgeCandidates
                .filter { it.fromId in visitedSet && it.toId in visitedSet }
                .distinctBy { it.dedupKey }
                .sortedWith(compareBy({ it.fromKey }, { it.toKey }, { it.type }))
                .map { GraphEdgeModel(it.fromKey, it.toKey, it.type) }

        val finalNodes =
            nodes
                .sortedWith(compareBy({ it.depth }, { it.key }))
                .map { GraphNodeModel(it.key, it.summary, it.statusKey, it.depth) }

        log.debug(
            "buildGraph done centerKey={} nodes={} edges={} truncated={}",
            centerKey.value,
            finalNodes.size,
            finalEdges.size,
            truncated,
        )
        return IssueGraphResult(
            centerKey = centerKey.value,
            depth = depth,
            nodes = finalNodes,
            edges = finalEdges,
            truncated = truncated,
        )
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * BFS 큐의 단일 노드를 확장한다.
     *
     * outward/inward 링크와 parent/child 관계를 각각 조회해 엣지 후보를 수집하고,
     * 미방문 이웃을 노드 목록·visited·큐에 추가한다.
     *
     * @return 누적 truncated 값 (기존 true 이면 유지, 새로 cap 초과면 true 로 갱신).
     */
    private fun expandNode(
        current: QueueItem,
        visited: MutableSet<UUID>,
        nodes: MutableList<QueueItem>,
        queue: LinkedList<QueueItem>,
        edgeCandidates: MutableList<EdgeCandidate>,
        truncated: Boolean,
        depth: Int,
    ): Boolean {
        var wasTruncated = truncated
        val nextDepth = current.depth + 1

        // outward 링크: current → neighbor
        linkRepository.findOutwardWithIssue(current.id).forEach { row ->
            edgeCandidates.add(
                EdgeCandidate(
                    fromKey = current.key,
                    toKey = row.otherIssueKey,
                    type = row.linkType.name,
                    fromId = current.id,
                    toId = row.otherIssueId,
                    dedupKey = "L:${row.linkId}",
                ),
            )
            val neighbor =
                QueueItem(
                    id = row.otherIssueId,
                    key = row.otherIssueKey,
                    summary = row.otherIssueSummary,
                    statusKey = row.otherCurrentStateKey,
                    depth = nextDepth,
                )
            wasTruncated = tryEnqueue(neighbor, visited, nodes, queue, depth, wasTruncated)
        }

        // inward 링크: neighbor → current
        linkRepository.findInwardWithIssue(current.id).forEach { row ->
            edgeCandidates.add(
                EdgeCandidate(
                    fromKey = row.otherIssueKey,
                    toKey = current.key,
                    type = row.linkType.name,
                    fromId = row.otherIssueId,
                    toId = current.id,
                    dedupKey = "L:${row.linkId}",
                ),
            )
            val neighbor =
                QueueItem(
                    id = row.otherIssueId,
                    key = row.otherIssueKey,
                    summary = row.otherIssueSummary,
                    statusKey = row.otherCurrentStateKey,
                    depth = nextDepth,
                )
            wasTruncated = tryEnqueue(neighbor, visited, nodes, queue, depth, wasTruncated)
        }

        // 부모: parent → current
        graphRepository.findParent(current.id)?.let { parent ->
            edgeCandidates.add(
                EdgeCandidate(
                    fromKey = parent.key,
                    toKey = current.key,
                    type = "PARENT",
                    fromId = parent.id,
                    toId = current.id,
                    dedupKey = "P:${parent.id}->${current.id}",
                ),
            )
            wasTruncated = tryEnqueue(parent.toQueueItem(nextDepth), visited, nodes, queue, depth, wasTruncated)
        }

        // 자식: current → child
        graphRepository.findChildren(current.id).forEach { child ->
            edgeCandidates.add(
                EdgeCandidate(
                    fromKey = current.key,
                    toKey = child.key,
                    type = "PARENT",
                    fromId = current.id,
                    toId = child.id,
                    dedupKey = "P:${current.id}->${child.id}",
                ),
            )
            wasTruncated = tryEnqueue(child.toQueueItem(nextDepth), visited, nodes, queue, depth, wasTruncated)
        }

        return wasTruncated
    }

    /**
     * 이웃 노드가 미방문이고 [NODE_CAP] 미만이면 visited·nodes·queue 에 추가한다.
     *
     * 노드 상한 초과 시 해당 이웃을 건너뛰고 [truncated]=true 를 반환한다.
     *
     * @param item 추가할 이웃 [QueueItem].
     * @param visited 이미 방문한 노드 UUID 집합.
     * @param nodes 결과 노드 목록 (누적).
     * @param queue BFS 대기 큐.
     * @param depth 요청된 최대 홉 거리.
     * @param truncated 기존 truncated 값.
     * @return 갱신된 truncated 값.
     */
    private fun tryEnqueue(
        item: QueueItem,
        visited: MutableSet<UUID>,
        nodes: MutableList<QueueItem>,
        queue: LinkedList<QueueItem>,
        depth: Int,
        truncated: Boolean,
    ): Boolean {
        if (item.id in visited) return truncated
        return if (visited.size < NODE_CAP) {
            visited.add(item.id)
            nodes.add(item)
            if (item.depth < depth) queue.add(item)
            truncated
        } else {
            true
        }
    }

    /**
     * 문자열 depth 파라미터를 유효한 정수로 변환한다.
     *
     * @param raw 클라이언트 입력 depth 문자열.
     * @return 1..[MAX_DEPTH] 범위의 정수.
     * @throws InvalidGraphDepthException raw 가 정수로 변환 불가하거나 범위를 벗어난 경우.
     */
    @Suppress("ThrowsCount") // null/blank 기본값, 파싱 실패, 범위 초과 — 3단계 검증이므로 분리 불가
    private fun resolveDepth(raw: String?): Int {
        if (raw == null || raw.isBlank()) return DEFAULT_DEPTH
        val value = raw.trim().toIntOrNull() ?: throw InvalidGraphDepthException(raw)
        if (value < 1 || value > MAX_DEPTH) throw InvalidGraphDepthException(raw)
        return value
    }

    // ── 변환 확장 함수 ────────────────────────────────────────────────────────

    private fun GraphNeighborRow.toQueueItem(depth: Int): QueueItem =
        QueueItem(id = id, key = key, summary = summary, statusKey = statusKey, depth = depth)
}
