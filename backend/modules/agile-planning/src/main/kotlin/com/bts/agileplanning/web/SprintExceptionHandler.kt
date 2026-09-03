// 스프린트 도메인/입력 예외를 RFC 7807 ProblemDetail HTTP 응답으로 변환하는 핸들러 — agile-planning BC

package com.bts.agileplanning.web

import com.bts.agileplanning.application.SprintAlreadyActiveException
import com.bts.agileplanning.application.SprintBoardNotScrumException
import com.bts.agileplanning.application.SprintDateLockedException
import com.bts.agileplanning.application.SprintDatesRequiredException
import com.bts.agileplanning.application.SprintNotFoundException
import com.bts.agileplanning.domain.InvalidSprintTransitionException
import org.slf4j.LoggerFactory
import org.springframework.dao.CannotAcquireLockException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.time.Instant

/**
 * 스프린트 REST API 의 도메인/입력 예외를 RFC 7807 ProblemDetail 형식으로 변환하는 핸들러.
 *
 * [assignableTypes] 를 [SprintController]·[SprintBurndownController]·[SprintVelocityController] 로
 * 한정하여 다른 컨트롤러의 예외를 잡지 않는다(memory: domain-exception-http-handler-basepackage-scope —
 * 도메인 예외 핸들러 스코프 교훈). [BoardExceptionHandler] 등 형제 핸들러와 동일하게 assignableTypes 로
 * 대상 컨트롤러를 명시 한정하여 확실하게 스코프를 제한한다.
 *
 * catch-all [Exception] 핸들러를 두되, [ResponseStatusException] 은 별도 핸들러로 상태를 전파하여
 * catch-all 이 401/403/404/409/422 를 500 으로 변질시키지 못하게 한다
 * (memory: catch-all-exceptionhandler-swallows-responsestatusexception 교훈).
 *
 * [InvalidSprintTransitionException] 은 [IllegalStateException] 을 상속하므로 명시 핸들러로 409 에 매핑한다.
 *
 * 에러 코드 접두사는 `AGILE_` 로 고정한다 (BTS 에러 코드 규칙).
 *
 * ### 매핑 규칙
 * - [MethodArgumentNotValidException] → 400 + AGILE_VALIDATION_FAILED
 * - [HttpMessageNotReadableException] → 400 + AGILE_VALIDATION_FAILED
 * - [MethodArgumentTypeMismatchException] → 400 + AGILE_VALIDATION_FAILED
 * - [MissingServletRequestParameterException] → 400 + AGILE_VALIDATION_FAILED
 * - [IllegalArgumentException] → 400 + AGILE_VALIDATION_FAILED (도메인 require 위반 — name 공백·기간 역전)
 * - [SprintDateLockedException] → 400 + AGILE_SPRINT_DATE_LOCKED (COMPLETED 스프린트 기간 잠금, FR-BL-02)
 * - [SprintNotFoundException] → 404 + AGILE_SPRINT_NOT_FOUND
 * - [InvalidSprintTransitionException] → 409 + AGILE_CONFLICT
 * - [SprintBoardNotScrumException] → 409 + AGILE_SPRINT_BOARD_NOT_SCRUM (칸반 보드 소속 스프린트 start, 부채 165)
 * - [SprintDatesRequiredException] → 422 + AGILE_SPRINT_DATES_REQUIRED (번다운 기간 미설정, FR-RP-01)
 * - [ResponseStatusException] → 명시 상태 전파(401/403/404/409 등, 일반 메시지)
 * - [Exception] (fallback) → 500 + AGILE_INTERNAL_ERROR
 *
 * TooManyFunctions: 도메인/입력 예외 각각에 @ExceptionHandler 가 필요하므로 함수 수가 임계치를 넘는다.
 * RestControllerAdvice 의 책임(예외 → HTTP 변환)은 분리 불가한 단일 관심사라 클래스 단위로 억제한다.
 */
