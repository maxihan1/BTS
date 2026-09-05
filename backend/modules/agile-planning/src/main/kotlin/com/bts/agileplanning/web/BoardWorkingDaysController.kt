// 보드 설정 「작업일」 탭 REST 컨트롤러 + 전용 예외 핸들러 (부채 177 Task 10)

package com.bts.agileplanning.web

import com.bts.agileplanning.application.WorkingDaysBoardNotFoundException
import com.bts.agileplanning.application.WorkingDaysInvalidException
import com.bts.agileplanning.application.WorkingDaysSettingsService
import com.bts.agileplanning.repository.BoardRepository
import com.bts.agileplanning.repository.BoardWorkingDays
import com.bts.agileplanning.web.dto.DataResponse
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * 작업일 탭 저장 요청 바디.
 *
 * @property standardDays 표준 근무일 요일 키(`MON`..`SUN`).
 * @property nonWorkingDates 비근무일(ISO `yyyy-MM-dd`).
 * @property timezone IANA 타임존.
 */
data class WorkingDaysRequest(
    val standardDays: List<String>? = null,
    val nonWorkingDates: List<LocalDate> = emptyList(),
    val timezone: String? = null,
)

/**
 * 작업일 탭 응답 바디.
 *
 * @property standardDays 저장된 표준 근무일. null 이면 미설정이다.
 * @property nonWorkingDates 저장된 비근무일(오름차순 · 중복 없음).
 * @property timezone 저장된 IANA 타임존. null 이면 미설정(UTC)이다.
 */
data class WorkingDaysResponse(
    val standardDays: List<String>?,
    val nonWorkingDates: List<LocalDate>,
    val timezone: String?,
) {
    companion object {
        /** 저장 결과를 응답 모양으로 옮긴다. */
        fun from(source: BoardWorkingDays): WorkingDaysResponse =
            WorkingDaysResponse(
                standardDays = source.standardDays,
                nonWorkingDates = source.nonWorkingDates,
                timezone = source.timezone,
            )
    }
}

/**
 * 보드 설정 「작업일」 탭 REST 컨트롤러 — agile-planning BC (부채 177 · R5 · J38·J39·J40).
 *
 * ### 왜 [BoardController] 가 아니라 별도 파일인가
 * `BoardController.kt` 는 이미 651줄 · 엔드포인트 11개로 부채 157(줄수 상한)에 걸려 있다(스펙 C-1).
 * 4탭을 그 파일에 더하면 800줄이 되고, 네 탭이 병렬로 같은 파일을 고치면 나중에 쓴 쪽이 앞선 것을
 * 덮는다. [BoardQuickFilterController] · [SprintBurndownController] 가 같은 이유로 이미 갈라져 있다.
 *
 * ### 엔드포인트
 * - PUT `/api/v1/boards/{boardId}/working-days` — 세 값 통째 교체.
 *
 * PATCH 가 아니라 **PUT** 인 이유는 이 탭의 저장이 부분 갱신이 아니라 **교체**이기 때문이다 —
 * 요청에 없는 비근무일은 지워진다. [BoardController.replaceColumnStates] 와 같은 판단이다.
 *
 * ### 권한 게이트 (R8·R9)
 * actor 추출(401) → 보드 메타 조회(404) → 권한 판정(403) → 서비스 위임. 순서를 기존 경로와
 * 같게 유지한다 — 뒤집으면 403 과 404 의 의미가 갈리고 로컬은 항상 허용이라 눈에 안 띈다.
 * 권한코드는 계획 Task 8 REFACTOR 가 4탭 공통으로 지정한 [IssuePermission.SOFT_DELETE] 다
 * (보드 단위 관리자 권한이 BTS 에 없어 프로젝트 단위로 갈음한다 — 편차 X8).
 *
 * ### 예외 매핑
 * [BoardExceptionHandler] 의 `assignableTypes` 는 [BoardController] · [BoardQuickFilterController]
 * 두 개뿐이라 이 컨트롤러를 덮지 않는다. 그 파일은 Task 8·9·13 과 공유하는 자원이라 건드리지 않고,
 * 대신 [BoardWorkingDaysExceptionHandler] 를 이 파일에 함께 둔다([BacklogExceptionHandler] ·
 * [TimelineExceptionHandler] 와 같은 「컨트롤러 하나에 advice 하나」 관용구).
 *
 * @param service 작업일 저장 유스케이스.
 * @param boardRepository 보드 메타(projectKey) 조회용. 권한 scope 산출과 404 판정에 쓴다.
 * @param permissionResolver cross-BC 권한 판정 포트(fail-closed, non-null 주입).
 */
