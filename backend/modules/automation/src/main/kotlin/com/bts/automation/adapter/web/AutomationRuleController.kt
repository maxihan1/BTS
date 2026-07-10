// 자동화 룰 CRUD REST 컨트롤러 — 프로젝트 스코프 + MANAGE_AUTOMATION 가드(actor 추출 401 → 권한 403 → 조회) (FR-AT-01 Task 6)

package com.bts.automation.adapter.web

import com.bts.automation.adapter.web.dto.AutomationRuleResponse
import com.bts.automation.adapter.web.dto.CreateAutomationRuleRequest
import com.bts.automation.adapter.web.dto.CreateAutomationRuleResponse
import com.bts.automation.adapter.web.dto.PatchAutomationRuleRequest
import com.bts.automation.application.AutomationForbiddenException
import com.bts.automation.application.AutomationRuleNotFoundException
import com.bts.automation.application.AutomationRuleService
import com.bts.automation.application.AutomationRuleVersionConflictException
import com.bts.automation.domain.AutomationDomainException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.time.Instant
import java.util.UUID

/**
 * 자동화 룰(AutomationRule) CRUD REST 컨트롤러 (FR-AT-01 Task 6).
 *
 * spec API 표 5 endpoint.
 * - `POST   /api/v1/projects/{projectKey}/automation/rules`      — 생성(201, WEBHOOK 이면 토큰 1회 동봉)
 * - `GET    /api/v1/projects/{projectKey}/automation/rules`      — 목록
 * - `GET    /api/v1/projects/{projectKey}/automation/rules/{id}` — 단건(토큰 미노출)
 * - `PATCH  /api/v1/projects/{projectKey}/automation/rules/{id}` — 부분수정(name·enabled·triggerConfig, OCC)
 * - `DELETE /api/v1/projects/{projectKey}/automation/rules/{id}` — soft delete
 *
 * 모든 엔드포인트는 MANAGE_AUTOMATION 가드를 거친다. 인가 순서는 **actor 추출(401) → 권한 판정(403) →
 * 리소스 조회** 순서를 지킨다([[auth-extraction-before-resource-lookup]]) — actor 추출은 이 컨트롤러가,
 * 권한 판정과 리소스 조회는 [AutomationRuleService] 가 담당한다.
 *
 * 트랜잭션 경계는 이 컨트롤러가 아니라 [AutomationRuleService] 가 담당한다(learning #91).
 *
 * @param service 자동화 룰 CRUD 유스케이스 서비스.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectKey}/automation/rules")
class AutomationRuleController(
    private val service: AutomationRuleService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 새 자동화 룰을 생성한다.
     *
     * @param projectKey 룰이 속할 프로젝트 키(경로 변수).
     * @param request 생성 요청 바디.
     * @return 201 Created + 생성된 룰([CreateAutomationRuleResponse], WEBHOOK 이면 원문 토큰 1회 동봉).
     */
    @PostMapping
    fun create(
        @PathVariable projectKey: String,
        @RequestBody request: CreateAutomationRuleRequest,
    ): ResponseEntity<CreateAutomationRuleResponse> {
        val actorId = AutomationActorExtractor.extract()
        val created =
            service.create(
                actorId = actorId,
                projectKey = projectKey,
                name = request.name,
                triggerType = request.triggerType,
                triggerConfig = request.triggerConfig,
            )
        log.info("AutomationRuleController.create actor={} projectKey={} id={}", actorId, projectKey, created.rule.id)
        return ResponseEntity.status(HttpStatus.CREATED).body(CreateAutomationRuleResponse.from(created))
    }

    /**
     * [projectKey] 의 자동화 룰 목록을 반환한다.
     *
     * @param projectKey 조회할 프로젝트 키(경로 변수).
     * @return 200 OK + 룰 목록.
     */
    @GetMapping
    fun list(
        @PathVariable projectKey: String,
    ): ResponseEntity<List<AutomationRuleResponse>> {
        val actorId = AutomationActorExtractor.extract()
        val rules = service.list(actorId, projectKey)
        return ResponseEntity.ok(rules.map(AutomationRuleResponse::from))
    }

    /**
     * [id] 자동화 룰을 단건 조회한다.
     *
     * @param projectKey 룰이 속해야 하는 프로젝트 키(경로 변수, 권한 범위 + 존재 스코프).
     * @param id 조회할 룰 id(경로 변수).
     * @return 200 OK + 룰(토큰 미노출).
     */
    @GetMapping("/{id}")
    fun get(
        @PathVariable projectKey: String,
        @PathVariable id: UUID,
    ): ResponseEntity<AutomationRuleResponse> {
        val actorId = AutomationActorExtractor.extract()
        val rule = service.get(actorId, projectKey, id)
        return ResponseEntity.ok(AutomationRuleResponse.from(rule))
    }

    /**
     * [id] 자동화 룰을 부분 수정한다(name·enabled·triggerConfig, OCC).
     *
     * @param projectKey 룰이 속해야 하는 프로젝트 키(경로 변수).
     * @param id 수정할 룰 id(경로 변수).
     * @param request 부분 수정 요청 바디(version 필수).
     * @return 200 OK + 변경된 룰.
     */
    @PatchMapping("/{id}")
    fun patch(
        @PathVariable projectKey: String,
        @PathVariable id: UUID,
        @RequestBody request: PatchAutomationRuleRequest,
    ): ResponseEntity<AutomationRuleResponse> {
        val actorId = AutomationActorExtractor.extract()
        val updated =
            service.patch(
                actorId = actorId,
                projectKey = projectKey,
                id = id,
                expectedVersion = request.version,
                name = request.name,
                enabled = request.enabled,
                triggerConfig = request.triggerConfig,
            )
        log.info("AutomationRuleController.patch actor={} projectKey={} id={}", actorId, projectKey, id)
        return ResponseEntity.ok(AutomationRuleResponse.from(updated))
    }

    /**
     * [id] 자동화 룰을 소프트 삭제한다.
     *
     * @param projectKey 룰이 속해야 하는 프로젝트 키(경로 변수).
     * @param id 삭제할 룰 id(경로 변수).
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @PathVariable projectKey: String,
        @PathVariable id: UUID,
    ) {
        val actorId = AutomationActorExtractor.extract()
        service.delete(actorId, projectKey, id)
        log.info("AutomationRuleController.delete actor={} projectKey={} id={}", actorId, projectKey, id)
    }
}

/**
 * [SecurityContextHolder] 에서 인증된 사용자의 UUID 를 추출한다(slack `SlackActorExtractor` 동형).
 *
 * [AutomationRuleController] 의 모든 핸들러가 서비스 호출(권한 판정·리소스 조회)보다 **먼저** 호출해
 * 존재 probe 를 차단한다([[auth-extraction-before-resource-lookup]]). BC 격리 — identity-access 의
 * 인증 주체 추출기를 직접 import 할 수 없어 automation BC 가 자체 구현한다.
 */
