// 자동화 룰 CRUD REST 컨트롤러 — 프로젝트 스코프 + MANAGE_AUTOMATION 가드(actor 추출 401 → 권한 403 → 조회) (FR-AT-01 Task 6)

package com.bts.automation.adapter.web

import com.bts.automation.adapter.web.dto.ActionRequest
import com.bts.automation.adapter.web.dto.AutomationRuleResponse
import com.bts.automation.adapter.web.dto.CreateAutomationRuleRequest
import com.bts.automation.adapter.web.dto.CreateAutomationRuleResponse
import com.bts.automation.adapter.web.dto.PatchAutomationRuleRequest
import com.bts.automation.application.AutomationActionInput
import com.bts.automation.application.AutomationForbiddenException
import com.bts.automation.application.AutomationRuleNotFoundException
import com.bts.automation.application.AutomationRuleService
import com.bts.automation.application.AutomationRuleVersionConflictException
import com.bts.automation.domain.AutomationDomainException
import com.bts.automation.domain.InvalidConditionExpressionException
import com.bts.automation.gitops.AutomationYamlCodec
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
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
 * - `PATCH  /api/v1/projects/{projectKey}/automation/rules/{id}` — 부분수정(name·enabled·triggerConfig·
 *   actions·actorUserId·condition, OCC)
 * - `DELETE /api/v1/projects/{projectKey}/automation/rules/{id}` — soft delete
 * - `GET    /api/v1/projects/{projectKey}/automation/rules/export` — 전 규칙(활성+비활성)을 GitOps YAML로
 *   내보내기(FR-AT-06 Task 3, webhook 토큰/해시 미노출)
 *
 * `condition`(조건 게이트 표현식, FR-AT-03)은 신규 엔드포인트 없이 생성/수정 payload 필드로만
 * 확장된다(spec FR-AT-03-7 — "신규 엔드포인트 없음").
 *
 * 모든 엔드포인트는 MANAGE_AUTOMATION 가드를 거친다. 인가 순서는 **actor 추출(401) → 권한 판정(403) →
 * 리소스 조회** 순서를 지킨다([[auth-extraction-before-resource-lookup]]) — actor 추출은 이 컨트롤러가,
 * 권한 판정과 리소스 조회는 [AutomationRuleService] 가 담당한다.
 *
 * 트랜잭션 경계는 이 컨트롤러가 아니라 [AutomationRuleService] 가 담당한다(learning #91).
 *
 * ## 규칙 충돌 lint 는 저장 트랜잭션 커밋 후 이 컨트롤러가 별도로 호출한다 (FR-AT-04 코드리뷰 BLOCKER 수정)
 * [create]/[patch] 는 [AutomationRuleService.create]/[AutomationRuleService.patch]([@Transactional])가
 * 정상 반환(=커밋 완료)한 **뒤에** [AutomationRuleService.analyzeProjectConflicts](`@Transactional` 아님)
 * 를 별도로 호출해 응답에 실을 규칙 충돌 목록을 얻는다. 두 호출 모두 이 컨트롤러가 주입받은
 * [service] 빈을 통하므로 Spring 프록시를 정상적으로 거친다 — 같은 클래스 내부 self-invocation
 * ([[transaction-self-invocation-requires-new]])이 아니다. 왜 이렇게 분리했는지는
 * [AutomationRuleService] 클래스 KDoc §규칙 충돌 lint 통합 참고(참여 트랜잭션 read 예외가 저장을
 * 오염시키던 BLOCKER 수정).
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
     * @return 201 Created + 생성된 룰([CreateAutomationRuleResponse], WEBHOOK 이면 원문 토큰 1회 동봉,
     *   `conflicts` 는 저장 커밋 후 별도 호출한 [AutomationRuleService.analyzeProjectConflicts] 결과).
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
                actorUserId = request.actorUserId,
                actions = request.actions.map(ActionRequest::toApplicationInput),
                condition = request.condition,
            )
        // 저장 트랜잭션이 커밋된 후 별도로 lint 를 호출한다(클래스 KDoc §규칙 충돌 lint 통합 참고,
        // 코드리뷰 BLOCKER 수정).
        val conflicts = service.analyzeProjectConflicts(projectKey)
        log.info("AutomationRuleController.create actor={} projectKey={} id={}", actorId, projectKey, created.rule.id)
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(CreateAutomationRuleResponse.from(created.copy(conflicts = conflicts)))
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
     * [projectKey] 의 자동화 룰 전체(활성+비활성, 소프트삭제 제외)를 GitOps YAML로 내보낸다(FR-AT-06 Task 3).
     *
     * [projectKey] 는 응답의 `Content-Disposition` 파일명에 그대로 삽입되므로 [validateProjectKeyForExport]
     * 로 화이트리스트 검증한다(헤더 인젝션 방어, search-export-import `ExportController` 선례 동형).
     *
     * @param projectKey 내보낼 프로젝트 키(경로 변수).
     * @return 200 OK + `application/yaml;charset=UTF-8` + `Content-Disposition: attachment;
     *   filename="automation-rules-{projectKey}.yaml"` + YAML 본문(webhook 토큰/해시/OCC version 미포함).
     * @throws AutomationForbiddenException [projectKey] 에 MANAGE_AUTOMATION 권한이 없을 때.
     * @throws AutomationProjectKeyInvalidException [projectKey] 가 화이트리스트를 벗어났을 때.
     */
    @GetMapping("/export")
    fun export(
        @PathVariable projectKey: String,
    ): ResponseEntity<String> {
        val actorId = AutomationActorExtractor.extract()
        validateProjectKeyForExport(projectKey)
        val rules = service.exportRules(actorId, projectKey)
        val yaml = AutomationYamlCodec.toYaml(projectKey, rules)
        log.info("AutomationRuleController.export actor={} projectKey={} count={}", actorId, projectKey, rules.size)
        return ResponseEntity
            .ok()
            .contentType(MediaType.parseMediaType(MEDIA_TYPE_YAML))
            .header(HttpHeaders.CONTENT_DISPOSITION, buildExportContentDisposition(projectKey))
            .body(yaml)
    }

    /**
     * [id] 자동화 룰을 부분 수정한다(name·enabled·triggerConfig·actions·actorUserId·condition, OCC).
     *
     * @param projectKey 룰이 속해야 하는 프로젝트 키(경로 변수).
     * @param id 수정할 룰 id(경로 변수).
     * @param request 부분 수정 요청 바디(version 필수).
     * @return 200 OK + 변경된 룰(`conflicts` 는 저장(또는 no-op) 커밋 후 별도 호출한
     *   [AutomationRuleService.analyzeProjectConflicts] 결과).
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
                actions = request.actions?.map(ActionRequest::toApplicationInput),
                actorUserId = request.actorUserId,
                condition = request.condition,
            )
        // 저장(또는 no-op) 트랜잭션이 커밋된 후 별도로 lint 를 호출한다(create 와 동일 사유).
        val conflicts = service.analyzeProjectConflicts(projectKey)
        log.info("AutomationRuleController.patch actor={} projectKey={} id={}", actorId, projectKey, id)
        return ResponseEntity.ok(AutomationRuleResponse.from(updated.copy(conflicts = conflicts)))
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
 * [ActionRequest](웹 DTO) → [AutomationActionInput](application 커맨드) 1:1 매핑 (FR-AT-02 Task 11).
 *
 * 필드 그대로 옮기기만 하고 검증/도메인 변환은 하지 않는다 — 실제 형식 검증·[com.bts.automation.domain.Action]
 * 매핑은 [AutomationRuleService] 책임이다([AutomationRuleService] 클래스 KDoc §액션/actor 매핑 참고).
 */
