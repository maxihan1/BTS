// 간트 차트 타임라인 조회 REST API 컨트롤러 — agile-planning BC (FR-TL-01 Task 5)

package com.bts.agileplanning.web

import com.bts.agileplanning.application.TimelineApplicationService
import com.bts.agileplanning.web.dto.DataResponse
import com.bts.agileplanning.web.dto.TimelineDepEdgeResponse
import com.bts.agileplanning.web.dto.TimelineDepsResponse
import com.bts.agileplanning.web.dto.TimelineItemResponse
import com.bts.agileplanning.web.dto.TimelineResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * 간트 차트 타임라인 조회 REST API 컨트롤러 — agile-planning BC.
 *
 * 엔드포인트.
 * - GET `/api/v1/timeline?project={projectKey}` — 프로젝트 타임라인 조회. 권한 BROWSE.
 * - GET `/api/v1/timeline/deps?project={projectKey}` — 프로젝트 내 `blocks` 의존 엣지 조회. 권한 BROWSE.
 *
 * ### 처리 순서 (존재 probe 차단)
 * 1. actor 추출([currentActorId]) — 미인증이면 401(리소스 조회 이전에 차단).
 * 2. service 위임 — service 내부에서 BROWSE 권한 판정(403) → 조회 수행 순으로 처리한다.
 *
 * ### 트랜잭션 정책
 * 컨트롤러는 트랜잭션 경계를 담당하지 않는다. 트랜잭션은 [TimelineApplicationService] 가 개시한다.
 *
 * ### 예외 처리
 * [TimelineExceptionHandler] 가 이 컨트롤러에서 발생하는 모든 예외를 처리한다.
 * assignableTypes 한정으로 다른 컨트롤러의 예외 핸들러와 스코프가 충돌하지 않는다.
 *
 * @param service 타임라인 조회 서비스.
 */
@RestController
@RequestMapping("/api/v1/timeline")
class TimelineController(
    private val service: TimelineApplicationService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 프로젝트의 타임라인 이슈 목록을 날짜 정렬해 반환한다.
     *
     * actor 추출 후 service 에 위임한다. service 내부에서 BROWSE 권한을 판정한다.
     *
     * @param project 조회할 프로젝트 키. 예: `"BTS"`. 필수 쿼리 파라미터.
     * @return 200 OK + [TimelineResponse] 봉투.
     * @throws ResponseStatusException 401 — 미인증.
     * @throws ResponseStatusException 403 — BROWSE 권한 미충족.
     */
    @GetMapping
    fun getTimeline(
        @RequestParam project: String,
    ): ResponseEntity<DataResponse<TimelineResponse>> {
        log.info("TimelineController.getTimeline project={}", project)

        val actor = currentActorId()
        val result = service.getTimeline(actorId = actor, projectKey = project)

        val response =
            TimelineResponse(
                items = result.items.map { TimelineItemResponse.from(it) },
                truncated = result.truncated,
            )

        return ResponseEntity.ok(DataResponse(response))
    }

    /**
     * 프로젝트의 `blocks` 의존 엣지 목록을 결정적 순서로 반환한다 (FR-TL-02).
     *
     * actor 추출 후 service 에 위임한다. service 내부에서 BROWSE 권한을 판정한다.
     *
     * @param project 조회할 프로젝트 키. 예: `"BTS"`. 필수 쿼리 파라미터.
     * @return 200 OK + [TimelineDepsResponse] 봉투.
     * @throws ResponseStatusException 401 — 미인증.
     * @throws ResponseStatusException 403 — BROWSE 권한 미충족.
     */
    @GetMapping("/deps")
    fun getDeps(
        @RequestParam project: String,
    ): ResponseEntity<DataResponse<TimelineDepsResponse>> {
        log.info("TimelineController.getDeps project={}", project)

        val actor = currentActorId()
        val result = service.getDeps(actorId = actor, projectKey = project)

        val response =
            TimelineDepsResponse(
                deps = result.edges.map { TimelineDepEdgeResponse.from(it) },
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
