// 댓글 REST 컨트롤러 — 목록 조회 엔드포인트 (FR-IM-01 PR3)

package com.bts.issue.comment.web

import com.bts.issue.adapter.inbound.rest.CurrentActor
import com.bts.issue.adapter.inbound.rest.DataResponse
import com.bts.issue.comment.application.CommentApplicationService
import com.bts.issue.config.BEARER_AUTH_SCHEME
import com.bts.issue.domain.IssueKey
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

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
        val response = views.map(CommentResponse::from)
        return ResponseEntity.ok(DataResponse(data = response))
    }

    /**
     * 이슈에 댓글을 작성한다.
     *
     * ## 저작자는 요청이 정하지 않는다 (FR-CO-01 D4)
     * [AddCommentRequest] 에 저작자 필드가 없고 [CommentApplicationService.create] 도 3인자여서,
     * 이 경로로는 남의 이름으로 댓글을 쓸 방법이 **구조적으로** 없다.
     *
     * ## 검증 책임 분담
     * - `body` **필드 누락**(`null`) → 여기서 `400`. 요청 형식 문제는 웹 관심사다.
     * - `body` **공백·길이 초과** → 서비스가 도메인 예외를 던지고 [CommentExceptionHandler] 가 `400`.
     *   REST 뿐 아니라 automation 도 같은 서비스를 쓰므로 본문 정책은 서비스에 있어야 한다(D7).
     *
     * @param key path variable 이슈 키 문자열.
     * @param request 댓글 작성 요청 본문.
     * @return 201 Created + 생성된 [CommentResponse].
     * @throws ResponseStatusException `body` 필드 누락 시 → 400.
     * @throws com.bts.issue.comment.domain.CommentBodyBlankException 본문이 공백만일 때 → 400.
     * @throws com.bts.issue.comment.domain.CommentBodyTooLongException 본문이 상한 초과일 때 → 400.
     * @throws com.bts.issue.domain.IssueAccessDeniedException UPDATE 권한 미보유 시 → 403.
     * @throws com.bts.issue.domain.IssueNotFoundException 이슈 미존재·소프트 삭제 시 → 404.
     */
    @Operation(operationId = "addComment", summary = "댓글 작성")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "생성 성공"),
        ApiResponse(responseCode = "400", description = "요청 형식 오류·본문 공백·길이 초과", content = [Content()]),
        ApiResponse(responseCode = "401", description = "미인증", content = [Content()]),
        ApiResponse(responseCode = "403", description = "UPDATE 권한 없음 · 아카이브 프로젝트", content = [Content()]),
        ApiResponse(responseCode = "404", description = "이슈 미존재", content = [Content()]),
    )
    @SecurityRequirement(name = BEARER_AUTH_SCHEME)
    @PostMapping
    fun addComment(
        @PathVariable key: String,
        @RequestBody request: AddCommentRequest,
    ): ResponseEntity<DataResponse<CommentResponse>> {
        val actor = CurrentActor.current()
        val body =
            request.body
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "body 는 필수입니다.")

        log.info("CommentController.addComment key={} actor={}", key, actor.value)

        val comment = service.create(actor = actor, issueKey = IssueKey(key), body = body)
        return ResponseEntity.status(HttpStatus.CREATED).body(DataResponse(data = CommentResponse.from(comment)))
    }

    /**
     * 댓글 본문을 수정한다 — 작성자 본인만 가능하다 (FR-CO-02).
     *
     * ## ★ 응답은 반드시 [CommentResponse.from] 을 경유한다
     * 그 팩토리가 [com.bts.issue.comment.application.CommentView.of] 에 위임하고, 거기가
     * `MarkdownRenderer.renderSafe` 를 부르는 **정화 단일 지점**이다. 여기서 렌더러를 직접 부르면
     * 목록·작성·수정 세 경로의 정화 정책이 갈라져 한쪽만 XSS 방어가 약해지는 회귀가 가능해진다
     * ([com.bts.issue.comment.application.CommentView.of] KDoc 참조).
     *
     * ## 검증 책임 분담 ([addComment] 와 동일)
     * - `body` **필드 누락**(`null`) → 여기서 `400`. 요청 형식 문제는 웹 관심사다.
     * - `body` **공백·길이 초과** → 서비스가 도메인 예외를 던지고 [CommentExceptionHandler] 가 `400`.
     *
     * @param key path variable 이슈 키 문자열.
     * @param commentId path variable 수정할 댓글 UUID.
     * @param request 댓글 수정 요청 본문 (저작자 필드 없음 — [UpdateCommentRequest] KDoc 참조).
     * @return 200 OK + 수정된 [CommentResponse]. 본문이 기존과 같으면 갱신 없이 기존 값을 돌려준다.
     * @throws ResponseStatusException `body` 필드 누락 시 → 400.
     * @throws com.bts.issue.comment.domain.CommentBodyBlankException 본문이 공백만일 때 → 400.
     * @throws com.bts.issue.comment.domain.CommentBodyTooLongException 본문이 상한 초과일 때 → 400.
     * @throws com.bts.issue.domain.IssueAccessDeniedException UPDATE 미보유 또는 타인 댓글일 때 → 403.
     * @throws com.bts.issue.domain.IssueNotFoundException 이슈 미존재·소프트 삭제 시 → 404.
     * @throws com.bts.issue.comment.domain.CommentNotFoundException 댓글 미존재·삭제됨·이슈 불일치 시 → 404.
     * @throws com.bts.issue.project.archive.ProjectArchivedException 아카이브된 프로젝트일 때 → 409.
     */
    @PatchMapping("/{commentId}")
    fun updateComment(
        @PathVariable key: String,
        @PathVariable commentId: UUID,
        @RequestBody request: UpdateCommentRequest,
    ): DataResponse<CommentResponse> {
        val actor = CurrentActor.current()
        val body =
            request.body
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "body 는 필수입니다.")

        log.info("CommentController.updateComment key={} commentId={} actor={}", key, commentId, actor.value)

        val comment = service.update(actor = actor, issueKey = IssueKey(key), commentId = commentId, body = body)
        return DataResponse(data = CommentResponse.from(comment))
    }

    /**
     * 댓글을 소프트 삭제한다 — 작성자 본인 또는 `SOFT_DELETE` 보유자(모더레이터) (FR-CO-02).
     *
     * 이미 삭제된 댓글에 대한 재삭제는 멱등 204 가 아니라 404 다 — 활성 댓글만 대상이다(스펙 E3).
     *
     * @param key path variable 이슈 키 문자열.
     * @param commentId path variable 삭제할 댓글 UUID.
     * @throws com.bts.issue.domain.IssueAccessDeniedException UPDATE 미보유, 또는 작성자도
     *   모더레이터도 아닐 때 → 403.
     * @throws com.bts.issue.domain.IssueNotFoundException 이슈 미존재·소프트 삭제 시 → 404.
     * @throws com.bts.issue.comment.domain.CommentNotFoundException 댓글 미존재·이미 삭제됨·이슈 불일치 시 → 404.
     * @throws com.bts.issue.project.archive.ProjectArchivedException 아카이브된 프로젝트일 때 → 409.
     */
    @DeleteMapping("/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteComment(
        @PathVariable key: String,
        @PathVariable commentId: UUID,
    ) {
        val actor = CurrentActor.current()
        log.info("CommentController.deleteComment key={} commentId={} actor={}", key, commentId, actor.value)

        service.delete(actor = actor, issueKey = IssueKey(key), commentId = commentId)
    }
}
