// IssueGraphService — 이슈 링크 그래프 BFS 빌드 서비스 (FR-LK-02 Task 2)

package com.bts.issue.link.application

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueKey
import com.bts.issue.link.domain.InvalidGraphDepthException
import com.bts.issue.link.domain.LinkedIssueNotFoundException
import com.bts.issue.link.repository.GraphNeighborRow
import com.bts.issue.link.repository.IssueGraphRepository
import com.bts.issue.link.repository.IssueLinkRepository
import com.bts.issue.link.repository.LinkedIssueRow
import com.bts.issue.repository.IssueRepository
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
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

// ── BFS 내부 타입 ─────────────────────────────────────────────────────────────

private data class QueueItem(
    val id: UUID,
    val key: String,
    val summary: String,
    val statusKey: String,
    val depth: Int,
)

private data class EdgeCandidate(
    val fromKey: String,
    val toKey: String,
    val type: String,
    val fromId: UUID,
    val toId: UUID,
    val dedupKey: String,
)

/**
 * BFS 누적 상태 홀더.
 *
 * visited / nodes / queue / 엣지 후보 / truncated 를 한 객체로 묶어 확장 헬퍼의
 * 파라미터 수를 줄이고(detekt LongParameterList 회피), 상태 전환을 한 곳에 모은다.
 *
 * @property maxDepth 요청된 최대 홉 거리. 노드의 depth 가 이보다 작을 때만 큐에 넣는다.
 * @property nodeCap 노드 수 상한. 초과 시 [truncated]=true.
 */
private class BfsTraversal(
    private val maxDepth: Int,
    private val nodeCap: Int,
    center: QueueItem,
    /**
     * 이웃 노드를 그래프에 들일지 판정한다 (이슈 키 → 볼 수 있나).
     *
     * ★[visit] 이 **노드가 들어오는 유일한 관문**이라 여기 한 곳만 막으면 된다.
     * 확장 지점(links·parent·children)마다 따로 걸면 하나를 빠뜨린다.
     * 엣지는 [finalEdges] 가 「양 끝이 모두 방문된 것」만 남기므로 자동으로 함께 사라진다.
     *
     * 중심 노드는 생성자에서 이미 들어오므로 이 술어를 타지 않는다 — 중심은 호출부가
     * 404 로 거른다(403 이면 실재가 드러난다).
     */
    private val canVisit: (String) -> Boolean,
) {
    val visited = mutableSetOf(center.id)
    val nodes = mutableListOf(center)
    val queue = ArrayDeque<QueueItem>().apply { add(center) }
    private val edges = mutableListOf<EdgeCandidate>()

    /** 노드 상한 초과로 일부 이웃을 건너뛰었으면 true. */
    var truncated = false
        private set

    /** 엣지 후보를 수집한다(최종 dedup·필터는 [finalEdges]). */
    fun addEdge(candidate: EdgeCandidate) {
        edges.add(candidate)
    }

    /**
     * 이웃 노드를 방문 처리한다.
     *
     * 이미 방문했거나 **볼 권한이 없으면** 무시한다. 미방문이지만 [nodeCap] 에 도달했으면
     * [truncated]=true 로 표시하고 추가하지 않는다.
     * 그 외에는 visited·nodes 에 추가하고, depth 가 [maxDepth] 미만이면 큐에도 넣는다.
     *
     * ★권한 거부는 [truncated] 로 세지 **않는다** — "상한 때문에 잘렸다" 와
     * "권한이 없어 숨겼다" 는 다른 사실이고, 후자를 플래그로 노출하면 그 자체가
     * "여기 내가 못 보는 이웃이 있다" 는 오라클이 된다.
     */
    fun visit(item: QueueItem) {
        // 볼 수 없는 이웃은 아예 안 들인다.
        if (item.id in visited || !canVisit(item.key)) return
        if (visited.size >= nodeCap) {
            truncated = true
            return
        }
        visited.add(item.id)
        nodes.add(item)
        if (item.depth < maxDepth) queue.add(item)
    }

    /** 양 끝이 모두 방문된 엣지만 dedup·정렬해 반환한다. */
    fun finalEdges(): List<GraphEdgeModel> =
        edges
            .filter { it.fromId in visited && it.toId in visited }
            .distinctBy { it.dedupKey }
            .sortedWith(compareBy({ it.fromKey }, { it.toKey }, { it.type }))
            .map { GraphEdgeModel(it.fromKey, it.toKey, it.type) }

    /** 노드를 depth ASC, key ASC 로 정렬해 반환한다. */
    fun finalNodes(): List<GraphNodeModel> =
        nodes
            .sortedWith(compareBy({ it.depth }, { it.key }))
            .map { GraphNodeModel(it.key, it.summary, it.statusKey, it.depth) }
}

