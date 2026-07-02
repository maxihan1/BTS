// 댓글 REST 컨트롤러 — 목록 조회 엔드포인트 (FR-IM-01 PR3)

package com.bts.issue.comment.web

import com.bts.issue.adapter.inbound.rest.CurrentActor
import com.bts.issue.adapter.inbound.rest.DataResponse
import com.bts.issue.comment.application.CommentApplicationService
import com.bts.issue.comment.application.CommentView
import com.bts.issue.config.BEARER_AUTH_SCHEME
import com.bts.issue.domain.IssueKey
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
import org.springframework.web.bind.annotation.RestController

/**
 * 댓글 REST 컨트롤러 (FR-IM-01 PR3).
 *
 * 엔드포인트 목록.
 * - GET /api/v1/issues/{key}/comments — 댓글 목록 조회 (200)
 *
 * ### ActorId 결선
 * [CurrentActor.current] 로 SecurityContextHolder 의 인증 주체를 actor 로 추출한다.
 * 미인증·익명·비-UUID·nil-UUID 주체는 401(UNAUTHORIZED) 로 거부한다.
 * actor 추출은 리소스 조회보다 앞서 수행하여 미인증자가 404 로 리소스 존재를 probe 하지 못하게 한다
 * ([com.bts.issue.worklog.web.WorklogController] 선례와 동일 근거).
 *
 * @param service 댓글 유스케이스 서비스.
 */
@Tag(name = "Comments", description = "이슈 댓글 조회 API (FR-IM-01 PR3)")
@RestController
@RequestMapping("/api/v1/issues/{key}/comments")
class CommentController(
    private val service: CommentApplicationService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 이슈의 댓글 목록을 조회한다.
     *
     * @param key path variable 이슈 키 문자열.
     * @return 200 OK + [CommentResponse] 목록 (`created_at` 오름차순).
     * @throws com.bts.issue.domain.IssueAccessDeniedException VIEW 권한 미보유 시 → 403.
     * @throws com.bts.issue.domain.IssueNotFoundException 이슈 미존재·소프트 삭제 시 → 404.
     */
    @Operation(operationId = "listComments", summary = "댓글 목록 조회")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "댓글 목록"),
        ApiResponse(responseCode = "401", description = "미인증", content = [Content()]),
        ApiResponse(responseCode = "403", description = "VIEW 권한 없음", content = [Content()]),
        ApiResponse(responseCode = "404", description = "이슈 미존재", content = [Content()]),
    )
    @SecurityRequirement(name = BEARER_AUTH_SCHEME)
    @GetMapping
    fun listComments(
        @PathVariable key: String,
    ): ResponseEntity<DataResponse<List<CommentResponse>>> {
        val actor = CurrentActor.current()
        log.info("CommentController.listComments key={} actor={}", key, actor.value)

        val views = service.list(actor = actor, issueKey = IssueKey(key))
        val response =
            views.map { view: CommentView ->
                CommentResponse(
                    id = view.id,
                    authorId = view.authorId,
                    body = view.body,
                    bodyHtml = view.bodyHtml,
                    createdAt = view.createdAt,
                    updatedAt = view.updatedAt,
                )
            }
        return ResponseEntity.ok(DataResponse(data = response))
    }
}
