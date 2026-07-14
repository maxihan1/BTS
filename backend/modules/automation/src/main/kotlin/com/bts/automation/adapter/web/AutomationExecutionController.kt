// 자동화 룰 실행 이력(RuleExecution) 조회 REST 컨트롤러 — 룰별 목록 + 단건 trace, MANAGE_AUTOMATION 가드 (FR-AT-05 Task 4)

package com.bts.automation.adapter.web

import com.bts.automation.adapter.web.dto.RuleExecutionDetailResponse
import com.bts.automation.adapter.web.dto.RuleExecutionSummaryResponse
import com.bts.automation.application.AutomationForbiddenException
import com.bts.automation.application.RuleExecutionNotFoundException
import com.bts.automation.application.RuleExecutionService
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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.time.Instant
import java.util.UUID

/**
 * 자동화 룰 실행 이력(RuleExecution) 조회 REST 컨트롤러 (FR-AT-05 Task 4).
 *
 * spec API 표 endpoint 2종.
 * - `GET /api/v1/projects/{projectKey}/automation/rules/{ruleId}/executions` — 룰별 이력 목록(최신순).
 * - `GET /api/v1/automation/executions/{id}` — 단건 trace(outcomes+triggerEvent 포함).
 *
 * replay(POST, 재실행)는 이 컨트롤러 범위가 아니다(Task 5).
 *
 * 두 경로의 prefix 가 서로 달라(하나는 프로젝트 스코프, 하나는 전역) class-level `@RequestMapping` 을
 * 두지 않고 메서드마다 전체 경로를 명시한다([com.bts.automation.adapter.web.AutomationRuleController] 와
 * 달리 단일 리소스 prefix 로 묶이지 않는다).
 *
 * ## 자체 actor 추출기 + 스코프 예외 핸들러 ([[domain-exception-http-handler-basepackage-scope]])
 * [AutomationRuleController] 의 예외 핸들러([com.bts.automation.adapter.web.AutomationRuleExceptionHandler])는
 * `assignableTypes` 가 그 컨트롤러로 한정돼 있어 이 컨트롤러의 예외를 잡지 않는다. 마찬가지로 그 컨트롤러의
 * private actor 추출기도 재사용할 수 없어([[auth-extraction-before-resource-lookup]] — actor 추출은 서비스
 * 호출(권한 판정·리소스 조회)보다 먼저), 이 파일이 자체 [AutomationExecutionActorExtractor] +
 * [AutomationExecutionExceptionHandler] 를 둔다(동형 복제, 코드 중복이지만 파일 경계를 넘는 private 공유는
 * 불가능하다).
 *
 * @param service 실행 이력 조회 유스케이스 서비스.
 */
@RestController
class AutomationExecutionController(
    private val service: RuleExecutionService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * [ruleId] 룰의 실행 이력을 최신순으로 조회한다.
     *
     * 룰 존재 여부는 확인하지 않는다(소프트/하드 삭제된 룰의 이력도 조회 가능 — NFR-4,
     * [RuleExecutionService.listByRule] 클래스 KDoc 참고).
     *
     * @param projectKey 룰이 속한 프로젝트 키(경로 변수, 권한 판정 스코프).
     * @param ruleId 대상 룰 id(경로 변수).
     * @param issueKey 지정하면 해당 이슈에 대한 실행만 반환(쿼리 파라미터, 선택).
     * @param limit 최대 반환 건수(쿼리 파라미터, 기본 [DEFAULT_LIMIT], [MAX_LIMIT] 초과 시 clamp).
     * @param before 지정하면 이 시각(ISO-8601 Instant) 이전 실행만 반환(쿼리 파라미터, keyset 커서, 선택).
     * @return 200 OK + 최신순 실행 이력 요약 목록.
     */
    @GetMapping("/api/v1/projects/{projectKey}/automation/rules/{ruleId}/executions")
    fun listByRule(
        @PathVariable projectKey: String,
        @PathVariable ruleId: UUID,
        @RequestParam(required = false) issueKey: String?,
        @RequestParam(defaultValue = DEFAULT_LIMIT.toString()) limit: Int,
        @RequestParam(required = false) before: Instant?,
    ): ResponseEntity<List<RuleExecutionSummaryResponse>> {
        val actorId = AutomationExecutionActorExtractor.extract()
        val clampedLimit = limit.coerceIn(MIN_LIMIT, MAX_LIMIT)
        val executions = service.listByRule(actorId, projectKey, ruleId, issueKey, clampedLimit, before)
        log.info(
            "AutomationExecutionController.listByRule actor={} projectKey={} ruleId={} count={}",
            actorId,
            projectKey,
            ruleId,
            executions.size,
        )
        return ResponseEntity.ok(executions.map(RuleExecutionSummaryResponse::from))
    }

    /**
     * [id] 실행 이력을 trace 상세 조회한다(outcomes+triggerEvent 포함).
     *
     * 권한 판정은 레코드의 소속 프로젝트 기준이다 — 미존재/타 프로젝트(권한 없음) 모두 404 로 수렴한다
     * ([RuleExecutionService.getById] 클래스 KDoc 참고).
     *
     * @param id 조회할 실행 이력 id(경로 변수).
     * @return 200 OK + trace 상세.
     */
    @GetMapping("/api/v1/automation/executions/{id}")
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<RuleExecutionDetailResponse> {
        val actorId = AutomationExecutionActorExtractor.extract()
        val execution = service.getById(actorId, id)
        log.info("AutomationExecutionController.get actor={} id={}", actorId, id)
        return ResponseEntity.ok(RuleExecutionDetailResponse.from(execution))
    }

    private companion object {
        const val DEFAULT_LIMIT = 50
        const val MIN_LIMIT = 1
        const val MAX_LIMIT = 200
    }
}