private object AutomationActorExtractor {
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
 * [AutomationRuleController] 예외를 RFC 7807 [ProblemDetail] 로 변환한다.
 *
 * [assignableTypes] 를 [AutomationRuleController] 로 한정해 다른 컨트롤러(Task 9 웹훅 인바운드 등)를
 * 가로채지 않는다([[domain-exception-http-handler-basepackage-scope]]). [ResponseStatusException] 은
 * 전용 핸들러가 상태를 그대로 전파하고, 분류되지 않은 예외만 [handleInternal] 이 500 으로 매핑한다
 * (401 이 500 으로 변질되지 않게 한다 — [[catch-all-exceptionhandler-swallows-responsestatusexception]]).
 *
 * 에러 코드 prefix 는 `AUTOMATION_` 로 고정한다.
 */
@RestControllerAdvice(assignableTypes = [AutomationRuleController::class])
class AutomationRuleExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    /** MANAGE_AUTOMATION 권한 없음 — 403. 내부 사정(비멤버/미해석 키)은 노출하지 않는 일반 메시지. */
    @ExceptionHandler(AutomationForbiddenException::class)
    fun handleForbidden(
        @Suppress("UnusedParameter") ex: AutomationForbiddenException,
    ): ProblemDetail {
        log.info("AUTOMATION_403 forbidden")
        return problem(
            HttpStatus.FORBIDDEN,
            "automation-rule-forbidden",
            "Forbidden",
            AUTOMATION_ACCESS_DENIED,
            "이 작업을 수행할 권한이 없습니다.",
        )
    }

