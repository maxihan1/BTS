// agile-planning BC 도메인/권한 예외를 RFC 7807 ProblemDetail HTTP 응답으로 변환하는 핸들러

package com.bts.agileplanning.web

import com.bts.agileplanning.application.QuickFilterEmptyQueryException
import com.bts.agileplanning.application.QuickFilterLimitExceededException
import com.bts.agileplanning.application.QuickFilterNameConflictException
import com.bts.agileplanning.application.QuickFilterNotFoundException
import com.bts.agileplanning.domain.BoardNameInvalidException
import com.bts.agileplanning.domain.BoardTypeInvalidException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.time.Instant

/**
 * 보드 접근 권한 거부 예외 — 403.
 *
 * 컨트롤러가 [com.bts.shared.permission.IssuePermissionResolver] 판정 결과 false 일 때 던진다.
 * 보안 — message 에는 내부 사정(보드 존재 여부·프로젝트 키·정책)을 담지 않는다. 일반 메시지만 노출한다
 * (memory: fr-pm-04-guard-exception-message-http-leak — Guard 예외 message HTTP 누출 차단).
 */
class BoardAccessDeniedException : RuntimeException("권한이 없습니다.")

/**
 * 보드 미존재(또는 soft-deleted) 예외 — 404.
 *
 * 컨트롤러가 권한 통과 후 보드 메타 조회 결과 null 일 때 던진다.
 * 보안 — message 에 boardId 등 내부 식별자를 담지 않는다.
 */
class BoardNotFoundException : RuntimeException("보드를 찾을 수 없습니다.")

/**
 * agile-planning BC 의 도메인/권한 예외를 RFC 7807 ProblemDetail 형식으로 변환하는 핸들러.
 *
 * [assignableTypes] 를 [BoardController]·[BoardQuickFilterController] 로 한정하여 SprintController 등
 * 다른 컨트롤러의 예외를 잡지 않는다(memory: domain-exception-http-handler-basepackage-scope 교훈).
 * [BoardQuickFilterController](FR-UX-01) 가 재사용하는 401/403/404 가 catch-all 로 500 변질되지 않으려면
 * 이 목록에 포함되어야 한다(리뷰 BLOCKER-B/C — 별도 전역 advice 신설 대신 assignableTypes 를 확장한다).
 *
 * catch-all [Exception] 핸들러를 두되, [ResponseStatusException] 은 별도 핸들러로 상태를 전파하여
 * catch-all 이 401/404/409/422 등을 500 으로 변질시키지 못하게 한다
 * (memory: catch-all-exceptionhandler-swallows-responsestatusexception 교훈).
 * [MethodArgumentTypeMismatchException]/[HttpMessageNotReadableException] 도 명시 등록해 path UUID
 * 형식 오류·본문 손상이 500 으로 변질되지 않게 한다 (FR-WT-01 동일 패턴). 에러 코드 접두사는 `AGILE_` 고정.
 *
 * ### 매핑 규칙
 * - [MethodArgumentNotValidException]/[HttpMessageNotReadableException]/[MethodArgumentTypeMismatchException]
 *   → 400 + AGILE_VALIDATION_FAILED
 * - [BoardNameInvalidException] → 400 + AGILE_VALIDATION_FAILED (도메인 이름 불변식 위반, FR-BD-01-2a).
 *   맨 [IllegalArgumentException] 이 아니라 이 한 타입만 잡는다 — 넓히면 두 컨트롤러 호출 사슬의
 *   내부 `require`/`check` 버그와 [NumberFormatException] 까지 400 으로 나가 5xx 경보에서 사라진다.
 * - [BoardAccessDeniedException] → 403 + AGILE_ACCESS_DENIED
 * - [BoardNotFoundException] → 404 + AGILE_BOARD_NOT_FOUND
 * - [QuickFilterNameConflictException] → 409 + AGILE_QUICK_FILTER_NAME_CONFLICT (OCC 충돌 문구와 구분, 리뷰 C4)
 * - [QuickFilterLimitExceededException] → 409 + AGILE_QUICK_FILTER_LIMIT_EXCEEDED (코드리뷰 CONCERN-1/2 — 상한 초과를
 *   OCC 충돌 문구와 구분)
 * - [QuickFilterNotFoundException] → 404 + AGILE_QUICK_FILTER_NOT_FOUND (퀵필터 미존재를 보드 미존재와 구분)
 * - [QuickFilterEmptyQueryException] → 400 + AGILE_QUICK_FILTER_EMPTY_QUERY (빈 필터 조건을 일반 검증 실패와 구분)
 * - [ResponseStatusException] → 명시 상태 전파(401/404/409/422 등, 일반 메시지)
 * - [Exception] (fallback) → 500 + AGILE_INTERNAL_ERROR
 *
 * TooManyFunctions: 도메인/권한 예외 각각에 @ExceptionHandler 가 필요하므로 함수 수가 임계치를 넘는다.
 * RestControllerAdvice 의 책임(예외→HTTP 변환)은 분리 불가한 단일 관심사라 클래스 단위로 억제한다.
 */
