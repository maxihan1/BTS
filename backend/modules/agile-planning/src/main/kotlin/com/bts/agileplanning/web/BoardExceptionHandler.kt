// agile-planning BC 도메인/권한 예외를 RFC 7807 ProblemDetail HTTP 응답으로 변환하는 핸들러

package com.bts.agileplanning.web

import com.bts.agileplanning.application.BoardStateNotMappedException
import com.bts.agileplanning.application.CardLayoutInvalidException
import com.bts.agileplanning.application.ColumnStateAmbiguousException
import com.bts.agileplanning.application.DetailViewFieldGroupInvalidException
import com.bts.agileplanning.application.DuplicateStateKeysException
import com.bts.agileplanning.application.EstimationBoardNotFoundException
import com.bts.agileplanning.application.MoveTargetAmbiguousException
import com.bts.agileplanning.application.QuickFilterEmptyQueryException
import com.bts.agileplanning.application.QuickFilterLimitExceededException
import com.bts.agileplanning.application.QuickFilterNameConflictException
import com.bts.agileplanning.application.QuickFilterNotFoundException
import com.bts.agileplanning.application.StateAlreadyMappedException
import com.bts.agileplanning.application.TimeTrackingBoardNotScrumException
import com.bts.agileplanning.application.TimeTrackingInvalidException
import com.bts.agileplanning.application.WorkingDaysBoardNotFoundException
import com.bts.agileplanning.application.WorkingDaysInvalidException
import com.bts.agileplanning.domain.BoardNameInvalidException
import com.bts.agileplanning.domain.BoardTypeInvalidException
import org.slf4j.LoggerFactory
import org.springframework.dao.CannotAcquireLockException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
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

private const val VALIDATION_FALLBACK_DETAIL = "요청 값이 올바르지 않습니다."
private const val CONFLICT_FALLBACK_DETAIL = "다른 변경과 충돌이 발생했습니다. 다시 시도해 주세요."