// ── 서비스 ────────────────────────────────────────────────────────────────────

/**
 * 이슈 링크 그래프를 BFS 로 빌드하는 Application Service (FR-LK-02).
 *
 * ## 알고리즘 (BFS)
 * 중심 이슈에서 시작해 요청된 depth(최대 [MAX_DEPTH]) 홉까지 이웃을 너비 우선 탐색한다.
 * - outward/inward issue_links 와 parent/child 관계를 모두 확장한다.
 * - 방문한 노드는 재추가하지 않는다 ([BfsTraversal] visited 셋).
 * - [NODE_CAP] 초과 시 해당 이웃을 건너뛰고 truncated=true 로 표시한다.
 * - 엣지는 dedupKey 기반으로 중복 제거하고, 양 끝이 모두 방문된 것만 남긴다.
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
    private val permissionResolver: IssuePermissionResolver,
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
     * @param actor 요청 주체 — 중심 이슈 VIEW 게이트와 이웃 필터에 함께 쓰인다.
     * @param centerKey 중심 이슈 키.
     * @param rawDepth 클라이언트가 전달한 depth 문자열. null 또는 blank 이면 [DEFAULT_DEPTH] 사용.
     * @return [IssueGraphResult] — 노드/엣지/truncated 포함.
     * @throws LinkedIssueNotFoundException 중심 이슈가 없거나 소프트삭제된 경우.
     * @throws InvalidGraphDepthException rawDepth 가 1~[MAX_DEPTH] 범위의 정수가 아닌 경우.
     */
    @Transactional(readOnly = true)
    fun buildGraph(
        actor: ActorId,
        centerKey: IssueKey,
        rawDepth: String?,
    ): IssueGraphResult {
        val depth = resolveDepth(rawDepth)
        log.debug("buildGraph centerKey={} depth={}", centerKey.value, depth)

        // ★권한을 리소스 조회보다 먼저. 그리고 거부는 403 이 아니라 **404** 다 —
        // 형제 `GET /api/v1/issues/{key}` 가 이미 404 로 실재를 숨긴다
        // (IssueApplicationService.assertViewIssueOrNotFound). 여기만 403 이면
        // 응답 코드 차이로 기밀 이슈의 존재가 드러난다.
        if (!canView(actor, centerKey.value)) throw LinkedIssueNotFoundException(centerKey)

        val center =
            issueRepository.findByKey(centerKey)
                ?: throw LinkedIssueNotFoundException(centerKey)

        val centerItem =
            QueueItem(
                id = center.id.value,
                key = center.key.value,
                summary = center.summary,
                statusKey = center.currentStateKey,
                depth = 0,
            )
        val traversal =
            BfsTraversal(
                maxDepth = depth,
                nodeCap = NODE_CAP,
                center = centerItem,
                canVisit = { key -> canView(actor, key) },
            )

        while (traversal.queue.isNotEmpty()) {
            val current = traversal.queue.removeFirst()
            if (current.depth < depth) expandNode(current, traversal)
        }

        val result =
            IssueGraphResult(
                centerKey = centerKey.value,
                depth = depth,
                nodes = traversal.finalNodes(),
                edges = traversal.finalEdges(),
                truncated = traversal.truncated,
            )
        log.debug(
            "buildGraph done centerKey={} nodes={} edges={} truncated={}",
            centerKey.value,
            result.nodes.size,
            result.edges.size,
            result.truncated,
        )
        return result
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * actor 가 이 이슈를 볼 수 있는지 — **던지지 않는** 판정이다.
     *
     * 그래프는 이웃을 걸러내야 하므로 한 건이 막혔다고 요청 전체를 실패시키면 안 된다.
     * 형제 `LinkApplicationService.canViewOther` 와 **같은 술어**다.
     */
    private fun canView(
        actor: ActorId,
        issueKey: String,
    ): Boolean =
        permissionResolver.hasPermission(
            actor.value,
            IssuePermission.VIEW,
            IssueScope.Issue(issueKey),
        )

    /** BFS 큐의 단일 노드를 확장한다 — 링크(outward/inward) + parent + child. */
    private fun expandNode(
        current: QueueItem,
        traversal: BfsTraversal,
    ) {
        expandLinks(current, traversal)
        expandParent(current, traversal)
        expandChildren(current, traversal)
    }

    /** outward/inward issue_links 를 엣지 후보로 수집하고 상대 이슈를 방문한다. */
    private fun expandLinks(
        current: QueueItem,
        traversal: BfsTraversal,
    ) {
        val nextDepth = current.depth + 1

        linkRepository.findOutwardWithIssue(current.id).forEach { row ->
            traversal.addEdge(
                EdgeCandidate(
                    fromKey = current.key,
                    toKey = row.otherIssueKey,
                    type = row.linkType.name,
                    fromId = current.id,
                    toId = row.otherIssueId,
                    dedupKey = "L:${row.linkId}",
                ),
            )
            traversal.visit(row.toQueueItem(nextDepth))
        }

        linkRepository.findInwardWithIssue(current.id).forEach { row ->
            traversal.addEdge(
                EdgeCandidate(
                    fromKey = row.otherIssueKey,
                    toKey = current.key,
                    type = row.linkType.name,
                    fromId = row.otherIssueId,
                    toId = current.id,
                    dedupKey = "L:${row.linkId}",
                ),
            )
            traversal.visit(row.toQueueItem(nextDepth))
        }
    }

    /** 부모(parent → current) 엣지를 수집하고 부모를 방문한다. */
    private fun expandParent(
        current: QueueItem,
        traversal: BfsTraversal,
    ) {
        val nextDepth = current.depth + 1
        graphRepository.findParent(current.id)?.let { parent ->
            traversal.addEdge(
                EdgeCandidate(
                    fromKey = parent.key,
                    toKey = current.key,
                    type = "PARENT",
                    fromId = parent.id,
                    toId = current.id,
                    dedupKey = "P:${parent.id}->${current.id}",
                ),
            )
            traversal.visit(parent.toQueueItem(nextDepth))
        }
    }

    /** 자식(current → child) 엣지를 수집하고 자식을 방문한다. */
    private fun expandChildren(
        current: QueueItem,
        traversal: BfsTraversal,
    ) {
        val nextDepth = current.depth + 1
        graphRepository.findChildren(current.id).forEach { child ->
            traversal.addEdge(
                EdgeCandidate(
                    fromKey = current.key,
                    toKey = child.key,
                    type = "PARENT",
                    fromId = current.id,
                    toId = child.id,
                    dedupKey = "P:${current.id}->${child.id}",
                ),
            )
            traversal.visit(child.toQueueItem(nextDepth))
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

    private fun LinkedIssueRow.toQueueItem(depth: Int): QueueItem =
        QueueItem(
            id = otherIssueId,
            key = otherIssueKey,
            summary = otherIssueSummary,
            statusKey = otherCurrentStateKey,
            depth = depth,
        )

    private fun GraphNeighborRow.toQueueItem(depth: Int): QueueItem =
        QueueItem(id = id, key = key, summary = summary, statusKey = statusKey, depth = depth)
}
