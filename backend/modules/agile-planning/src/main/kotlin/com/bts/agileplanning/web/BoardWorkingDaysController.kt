// 보드 설정 「작업일」 탭 REST 컨트롤러 — 근무일·비근무일·타임존 PUT (부채 177 Task 10)

package com.bts.agileplanning.web

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
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.util.UUID

/**
 * 작업일 탭 저장 요청 바디.
 *
 * ## ★ `standardDays` 의 null 과 `[]` 는 다른 뜻이다 (스펙 R6)
 * **null(또는 키 생략) = 미설정 = 달력일 전부**이고 200 이다. **`[]` = 근무일 0개**라 400 이다.
 * 두 값을 뭉개는 클라이언트는 「근무일을 안 쓰겠다」는 뜻으로 `[]` 를 보내는데, 그것은
 * 번다운 ideal 선의 0 나눗셈이다(E1). 근무일을 쓰지 않으려면 **값을 비우지 말고 키를 빼라.**
 *
 * Bean Validation 어노테이션을 달지 않는다 — 허용값 판정을 [WorkingDaysSettingsService] 한 곳에
 * 모아 두 자리가 갈리지 않게 한다([com.bts.agileplanning.web.dto.CreateBoardRequest.boardType] 과
 * 같은 판단). 위반은 named exception 으로 400 이 된다.
 *
 * @property standardDays 표준 근무일 요일 키(`MON`..`SUN`). **null = 미설정**(≠ 빈 리스트).
 * @property nonWorkingDates 비근무일(ISO `yyyy-MM-dd`). 중복은 서버가 제거한다.
 *   키를 생략하면 **기존 비근무일이 전부 지워진다** — PUT 은 교체이지 부분 갱신이 아니다.
 * @property timezone IANA 타임존. null = 미설정(UTC).
 */
data class WorkingDaysRequest(
    val standardDays: List<String>? = null,
    val nonWorkingDates: List<LocalDate> = emptyList(),
    val timezone: String? = null,
)

/**
 * 작업일 탭 응답 바디 — **정규화된 뒤의** 실제 저장값이다.
 *
 * 요청과 응답이 다를 수 있다. 중복 비근무일은 제거되고 요일은 주 순서로 정렬된다.
 * 되돌려주는 이유가 그것이다 — 사용자가 자기가 무엇을 저장했는지 알아야 한다.
 *
 * @property standardDays 저장된 표준 근무일. **null 이면 미설정 = 달력일 전부**(R6).
 * @property nonWorkingDates 저장된 비근무일(오름차순 · 중복 없음).
 * @property timezone 저장된 IANA 타임존. null 이면 미설정(UTC).
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
 * 권한코드는 설정 4탭 공통인 [IssuePermission.CREATE] 다(부채 177 Task 29 가 통일했다).
 * 보드 단위 관리자 권한이 BTS 에 없어 프로젝트 단위로 갈음한다(편차 X8). 화면이
 * `permissions.CREATE` 하나로 편집 UI 를 열기 때문에(`settings.tsx:103`), 이 탭만 다른 권한코드를
 * 요구하면 CREATE 만 가진 사용자가 편집 UI 를 보고 403 을 맞는다.
 *
 * ### 예외 매핑
 * [BoardExceptionHandler] 의 `assignableTypes` 가 이 컨트롤러를 포함한다(부채 177 Task 29).
 * 이 파일에 있던 전용 advice 는 걷어냈다 — 형제 탭 셋과 봉투가 갈리는 원인이었고, 같은 요청에
 * advice 가 둘이면 어느 쪽이 이기는지도 불명확했다. [WorkingDaysInvalidException] 과
 * [com.bts.agileplanning.application.WorkingDaysBoardNotFoundException] 매핑은 그 advice 로 옮겼다.
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
     * ★**미설정(NULL) = 달력일 전부다.** `standardDays` 를 보내지 않거나 null 로 보내면 200 이고
     * `working_days` 에 SQL NULL 이 들어간다 — 그 보드의 번다운은 x축도 ideal 선의 분모도
     * **달력일 수** 그대로다(R6). 「근무일 0개(`[]`)」와 뜻이 다르고 그쪽은 400 이다(E1).
     * 판정의 정본과 근거는 [WorkingDaysSettingsService] KDoc 에 있다.
     *
     * @param boardId path variable 대상 보드 UUID.
     * @param request 저장 요청 바디.
     * @return 200 OK + 정규화된 [WorkingDaysResponse].
     * @throws BoardNotFoundException 404 — 보드 미존재 또는 soft-deleted.
     * @throws BoardAccessDeniedException 403 — CREATE 권한 미충족.
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
        val scope = IssueScope.Project(board.projectKey)
        if (!permissionResolver.hasPermission(actor, IssuePermission.CREATE, scope)) {
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
