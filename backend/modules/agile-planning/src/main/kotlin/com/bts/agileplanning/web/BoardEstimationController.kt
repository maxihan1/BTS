// 보드 설정 「추정」 탭 REST 컨트롤러 — 시간 추적 PATCH (부채 177 · J36·J37)

package com.bts.agileplanning.web

import com.bts.agileplanning.application.EstimationBoardNotFoundException
import com.bts.agileplanning.application.EstimationSettingsService
import com.bts.agileplanning.repository.BoardRepository
import com.bts.agileplanning.web.dto.DataResponse
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * 추정 탭 요청 바디.
 *
 * @property timeTracking `"NONE"` 또는 `"REMAINING_AND_SPENT"`(J36). **허용값 판정은 DTO 가 지지 않는다** —
 *   [com.bts.agileplanning.application.TimeTracking.from] 한 곳에 모은다. DTO 의 `@field:NotBlank` 는
 *   1차 방어일 뿐이다(memory: patch-merge-domain-bypass · decorative-annotation-copied-from-sibling).
 */
data class EstimationSettingsRequest(
    @field:NotBlank
    val timeTracking: String,
)

/**
 * 추정 탭 응답 바디.
 *
 * @property timeTracking 저장된 시간 추적 값.
 */
data class EstimationSettingsResponse(
    val timeTracking: String,
)

/**
 * 보드 설정 「추정」 탭 REST 컨트롤러 — agile-planning BC (부채 177).
 *
 * 엔드포인트.
 * - PATCH `/api/v1/boards/{boardId}/estimation` — 시간 추적 갱신. 권한 [IssuePermission.CREATE].
 *
 * ## 왜 [BoardController] 가 아닌가 (부채 157 · 스펙 C-1)
 * `BoardController.kt` 는 이미 651줄에 엔드포인트 11개다. 설정 4탭을 더하면 800줄이 된다.
 * Task 7 이 `BoardRepository.kt` 를 안 키우려고 리포지터리를 뺀 것과 **같은 이유가 컨트롤러에도
 * 그대로 적용된다.** [BoardQuickFilterController] · [SprintBurndownController] 가 이 BC 의 선례다.
 *
 * ## 권한 게이트 — 순서를 기존 경로와 같게
 * actor 추출(401) → 보드 메타 조회(404) → CREATE 권한(403) → 서비스 위임.
 * [BoardQuickFilterController.requireCreateAccess] 와 **같은 순서**다. 존재 확인과 권한 확인의 순서가
 * 뒤집히면 403/404 의미가 뒤바뀌고, 로컬은 항상 허용이라 그 사고가 안 보인다
 * (memory: permission-assert-before-existence-makes-403-lie).
 *
 * 권한코드는 **CREATE** 다. 설정 화면의 다른 쓰기 경로(`PATCH /boards/{id}` ·
 * `PATCH /boards/{id}/columns/{columnId}` · 퀵필터 CRUD)가 전부 CREATE 를 쓰고,
 * 화면 자체도 `permissions.CREATE` 로 편집 가능 여부를 정한다
 * (`apps/web/src/routes/projects.$projectKey.board.settings.tsx:103`). 새 권한 축을 만들지 않는다.
 *
 * ## 예외 → HTTP
 * ★[BoardExceptionHandler] 의 `assignableTypes` 에 **이 컨트롤러는 없다.** 그 파일은 Task 8·10·13 과
 * 공유하는 자원이라 이 task 가 건드리지 않았다. 그래서 이 경로의 예외는 전부
 * [ResponseStatusException] 계열이다 — 전용 advice 없이도 **상태 코드가 그대로 전파**된다
 * (형제 `SprintExceptions.kt` 가 같은 규약을 KDoc 에 적어 뒀다). 대가는 RFC 7807 바디의
 * `errorCode` 가 붙지 않는다는 것이고, 그것을 붙이려면 공유 핸들러에 항목을 더해야 한다.
 *
 * ## 트랜잭션
 * 컨트롤러는 트랜잭션 경계를 담당하지 않는다. [EstimationSettingsService] 가 개시한다.
 *
 * @param service 시간 추적 저장 유스케이스.
 * @param boardRepository 권한 scope 산출(projectKey)과 404 판정용.
 * @param permissionResolver cross-BC 권한 판정 포트(fail-closed, non-null 주입).
 */
@RestController
@RequestMapping("/api/v1/boards/{boardId}/estimation")
class BoardEstimationController(
    private val service: EstimationSettingsService,
    private val boardRepository: BoardRepository,
    private val permissionResolver: IssuePermissionResolver,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 시간 추적 설정을 갱신한다 (J36 · J37).
     *
     * @param boardId path variable 보드 UUID.
     * @param request 요청 바디.
     * @return 200 OK + [EstimationSettingsResponse].
     * @throws ResponseStatusException 401 — 미인증.
     * @throws EstimationBoardNotFoundException 404 — 보드 미존재 또는 소프트 삭제.
     * @throws ResponseStatusException 403 — CREATE 권한 미충족.
     * @throws com.bts.agileplanning.application.TimeTrackingBoardNotScrumException 409 — 칸반 보드(E5).
     * @throws com.bts.agileplanning.application.TimeTrackingInvalidException 400 — 허용값 밖.
     */
    @PatchMapping
    fun updateEstimation(
        @PathVariable boardId: UUID,
        @Valid @RequestBody request: EstimationSettingsRequest,
    ): ResponseEntity<DataResponse<EstimationSettingsResponse>> {
        log.info("BoardEstimationController.updateEstimation boardId={}", boardId)

        requireCreateAccess(boardId)
        val saved = service.updateTimeTracking(boardId, request.timeTracking)
        return ResponseEntity.ok(DataResponse(EstimationSettingsResponse(saved)))
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * actor 추출 → 보드 메타 조회(404) → CREATE 권한 판정(403) 을 한 순서로 수행한다.
     *
     * 보드를 여기서 한 번 읽고 [EstimationSettingsService] 가 자기 트랜잭션 안에서 **다시 읽는다.**
     * 중복 조회가 아니라 역할이 다르다 — 여기서 읽는 이유는 권한 scope(projectKey) 산출이고,
     * 종류(스크럼/칸반) 판정은 쓰기와 같은 트랜잭션 안에 있어야 한다.
     * [BoardQuickFilterController] 도 같은 모양이다.
     *
     * @param boardId 접근할 보드 UUID.
     * @return 인증 주체 UUID.
     */
    private fun requireCreateAccess(boardId: UUID): UUID {
        val actor = currentActorId()
        val board = boardRepository.findById(boardId) ?: throw EstimationBoardNotFoundException()
        if (!permissionResolver.hasPermission(actor, IssuePermission.CREATE, IssueScope.Project(board.projectKey))) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "접근 권한이 없습니다.")
        }
        return actor
    }

    /**
     * [SecurityContextHolder] 에서 인증 주체 UUID 를 추출한다.
     *
     * actor 추출은 리소스 조회보다 **먼저** 해야 한다 — 미인증자가 404/409 로 보드의 존재와 종류를
     * 알아내는 probe 를 막는다(memory: auth-extraction-before-resource-lookup).
     * [BoardQuickFilterController] · [SprintBurndownController] 가 쓰는 같은 헬퍼를 복제한다 —
     * 이 task 의 허용 파일이 이 파일로 한정돼 공용 컴포넌트로 뽑지 않는다.
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