/**
 * agile-planning BC 의 도메인/권한 예외를 RFC 7807 ProblemDetail 형식으로 변환하는 핸들러.
 *
 * [assignableTypes] 를 보드 계열 컨트롤러 여섯으로 한정하여 SprintController 등 다른 컨트롤러의 예외를
 * 잡지 않는다(memory: domain-exception-http-handler-basepackage-scope 교훈).
 * [BoardQuickFilterController](FR-UX-01) 가 재사용하는 401/403/404 가 catch-all 로 500 변질되지 않으려면
 * 이 목록에 포함되어야 한다(리뷰 BLOCKER-B/C — 별도 전역 advice 신설 대신 assignableTypes 를 확장한다).
 *
 * ### 보드 설정 탭 컨트롤러 넷도 이 목록에 있다 (부채 177 Task 29)
 * [BoardCardLayoutController] · [BoardEstimationController] · [BoardWorkingDaysController] ·
 * [BoardDetailViewController] 는 **같은 설정 화면의 네 탭**이다. 이 목록에 없던 동안 넷이 서로 다르게
 * 우회했다 — 둘은 [ResponseStatusException] 계열만 던져 상태 코드만 맞췄고(본문이 빈 채로 나갔다),
 * 하나는 자기 파일 안에 별도 advice 를 뒀다. 그 결과 한 화면의 네 탭이 **서로 다른 오류 본문**을 냈고
 * 프론트가 탭마다 다르게 파싱해야 했다. 넷을 여기로 모아 봉투를 하나로 만든다
 * (`BoardSettingsTabErrorEnvelopeTest` 가 네 탭의 404 본문이 서로 같은지를 잰다).
 *
 * ### ★ [assignableTypes] 는 **열거**다 — 다섯 번째 탭이 같은 자리를 또 밟는다
 * 이 목록은 컨트롤러를 하나씩 적어 두는 방식이라, 새 컨트롤러를 만든 사람이 여기에 자기 이름을
 * 더하는 것을 잊으면 **조용히 안 덮인다.** 컴파일도 통과하고 정상 경로 테스트도 전부 초록이라
 * 오류를 실제로 내 보기 전에는 드러나지 않는다. 넷이 각자 우회를 만든 원인이 바로 그것이다.
 *
 * ★게다가 오류 경로가 [ResponseStatusException] 계열이면 이 advice 를 통째로 지워도 **상태 코드는
 * 그대로 나온다**(Spring 의 `ResponseStatusExceptionResolver`). 「404 인가」만 재는 테스트는
 * 덮였는지 아닌지를 전혀 재지 못한다 — `BoardSettingsTabErrorEnvelopeTest` KDoc 의 뮤테이션 X2 가
 * 그 실측이다. 그래서 그 파일은 상태 코드가 아니라 본문 봉투를 잰다.
 *
 * 열거를 패키지 스캔이나 공통 마커 인터페이스로 바꾸는 것은 이 자리에서 하지 않는다 —
 * 스코프가 넓어지면 [SprintExceptionHandler] 등 형제 advice 관할까지 삼킨다. 후속 처방 후보는
 * `TODOS.md` 의 「agile-planning — 보드 오류 봉투 advice 가 대상 컨트롤러를 손으로 열거한다」에 있다.
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
 * - [BoardNotFoundException] · [WorkingDaysBoardNotFoundException] · [EstimationBoardNotFoundException]
 *   → 404 + AGILE_BOARD_NOT_FOUND
 * - [WorkingDaysInvalidException] → 400 + AGILE_VALIDATION_FAILED (사유를 detail 에 싣는다)
 * - [QuickFilterNameConflictException] → 409 + AGILE_QUICK_FILTER_NAME_CONFLICT (OCC 충돌 문구와 구분, 리뷰 C4)
 * - [QuickFilterLimitExceededException] → 409 + AGILE_QUICK_FILTER_LIMIT_EXCEEDED (코드리뷰 CONCERN-1/2 — 상한 초과를
 *   OCC 충돌 문구와 구분)
 * - [QuickFilterNotFoundException] → 404 + AGILE_QUICK_FILTER_NOT_FOUND (퀵필터 미존재를 보드 미존재와 구분)
 * - [QuickFilterEmptyQueryException] → 400 + AGILE_QUICK_FILTER_EMPTY_QUERY (빈 필터 조건을 일반 검증 실패와 구분)
 * - [CannotAcquireLockException] → 503 + AGILE_UNAVAILABLE (advisory lock 200ms 예산 초과, 부채 166 ②).
 *   같은 매핑이 [SprintExceptionHandler] 에도 있다 — 형제 락 `scrum-board:<projectKey>` 가 두 경로에서
 *   도달하므로 한쪽을 지우면 그 경로가 500 을 낸다(스펙 E8). 사유 전문은 [handleLockTimeout] KDoc.
 * - [ResponseStatusException] → 명시 상태 전파(401/404/409/422 등, 일반 메시지)
 * - [Exception] (fallback) → 500 + AGILE_INTERNAL_ERROR
 *
 * TooManyFunctions: 도메인/권한 예외 각각에 @ExceptionHandler 가 필요하므로 함수 수가 임계치를 넘는다.
 * RestControllerAdvice 의 책임(예외→HTTP 변환)은 분리 불가한 단일 관심사라 클래스 단위로 억제한다.
 */
