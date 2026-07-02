// 스프린트 벨로시티(Velocity) 차트 조회 REST 컨트롤러 — agile-planning BC (FR-RP-02 Task 5)

package com.bts.agileplanning.web

import com.bts.agileplanning.application.SprintVelocityService
import com.bts.agileplanning.web.dto.DataResponse
import com.bts.agileplanning.web.dto.VelocityResponse
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
 * 스프린트 벨로시티(Velocity) 차트 조회 REST 컨트롤러 — agile-planning BC.
 *
 * 엔드포인트.
 * - GET `/api/v1/projects/{projectKey}/velocity` — 최근 완료 스프린트 벨로시티 시계열 조회. 200.
 *
 * ### 책임 분리
 * 권한 판정과 계산은 [SprintVelocityService] 및 도메인 계층 책임이다. 컨트롤러는 actor 추출과
 * service 위임, 응답 변환만 담당한다.
 *
 * ### 처리 순서 (존재 probe 차단)
 * 1. actor 추출([currentActorId]) — 미인증이면 401(리소스 조회 이전에 차단).
 * 2. service 위임 — service 내부에서 프로젝트 BROWSE 권한 판정(403) -> 계산 순으로 처리한다.
 *
 * ### 예외 -> HTTP 상태 매핑 ([SprintExceptionHandler] 처리)
 * - 미인증(SecurityContext 없음/익명/비-UUID) -> 401
 * - 프로젝트 BROWSE 권한 미충족(403 [ResponseStatusException]) -> 403
 *
 * [SprintController]·[SprintBurndownController] 는 변경하지 않는다 — 신규 GET 엔드포인트를
 * 별도 컨트롤러로 분리해 기존 테스트 회귀를 0으로 유지한다.
 *
 * @param service 벨로시티 유스케이스 서비스.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectKey}/velocity")
class SprintVelocityController(
    private val service: SprintVelocityService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 프로젝트의 최근 완료 스프린트 벨로시티 시계열을 조회한다.
     *
     * @param projectKey path variable 프로젝트 키.
     * @param limit 조회할 최근 COMPLETED 스프린트 개수. 기본값 [DEFAULT_LIMIT]. 실제 클램프 범위는
     *   [SprintVelocityService] 가 결정한다.
     * @return 200 OK + [VelocityResponse].
     * @throws ResponseStatusException 401 — 미인증.
     * @throws ResponseStatusException 403 — BROWSE 권한 미충족.
     */
    @GetMapping
    fun getVelocity(
        @PathVariable projectKey: String,
        @RequestParam(defaultValue = DEFAULT_LIMIT) limit: Int,
    ): ResponseEntity<DataResponse<VelocityResponse>> {
        log.info("SprintVelocityController.getVelocity projectKey={} limit={}", projectKey, limit)

        val actor = currentActorId()
        val result = service.getVelocity(actorId = actor, projectKey = projectKey, limit = limit)
        return ResponseEntity.ok(DataResponse(VelocityResponse.from(result)))
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * [SecurityContextHolder] 에서 인증 주체 UUID 를 추출한다.
     *
     * actor 추출은 리소스 조회보다 먼저 수행해야 한다(미인증자의 존재 probe 차단,
     * memory: auth-extraction-before-resource-lookup 교훈). [SprintBurndownController]·[BacklogController]
     * 의 동일 헬퍼 로직을 복제한다 — 작업 범위(files 선언)가 이 파일로 한정되어 기존 컨트롤러
     * 파일은 수정하지 않는다.
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

    private companion object {
        /** `limit` 쿼리 파라미터 기본값. 실제 상한/하한 클램프는 [SprintVelocityService] 책임. */
        const val DEFAULT_LIMIT = "10"
    }
}
