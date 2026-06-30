// 이슈 링크 그래프 조회 REST 컨트롤러 — GET /api/v1/issues/{key}/graph (FR-LK-02)

package com.bts.issue.link.web

import com.bts.issue.adapter.inbound.rest.DataResponse
import com.bts.issue.config.BEARER_AUTH_SCHEME
import com.bts.issue.domain.IssueKey
import com.bts.issue.link.application.IssueGraphService
import com.bts.issue.link.web.dto.GraphResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 이슈 링크 그래프 조회 REST API 컨트롤러 (FR-LK-02).
 *
 * 엔드포인트.
 * - GET `/api/v1/issues/{key}/graph` — 중심 이슈를 기점으로 BFS 그래프 조회 → 200
 *
 * ### depth 위임
 * `depth` 파라미터 검증 및 기본값(2) 적용은 [IssueGraphService.buildGraph] 에서 담당한다.
 *
 * ### 오류 매핑
 * 도메인 예외 → HTTP 상태/`errorCode` 변환은 [LinkExceptionHandler] 가 담당한다.
 * - [com.bts.issue.link.domain.LinkedIssueNotFoundException] → 404
 * - [com.bts.issue.link.domain.InvalidGraphDepthException] → 400
 *
 * @param issueGraphService 그래프 BFS 빌드 Application Service.
 */
@Tag(name = "Issue Graph", description = "이슈 링크 BFS 그래프 조회 API (FR-LK-02)")
@RestController
@RequestMapping("/api/v1/issues/{key}")
class IssueGraphController(
    private val issueGraphService: IssueGraphService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 중심 이슈를 기점으로 BFS 그래프를 조회한다.
     *
     * @param key 중심 이슈 키 (예: "BTS-1").
     * @param depth 최대 홉 거리 문자열. null 또는 blank 이면 기본값(2) 적용. 1~3 범위 외 → 400.
     * @return 200 OK + [GraphResponse] (nodes/edges/truncated 포함).
     */
    @Operation(
        operationId = "getIssueGraph",
        summary = "이슈 링크 BFS 그래프 조회",
        description = "중심 이슈를 기점으로 BFS 탐색(기본 depth=2, 최대 depth=5)하여 노드·엣지 그래프를 반환한다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "그래프 (nodes + edges)"),
        ApiResponse(responseCode = "400", description = "depth 파라미터 범위 오류", content = [Content()]),
        ApiResponse(responseCode = "401", description = "미인증", content = [Content()]),
        ApiResponse(responseCode = "404", description = "이슈 미존재", content = [Content()]),
    )
    @SecurityRequirement(name = BEARER_AUTH_SCHEME)
    @GetMapping("/graph")
    fun getGraph(
        @PathVariable key: String,
        @RequestParam(name = "depth", required = false) depth: String?,
    ): ResponseEntity<DataResponse<GraphResponse>> {
        log.debug("getGraph key={} depth={}", key, depth)
        val result = issueGraphService.buildGraph(IssueKey(key), depth)
        return ResponseEntity.ok(DataResponse(data = GraphResponse.from(result)))
    }
}
