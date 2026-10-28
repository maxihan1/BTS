// 수동 실행(MANUAL) 트리거 REST — 실행 가능한 룰 목록 · 이슈로 실행 (R9 · JAU-5 · FR-AT-01)

package com.bts.automation.adapter.web

import com.bts.automation.application.ManualRunForbiddenException
import com.bts.automation.application.ManualRunNotFoundException
import com.bts.automation.application.ManualRunService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * 이슈 화면의 「자동화 ▸」 메뉴용 엔드포인트 2개. 룰 관리 컨트롤러([AutomationRuleController])와 달리
 * `MANAGE_AUTOMATION` 이 아니라 이슈 열람자 권한으로 판정한다([ManualRunService] KDoc).
 *
 * - `GET  /api/v1/projects/{projectKey}/automation/manual-rules` — `[{id, name}]`
 * - `POST /api/v1/projects/{projectKey}/automation/manual-rules/{ruleId}/run` body `{issueKey}` — 202
 */
@RestController
@RequestMapping("/api/v1/projects/{projectKey}/automation/manual-rules")
class ManualRunController(
    private val service: ManualRunService,
) {
    @GetMapping
    fun list(
        @PathVariable projectKey: String,
    ): List<ManualRuleResponse> {
        val rules = service.listRunnable(currentActorId(), projectKey)
        return rules.map { ManualRuleResponse(it.id, it.name) }
    }

    @PostMapping("/{ruleId}/run")
    fun run(
        @PathVariable projectKey: String,
        @PathVariable ruleId: UUID,
        @RequestBody request: ManualRunRequest,
    ): ResponseEntity<Unit> {
        service.run(currentActorId(), projectKey, ruleId, request.issueKey)
        return ResponseEntity.accepted().build()
    }
}

/** 실행 가능한 MANUAL 룰 한 건 — 메뉴 표시에 필요한 것만. */
data class ManualRuleResponse(
    val id: UUID,
    val name: String,
)

/** 수동 실행 요청 — 대상 이슈 키만 받는다(triggerEvent 는 서버가 만든다). */
data class ManualRunRequest(
    val issueKey: String,
)

/** 인증 주체 UUID — 미인증 · 형식 오류는 401(`AutomationActorExtractor` 동형 — 그쪽이 파일 private 이라 복제). */
private fun currentActorId(): UUID {
    val authentication =
        SecurityContextHolder.getContext().authentication
            ?.takeIf { it.isAuthenticated && it !is AnonymousAuthenticationToken }
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required")
    return try {
        UUID.fromString(authentication.name).also { require(it != UUID(0L, 0L)) }
    } catch (e: IllegalArgumentException) {
        throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required", e)
    }
}

/** [ManualRunController] 전용 예외 매핑. catch-all 이 없으므로 그 밖의 예외는 전역 처리에 맡긴다. */
@RestControllerAdvice(assignableTypes = [ManualRunController::class])
class ManualRunExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ManualRunNotFoundException::class)
    fun handleNotFound(ex: ManualRunNotFoundException): ProblemDetail {
        log.info("AUTOMATION_404 manual_run_not_found")
        return problem(HttpStatus.NOT_FOUND, "AUTOMATION_MANUAL_RUN_NOT_FOUND", ex.message)
    }

    @ExceptionHandler(ManualRunForbiddenException::class)
    fun handleForbidden(ex: ManualRunForbiddenException): ProblemDetail {
        log.info("AUTOMATION_403 manual_run_forbidden")
        return problem(HttpStatus.FORBIDDEN, "AUTOMATION_ACCESS_DENIED", ex.message)
    }

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(ex: ResponseStatusException): ProblemDetail =
        problem(HttpStatus.valueOf(ex.statusCode.value()), "AUTOMATION_UNAUTHENTICATED", ex.reason)

    @ExceptionHandler(HttpMessageNotReadableException::class, MethodArgumentTypeMismatchException::class)
    fun handleMalformed(
        @Suppress("UnusedParameter") ex: Exception,
    ): ProblemDetail = problem(HttpStatus.BAD_REQUEST, "AUTOMATION_MALFORMED_REQUEST", "요청 형식이 올바르지 않습니다.")

    private fun problem(
        status: HttpStatus,
        errorCode: String,
        detail: String?,
    ): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail ?: status.reasonPhrase).apply {
            setProperty("errorCode", errorCode)
        }
}
