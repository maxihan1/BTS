// Git 웹훅 등록 REST 컨트롤러 — 프로젝트 스코프 + MANAGE_AUTOMATION 가드(actor 추출 401 → 권한 403 → 조회) (FR-AT-07 PR-C Task 11)

package com.bts.automation.adapter.web

import com.bts.automation.adapter.web.dto.CreateGitWebhookRequest
import com.bts.automation.adapter.web.dto.CreateGitWebhookResponse
import com.bts.automation.adapter.web.dto.GitWebhookSummaryResponse
import com.bts.automation.application.AutomationForbiddenException
import com.bts.automation.application.GitWebhookNotFoundException
import com.bts.automation.application.GitWebhookRegistrationService
import com.bts.automation.application.GitWebhookSecretInvalidException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.time.Instant
import java.util.UUID

/**
 * Git 인바운드 웹훅 등록 REST 컨트롤러 (FR-AT-07 PR-C Task 11).
 *
 * spec §5-2 endpoint 3종.
 * - `POST   /api/v1/projects/{projectKey}/automation/git-webhooks`      — 등록(201, 원문 토큰 1회 동봉)
 * - `GET    /api/v1/projects/{projectKey}/automation/git-webhooks`      — 목록(**token·secret 미포함**)
 * - `DELETE /api/v1/projects/{projectKey}/automation/git-webhooks/{id}` — 소프트 삭제(204)
 *
 * ## 인증이 필요한 관리 API 다 — permitAll 대상이 아니다
 * 같은 FR 의 **인바운드** 경로(`POST /api/v1/webhooks/git/{token}`)는 외부 Git 서버가 호출하므로
 * permitAll + 서명 검증이지만, 이 등록 API 는 사람이 호출하는 프로젝트 관리 API 라 일반 규칙대로
 * 필터 체인 `authenticated()` 를 그대로 적용받는다(중앙 `SecurityConfig` 등록은 Task 12 소유).
 *
 * ## 가드 (`AutomationRuleController` 동형 — 겉모양만 베끼고 가드를 빠뜨리지 않도록 전수 대조했다)
 * 모든 엔드포인트가 **actor 추출(401) → 권한 판정(403) → 리소스 조회** 순서를 지킨다
 * ([[auth-extraction-before-resource-lookup]]). actor 추출은 이 컨트롤러가, 권한 판정과 리소스 조회는
 * [GitWebhookRegistrationService] 가 담당한다. 순서를 뒤집으면 권한 없는 사용자가 403/404(존재 여부)
 * 또는 403/400(본문 검증) 차이로 내부 상태를 알아낸다 — 두 경우 모두 테스트로 실증한다.
 *
 * 트랜잭션 경계는 이 컨트롤러가 아니라 [GitWebhookRegistrationService] 가 담당한다.
 *
 * ## 자체 actor 추출기 + 스코프 예외 핸들러
 * [AutomationRuleController] 의 actor 추출기는 `private`(파일 경계)이고 예외 핸들러도 `assignableTypes`
 * 로 그 컨트롤러에 한정돼 있어 재사용할 수 없다. `AutomationExecutionController` 와 같은 이유로 이
 * 파일이 자체 [GitWebhookActorExtractor] + [GitWebhookRegistrationExceptionHandler] 를 둔다(동형 복제).
 *
 * @param service Git 웹훅 등록 유스케이스 서비스.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectKey}/automation/git-webhooks")
class GitWebhookRegistrationController(
    private val service: GitWebhookRegistrationService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Git 웹훅을 등록하고 원문 URL 토큰을 1회 발급한다.
     *
     * @param projectKey 웹훅이 속할 프로젝트 키(경로 변수).
     * @param request 등록 요청 바디(provider + secret).
     * @return 201 Created + [CreateGitWebhookResponse](원문 토큰 1회 동봉 — 이후 재조회 불가).
     */
    @PostMapping
    fun create(
        @PathVariable projectKey: String,
        @RequestBody request: CreateGitWebhookRequest,
    ): ResponseEntity<CreateGitWebhookResponse> {
        val actorId = GitWebhookActorExtractor.extract()
        val registered = service.register(actorId, projectKey, request.provider, request.secret)
        // 원문 토큰은 로그에 남기지 않는다(DEVELOPMENT.md §1.1-2) — 식별자만 남긴다.
        log.info(
            "GitWebhookRegistrationController.create actor={} projectKey={} id={}",
            actorId,
            projectKey,
            registered.webhook.id,
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(CreateGitWebhookResponse.from(registered))
    }

    /**
     * [projectKey] 의 활성 웹훅 목록을 반환한다(token·secret 미포함).
     *
     * @param projectKey 조회할 프로젝트 키(경로 변수).
     * @return 200 OK + [GitWebhookSummaryResponse] 목록.
     */
    @GetMapping
    fun list(
        @PathVariable projectKey: String,
    ): ResponseEntity<List<GitWebhookSummaryResponse>> {
        val actorId = GitWebhookActorExtractor.extract()
        val webhooks = service.list(actorId, projectKey)
        log.info(
            "GitWebhookRegistrationController.list actor={} projectKey={} count={}",
            actorId,
            projectKey,
            webhooks.size,
        )
        return ResponseEntity.ok(webhooks.map(GitWebhookSummaryResponse::from))
    }

    /**
     * [id] 웹훅을 소프트 삭제한다.
     *
     * @param projectKey 웹훅이 속해야 하는 프로젝트 키(경로 변수).
     * @param id 삭제할 웹훅 id(경로 변수).
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @PathVariable projectKey: String,
        @PathVariable id: UUID,
    ) {
        val actorId = GitWebhookActorExtractor.extract()
        service.delete(actorId, projectKey, id)
        log.info("GitWebhookRegistrationController.delete actor={} projectKey={} id={}", actorId, projectKey, id)
    }
}

/**
 * [SecurityContextHolder] 에서 인증된 사용자의 UUID 를 추출한다(`AutomationActorExtractor` 동형 복제 —
 * [GitWebhookRegistrationController] 클래스 KDoc §자체 actor 추출기 참고). 모든 핸들러가 서비스
 * 호출(권한 판정·리소스 조회)보다 **먼저** 호출해 존재 probe 를 차단한다
 * ([[auth-extraction-before-resource-lookup]]).
 */
private object GitWebhookActorExtractor {
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
 * [GitWebhookRegistrationController] 예외를 RFC 7807 [ProblemDetail] 로 변환한다.
 *
 * [assignableTypes] 를 이 컨트롤러로 한정해 다른 컨트롤러를 가로채지 않는다
 * ([[domain-exception-http-handler-basepackage-scope]]). [ResponseStatusException] 은 전용 핸들러가
 * 상태를 그대로 전파하고, 분류되지 않은 예외만 [handleInternal] 이 500 으로 매핑한다(401 이 500 으로
 * 변질되지 않게 한다 — [[catch-all-exceptionhandler-swallows-responsestatusexception]]).
 *
 * ## 응답 detail 은 전부 고정 문자열 — 예외 message 를 그대로 싣지 않는다
 * 예외 message 가 응답 detail 로 새면 내부 사정(존재 여부·정책)이 노출된다
 * ([[fr-pm-04-guard-exception-message-http-leak]]). message 는 서버 로그 전용이다.
 */
@RestControllerAdvice(assignableTypes = [GitWebhookRegistrationController::class])
class GitWebhookRegistrationExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    /** MANAGE_AUTOMATION 권한 없음 — 403(일반 메시지). */
    @ExceptionHandler(AutomationForbiddenException::class)
    fun handleForbidden(
        @Suppress("UnusedParameter") ex: AutomationForbiddenException,
    ): ProblemDetail {
        log.info("AUTOMATION_403 git_webhook_forbidden")
        return problem(
            HttpStatus.FORBIDDEN,
            "automation-git-webhook-forbidden",
            "Forbidden",
            GitWebhookErrorCodes.ACCESS_DENIED,
            "이 작업을 수행할 권한이 없습니다.",
        )
    }

    /** 웹훅 없음(또는 다른 프로젝트 소속이라 존재를 숨김) — 404. */
    @ExceptionHandler(GitWebhookNotFoundException::class)
    fun handleNotFound(ex: GitWebhookNotFoundException): ProblemDetail {
        log.info("AUTOMATION_404 git_webhook_not_found webhookId={}", ex.id)
        return problem(
            HttpStatus.NOT_FOUND,
            "automation-git-webhook-not-found",
            "Not Found",
            GitWebhookErrorCodes.NOT_FOUND,
            "Git 웹훅을 찾을 수 없습니다.",
        )
    }

    /**
     * secret 이 서명 검증 방어선으로 쓸 수 없는 값 — 400.
     *
     * detail 은 **정책 문구 고정값**이다. 요청자가 방금 보낸 값이라도 되돌려 싣지 않는다(응답 로그·프록시
     * 캐시로 secret 이 새는 표면을 만들지 않는다). 구체적 위반 사유는 [ex] message 로 서버 로그에만 남는다.
     */
    @ExceptionHandler(GitWebhookSecretInvalidException::class)
    fun handleSecretInvalid(ex: GitWebhookSecretInvalidException): ProblemDetail {
        log.info("AUTOMATION_400 git_webhook_secret_invalid reason={}", ex.message)
        return problem(
            HttpStatus.BAD_REQUEST,
            "automation-git-webhook-secret-invalid",
            "Bad Request",
            GitWebhookErrorCodes.SECRET_INVALID,
            "secret 은 공백이 아닌 16자 이상 4096자 이하 문자열이어야 합니다.",
        )
    }

    /** [GitWebhookActorExtractor] 의 미인증 401 등 명시 상태 전파. */
    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(ex: ResponseStatusException): ProblemDetail {
        val status = HttpStatus.valueOf(ex.statusCode.value())
        log.info("AUTOMATION_{} git_webhook_response_status", status.value())
        return problem(
            status,
            "automation-git-webhook-response-status",
            status.reasonPhrase,
            GitWebhookErrorCodes.UNAUTHENTICATED,
            "인증이 필요합니다. 세션이 만료되었을 수 있습니다.",
        )
    }

    /** 경로 변수 타입 불일치(`id` 가 UUID 형식이 아님) — 400. */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(ex: MethodArgumentTypeMismatchException): ProblemDetail {
        log.info("AUTOMATION_400 git_webhook_type_mismatch param='{}'", ex.name)
        return problem(
            HttpStatus.BAD_REQUEST,
            "automation-git-webhook-invalid",
            "Bad Request",
            GitWebhookErrorCodes.MALFORMED_REQUEST,
            "요청 파라미터 값이 올바르지 않습니다.",
        )
    }

    /** 요청 바디가 유효한 JSON 이 아니거나 provider 가 화이트리스트 밖 — 400. */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleMalformedRequest(
        @Suppress("UnusedParameter") ex: HttpMessageNotReadableException,
    ): ProblemDetail {
        // 원본 message 에는 요청 바디 조각(=secret)이 섞일 수 있어 로그에도 남기지 않는다.
        log.info("AUTOMATION_400 git_webhook_malformed_request")
        return problem(
            HttpStatus.BAD_REQUEST,
            "automation-git-webhook-invalid",
            "Bad Request",
            GitWebhookErrorCodes.MALFORMED_REQUEST,
            "요청 본문이 유효하지 않습니다.",
        )
    }

    /**
     * 분류되지 않은 모든 예외 — 500. 스택트레이스는 서버 로그 전용, 응답에는 일반 메시지만.
     *
     * 암호화 키 미설정([com.bts.automation.AutomationEncryptionConfig])은 부팅이 아니라 **여기**로
     * 떨어진다(`IllegalStateException`) — 배포 후 스모크 점검을 health 가 아니라 실제 등록 호출로 해야
     * 하는 이유다([[use-time-validated-env-passes-boot-fails-on-use]]).
     */
    @ExceptionHandler(Exception::class)
    fun handleInternal(ex: Exception): ProblemDetail {
        log.error("AUTOMATION_500 git_webhook_internal_error", ex)
        return problem(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "automation-git-webhook-internal-error",
            "Internal Server Error",
            GitWebhookErrorCodes.INTERNAL_ERROR,
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

/** [GitWebhookRegistrationController] 전용 에러 코드 상수(`AUTOMATION_` prefix 고정 — 모듈 공통 관례). */
private object GitWebhookErrorCodes {
    const val ACCESS_DENIED = "AUTOMATION_ACCESS_DENIED"
    const val NOT_FOUND = "AUTOMATION_GIT_WEBHOOK_NOT_FOUND"
    const val SECRET_INVALID = "AUTOMATION_GIT_WEBHOOK_SECRET_INVALID"
    const val MALFORMED_REQUEST = "AUTOMATION_MALFORMED_REQUEST"
    const val UNAUTHENTICATED = "AUTOMATION_UNAUTHENTICATED"
    const val INTERNAL_ERROR = "AUTOMATION_INTERNAL_ERROR"
}