@Suppress("TooManyFunctions")
@RestControllerAdvice(
    assignableTypes = [SprintController::class, SprintBurndownController::class, SprintVelocityController::class],
)
class SprintExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    // ── 400 VALIDATION_FAILED ─────────────────────────────────────────────────

    /**
     * Bean Validation(@Valid) 실패 — 400.
     *
     * @param ex Spring MVC 가 생성한 검증 실패 예외.
     */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationFailed(ex: MethodArgumentNotValidException): ProblemDetail {
        val fieldErrors =
            ex.bindingResult.fieldErrors.joinToString("; ") { "${it.field}: ${it.defaultMessage}" }
        log.info("AGILE_400 sprint_validation_failed fields='{}'", fieldErrors)
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "agile-validation-failed",
            title = "Validation Failed",
            errorCode = AGILE_VALIDATION_FAILED,
            detail = "요청 값 검증에 실패했습니다.",
        )
    }

    /**
     * 요청 본문 역직렬화 실패 — 400.
     *
     * JSON 형식 오류 또는 타입 불일치 시 발생한다.
     * 보안 — 역직렬화 오류 상세를 응답에 포함하지 않는다. 원인은 로그에만 기록한다.
     *
     * @param ex 역직렬화 실패 예외.
     */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleHttpMessageNotReadable(ex: HttpMessageNotReadableException): ProblemDetail {
        log.info("AGILE_400 sprint_message_not_readable cause='{}'", ex.cause?.message ?: ex.message)
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "agile-validation-failed",
            title = "Validation Failed",
            errorCode = AGILE_VALIDATION_FAILED,
            detail = "요청 본문을 읽을 수 없습니다. JSON 형식 또는 필드 값을 확인해 주세요.",
        )
    }

    /**
     * 경로 변수 또는 요청 파라미터 타입 불일치 — 400.
     *
     * path variable 이 UUID 타입이어야 할 때 올바르지 않은 값이 전달되면 발생한다.
     * 보안 — 파라미터 이름·요청값 등 내부 정보를 응답에 포함하지 않는다.
     *
     * @param ex 타입 불일치 예외.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleMethodArgumentTypeMismatch(ex: MethodArgumentTypeMismatchException): ProblemDetail {
        log.info("AGILE_400 sprint_type_mismatch param='{}'", ex.name)
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "agile-validation-failed",
            title = "Validation Failed",
            errorCode = AGILE_VALIDATION_FAILED,
            detail = "요청 경로 또는 파라미터 형식이 올바르지 않습니다.",
        )
    }

    /**
     * 필수 요청 파라미터 누락 — 400.
     *
     * projectKey 등 required=true 파라미터가 없을 때 발생한다.
     *
     * @param ex 필수 파라미터 누락 예외.
     */
    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun handleMissingServletRequestParameter(ex: MissingServletRequestParameterException): ProblemDetail {
        log.info("AGILE_400 sprint_missing_param name='{}'", ex.parameterName)
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "agile-validation-failed",
            title = "Validation Failed",
            errorCode = AGILE_VALIDATION_FAILED,
            detail = "필수 요청 파라미터가 누락되었습니다.",
        )
    }

    /**
     * 도메인 require 위반(잘못된 입력값) — 400.
     *
     * [Sprint] init 블록의 require(name.isNotBlank()) · require(!startDate.isAfter(endDate)) 가
     * 실패할 때 던지는 [IllegalArgumentException] 을 400 으로 매핑한다.
     * Bean Validation(@NotBlank) 이 무동작인 경우에도 이 핸들러가 400 을 반환해 동작을 보장한다.
     *
     * 보안 — 도메인 내부 메시지를 응답에 노출하지 않고 일반 메시지만 반환한다. 원인은 로그에만 기록한다.
     *
     * @param ex 도메인 require 위반 예외.
     */
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException): ProblemDetail {
        log.info("AGILE_400 sprint_illegal_argument cause='{}'", ex.message)
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "agile-validation-failed",
            title = "Validation Failed",
            errorCode = AGILE_VALIDATION_FAILED,
            detail = "요청 값 검증에 실패했습니다.",
        )
    }

    /**
     * [SprintDateLockedException] — COMPLETED 스프린트의 기간 변경 시도 — 400 (FR-BL-02 FR-2).
     *
     * ### 왜 전용 핸들러가 필요한가
     * 이 예외는 [ResponseStatusException] 을 상속하므로 핸들러가 없으면 아래 상태 전파 핸들러
     * ([handleResponseStatus]) 가 잡아 `BAD_REQUEST -> AGILE_VALIDATION_FAILED` 로 **errorCode 를
     * 덮어쓴다**. 그러면 「입력값이 틀렸다」와 「완료된 스프린트라 기간이 잠겼다」가 한 코드로
     * 뭉뚱그려져 UI 가 사유를 안내하지 못한다. 형제 [SprintAlreadyActiveException] 과 같은 규약이다.
     *
     * 보안 — 거부된 날짜 값과 내부 식별자를 detail 에 노출하지 않는다. 원인은 로그에만 기록한다.
     *
     * @param ex 기간 잠금 예외.
     */
    @ExceptionHandler(SprintDateLockedException::class)
    fun handleSprintDateLocked(ex: SprintDateLockedException): ProblemDetail {
        log.info("AGILE_400 sprint_date_locked reason='{}'", ex.reason)
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "agile-sprint-date-locked",
            title = "Sprint Date Locked",
            errorCode = AGILE_SPRINT_DATE_LOCKED,
            detail = "완료된 스프린트는 기간을 바꿀 수 없습니다. 이름과 목표만 수정할 수 있습니다.",
        )
    }

    // ── 404 SPRINT_NOT_FOUND ─────────────────────────────────────────────────

    /**
     * [SprintNotFoundException] — 스프린트 미존재 또는 soft-deleted — 404.
     *
     * OCC(낙관적 잠금) 충돌 시도 포함.
     * 보안 — 내부 식별자(sprintId)를 응답에 포함하지 않는다.
     *
     * @param ex 스프린트 미존재 예외.
     */
    @ExceptionHandler(SprintNotFoundException::class)
    fun handleSprintNotFound(
        @Suppress("UnusedParameter") ex: SprintNotFoundException,
    ): ProblemDetail {
        log.info("AGILE_404 sprint_not_found")
        return problem(
            status = HttpStatus.NOT_FOUND,
            type = "agile-sprint-not-found",
            title = "Sprint Not Found",
            errorCode = AGILE_SPRINT_NOT_FOUND,
            detail = "스프린트를 찾을 수 없습니다.",
        )
    }

    // ── 409 CONFLICT (도메인 전환 위반) ──────────────────────────────────────

    /**
     * [InvalidSprintTransitionException] — 허용되지 않는 스프린트 상태 전환 — 409.
     *
     * [IllegalStateException] 을 상속하므로 catch-all 에 앞서 명시 등록한다.
     * 보안 — 내부 FSM 상태 세부를 detail 에 노출하지 않는다. 로그에만 기록한다.
     *
     * @param ex 전환 위반 예외.
     */
    @ExceptionHandler(InvalidSprintTransitionException::class)
    fun handleInvalidTransition(ex: InvalidSprintTransitionException): ProblemDetail {
        log.info("AGILE_409 sprint_invalid_transition reason='{}'", ex.message)
        return problem(
            status = HttpStatus.CONFLICT,
            type = "agile-sprint-invalid-transition",
            title = "Invalid Sprint Transition",
            errorCode = AGILE_CONFLICT,
            detail = "현재 스프린트 상태에서는 해당 전환이 허용되지 않습니다.",
        )
    }

    /**
     * [SprintAlreadyActiveException] — 보드에 이미 ACTIVE 스프린트가 있음 — 409 (FR-BD-04 D4).
     *
     * ### 왜 전용 핸들러가 필요한가
     * 이 예외는 [ResponseStatusException] 을 상속하므로 핸들러가 없으면 아래 상태 전파 핸들러
     * ([handleResponseStatus]) 가 잡아 `CONFLICT -> AGILE_CONFLICT` 로 **errorCode 를 덮어쓴다**.
     * 형제 [com.bts.agileplanning.application.SprintCompletedAssignException] 등 409 3형제가 실제로
     * 그렇게 뭉뚱그려져 있다. 클라이언트가 「전환 불가」와 「이미 활성 있음」을 가려야 UI 가
     * 「완료 후 다시 시작」으로 안내할 수 있으므로 코드를 나눈다.
     *
     * 보안 — 충돌한 스프린트의 식별자·이름을 detail 에 노출하지 않는다. 로그에만 기록한다.
     *
     * @param ex 활성 스프린트 중복 예외.
     */
    @ExceptionHandler(SprintAlreadyActiveException::class)
    fun handleSprintAlreadyActive(ex: SprintAlreadyActiveException): ProblemDetail {
        log.info("AGILE_409 sprint_already_active reason='{}'", ex.message)
        return problem(
            status = HttpStatus.CONFLICT,
            type = "agile-sprint-already-active",
            title = "Sprint Already Active",
            errorCode = AGILE_SPRINT_ALREADY_ACTIVE,
            detail = "이 보드에는 이미 시작된 스프린트가 있습니다.",
        )
    }

    // ── 409 SPRINT_BOARD_NOT_SCRUM (칸반 보드 소속 스프린트 시작, 부채 165) ──

    /**
     * [SprintBoardNotScrumException] — 소속 보드가 SCRUM 이 아닌 스프린트의 start — 409.
     *
     * ★ 전용 핸들러가 없으면 이 예외도 [handleResponseStatus] 가 잡아 errorCode 를
     * `AGILE_CONFLICT` 로 덮어쓴다 — 형제 [SprintAlreadyActiveException] 이 겪은 자리이고
     * 그 예외의 KDoc 이 ★ 로 못박아 둔 지점이다. 클라이언트가 「전환이 불가하다」와
     * 「보드 종류가 다르다」를 가려야 UI 가 「스크럼 보드로 옮긴 뒤 시작」을 안내할 수 있다.
     *
     * ### 왜 404 가 아니라 409 인가
     * 스프린트는 **존재한다.** 404 는 거짓말이고 memory
     * `permission-assert-before-existence-makes-403-lie` 와 같은 양식의 오도다 —
     * 「없다」고 답하면 클라이언트는 목록을 다시 받아 사라진 행을 찾다 끝난다.
     * [com.bts.agileplanning.application.SprintApplicationService] 의 `resolveTargetBoard` 가
     * 던지는 404 는 「**요청이 지정한** 보드를 못 찾음」이라 의미가 다르다. 여기서 못 찾는 것은
     * 요청이 아니라 스프린트가 이미 매달려 있는 보드다.
     *
     * 보안 — 보드 식별자·종류를 detail 에 노출하지 않는다. 사유는 로그에만 기록한다.
     *
     * @param ex 소속 보드 종류 불일치 예외.
     */
    @ExceptionHandler(SprintBoardNotScrumException::class)
    fun handleSprintBoardNotScrum(ex: SprintBoardNotScrumException): ProblemDetail {
        log.info("AGILE_409 sprint_board_not_scrum reason='{}'", ex.message)
        return problem(
            status = HttpStatus.CONFLICT,
            type = "agile-sprint-board-not-scrum",
            title = "Sprint Board Not Scrum",
            errorCode = AGILE_SPRINT_BOARD_NOT_SCRUM,
            detail = "스크럼 보드에 속한 스프린트만 시작할 수 있습니다.",
        )
    }

    // ── 422 SPRINT_DATES_REQUIRED (번다운 기간 미설정, FR-RP-01) ─────────────

    /**
     * [SprintDatesRequiredException] — 번다운 계산에 필요한 start_date/end_date 미설정 — 422.
     *
     * 시간축 정박점(start~end)이 없어 Ideal/Actual 라인을 산출할 수 없을 때 던진다(스펙 S3).
     * 보안 — 내부 메시지를 그대로 노출하지 않고 일반화된 detail 을 반환한다. 원인은 로그에만 기록한다.
     *
     * @param ex 기간 미설정 예외.
     */
    @ExceptionHandler(SprintDatesRequiredException::class)
    fun handleSprintDatesRequired(ex: SprintDatesRequiredException): ProblemDetail {
        log.info("AGILE_422 sprint_dates_required cause='{}'", ex.message)
        return problem(
            status = HttpStatus.UNPROCESSABLE_ENTITY,
            type = "agile-sprint-dates-required",
            title = "Sprint Dates Required",
            errorCode = AGILE_SPRINT_DATES_REQUIRED,
            detail = "스프린트 기간(start_date, end_date)이 설정되지 않아 번다운을 계산할 수 없습니다.",
        )
    }

    // ── 503 UNAVAILABLE (advisory lock 예산 초과 · 부채 166 ② · 스펙 S4) ─────

    /**
     * [CannotAcquireLockException] — advisory lock 을 200ms 예산 안에 얻지 못했다 — 503.
     *
     * ### 어디서 오는가 (실측 · 2026-09-03)
     * `AdvisoryLockBudget.acquireXactLockWithBudget` 가 `set_config('lock_timeout','200ms',true)` 를
     * 건 뒤 `pg_advisory_xact_lock` 을 잡는다. 초과하면 PostgreSQL 이 SQLSTATE `55P03`
     * (canceling statement due to lock timeout) 으로 statement 를 취소하고, jOOQ
     * `JooqExceptionTranslator` 가 그것을 **이 타입**으로 옮긴다(cause 는 `PSQLException`).
     * 타입은 추측이 아니라 `AdvisoryLockBudget.kt` KDoc 의 실측 기록이 정본이다.
     *
     * 이 advice 가 관할하는 락은 둘이다 — `sprint-start:<boardId>`(start) 와, 스프린트 **생성**이
     * `SprintApplicationService.resolveTargetBoard` → `BoardApplicationService.ensureScrumBoard`
     * 로 잡는 형제 락 `scrum-board:<projectKey>`. 형제 락은 보드 컨트롤러 경로로도 도달할 수 있어
     * 같은 매핑이 [BoardExceptionHandler] 에도 있다 — 한쪽만 걸면 다른 쪽이 500 이다(스펙 E8).
     *
     * ### ★ 행 락은 이 타입을 낼 수 없다
     * `acquireXactLockWithBudget` 는 락을 잡은 **직후** `lock_timeout` 을 `0`(무제한)으로 되돌린다
     * (N6). 그래서 뒤따르는 `findActiveByBoard`·`updateStatus` 의 **행 락 대기는 예산 밖**이고,
     * 이 503 은 오직 advisory lock 획득 실패에만 대응한다. 「행 락도 503 이 되나?」를 다음 사람이
     * 다시 파헤치지 않도록 여기 적어 둔다 — 답은 **아니오**다.
     *
     * ### ★ 왜 도메인 예외로 감싸지 않았나 (설계 판단)
     * Spring 예외를 web 계층에서 직접 잡는다. 감싸려면 예외가 **발생하는 자리**인
     * `AdvisoryLockBudget`/리포지터리 또는 서비스가 변환해야 하는데, 그러면 이 매핑 하나를 위해
     * 트랜잭션 경계 코드를 건드리게 된다. 이득은 「web 계층이 `org.springframework.dao` 를 모른다」
     * 뿐이고, 손해는 락 획득 경로에 try/catch 를 심는 것이다 — 지금은 값이 안 맞는다.
     *
     * 나중에 감싸고 싶어지면 고칠 곳은 셋이다. ① `application/SprintExceptions.kt` 에
     * `SprintLockTimeoutException`(또는 BC 공용 이름)을 만들고 ② 서비스가
     * `catch (e: CannotAcquireLockException) { throw ... }` 로 변환하고 ③ 두 핸들러의
     * `@ExceptionHandler` 타입을 바꾼다. 응답 계약(503 `AGILE_UNAVAILABLE`)은 그대로 둔다.
     *
     * ### ★ 좁게 잡는다 — 넓히지 마라
     * 상위 `PessimisticLockingFailureException` 이 아니라 이 타입만 잡는다. 형제
     * `DeadlockLoserDataAccessException`(`40P01`) · `CannotSerializeTransactionException`(`40001`) 은
     * **안 잡힌다** — 의도한 좁힘이다. 데드락과 직렬화 실패는 「잠시 후 재시도」와 성격이 다르고
     * 500 으로 남아 경보에 걸리는 편이 낫다.
     *
     * 다만 `@ExceptionHandler` 는 이 advice 가 관할하는 컨트롤러 **전역**이다. 현재 이 BC 에서
     * 이 타입을 내는 곳은 advisory lock 예산뿐이지만(Task 6 실측), 나중에 다른 곳이 같은 타입을
     * 던지면 그것도 503 으로 뭉뚱그려진다. 그때는 여기가 아니라 위 「감싸기」로 옮겨야 한다.
     *
     * 보안 — 락 키에 `boardId`·`projectKey` 가, 예외 메시지에 SQL 이 들어 있다. 둘 다 detail 에
     * 노출하지 않는다. 로그에도 SQL 대신 **어느 엔드포인트에서 났는지**만 남긴다.
     *
     * @param ex 락 획득 실패 예외. 메시지에 SQL·락 키가 들어 있어 응답·로그 어디에도 싣지 않는다.
     * @param request 실패 지점을 식별하기 위한 요청 정보. `uri=…` 만 로그에 남긴다.
     */
    @ExceptionHandler(CannotAcquireLockException::class)
    fun handleLockTimeout(
        @Suppress("UnusedParameter") ex: CannotAcquireLockException,
        request: WebRequest,
    ): ProblemDetail {
        log.warn("AGILE_503 advisory_lock_timeout at='{}'", request.getDescription(false))
        return problem(
            status = HttpStatus.SERVICE_UNAVAILABLE,
            type = "agile-unavailable",
            title = "Service Unavailable",
            errorCode = AGILE_UNAVAILABLE,
            detail = "다른 작업이 처리 중이라 잠시 후 다시 시도해 주세요.",
        )
    }

    // ── ResponseStatusException 상태 전파 (catch-all 변질 차단) ─────────────

    /**
     * [ResponseStatusException] — 컨트롤러/서비스가 명시한 HTTP 상태를 그대로 전파한다.
     *
     * 서비스가 던지는 403(권한 거부), 404(이슈 미가시), 409(COMPLETED 스프린트 할당) 등이
     * catch-all 에 가로채여 500 으로 변질되는 것을 차단한다.
     * 보안 — detail 에 내부 사유를 노출하지 않는다. 상태 코드 기반 일반 메시지를 사용한다.
     *
     * @param ex 명시 상태 코드를 보유한 예외.
     */
    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(ex: ResponseStatusException): ProblemDetail {
        val status = HttpStatus.valueOf(ex.statusCode.value())
        log.info("AGILE_{} sprint_response_status reason='{}'", status.value(), ex.reason)
        val (errorCode, detail) =
            when (status) {
                HttpStatus.UNAUTHORIZED ->
                    AGILE_UNAUTHENTICATED to "인증이 필요합니다. 세션이 만료되었을 수 있습니다."
                HttpStatus.FORBIDDEN ->
                    AGILE_ACCESS_DENIED to "이 작업을 수행할 권한이 없습니다."
                HttpStatus.NOT_FOUND ->
                    AGILE_SPRINT_NOT_FOUND to "스프린트 또는 이슈를 찾을 수 없습니다."
                HttpStatus.CONFLICT ->
                    AGILE_CONFLICT to "다른 변경과 충돌이 발생했습니다. 다시 시도해 주세요."
                HttpStatus.BAD_REQUEST ->
                    AGILE_VALIDATION_FAILED to "요청 값이 올바르지 않습니다."
                else ->
                    AGILE_INTERNAL_ERROR to "요청을 처리할 수 없습니다."
            }
        return problem(
            status = status,
            type = "agile-sprint-response-status",
            title = status.reasonPhrase,
            errorCode = errorCode,
            detail = detail,
        )
    }

    // ── 500 INTERNAL_ERROR (fallback) ─────────────────────────────────────────

    /**
     * 분류되지 않은 모든 예외 — 500.
     *
     * @param ex 처리되지 않은 예외.
     */
    @ExceptionHandler(Exception::class)
    fun handleInternalError(ex: Exception): ProblemDetail {
        log.error("AGILE_500 sprint_internal_error", ex)
        return problem(
            status = HttpStatus.INTERNAL_SERVER_ERROR,
            type = "agile-internal-error",
            title = "Internal Server Error",
            errorCode = AGILE_INTERNAL_ERROR,
            detail = "서버 내부 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.",
        )
    }

    // ── private helper ────────────────────────────────────────────────────────

    /**
     * RFC 7807 [ProblemDetail] 인스턴스를 생성하는 헬퍼.
     *
     * 표준 필드 외에 커스텀 errorCode 와 timestamp 를 추가한다.
     *
     * @param status HTTP 응답 상태 코드.
     * @param type `https://bts.example.com/problems/` 뒤에 붙는 type suffix.
     * @param title 사람이 읽을 수 있는 문제 유형 요약.
     * @param errorCode BTS 에러 코드 상수 (AGILE_ 접두사).
     * @param detail 이 특정 발생에 대한 상세 설명.
     * @return 완성된 [ProblemDetail] 인스턴스.
     */
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

    /** agile-planning BC 스프린트 에러 코드 상수. 모두 AGILE_ 접두사를 사용한다. */
    private companion object {
        const val AGILE_VALIDATION_FAILED = "AGILE_VALIDATION_FAILED"
        const val AGILE_UNAUTHENTICATED = "AGILE_UNAUTHENTICATED"
        const val AGILE_ACCESS_DENIED = "AGILE_ACCESS_DENIED"
        const val AGILE_SPRINT_NOT_FOUND = "AGILE_SPRINT_NOT_FOUND"
        const val AGILE_CONFLICT = "AGILE_CONFLICT"
        const val AGILE_SPRINT_ALREADY_ACTIVE = "AGILE_SPRINT_ALREADY_ACTIVE"
        const val AGILE_SPRINT_BOARD_NOT_SCRUM = "AGILE_SPRINT_BOARD_NOT_SCRUM"
        const val AGILE_SPRINT_DATE_LOCKED = "AGILE_SPRINT_DATE_LOCKED"
        const val AGILE_SPRINT_DATES_REQUIRED = "AGILE_SPRINT_DATES_REQUIRED"
        const val AGILE_UNAVAILABLE = "AGILE_UNAVAILABLE"
        const val AGILE_INTERNAL_ERROR = "AGILE_INTERNAL_ERROR"
    }
}