@Suppress("TooManyFunctions")
@RestControllerAdvice(
    assignableTypes = [
        BoardController::class,
        BoardQuickFilterController::class,
        BoardCardLayoutController::class,
        BoardEstimationController::class,
        BoardWorkingDaysController::class,
        BoardDetailViewController::class,
    ],
)
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
     * 작업일 설정 값 위반 — 400 (스펙 E1 · J38·J40).
     *
     * 근무일 0개 · 미지원 요일 키 · 비-IANA 타임존이 여기로 온다.
     * 다른 400 과 달리 [WorkingDaysInvalidException.reason] 을 detail 에 그대로 싣는다 —
     * 그 문자열은 사용자에게 무엇을 고쳐야 하는지 알리는 문구이고 내부 식별자를 담지 않는다
     * (정본은 [com.bts.agileplanning.application.WorkingDaysSettingsService] 의 검증 함수들).
     *
     * @param ex 사유를 담은 검증 예외.
     */
    @ExceptionHandler(WorkingDaysInvalidException::class)
    fun handleWorkingDaysInvalid(ex: WorkingDaysInvalidException): ProblemDetail {
        log.info("AGILE_400 working_days_invalid reason='{}'", ex.reason)
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "agile-validation-failed",
            title = "Validation Failed",
            errorCode = AGILE_VALIDATION_FAILED,
            detail = ex.reason,
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

    /**
     * [MoveTargetAmbiguousException] — 이동 대상을 정확히 하나 지정하지 않았다 — 400 (R7).
     *
     * 전용 코드를 만들지 않고 [AGILE_VALIDATION_FAILED] 를 재사용한다 — 요청 **모양**의 오류이지
     * 사용자가 마주할 도메인 상태가 아니라서, UI 가 분기할 이유가 없다. 클라이언트 버그다.
     *
     * @param ex 대상 지정 오류 예외. 메시지가 「둘 다 없음/둘 다 있음」을 구분한다.
     */
    @ExceptionHandler(MoveTargetAmbiguousException::class)
    fun handleMoveTargetAmbiguous(ex: MoveTargetAmbiguousException): ProblemDetail {
        log.info("AGILE_400 move_target_ambiguous cause='{}'", ex.message)
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "agile-validation-failed",
            title = "Validation Failed",
            errorCode = AGILE_VALIDATION_FAILED,
            detail = ex.message,
        )
    }

    /**
     * [ColumnStateAmbiguousException] — 하위 호환 `toColumnId` 가 상태 2개 이상 컬럼을 가리켰다 — 400 (R7).
     *
     * ### 왜 [AGILE_VALIDATION_FAILED] 를 재사용하지 않는가
     *
     * 이건 클라이언트 버그가 아니라 **마이그레이션 신호**다. 요청은 1:1 시절 문법으로 옳았고,
     * 보드가 1:N 이 되면서 해석이 불가능해졌다. 클라이언트는 이 코드를 보고 `toStateKey` 경로로
     * 옮겨 가야 한다 — 일반 검증 실패와 섞이면 그 신호가 묻힌다.
     *
     * @param ex 모호 예외. 어느 컬럼이 상태 몇 개를 담았는지 담고 있다.
     */
    @ExceptionHandler(ColumnStateAmbiguousException::class)
    fun handleColumnStateAmbiguous(ex: ColumnStateAmbiguousException): ProblemDetail {
        log.info("AGILE_400 column_state_ambiguous stateCount={}", ex.stateCount)
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "agile-column-state-ambiguous",
            title = "Column State Ambiguous",
            errorCode = AGILE_COLUMN_STATE_AMBIGUOUS,
            detail = "이 컬럼은 상태를 ${ex.stateCount}개 담고 있습니다. 이동할 상태를 toStateKey 로 지정하세요.",
        )
    }

    /**
     * [DuplicateStateKeysException] — 요청의 `stateKeys` 에 중복 — 400 (E9).
     *
     * DB 의 `UNIQUE (column_id, state_key)` 는 **경합**을 막는 제약이고 이건 요청 모양의 오류다.
     * 409 로 내면 「다른 사람이 먼저 썼다」로 읽혀 원인을 오도한다.
     *
     * @param ex 중복 예외. 어느 키가 겹쳤는지 담고 있다.
     */
    @ExceptionHandler(DuplicateStateKeysException::class)
    fun handleDuplicateStateKeys(ex: DuplicateStateKeysException): ProblemDetail {
        log.info("AGILE_400 duplicate_state_keys keys='{}'", ex.duplicated)
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "agile-validation-failed",
            title = "Validation Failed",
            errorCode = AGILE_VALIDATION_FAILED,
            detail = "stateKeys 에 중복이 있습니다: ${ex.duplicated.joinToString()}",
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
     * 보드 미존재 또는 soft-deleted — 404.
     *
     * 세 타입을 한 봉투로 합류시킨다. [BoardNotFoundException] 은 컨트롤러 게이트가,
     * [WorkingDaysBoardNotFoundException] 과 [EstimationBoardNotFoundException] 은 서비스가
     * **게이트와 저장 사이의 경합**에서 던진다 — 사용자에게는 같은 사실("보드가 없다")이라
     * 코드를 나누면 프론트가 같은 화면을 두 갈래로 다뤄야 한다.
     *
     * @param ex 보드 미존재 예외(내부 식별자 미포함).
     */
    @ExceptionHandler(
        BoardNotFoundException::class,
        WorkingDaysBoardNotFoundException::class,
        EstimationBoardNotFoundException::class,
    )
    fun handleBoardNotFound(
        @Suppress("UnusedParameter") ex: RuntimeException,
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
     * [BoardStateNotMappedException] — `toStateKey` 가 이 보드의 어느 컬럼에도 없다 — 404 (E4).
     *
     * ### 보드 미존재와 코드를 나누는 이유
     *
     * 둘 다 404 라 상태 코드만으로는 구분이 안 되는데, 사용자가 할 수 있는 일이 다르다.
     * 보드가 없으면 끝이고, 상태가 미매핑이면 **컬럼에 그 상태를 추가하면** 된다 —
     * 보드 조회 응답의 `unmappedStates`(R8)가 그 목록을 이미 주고 있다.
     *
     * 상태 키는 워크플로우 정본 키라 내부 사정이 아니다. detail 에 담아 UI 가 바로 안내할 수 있게 한다.
     *
     * @param ex 미매핑 상태 예외.
     */
    @ExceptionHandler(BoardStateNotMappedException::class)
    fun handleBoardStateNotMapped(ex: BoardStateNotMappedException): ProblemDetail {
        log.info("AGILE_404 board_state_not_mapped stateKey='{}'", ex.stateKey)
        return problem(
            status = HttpStatus.NOT_FOUND,
            type = "agile-board-state-not-mapped",
            title = "Board State Not Mapped",
            errorCode = AGILE_BOARD_STATE_NOT_MAPPED,
            detail = "이 보드의 어느 컬럼에도 매핑되지 않은 상태입니다: ${ex.stateKey}",
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
     * [StateAlreadyMappedException] — 그 상태를 이미 다른 컬럼이 쓰고 있다 — 409 (E7 · X1).
     *
     * detail 에 **어느 컬럼인지**를 싣는다. 컬럼 UUID 는 같은 보드 응답이 이미 노출하는 값이라
     * 내부 사정 누설이 아니고, 이 정보가 없으면 사용자가 충돌을 풀 방법을 못 찾는다.
     *
     * @param ex 매핑 충돌 예외.
     */
    @ExceptionHandler(StateAlreadyMappedException::class)
    fun handleStateAlreadyMapped(ex: StateAlreadyMappedException): ProblemDetail {
        log.info("AGILE_409 state_already_mapped stateKey='{}'", ex.stateKey)
        return problem(
            status = HttpStatus.CONFLICT,
            type = "agile-state-already-mapped",
            title = "State Already Mapped",
            errorCode = AGILE_STATE_ALREADY_MAPPED,
            detail = "상태 '${ex.stateKey}' 는 이미 다른 컬럼(${ex.ownerColumnId})이 쓰고 있습니다.",
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

    // ── 503 UNAVAILABLE (advisory lock 예산 초과 · 부채 166 ② · 스펙 E8) ───────

    /**
     * [CannotAcquireLockException] — advisory lock 을 200ms 예산 안에 얻지 못했다 — 503.
     *
     * ### 왜 보드 쪽에도 필요한가 (스펙 E8)
     * 형제 락 `scrum-board:<projectKey>` 는 [com.bts.agileplanning.application.BoardApplicationService]
     * `ensureScrumBoard` 가 잡는다. 그 메서드는 **보드 서비스의 public 메서드**라 스프린트 생성
     * 경로([SprintExceptionHandler] 관할)와 보드 컨트롤러 경로 **양쪽**에서 도달할 수 있다.
     * 한쪽에만 매핑을 걸면 다른 쪽이 500 을 낸다 — 그래서 같은 매핑이 두 advice 에 있다.
     *
     * 예외 타입은 추측이 아니라 `AdvisoryLockBudget.kt` KDoc 의 실측(2026-09-03)이 정본이다.
     * PostgreSQL `55P03`(canceling statement due to lock timeout) → jOOQ `JooqExceptionTranslator`
     * → 이 타입(cause 는 `PSQLException`).
     *
     * ### ★ 행 락은 이 타입을 낼 수 없다
     * 락 획득 **직후** `lock_timeout` 을 `0`(무제한)으로 되돌리므로(N6) 뒤따르는 행 락 대기는
     * 예산 밖이다. 이 503 은 오직 advisory lock 획득 실패에만 대응한다 — 「행 락도 503 이 되나?」의
     * 답은 **아니오**다.
     *
     * ### ★ 좁게 잡는다 · 왜 도메인 예외로 안 감쌌나
     * 상위 `PessimisticLockingFailureException` 이 아니라 이 타입만 잡는다. 형제
     * `DeadlockLoserDataAccessException`(`40P01`) · `CannotSerializeTransactionException`(`40001`) 은
     * 안 잡힌다(의도한 좁힘). 감싸기를 하지 않은 사유와 나중에 감쌀 때 고칠 세 곳은
     * [SprintExceptionHandler.handleLockTimeout] KDoc 이 정본이다 — 두 곳에 나눠 적으면 갈린다.
     *
     * 보안 — 락 키에 `projectKey` 가, 예외 메시지에 SQL 이 들어 있다. 둘 다 detail 에 노출하지
     * 않는다. 로그에도 SQL 대신 **어느 엔드포인트에서 났는지**만 남긴다.
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

    // ── ResponseStatusException 상태 전파 (catch-all 변질 차단) ────────────────

    /**
     * [ResponseStatusException] — 컨트롤러/서비스가 명시한 HTTP 상태를 그대로 전파한다.
     *
     * [CurrentActor.current] 의 401, [BoardApplicationService] 의 422(워크플로우 미할당)/409(버전 충돌)/
     * 400(보드-이슈 정합) 등이 catch-all 에 가로채여 500 으로 변질되던 문제를 차단한다.
     * `@RestControllerAdvice` 는 Spring 의 ResponseStatusExceptionResolver 보다 먼저 실행되므로,
     * [Exception] 보다 구체적인 이 핸들러를 등록해 Spring 이 우선 선택하도록 한다.
     *
     * 보안 — 401·403·404·409 등은 상태 코드 기반 **일반 메시지**를 쓴다(원본 사유는 로그에만).
     *
     * ★**400 만 예외다**(리뷰 C7 · 2026-09-06). 서비스들이 사전 검증의 존재 이유를 「사용자에게
     * 400 의 **이유**를 주려고」라고 KDoc 에 적어 두는데 그것을 버리면 그 문장이 거짓이 된다.
     * 400 의 `reason` 은 서버가 만든 고정 문구(허용값·상한)이고 요청 값을 되비추지 않는다 —
     * 그 규율은 각 예외 클래스가 진다(`QuickFilterEmptyQueryException` 이 세운 선례).
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
                    // ★탭 예외는 자기 사유를 싣는다(재리뷰 ②). 종전 고정 문구는
                    //   「다시 시도해 주세요」인데 `TimeTrackingBoardNotScrumException` 이 뜻하는
                    //   「칸반 보드에서는 바꿀 수 없다」는 **재시도로 영원히 성공하지 않는다** —
                    //   그 예외 KDoc 이 「이 보드에서 영영 불가능하다」고 적는다.
                    //   400 에서 C7 이 고친 것과 같은 결함이 409 에 남아 있었다.
                    AGILE_CONFLICT to conflictReason(ex)
                HttpStatus.UNPROCESSABLE_ENTITY ->
                    AGILE_UNPROCESSABLE to "요청을 처리할 수 없습니다. 워크플로우 또는 해결 방안 설정을 확인해 주세요."
                HttpStatus.BAD_REQUEST ->
                    // ★`ex.reason` 을 싣되 **설정 4탭의 예외 타입에서만** 싣는다(리뷰 C7 · 재리뷰 C1).
                    //   서비스들이 사전 검증의 존재 이유를 「사용자에게 400 의 **이유**를 주려고」라고
                    //   KDoc 에 적어 두는데, 버리면 그 문장이 거짓이 되고 「최대 3개입니다」·
                    //   「칸반에는 백로그 뷰가 없습니다」가 로그에만 남는다.
                    //
                    // ★★**타입으로 좁히는 이유.** 이 advice 는 컨트롤러 6개를 덮고, 그중
                    //   `BoardController` 경로의 `BoardFilterQueryParser` 는 `reason` 에
                    //   **요청 값을 그대로 되비춘다**(`... 유효한 UUID 형식이 아닙니다: $value`).
                    //   상태 코드만 보고 전부 통과시키면 사용자 입력이 길이 제한 없이 응답 본문에
                    //   실린다 — 재리뷰가 이 델타가 새로 연 표면으로 지목했다. C7 이 실제로 지명한
                    //   것은 **설정 탭의 고정 문구**(허용값·상한)이고 그것들은 요청 값을 담지 않는다.
                    AGILE_VALIDATION_FAILED to settingsTabReason(ex)
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

    /**
     * 400 의 `detail` 에 실을 문구를 고른다 — **설정 4탭의 예외만** 자기 `reason` 을 쓴다.
     *
     * 나머지(필터 파싱 등)는 종전 고정 문구로 되돌아간다. 그 경로들은 `reason` 에 요청 값을
     * 되비추므로 통째로 열면 사용자 입력이 응답 본문에 실린다(재리뷰 C1).
     *
     * ★새 설정 탭을 더하면 이 목록에도 더해야 한다 — 두 목록이 서로를 검사하지 않는 자리다.
     *   `BoardSettingsTabErrorEnvelopeTest` 의 400 detail 축이 빠진 탭을 잡는다.
     */
    private fun settingsTabReason(ex: ResponseStatusException): String =
        when (ex) {
            is CardLayoutInvalidException,
            is DetailViewFieldGroupInvalidException,
            is TimeTrackingInvalidException,
            -> ex.reason ?: VALIDATION_FALLBACK_DETAIL
            else -> VALIDATION_FALLBACK_DETAIL
        }

    /**
     * 409 의 `detail` — **설정 탭의 예외만** 자기 `reason` 을 쓴다([settingsTabReason] 과 같은 규율).
     *
     * `TimeTrackingBoardNotScrumException` 의 「칸반 보드에서만 바꿀 수 없다」는 재시도로 풀리지
     * 않는 조건이라, 고정 문구 「다시 시도해 주세요」가 사용자를 잘못 인도한다.
     */
    private fun conflictReason(ex: ResponseStatusException): String =
        when (ex) {
            is TimeTrackingBoardNotScrumException -> ex.reason ?: CONFLICT_FALLBACK_DETAIL
            else -> CONFLICT_FALLBACK_DETAIL
        }

    /**
     * 저장 계층의 무결성 제약 위반을 **409** 로 거둔다 — 500 이 아니다(리뷰 C2).
     *
     * **왜 생기나.** 설정 4탭 중 셋(`replaceCardLayout` · `replaceNonWorkingDates` ·
     * `replaceDetailViewFields`)이 OCC 없이 `DELETE` → `INSERT` 다. READ COMMITTED 에서 두 관리자가
     * 같은 보드를 동시에 저장하면 뒤 트랜잭션의 `DELETE` 가 자기 스냅샷 밖인 앞 트랜잭션의 신규 행을
     * 못 지우고, 이어지는 `INSERT` 가 PK 중복(23505)으로 죽는다. 리포지터리 KDoc 이 예고한
     * CHECK 위반도 같은 경로다. 핸들러가 없으면 둘 다 catch-all 로 떨어져 `AGILE_INTERNAL_ERROR` 가 된다.
     *
     * ★**추정 탭은 이 경로가 아니다.** `UPDATE` 한 문장이라 이 경합이 성립하지 않는다.
     *
     * ★**프론트의 드래그 잠금은 한 브라우저 안의 lost update 만 닫는다.** 두 관리자 경합은
     * 그것과 다른 층이고, 여기가 그 층의 유일한 방어선이다.
     *
     * 두 갈래를 다 잡는다 — Spring `PersistenceExceptionTranslator` 가 개입하면
     * [DataIntegrityViolationException], 미개입이면 jOOQ 가 직접 던진다.
     * 형제 `BoardQuickFilterService.tryPersist` 가 세운 관용구를 advice 한 곳으로 모은 것이다
     * (세 서비스에 복제하면 그 자체가 「서로를 검사하지 않는 세 목록」이 된다).
     */
    @ExceptionHandler(
        org.springframework.dao.DataIntegrityViolationException::class,
        org.jooq.exception.IntegrityConstraintViolationException::class,
    )
    fun handleIntegrityViolation(ex: Exception): ProblemDetail {
        log.warn("보드 설정 저장 무결성 위반 — {}", ex.javaClass.simpleName)
        return problem(
            status = HttpStatus.CONFLICT,
            type = "agile-settings-conflict",
            title = "Conflict",
            errorCode = AGILE_CONFLICT,
            detail = "다른 관리자가 방금 이 보드의 설정을 저장했습니다. 새로고침 후 다시 시도해 주세요.",
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
        const val AGILE_BOARD_STATE_NOT_MAPPED = "AGILE_BOARD_STATE_NOT_MAPPED"
        const val AGILE_COLUMN_STATE_AMBIGUOUS = "AGILE_COLUMN_STATE_AMBIGUOUS"
        const val AGILE_STATE_ALREADY_MAPPED = "AGILE_STATE_ALREADY_MAPPED"
        const val AGILE_CONFLICT = "AGILE_CONFLICT"
        const val AGILE_QUICK_FILTER_NAME_CONFLICT = "AGILE_QUICK_FILTER_NAME_CONFLICT"
        const val AGILE_QUICK_FILTER_LIMIT_EXCEEDED = "AGILE_QUICK_FILTER_LIMIT_EXCEEDED"
        const val AGILE_QUICK_FILTER_NOT_FOUND = "AGILE_QUICK_FILTER_NOT_FOUND"
        const val AGILE_QUICK_FILTER_EMPTY_QUERY = "AGILE_QUICK_FILTER_EMPTY_QUERY"
        const val AGILE_UNPROCESSABLE = "AGILE_UNPROCESSABLE"
        const val AGILE_UNAVAILABLE = "AGILE_UNAVAILABLE"
        const val AGILE_INTERNAL_ERROR = "AGILE_INTERNAL_ERROR"
    }
}