/**
 * [SecurityContextHolder] 에서 인증된 사용자의 UUID 를 추출한다([com.bts.automation.adapter.web.AutomationRuleController]
 * 의 `AutomationActorExtractor` 동형 복제 — 클래스 KDoc "자체 actor 추출기 + 스코프 예외 핸들러" 참고).
 *
 * [AutomationExecutionController] 의 모든 핸들러가 서비스 호출(권한 판정·리소스 조회)보다 **먼저** 호출해
 * 존재 probe 를 차단한다([[auth-extraction-before-resource-lookup]]).
 */
private object AutomationExecutionActorExtractor {
    /**
     * 인증 주체를 UUID 로 추출한다.
     *
     * @return 인증된 사용자의 UUID.
     * @throws ResponseStatusException 401 미인증 또는 UUID 변환 실패 시.
     */
    fun extract(): UUID {
        val authentication =
            SecurityContextHolder.getContext().authentication
                ?.takeIf { it.isAuthenticated && it !is AnonymousAuthenticationToken }
                ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required")
        return try {
            val uuid = UUID.fromString(authentication.name)
            require(uuid != UUID(0L, 0L)) { "nil UUID는 actor로 허용되지 않습니다." }
            uuid
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required", e)
        }
    }
}

/**
 * [AutomationExecutionController] 예외를 RFC 7807 [ProblemDetail] 로 변환한다.
 *
 * [assignableTypes] 를 [AutomationExecutionController] 로 한정해 다른 컨트롤러를 가로채지 않는다
 * ([[domain-exception-http-handler-basepackage-scope]]). [ResponseStatusException] 은 전용 핸들러가
 * 상태를 그대로 전파하고, 분류되지 않은 예외만 [handleInternal] 이 500 으로 매핑한다(401 이 500 으로
 * 변질되지 않게 한다 — [[catch-all-exceptionhandler-swallows-responsestatusexception]]).
 *
 * 에러 코드 prefix 는 `AUTOMATION_` 로 고정한다([com.bts.automation.adapter.web.AutomationRuleExceptionHandler]
 * 동일 관례).
 */