@Suppress("TooManyFunctions")
@RestControllerAdvice(assignableTypes = [BoardController::class, BoardQuickFilterController::class])
class BoardExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    // ── 400 VALIDATION_FAILED ─────────────────────────────────────────────────

    /**
     * Bean Validation (`@Valid`) 실패 — 400.
     *
     * @param ex Spring MVC 가 생성한 검증 실패 예외. 필드별 오류 목록을 포함한다.
     */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationFailed(ex: MethodArgumentNotValidException): ProblemDetail {
        val fieldErrors =
            ex.bindingResult.fieldErrors.joinToString("; ") { "${it.field}: ${it.defaultMessage}" }
        log.info("AGILE_400 validation_failed fields='{}'", fieldErrors)
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
     * JSON 형식 오류 또는 타입 불일치 시 발생한다. catch-all 이 500 으로 변질시키지 못하도록 명시 등록한다.
     * 보안 — 역직렬화 오류 상세를 응답에 포함하지 않고 일반 메시지만 반환한다. 원인은 로그에만 기록한다.
     *
     * @param ex 역직렬화 실패를 나타내는 Spring HTTP 메시지 변환 예외.
     */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleHttpMessageNotReadable(ex: HttpMessageNotReadableException): ProblemDetail {
        log.info("AGILE_400 message_not_readable cause='{}'", ex.cause?.message ?: ex.message)
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
     * 경로 변수가 UUID 타입이어야 할 때 올바르지 않은 값이 전달되면 발생한다.
     * catch-all 이 500 으로 변질시키지 못하도록 명시 등록한다.
     * 보안 — 파라미터 이름·요청값 등 내부 정보를 응답에 포함하지 않는다. 로그에만 기록한다.
     *
     * @param ex 파라미터 이름·요청값·목표 타입 정보를 포함하는 예외.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleMethodArgumentTypeMismatch(ex: MethodArgumentTypeMismatchException): ProblemDetail {
        log.info("AGILE_400 type_mismatch param='{}'", ex.name)
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "agile-validation-failed",
            title = "Validation Failed",
            errorCode = AGILE_VALIDATION_FAILED,
            detail = "요청 경로 또는 파라미터 형식이 올바르지 않습니다.",
        )
    }

    /**
     * 보드 이름 불변식 위반 — 400.
     *
     * [com.bts.agileplanning.domain.Board] init 블록이 공백 이름에 던지는
     * [BoardNameInvalidException] 을 400 으로 매핑한다. 이 핸들러가 없으면 catch-all [Exception] 이 삼켜
     * 공백 이름 PATCH 가 500 AGILE_INTERNAL_ERROR 로 나간다 — 같은 매핑이 [SprintExceptionHandler] 에
     * 있으나 그쪽 `assignableTypes` 는 스프린트 컨트롤러라 보드 요청에는 오지 않는다.
     *
     * 컨트롤러가 공백을 미리 막는 우회 대신 이 매핑을 두는 이유는, 선차단하면 도메인 불변식이
     * dead code 가 되고 그 불변식을 지키는 테스트가 도달 불가 조건을 지키게 되기 때문이다.
     *
     * ### 왜 [IllegalArgumentException] 이 아니라 이 타입인가
     * 상위 타입으로 잡으면 [BoardController]·[BoardQuickFilterController] 호출 사슬 전체의
     * `require`/`check` 실패와 [NumberFormatException] 같은 하위 타입까지 400 이 된다. 그러면 서버
     * 버그가 클라이언트 입력 오류로 위장돼 5xx 경보에서 사라진다. 분류되지 않은 나머지는 catch-all
     * [Exception] 핸들러가 500 으로 보낸다(`ERR-1` 테스트가 그 경계를 고정한다).
     *
     * 보안 — 도메인 내부 메시지를 응답에 노출하지 않고 일반 메시지만 반환한다. 원인은 로그에만 기록한다.
     *
     * @param ex 보드 이름 불변식 위반 예외.
     */
    @ExceptionHandler(BoardNameInvalidException::class)
    fun handleBoardNameInvalid(ex: BoardNameInvalidException): ProblemDetail {
        log.info("AGILE_400 board_name_invalid cause='{}'", ex.message)
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "agile-validation-failed",
            title = "Validation Failed",
            errorCode = AGILE_VALIDATION_FAILED,
            detail = "요청 값 검증에 실패했습니다.",
        )
    }

    /**
     * 보드 종류가 허용값 밖 — 400 (FR-BD-04 D4).
     *
     * [BoardController.create] 가 `boardType` 을 [com.bts.agileplanning.domain.BoardType.from] 으로
     * 읽을 때 던지는 [BoardTypeInvalidException] 을 매핑한다.
     *
     * ### 왜 [AGILE_VALIDATION_FAILED] 를 재사용하지 않는가
     * 클라이언트가 「어느 필드가 왜 틀렸는지」를 상태코드만으로는 못 가린다. 보드 생성은 필수 필드
     * 누락(400 `AGILE_VALIDATION_FAILED`)과 종류 오타가 둘 다 400 이라, 코드를 나눠야 UI 가
     * 종류 선택으로 되돌릴지 폼 전체를 되짚을지 정할 수 있다.
     *
     * ### 왜 [IllegalArgumentException] 이 아니라 이 타입인가
     * [handleBoardNameInvalid] KDoc 과 같은 이유다 — 상위 타입으로 잡으면 호출 사슬 전체의
     * `require`/`check` 실패까지 400 이 되어 서버 버그가 클라이언트 입력 오류로 위장한다.
     *
     * 보안 — 도메인 메시지(입력 원문 포함)를 응답에 싣지 않는다. 원인은 로그에만 남긴다.
     *
     * @param ex 보드 종류 허용값 위반 예외.
     */
    @ExceptionHandler(BoardTypeInvalidException::class)
    fun handleBoardTypeInvalid(ex: BoardTypeInvalidException): ProblemDetail {
        log.info("AGILE_400 board_type_invalid cause='{}'", ex.message)
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "agile-board-type-invalid",
            title = "Invalid Board Type",
            errorCode = AGILE_BOARD_TYPE_INVALID,
            detail = "보드 종류는 SCRUM 또는 KANBAN 이어야 합니다.",
        )
    }

    /**
     * [QuickFilterEmptyQueryException] — 빈 필터 조건으로 퀵필터 저장 시도(EC1) — 400.
     *
     * 코드리뷰 CONCERN-1/2 — 일반 [ResponseStatusException] 핸들러의 AGILE_VALIDATION_FAILED 대신
     * 전용 errorCode 로 원인(빈 조건)을 구분한다.
     *
     * @param ex 빈 필터 조건 예외(내부 식별자 미포함).
     */
    @ExceptionHandler(QuickFilterEmptyQueryException::class)
    fun handleQuickFilterEmptyQuery(
        @Suppress("UnusedParameter") ex: QuickFilterEmptyQueryException,
    ): ProblemDetail {
        log.info("AGILE_400 quick_filter_empty_query")
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "agile-quick-filter-empty-query",
            title = "Quick Filter Empty Query",
            errorCode = AGILE_QUICK_FILTER_EMPTY_QUERY,
            detail = "필터 조건을 하나 이상 지정해야 합니다.",
        )
    }

    // ── 403 ACCESS_DENIED ─────────────────────────────────────────────────────

    /**
     * [BoardAccessDeniedException] — 보드 권한 없음 — 403.
     *
     * 보안 — detail 에 내부 사정(보드 존재 여부·정책)을 노출하지 않고 일반 메시지를 사용한다.
     *
     * @param ex 권한 거부 예외(내부 정보 미포함).
     */
    @ExceptionHandler(BoardAccessDeniedException::class)
    fun handleAccessDenied(
        @Suppress("UnusedParameter") ex: BoardAccessDeniedException,
    ): ProblemDetail {
        log.info("AGILE_403 access_denied")
        return problem(
            status = HttpStatus.FORBIDDEN,
            type = "agile-access-denied",
            title = "Access Denied",
            errorCode = AGILE_ACCESS_DENIED,
            detail = "이 작업을 수행할 권한이 없습니다.",
        )
    }

    // ── 404 BOARD_NOT_FOUND ───────────────────────────────────────────────────

    /**
     * [BoardNotFoundException] — 보드 미존재 또는 soft-deleted — 404.
     *
     * @param ex 보드 미존재 예외(내부 식별자 미포함).
     */
    @ExceptionHandler(BoardNotFoundException::class)
    fun handleBoardNotFound(
        @Suppress("UnusedParameter") ex: BoardNotFoundException,
    ): ProblemDetail {
        log.info("AGILE_404 board_not_found")
        return problem(
            status = HttpStatus.NOT_FOUND,
            type = "agile-board-not-found",
            title = "Board Not Found",
            errorCode = AGILE_BOARD_NOT_FOUND,
            detail = "보드를 찾을 수 없습니다.",
        )
    }

    /**
     * [QuickFilterNotFoundException] — 퀵필터 미존재 또는 타 보드 소속(EC5) — 404.
     *
     * 코드리뷰 CONCERN-1/2 — 일반 [ResponseStatusException] 핸들러의 AGILE_BOARD_NOT_FOUND 로 뭉뚱그려지면
     * "보드를 찾을 수 없습니다" 문구가 실제로는 필터 미존재인 상황에 부정확하게 노출된다. 전용 errorCode/문구로 구분한다.
     *
     * @param ex 퀵필터 미존재 예외(내부 식별자 미포함).
     */
    @ExceptionHandler(QuickFilterNotFoundException::class)
    fun handleQuickFilterNotFound(
        @Suppress("UnusedParameter") ex: QuickFilterNotFoundException,
    ): ProblemDetail {
        log.info("AGILE_404 quick_filter_not_found")
        return problem(
            status = HttpStatus.NOT_FOUND,
            type = "agile-quick-filter-not-found",
            title = "Quick Filter Not Found",
            errorCode = AGILE_QUICK_FILTER_NOT_FOUND,
            detail = "퀵필터를 찾을 수 없습니다.",
        )
    }

    // ── 409 QUICK_FILTER_NAME_CONFLICT ────────────────────────────────────────

    /**
     * [QuickFilterNameConflictException] — 같은 보드 내 퀵필터 이름 중복(EC2) — 409.
     *
     * 일반 [ResponseStatusException] 핸들러의 OCC 충돌 문구와 뉘앙스가 겹치지 않도록 전용 메시지를
     * 반환한다(리뷰 C4). 서브타입이라도 Spring 은 가장 가까운(구체적인) 핸들러를 우선 선택한다.
     *
     * @param ex 이름 중복 예외(내부 식별자 미포함).
     */
    @ExceptionHandler(QuickFilterNameConflictException::class)
    fun handleQuickFilterNameConflict(
        @Suppress("UnusedParameter") ex: QuickFilterNameConflictException,
    ): ProblemDetail {
        log.info("AGILE_409 quick_filter_name_conflict")
        return problem(
            status = HttpStatus.CONFLICT,
            type = "agile-quick-filter-name-conflict",
            title = "Quick Filter Name Conflict",
            errorCode = AGILE_QUICK_FILTER_NAME_CONFLICT,
            detail = "같은 이름의 퀵필터가 이미 있습니다.",
        )
    }

    /**
     * [QuickFilterLimitExceededException] — 보드당 퀵필터 20건 상한 초과(EC3) — 409.
     *
     * 코드리뷰 CONCERN-1/2 — 일반 [ResponseStatusException] 핸들러의 OCC 충돌 문구("다른 변경과 충돌이
     * 발생했습니다. 다시 시도해 주세요.")는 상한 초과 상황에 부적절하다. 전용 errorCode/문구로 구분한다.
     *
     * @param ex 상한 초과 예외(내부 식별자 미포함).
     */
    @ExceptionHandler(QuickFilterLimitExceededException::class)
    fun handleQuickFilterLimitExceeded(
        @Suppress("UnusedParameter") ex: QuickFilterLimitExceededException,
    ): ProblemDetail {
        log.info("AGILE_409 quick_filter_limit_exceeded")
        return problem(
            status = HttpStatus.CONFLICT,
            type = "agile-quick-filter-limit-exceeded",
            title = "Quick Filter Limit Exceeded",
            errorCode = AGILE_QUICK_FILTER_LIMIT_EXCEEDED,
            detail = "보드당 퀵필터는 최대 20개까지 저장할 수 있습니다.",
        )
    }

    // ── ResponseStatusException 상태 전파 (catch-all 변질 차단) ────────────────

    /**
     * [ResponseStatusException] — 컨트롤러/서비스가 명시한 HTTP 상태를 그대로 전파한다.
     *
     * [CurrentActor.current] 의 401, [BoardApplicationService] 의 422(워크플로우 미할당)/409(버전 충돌)/
     * 400(보드-이슈 정합) 등이 catch-all 에 가로채여 500 으로 변질되던 문제를 차단한다.
     * `@RestControllerAdvice` 는 Spring 의 ResponseStatusExceptionResolver 보다 먼저 실행되므로,
     * [Exception] 보다 구체적인 이 핸들러를 등록해 Spring 이 우선 선택하도록 한다.
     *
     * 보안 — detail 에 `ex.reason` 등 내부 정보를 노출하지 않고 상태 코드 기반 일반 메시지를 사용한다(원본 사유는 로그에만 기록).
     *
     * @param ex 컨트롤러/서비스 계층에서 던진 상태 코드 보유 예외.
     */
    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(ex: ResponseStatusException): ProblemDetail {
        val status = HttpStatus.valueOf(ex.statusCode.value())
        log.info("AGILE_{} response_status reason='{}'", status.value(), ex.reason)
        val (errorCode, detail) =
            when (status) {
                HttpStatus.UNAUTHORIZED ->
                    AGILE_UNAUTHENTICATED to "인증이 필요합니다. 세션이 만료되었을 수 있습니다."
                HttpStatus.FORBIDDEN ->
                    AGILE_ACCESS_DENIED to "이 작업을 수행할 권한이 없습니다."
                HttpStatus.NOT_FOUND ->
                    AGILE_BOARD_NOT_FOUND to "보드를 찾을 수 없습니다."
                HttpStatus.CONFLICT ->
                    AGILE_CONFLICT to "다른 변경과 충돌이 발생했습니다. 다시 시도해 주세요."
                HttpStatus.UNPROCESSABLE_ENTITY ->
                    AGILE_UNPROCESSABLE to "요청을 처리할 수 없습니다. 워크플로우 또는 해결 방안 설정을 확인해 주세요."
                HttpStatus.BAD_REQUEST ->
                    AGILE_VALIDATION_FAILED to "요청 값이 올바르지 않습니다."
                else ->
                    AGILE_INTERNAL_ERROR to "요청을 처리할 수 없습니다."
            }
        return problem(
            status = status,
            type = "agile-response-status",
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
        log.error("AGILE_500 internal_error", ex)
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
     * 표준 필드 외에 커스텀 `errorCode` 와 `timestamp` 를 추가한다.
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

    /** agile-planning BC 에러 코드 상수. 모두 `AGILE_` 접두사를 사용한다. */
    private companion object {
        const val AGILE_VALIDATION_FAILED = "AGILE_VALIDATION_FAILED"
        const val AGILE_UNAUTHENTICATED = "AGILE_UNAUTHENTICATED"
        const val AGILE_ACCESS_DENIED = "AGILE_ACCESS_DENIED"
        const val AGILE_BOARD_NOT_FOUND = "AGILE_BOARD_NOT_FOUND"
        const val AGILE_BOARD_TYPE_INVALID = "AGILE_BOARD_TYPE_INVALID"
        const val AGILE_CONFLICT = "AGILE_CONFLICT"
        const val AGILE_QUICK_FILTER_NAME_CONFLICT = "AGILE_QUICK_FILTER_NAME_CONFLICT"
        const val AGILE_QUICK_FILTER_LIMIT_EXCEEDED = "AGILE_QUICK_FILTER_LIMIT_EXCEEDED"
        const val AGILE_QUICK_FILTER_NOT_FOUND = "AGILE_QUICK_FILTER_NOT_FOUND"
        const val AGILE_QUICK_FILTER_EMPTY_QUERY = "AGILE_QUICK_FILTER_EMPTY_QUERY"
        const val AGILE_UNPROCESSABLE = "AGILE_UNPROCESSABLE"
        const val AGILE_INTERNAL_ERROR = "AGILE_INTERNAL_ERROR"
    }
}
