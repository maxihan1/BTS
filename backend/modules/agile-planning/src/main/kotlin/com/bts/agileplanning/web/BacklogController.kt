// 백로그 조회 REST API 컨트롤러 — 미할당·스프린트별 그룹핑 (FR-BL-01/02 Task 3)

package com.bts.agileplanning.web

import com.bts.agileplanning.application.BacklogApplicationService
import com.bts.agileplanning.web.dto.BacklogResponse
import com.bts.agileplanning.web.dto.DataResponse
import com.bts.agileplanning.web.dto.SprintIssuesResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * 백로그 조회 REST API 컨트롤러 — agile-planning BC.
 *
 * 엔드포인트.
 * - GET `/api/v1/projects/{projectKey}/backlog` — 프로젝트 백로그 조회. 권한 BROWSE.
 *
 * ### 처리 순서 (존재 probe 차단)
 * 1. actor 추출([currentActorId]) — 미인증이면 401(리소스 조회 이전에 차단).
 * 2. service 위임 — service 내부에서 BROWSE 권한 판정(403) → 조회 수행 순으로 처리한다.
 *
 * ### 트랜잭션 정책
 * 컨트롤러는 트랜잭션 경계를 담당하지 않는다. 트랜잭션은 [BacklogApplicationService] 가 개시한다.
 *
 * ### 예외 처리
 * [BacklogExceptionHandler] 가 이 컨트롤러에서 발생하는 모든 예외를 처리한다.
 *
 * @param service 백로그 조회 서비스.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectKey}/backlog")
class BacklogController(
    private val service: BacklogApplicationService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 프로젝트의 백로그(미할당)와 스프린트별 이슈를 조회한다.
     *
     * actor 추출 후 service 에 위임한다. service 내부에서 BROWSE 권한을 판정한다.
     *
     * `board` 는 [UUID] 로 바인딩한다 — 형식 계약을 타입으로 못박으면 잘못된 형식이
     * 서비스에 닿기 전에 Spring 이 400 으로 막는다(learnings 2026-06-25). 폴백·존재 판정은
     * 컨트롤러가 하지 않는다. 미지정이면 null 을 그대로 넘기고 service 가 기본 보드를 정한다.
     *
     * @param projectKey path variable 프로젝트 키.
     * @param board 스코프할 보드 UUID. 선택 — 미지정이면 service 가 기본 보드로 폴백한다.
     * @return 200 OK + [BacklogResponse] 봉투.
     * @throws ResponseStatusException 401 — 미인증.
     * @throws ResponseStatusException 403 — BROWSE 권한 미충족.
     * @throws ResponseStatusException 404 — [board] 가 이 프로젝트의 보드가 아닐 때.
     */
    @GetMapping
    fun getBacklog(
        @PathVariable projectKey: String,
        @RequestParam(name = "board", required = false) board: UUID?,
    ): ResponseEntity<DataResponse<BacklogResponse>> {
        log.info("BacklogController.getBacklog projectKey={}, board={}", projectKey, board)

        val actor = currentActorId()
        val result = service.getBacklog(actorId = actor, projectKey = projectKey, boardId = board)

        val response =
            BacklogResponse(
                backlog = result.backlog,
                sprints =
                    result.sprints.map { sw ->
                        SprintIssuesResponse(
                            sprint = sw.sprint,
                            issues = sw.issues,
                        )
                    },
                truncated = result.truncated,
            )

        return ResponseEntity.ok(DataResponse(response))
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * [SecurityContextHolder] 에서 인증 주체 UUID 를 추출한다.
     *
     * actor 추출은 리소스 조회보다 먼저 수행해야 한다(미인증자의 존재 probe 차단,
     * memory: auth-extraction-before-resource-lookup). 미인증·익명·비-UUID 주체는 401 로 거부한다.
     *
     * @return 인증 주체 UUID.
     * @throws ResponseStatusException 401 — 인증이 없거나 주체가 유효한 UUID 가 아닐 때.
     */
    private fun currentActorId(): UUID {
        val authentication =
            SecurityContextHolder.getContext().authentication
                ?.takeIf { it.isAuthenticated && it !is AnonymousAuthenticationToken }
                ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required")
        return try {
            UUID.fromString(authentication.name)
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required", e)
        }
    }
}