@RestControllerAdvice(assignableTypes = [AutomationExecutionController::class])
class AutomationExecutionExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    /** MANAGE_AUTOMATION 권한 없음(프로젝트 스코프 목록 조회) — 403. */
    @ExceptionHandler(AutomationForbiddenException::class)
    fun handleForbidden(
        @Suppress("UnusedParameter") ex: AutomationForbiddenException,
    ): ProblemDetail {
        log.info("AUTOMATION_403 forbidden")
        return problem(
            HttpStatus.FORBIDDEN,
            "automation-execution-forbidden",
            "Forbidden",
            RuleExecutionErrorCodes.ACCESS_DENIED,
            "이 작업을 수행할 권한이 없습니다.",
        )
    }

    /** 실행 이력 없음(또는 다른 프로젝트 소속이라 권한 없음, 존재 숨김) — 404. */
    @ExceptionHandler(RuleExecutionNotFoundException::class)
    fun handleNotFound(ex: RuleExecutionNotFoundException): ProblemDetail {
        log.info("AUTOMATION_404 execution_not_found executionId={}", ex.executionId)
        return problem(
            HttpStatus.NOT_FOUND,
            "automation-execution-not-found",
            "Not Found",
            RuleExecutionErrorCodes.EXECUTION_NOT_FOUND,
            "실행 이력을 찾을 수 없습니다.",
        )
    }

    /** [AutomationExecutionActorExtractor] 의 미인증 401 등 명시 상태 전파. */
    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(ex: ResponseStatusException): ProblemDetail {
        val status = HttpStatus.valueOf(ex.statusCode.value())
        log.info("AUTOMATION_{} response_status", status.value())
        return problem(
            status,
            "automation-execution-response-status",
            status.reasonPhrase,
            RuleExecutionErrorCodes.UNAUTHENTICATED,
            "인증이 필요합니다. 세션이 만료되었을 수 있습니다.",
        )
    }

    /**
     * 쿼리 파라미터 타입 불일치(`before` 가 ISO Instant 형식이 아님 등) — 400.
     *
     * catch-all 보다 구체적인 예외라 Spring 이 이 핸들러를 우선 매칭한다.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(ex: MethodArgumentTypeMismatchException): ProblemDetail {
        log.info("AUTOMATION_400 type_mismatch param='{}' value='{}'", ex.name, ex.value)
        return problem(
            HttpStatus.BAD_REQUEST,
            "automation-execution-invalid",
            "Bad Request",
            RuleExecutionErrorCodes.MALFORMED_REQUEST,
            "요청 파라미터 값이 올바르지 않습니다.",
        )
    }

    /** 요청 바디가 유효한 JSON 이 아님 — 400(이 컨트롤러엔 요청 바디가 없지만 방어적으로 등록한다). */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleMalformedRequest(
        @Suppress("UnusedParameter") ex: HttpMessageNotReadableException,
    ): ProblemDetail {
        log.info("AUTOMATION_400 malformed_request")
        return problem(
            HttpStatus.BAD_REQUEST,
            "automation-execution-invalid",
            "Bad Request",
            RuleExecutionErrorCodes.MALFORMED_REQUEST,
            "요청 본문이 유효하지 않습니다.",
        )
    }

    /** 분류되지 않은 모든 예외 — 500. 스택트레이스는 서버 로그 전용, 응답에는 일반 메시지만. */
    @ExceptionHandler(Exception::class)
    fun handleInternal(ex: Exception): ProblemDetail {
        log.error("AUTOMATION_500 internal_error", ex)
        return problem(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "automation-execution-internal-error",
            "Internal Server Error",
            RuleExecutionErrorCodes.INTERNAL_ERROR,
            "서버 내부 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.",
        )
    }

    private fun problem(
        status: HttpStatus,
        type: String,
        title: String,
        errorCode: String,
        detail: String,
    ): ProblemDetail {
        val pd = ProblemDetail.forStatus(status)
        pd.type = URI.create("https://bts.example.com/problems/$type")
        pd.title = title
        pd.detail = detail
        pd.setProperty("errorCode", errorCode)
        pd.setProperty("timestamp", Instant.now().toString())
        return pd
    }
}

/**
 * [AutomationExecutionController] 전용 에러 코드 상수([com.bts.automation.adapter.web.AutomationRuleExceptionHandler]
 * 의 `private companion object` 동일 관례 — `AUTOMATION_` prefix 고정).
 */
private object RuleExecutionErrorCodes {
    const val ACCESS_DENIED = "AUTOMATION_ACCESS_DENIED"
    const val EXECUTION_NOT_FOUND = "AUTOMATION_EXECUTION_NOT_FOUND"
    const val MALFORMED_REQUEST = "AUTOMATION_MALFORMED_REQUEST"
    const val UNAUTHENTICATED = "AUTOMATION_UNAUTHENTICATED"
    const val INTERNAL_ERROR = "AUTOMATION_INTERNAL_ERROR"
}