@RestController
@RequestMapping("/api/v1/boards/{boardId}/working-days")
class BoardWorkingDaysController(
    private val service: WorkingDaysSettingsService,
    private val boardRepository: BoardRepository,
    private val permissionResolver: IssuePermissionResolver,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 작업일 설정 세 값을 통째로 저장한다.
     *
     * @param boardId path variable 대상 보드 UUID.
     * @param request 저장 요청 바디.
     * @return 200 OK + 정규화된 [WorkingDaysResponse].
     * @throws BoardNotFoundException 404 — 보드 미존재 또는 soft-deleted.
     * @throws BoardAccessDeniedException 403 — 권한 미충족.
     * @throws WorkingDaysInvalidException 400 — 근무일 0개 · 미지원 요일 · 비-IANA 타임존.
     */
    @PutMapping
    fun save(
        @PathVariable boardId: UUID,
        @RequestBody request: WorkingDaysRequest,
    ): ResponseEntity<DataResponse<WorkingDaysResponse>> {
        log.info("BoardWorkingDaysController.save boardId={}", boardId)

        requireSettingsAccess(boardId)
        val saved =
            service.save(
                boardId = boardId,
                standardDays = request.standardDays,
                nonWorkingDates = request.nonWorkingDates,
                timezone = request.timezone,
            )
        return ResponseEntity.ok(DataResponse(WorkingDaysResponse.from(saved)))
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * actor 추출 → 보드 메타 조회(404) → 설정 쓰기 권한 판정(403)을 한 순서로 수행한다.
     *
     * @param boardId 접근할 보드 UUID.
     * @throws ResponseStatusException 401 — 미인증.
     * @throws BoardNotFoundException 404 — 보드 미존재 또는 soft-deleted.
     * @throws BoardAccessDeniedException 403 — 권한 미충족.
     */
    private fun requireSettingsAccess(boardId: UUID) {
        val actor = currentActorId()
        val board = boardRepository.findById(boardId) ?: throw BoardNotFoundException()
        if (!permissionResolver.hasPermission(
                actor,
                IssuePermission.SOFT_DELETE,
                IssueScope.Project(board.projectKey),
            )
        ) {
            throw BoardAccessDeniedException()
        }
    }

    /**
     * [SecurityContextHolder] 에서 인증 주체 UUID 를 추출한다.
     *
     * 리소스 조회보다 먼저 수행해야 미인증자의 존재 probe 를 막는다.
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

/**
 * [BoardWorkingDaysController] 전용 예외 → RFC 7807 [ProblemDetail] 변환 핸들러.
 *
 * `assignableTypes` 를 이 컨트롤러 하나로 한정한다 — 형제 advice 의 스코프를 넓히면 남의 컨트롤러
 * 예외를 가로챈다([BacklogExceptionHandler] 와 같은 근거).
 *
 * ★**catch-all `Exception` 핸들러를 두지 않는다.** 두는 순간 프레임워크 예외(401 등)까지 삼켜
 * 500 으로 변질시킬 통로가 열린다. 분류되지 않은 예외는 그대로 500 으로 나가는 편이 낫다
 * (memory: catch-all-exceptionhandler-swallows-responsestatusexception).
 */
@RestControllerAdvice(assignableTypes = [BoardWorkingDaysController::class])
class BoardWorkingDaysExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 작업일 설정 값 위반 — 400 (E1 · J38·J40).
     *
     * @param ex 사유를 담은 검증 예외.
     */
    @ExceptionHandler(WorkingDaysInvalidException::class)
    fun handleInvalid(ex: WorkingDaysInvalidException): ProblemDetail {
        log.info("AGILE_400 working_days_invalid reason='{}'", ex.reason)
        return problem(HttpStatus.BAD_REQUEST, "Validation Failed", AGILE_VALIDATION_FAILED, ex.reason)
    }

    /**
     * 요청 본문 역직렬화 실패 또는 경로 변수 타입 불일치 — 400.
     *
     * 날짜 형식 오류(`"2026-13-45"`)가 여기로 온다. 보안 — 파싱 상세는 로그에만 남긴다.
     *
     * @param ex 역직렬화 실패 또는 타입 불일치 예외.
     */
    @ExceptionHandler(HttpMessageNotReadableException::class, MethodArgumentTypeMismatchException::class)
    fun handleMalformedRequest(ex: Exception): ProblemDetail {
        log.info("AGILE_400 working_days_malformed_request cause='{}'", ex.message)
        return problem(
            HttpStatus.BAD_REQUEST,
            "Validation Failed",
            AGILE_VALIDATION_FAILED,
            "요청 값이 올바르지 않습니다. 날짜 형식(yyyy-MM-dd)과 필드 값을 확인해 주세요.",
        )
    }

    /**
     * 권한 미충족 — 403. 내부 사정(보드 존재 여부·정책)을 노출하지 않는다.
     *
     * @param ex 권한 거부 예외(내부 정보 미포함).
     */
    @ExceptionHandler(BoardAccessDeniedException::class)
    fun handleAccessDenied(
        @Suppress("UnusedParameter") ex: BoardAccessDeniedException,
    ): ProblemDetail {
        log.info("AGILE_403 working_days_access_denied")
        return problem(HttpStatus.FORBIDDEN, "Access Denied", AGILE_ACCESS_DENIED, "이 작업을 수행할 권한이 없습니다.")
    }

    /**
     * 보드 미존재 — 404. 게이트에서 나는 것과 저장 직전 경합으로 나는 것을 같은 코드로 합류시킨다.
     *
     * @param ex 보드 미존재 예외(내부 식별자 미포함).
     */
    @ExceptionHandler(BoardNotFoundException::class, WorkingDaysBoardNotFoundException::class)
    fun handleBoardNotFound(
        @Suppress("UnusedParameter") ex: RuntimeException,
    ): ProblemDetail {
        log.info("AGILE_404 working_days_board_not_found")
        return problem(HttpStatus.NOT_FOUND, "Board Not Found", AGILE_BOARD_NOT_FOUND, "보드를 찾을 수 없습니다.")
    }

    /**
     * [ResponseStatusException] — 명시 상태를 그대로 전파한다(401).
     *
     * `@RestControllerAdvice` 는 Spring 의 ResponseStatusExceptionResolver 보다 먼저 실행되므로
     * 명시 등록해야 응답 모양이 형제 엔드포인트와 갈리지 않는다.
     *
     * @param ex 명시 상태 코드를 보유한 예외.
     */
    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(ex: ResponseStatusException): ProblemDetail {
        val status = HttpStatus.valueOf(ex.statusCode.value())
        log.info("AGILE_{} working_days_response_status", status.value())
        val (errorCode, detail) =
            when (status) {
                HttpStatus.UNAUTHORIZED -> AGILE_UNAUTHENTICATED to "인증이 필요합니다. 세션이 만료되었을 수 있습니다."
                HttpStatus.FORBIDDEN -> AGILE_ACCESS_DENIED to "이 작업을 수행할 권한이 없습니다."
                HttpStatus.NOT_FOUND -> AGILE_BOARD_NOT_FOUND to "보드를 찾을 수 없습니다."
                else -> AGILE_VALIDATION_FAILED to "요청 값이 올바르지 않습니다."
            }
        return problem(status, status.reasonPhrase, errorCode, detail)
    }

    /**
     * RFC 7807 [ProblemDetail] 을 만든다 — 형제 핸들러와 같은 필드 구성.
     */
    private fun problem(
        status: HttpStatus,
        title: String,
        errorCode: String,
        detail: String,
    ): ProblemDetail {
        val pd = ProblemDetail.forStatus(status)
        pd.type = URI.create("https://bts.example.com/problems/agile-working-days")
        pd.title = title
        pd.detail = detail
        pd.setProperty("errorCode", errorCode)
        pd.setProperty("timestamp", Instant.now().toString())
        return pd
    }

    private companion object {
        const val AGILE_VALIDATION_FAILED = "AGILE_VALIDATION_FAILED"
        const val AGILE_UNAUTHENTICATED = "AGILE_UNAUTHENTICATED"
        const val AGILE_ACCESS_DENIED = "AGILE_ACCESS_DENIED"
        const val AGILE_BOARD_NOT_FOUND = "AGILE_BOARD_NOT_FOUND"
    }
}
