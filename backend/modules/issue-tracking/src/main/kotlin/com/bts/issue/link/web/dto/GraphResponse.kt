// 이슈 링크 그래프 조회 응답 DTO — 노드/엣지/중심 이슈 정보를 포함한다 (FR-LK-02)

package com.bts.issue.link.web.dto

import com.bts.issue.link.application.GraphEdgeModel
import com.bts.issue.link.application.GraphNodeModel
import com.bts.issue.link.application.IssueGraphResult

/**
 * `GET /api/v1/issues/{key}/graph` 응답 바디.
 *
 * @property center 중심 이슈 키.
 * @property depth 요청된 최대 홉 거리.
 * @property nodes 수집된 이슈 노드 목록.
 * @property edges 수집된 엣지 목록.
 * @property truncated 노드 상한 초과로 일부 이웃이 잘렸으면 true.
 */
data class GraphResponse(
    val center: String,
    val depth: Int,
    val nodes: List<GraphNodeDto>,
    val edges: List<GraphEdgeDto>,
    val truncated: Boolean,
) {
    companion object {
        /** [IssueGraphResult] 를 응답 DTO 로 변환한다. */
        fun from(result: IssueGraphResult): GraphResponse =
            GraphResponse(
                center = result.centerKey,
                depth = result.depth,
                nodes = result.nodes.map { GraphNodeDto.from(it) },
                edges = result.edges.map { GraphEdgeDto.from(it) },
                truncated = result.truncated,
            )
    }
}

/**
 * 그래프 이슈 노드 DTO.
 *
 * @property key 이슈 키 (예: "BTS-1").
 * @property summary 이슈 제목.
 * @property statusKey 현재 워크플로우 상태 키 (예: "open").
 * @property depth 중심 이슈로부터의 홉 거리. 중심=0.
 */
data class GraphNodeDto(
    val key: String,
    val summary: String,
    val statusKey: String,
    val depth: Int,
) {
    companion object {
        /** [GraphNodeModel] 을 DTO 로 변환한다. */
        fun from(model: GraphNodeModel): GraphNodeDto =
            GraphNodeDto(
                key = model.key,
                summary = model.summary,
                statusKey = model.statusKey,
                depth = model.depth,
            )
    }
}

/**
 * 그래프 엣지 DTO.
 *
 * @property from 엣지 출발 노드 이슈 키.
 * @property to 엣지 도착 노드 이슈 키.
 * @property type 링크 타입 대문자 문자열 (예: "BLOCKS", "PARENT").
 */
data class GraphEdgeDto(
    val from: String,
    val to: String,
    val type: String,
) {
    companion object {
        /** [GraphEdgeModel] 을 DTO 로 변환한다. */
        fun from(model: GraphEdgeModel): GraphEdgeDto =
            GraphEdgeDto(
                from = model.fromKey,
                to = model.toKey,
                type = model.type,
            )
    }
}