    /** 룰 없음(또는 다른 프로젝트 소속, 존재 숨김) — 404. */
    @ExceptionHandler(AutomationRuleNotFoundException::class)
    fun handleNotFound(ex: AutomationRuleNotFoundException): ProblemDetail {
        log.info("AUTOMATION_404 rule_not_found ruleId={}", ex.ruleId)
        return problem(
            HttpStatus.NOT_FOUND,
            "automation-rule-not-found",
            "Not Found",
            AUTOMATION_RULE_NOT_FOUND,
            "자동화 룰을 찾을 수 없습니다.",
        )
    }

    /** OCC 버전 충돌 — 409. */
    @ExceptionHandler(AutomationRuleVersionConflictException::class)
    fun handleVersionConflict(ex: AutomationRuleVersionConflictException): ProblemDetail {
        log.info("AUTOMATION_409 version_conflict ruleId={}", ex.ruleId)
        return problem(
            HttpStatus.CONFLICT,
            "automation-rule-version-conflict",
            "Conflict",
            AUTOMATION_RULE_VERSION_CONFLICT,
            "다른 변경이 먼저 반영되었습니다. 최신 정보를 다시 불러온 뒤 시도해 주세요.",
        )
    }

    /** 룰 필드 또는 triggerConfig 형식 위반(도메인 sealed 예외) — 400. */
    @ExceptionHandler(AutomationDomainException::class)
    fun handleDomainInvalid(ex: AutomationDomainException): ProblemDetail {
        log.info("AUTOMATION_400 domain_invalid detail={}", ex.message)
        return problem(
            HttpStatus.BAD_REQUEST,
            "automation-rule-invalid",
            "Bad Request",
            AUTOMATION_RULE_INVALID,
            ex.message ?: "요청 값이 올바르지 않습니다.",
        )
    }

    /** [AutomationActorExtractor] 의 미인증 401 등 명시 상태 전파. */
    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(ex: ResponseStatusException): ProblemDetail {
        val status = HttpStatus.valueOf(ex.statusCode.value())
        log.info("AUTOMATION_{} response_status", status.value())
        return problem(
            status,
            "automation-rule-response-status",
            status.reasonPhrase,
            AUTOMATION_UNAUTHENTICATED,
            "인증이 필요합니다. 세션이 만료되었을 수 있습니다.",
        )
    }

    /** 분류되지 않은 모든 예외 — 500. 스택트레이스는 서버 로그 전용, 응답에는 일반 메시지만. */
    @ExceptionHandler(Exception::class)
    fun handleInternal(ex: Exception): ProblemDetail {
        log.error("AUTOMATION_500 internal_error", ex)
        return problem(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "automation-rule-internal-error",
            "Internal Server Error",
            AUTOMATION_INTERNAL_ERROR,
            "서버 내부 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.",
        )
    }

    // ── private helpers ───────────────────────────────────────────────────────

    @Suppress("LongParameterList")
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

    private companion object {
        const val AUTOMATION_ACCESS_DENIED = "AUTOMATION_ACCESS_DENIED"
        const val AUTOMATION_RULE_NOT_FOUND = "AUTOMATION_RULE_NOT_FOUND"
        const val AUTOMATION_RULE_VERSION_CONFLICT = "AUTOMATION_RULE_VERSION_CONFLICT"
        const val AUTOMATION_RULE_INVALID = "AUTOMATION_RULE_INVALID"
        const val AUTOMATION_UNAUTHENTICATED = "AUTOMATION_UNAUTHENTICATED"
        const val AUTOMATION_INTERNAL_ERROR = "AUTOMATION_INTERNAL_ERROR"
    }
}