private fun ActionRequest.toApplicationInput(): AutomationActionInput {
    return AutomationActionInput(type = type, config = config)
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
 * [AutomationRuleController.export] 가 응답하는 YAML 본문의 미디어 타입(spec API 인터페이스 표 —
 * `application/yaml`). 규칙 이름 등에 한글이 담길 수 있어 `charset=UTF-8` 을 명시한다 — 명시하지 않으면
 * [org.springframework.http.converter.StringHttpMessageConverter] 가 기본 charset(ISO-8859-1)으로 바이트를
 * 쓰고, 클라이언트가 응답 헤더로 UTF-8 여부를 판단할 근거가 사라진다(JSON 컨버터가 항상
 * `charset=UTF-8` 을 명시하는 선례와 동형).
 */
private const val MEDIA_TYPE_YAML = "application/yaml;charset=UTF-8"

/**
 * `Content-Disposition` 파일명에 삽입 가능한 projectKey 화이트리스트(영문자·숫자·하이픈·언더스코어) —
 * CRLF 등 헤더 인젝션 문자를 원천 차단한다(search-export-import `ExportController.PROJECT_KEY_PATTERN`
 * 선례 동형, 언더스코어 허용은 plan Task 3 GREEN 명세).
 */
private val EXPORT_PROJECT_KEY_PATTERN = Regex("^[A-Za-z0-9_-]+$")

/**
 * [AutomationRuleController.export] 의 [projectKey] 가 [EXPORT_PROJECT_KEY_PATTERN] 을 벗어나지 않는지
 * 검증한다 — `Content-Disposition` 헤더 인젝션 방어(search-export-import `ExportController` 선례 동형).
 *
 * @throws AutomationProjectKeyInvalidException [projectKey] 가 화이트리스트를 벗어났을 때.
 */
private fun validateProjectKeyForExport(projectKey: String) {
    if (!EXPORT_PROJECT_KEY_PATTERN.matches(projectKey)) {
        throw AutomationProjectKeyInvalidException("projectKey는 영문자·숫자·하이픈·언더스코어만 허용됩니다.")
    }
}

/**
 * [AutomationRuleController.export] 의 `Content-Disposition` 헤더 값을 조립한다
 * (search-export-import `ExportController.buildContentDisposition` 선례 동형).
 *
 * [projectKey] 는 호출 시점에 이미 [validateProjectKeyForExport] 로 검증되어 있다고 전제한다 — 이 함수
 * 자체는 검증을 반복하지 않는다.
 *
 * @param projectKey [validateProjectKeyForExport] 를 통과한 화이트리스트 프로젝트 키.
 * @return `attachment; filename="automation-rules-{projectKey}.yaml"` 헤더 값.
 */
private fun buildExportContentDisposition(projectKey: String): String {
    return "attachment; filename=\"automation-rules-$projectKey.yaml\""
}

/**
 * [AutomationRuleController.export] 의 [projectKey] 가 [EXPORT_PROJECT_KEY_PATTERN] 화이트리스트를
 * 벗어났음을 나타낸다 — `Content-Disposition` 헤더 인젝션 방어(400).
 *
 * `domain` 패키지 밖이라 `sealed class AutomationDomainException` 의 서브타입으로 선언할 수 없다
 * ([com.bts.automation.gitops.AutomationYamlInvalidException] 선례 동형, Kotlin sealed 서브클래스는
 * 동일 패키지 제약).
 *
 * @param message 위반 내용을 설명하는 일반 메시지.
 */
class AutomationProjectKeyInvalidException(message: String) : RuntimeException(message)

/**
 * [AutomationRuleController] 예외를 RFC 7807 [ProblemDetail] 로 변환한다.
 *
 * [assignableTypes] 를 [AutomationRuleController] 로 한정해 다른 컨트롤러(Task 9 웹훅 인바운드 등)를
 * 가로채지 않는다([[domain-exception-http-handler-basepackage-scope]]). [ResponseStatusException] 은
 * 전용 핸들러가 상태를 그대로 전파하고, 분류되지 않은 예외만 [handleInternal] 이 500 으로 매핑한다
 * (401 이 500 으로 변질되지 않게 한다 — [[catch-all-exceptionhandler-swallows-responsestatusexception]]).
 *
 * 에러 코드 prefix 는 `AUTOMATION_` 로 고정한다 — 단 [handleInvalidCondition] 의
 * `INVALID_CONDITION_EXPRESSION` 은 spec(FR-AT-03-6·API 인터페이스 표)이 3회 명시한 리터럴 와이어 코드를
 * 그대로 따른 의도적 예외다(게이트1 통과 스펙 문구, prefix 관례보다 스펙 리터럴 우선).
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

    /**
     * 조건 표현식 형식/화이트리스트/크기 위반(FR-AT-03) — 400.
     *
     * [InvalidConditionExpressionException] 도 [AutomationDomainException] 의 하위 타입이라 아래
     * [handleDomainInvalid] 에도 매칭되지만, spec(FR-AT-03-6·API 표)이 명시한 전용 코드
     * `INVALID_CONDITION_EXPRESSION` 을 그대로 노출하기 위해 더 구체적인 타입의 핸들러를 별도로 둔다
     * (Spring 이 예외 타입 계층에서 가장 구체적인 핸들러를 우선 매칭).
     */
    @ExceptionHandler(InvalidConditionExpressionException::class)
    fun handleInvalidCondition(ex: InvalidConditionExpressionException): ProblemDetail {
        log.info("AUTOMATION_400 invalid_condition_expression detail={}", ex.message)
        return problem(
            HttpStatus.BAD_REQUEST,
            "automation-rule-condition-invalid",
            "Bad Request",
            INVALID_CONDITION_EXPRESSION,
            ex.message ?: "조건 표현식이 올바르지 않습니다.",
        )
    }

    /**
     * export 의 projectKey 가 [EXPORT_PROJECT_KEY_PATTERN] 화이트리스트를 벗어남(`Content-Disposition`
     * 헤더 인젝션 방어, FR-AT-06 Task 3) — 400. [AutomationDomainException] 서브타입이 아니라(도메인
     * sealed 클래스의 동일 패키지 제약, [AutomationProjectKeyInvalidException] KDoc 참고) 별도 핸들러가
     * 필요하다.
     */
    @ExceptionHandler(AutomationProjectKeyInvalidException::class)
    fun handleProjectKeyInvalid(ex: AutomationProjectKeyInvalidException): ProblemDetail {
        log.info("AUTOMATION_400 project_key_invalid")
        return problem(
            HttpStatus.BAD_REQUEST,
            "automation-rule-project-key-invalid",
            "Bad Request",
            AUTOMATION_RULE_INVALID,
            ex.message ?: "projectKey 형식이 올바르지 않습니다.",
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

    /**
     * 요청 바디가 유효한 JSON 이 아니거나 필수 필드(예: PATCH `version`)가 누락됨 — 400.
     *
     * catch-all 보다 구체적인 예외라 Spring 이 이 핸들러를 우선 매칭한다. 클라이언트 입력 오류를
     * 500(서버 오류)으로 변질시키지 않는다.
     */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleMalformedRequest(
        @Suppress("UnusedParameter") ex: HttpMessageNotReadableException,
    ): ProblemDetail {
        log.info("AUTOMATION_400 malformed_request")
        return problem(
            HttpStatus.BAD_REQUEST,
            "automation-rule-malformed-request",
            "Bad Request",
            AUTOMATION_MALFORMED_REQUEST,
            "요청 본문이 유효하지 않습니다. 필수 필드와 JSON 형식을 확인해 주세요.",
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
        const val INVALID_CONDITION_EXPRESSION = "INVALID_CONDITION_EXPRESSION"
        const val AUTOMATION_ACCESS_DENIED = "AUTOMATION_ACCESS_DENIED"
        const val AUTOMATION_RULE_NOT_FOUND = "AUTOMATION_RULE_NOT_FOUND"
        const val AUTOMATION_RULE_VERSION_CONFLICT = "AUTOMATION_RULE_VERSION_CONFLICT"
        const val AUTOMATION_RULE_INVALID = "AUTOMATION_RULE_INVALID"
        const val AUTOMATION_MALFORMED_REQUEST = "AUTOMATION_MALFORMED_REQUEST"
        const val AUTOMATION_UNAUTHENTICATED = "AUTOMATION_UNAUTHENTICATED"
        const val AUTOMATION_INTERNAL_ERROR = "AUTOMATION_INTERNAL_ERROR"
    }
}
