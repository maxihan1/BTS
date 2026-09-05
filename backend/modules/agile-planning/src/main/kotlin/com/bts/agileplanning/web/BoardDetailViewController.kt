// 보드 상세 보기 필드 구성 REST 컨트롤러 — 그룹 4종 조회 + 그룹 단위 PATCH (부채 177 Task 13)

package com.bts.agileplanning.web

import com.bts.agileplanning.application.DetailViewSettingsService
import com.bts.agileplanning.repository.BoardRepository
import com.bts.agileplanning.web.dto.DataResponse
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * 상세 보기 구성 PATCH 요청 바디 (J47 · J48).
 *
 * 스펙 §API 의 `detailViewFields` 와 같은 모양이다 — 그룹 키에 필드 키 목록을 단다.
 *
 * @property groups `fieldGroup → 필드 키 목록`. **요청에 없는 그룹은 건드리지 않는다**(PATCH 부분 갱신).
 *   목록의 순서가 곧 그룹 안 표시 순서다(J48 의 드래그 결과). 빈 목록은 그 그룹을 비운다.
 */
data class DetailViewFieldsPatchRequest(
    val groups: Map<String, List<String>>,
)

/**
 * 상세 보기 구성 응답 바디.
 *
 * @property groups `fieldGroup → 필드 키 목록`. 구성이 없는 그룹도 **빈 목록으로 항상 실린다** —
 *   모달과 사이드패널이 키 부재를 따로 다루지 않게 하기 위해서다(R7c).
 */
data class DetailViewFieldsResponse(
    val groups: Map<String, List<String>>,
)

/**
 * 보드 상세 보기 필드 구성 REST 컨트롤러 — agile-planning BC (부채 177 · R7 · J46~J49).
 *
 * 이슈 상세 화면이 어떤 필드를 어느 그룹에 어떤 순서로 보여줄지를 **보드 단위**로 정한다(J46).
 * 모달과 사이드패널 두 표현이 같은 구성을 읽는다(R7c).
 *
 * 엔드포인트 목록.
 * - GET   `/api/v1/boards/{boardId}/detail-view-fields` — 조회. 권한 [IssuePermission.BROWSE].
 * - PATCH `/api/v1/boards/{boardId}/detail-view-fields` — 그룹 단위 교체. 권한 [IssuePermission.SOFT_DELETE].
 *
 * ### 왜 [BoardController] 가 아니라 별도 컨트롤러인가 (스펙 C-1 · 부채 157)
 * `BoardController.kt` 는 이미 651줄에 엔드포인트 11개다. 설정 4탭을 거기 얹으면 800줄이 된다 —
 * Task 7 이 `BoardRepository.kt` 를 안 키우려고 리포지터리를 뺀 것과 **같은 이유가 컨트롤러에도**
 * 그대로 적용된다. 이 BC 에는 [BoardQuickFilterController]·[SprintBurndownController]·
 * [SprintVelocityController] 라는 선례가 이미 셋 있고, 이 컨트롤러는 그중
 * [BoardQuickFilterController] 의 경로 규칙·권한 게이트를 그대로 따른다.
 *
 * ### ★편차 X8 — 보드 단위 권한이 없어 프로젝트 권한으로 갈음한다
 * J49 는 이 화면을 *"a user with the **Jira admin** or a **board admin** permissions"* 로 제한한다.
 * BTS 에는 **보드 단위 권한 모델이 없다**(`#450` 이 T3 이연으로 등재). 그래서 쓰기는 보드 설정 변경과
 * 같은 무게의 프로젝트 권한인 [IssuePermission.SOFT_DELETE] 로 갈음한다 —
 * [BoardCardLayoutController](Task 8)가 타는 게이트와 **같은 것**이고, 같은 wave 에서 서로를 보지 않고
 * 작성했는데도 권한코드·스코프·검사 순서가 일치했다. 4탭이 서로 다른 권한을 타면 「보드 설정」이라는
 * 한 화면 안에서 탭마다 403 이 갈린다.
 *
 * ★**타 BC 의 멤버십 role 을 직접 조회하지 않는다.** 권한 판정은 권한코드 + [IssuePermissionResolver]
 * 창구만 쓴다(FR-PM-07). 보드 단위 권한이 생기면 갈음을 걷어낼 자리는 [requireBoardAccess] 한 곳이다.
 *
 * 읽기는 [IssuePermission.BROWSE] 다 — 보드를 볼 수 있으면 그 보드의 상세 보기 구성도 볼 수 있어야
 * 이슈 상세 화면이 그려진다(R7c). 쓰기 권한을 읽기에까지 요구하면 일반 사용자의 이슈 상세가 깨진다.
 *
 * ### ★예외 매핑 — 이 컨트롤러는 [BoardExceptionHandler] 에 **덮이지 않는다**
 * 그 advice 의 `assignableTypes` 는 [BoardController]·[BoardQuickFilterController] 뿐이고, 그 파일은
 * T8·T9·T10·T13 이 공유하는 자원이라 이 task 가 넓히지 않는다(계획 §탭별 컨트롤러 — 「자의로 고치지
 * 말고 보고하라」). 그래서 오류 경로를 전부 [ResponseStatusException](또는 그것을 상속한 도메인 예외)로
 * 낸다 — Spring 의 `ResponseStatusExceptionResolver` 가 advice 없이도 상태를 그대로 내보내므로
 * catch-all 500 변질이 일어나지 않는다.
 * [BoardAccessDeniedException]·[BoardNotFoundException](평범한 `RuntimeException`)을 쓰면 이 컨트롤러에서는
 * **500 이 된다** — 나중에 `assignableTypes` 에 이 클래스를 더한 뒤에야 바꿀 수 있다.
 *
 * ★이것은 T8·T9·T10 과 **공유하는 미결 사항**이다. [BoardCardLayoutController] 도 같은 이유로 같은
 * 우회를 택했다. 넷이 합류할 때 `assignableTypes` 에 네 컨트롤러를 더하면 `handleResponseStatus` 가
 * 이 예외들을 받아 `AGILE_` 봉투로 통일된다 — 그때 이 우회 주석도 함께 걷어낸다.
 *
 * ### 트랜잭션 정책
 * 컨트롤러는 트랜잭션 경계를 담당하지 않는다. 트랜잭션은 [DetailViewSettingsService] 가 개시한다.
 *
 * @param service 상세 보기 구성 유스케이스 서비스.
 * @param boardRepository 보드 메타(projectKey) 조회용. 권한 scope 산출과 404 판정에 사용한다.
 * @param permissionResolver cross-BC 권한 판정 포트(fail-closed, non-null 주입).
 */
@RestController
@RequestMapping("/api/v1/boards/{boardId}/detail-view-fields")
class BoardDetailViewController(
    private val service: DetailViewSettingsService,
    private val boardRepository: BoardRepository,
    private val permissionResolver: IssuePermissionResolver,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 상세 보기 구성을 조회한다.
     *
     * @param boardId path variable 보드 UUID.
     * @return 200 OK + [DetailViewFieldsResponse]. 그룹 4종이 항상 실린다.
     * @throws ResponseStatusException 401 — 미인증.
     * @throws ResponseStatusException 404 — 보드 미존재 또는 soft-deleted.
     * @throws ResponseStatusException 403 — BROWSE 권한 미충족.
     */
    @GetMapping
    fun getFields(
        @PathVariable boardId: UUID,
    ): ResponseEntity<DataResponse<DetailViewFieldsResponse>> {
        log.info("BoardDetailViewController.getFields boardId={}", boardId)

        requireBoardAccess(boardId, IssuePermission.BROWSE)
        return ResponseEntity.ok(DataResponse(DetailViewFieldsResponse(service.findFields(boardId))))
    }

    /**
     * 요청에 실린 그룹의 구성을 교체한다.
     *
     * @param boardId path variable 보드 UUID.
     * @param request 교체 요청 바디. 요청에 없는 그룹은 그대로 남는다.
     * @return 200 OK + 교체 후 전체 구성.
     * @throws ResponseStatusException 401 — 미인증.
     * @throws ResponseStatusException 404 — 보드 미존재 또는 soft-deleted.
     * @throws ResponseStatusException 403 — SOFT_DELETE 권한 미충족(편차 X8).
     * @throws com.bts.agileplanning.application.DetailViewFieldGroupInvalidException 400 — 미지원 그룹.
     */
    @PatchMapping
    fun patchFields(
        @PathVariable boardId: UUID,
        @RequestBody request: DetailViewFieldsPatchRequest,
    ): ResponseEntity<DataResponse<DetailViewFieldsResponse>> {
        log.info("BoardDetailViewController.patchFields boardId={} groups={}", boardId, request.groups.keys)

        requireBoardAccess(boardId, IssuePermission.SOFT_DELETE)
        val fields = service.replaceGroups(boardId, request.groups)
        return ResponseEntity.ok(DataResponse(DetailViewFieldsResponse(fields)))
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * actor 추출 → 보드 메타 조회(404) → 권한 판정(403)을 **이 순서로** 수행한다.
     *
     * ★순서가 곧 의미다. 권한을 먼저 보면 없는 보드에 403 이 나가 「있는데 권한이 없다」로 읽히고,
     * 반대로 존재를 흘리면 권한 없는 사용자가 보드 존재를 probe 할 수 있다. 기존 경로
     * ([BoardController] 의 `loadBoardWithCreate` · [BoardQuickFilterController] 의 `requireCreateAccess`)와
     * **같은 순서**를 유지한다 — 로컬 개발 환경은 권한이 항상 허용이라 이 뒤집힘이 눈에 보이지 않는다.
     *
     * @param boardId 접근할 보드 UUID.
     * @param permission 판정할 권한코드. 읽기는 BROWSE, 쓰기는 SOFT_DELETE(편차 X8).
     * @throws ResponseStatusException 401 — 미인증.
     * @throws ResponseStatusException 404 — 보드 미존재 또는 soft-deleted.
     * @throws ResponseStatusException 403 — 권한 미충족.
     */
    private fun requireBoardAccess(
        boardId: UUID,
        permission: IssuePermission,
    ) {
        val actor = currentActorId()
        val board =
            boardRepository.findById(boardId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "보드를 찾을 수 없습니다.")
        if (!permissionResolver.hasPermission(actor, permission, IssueScope.Project(board.projectKey))) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "권한이 없습니다.")
        }
    }

    /**
     * [SecurityContextHolder] 에서 인증 주체 UUID 를 추출한다.
     *
     * actor 추출은 리소스 조회보다 먼저 수행해야 한다(미인증자의 존재 probe 차단,
     * memory: auth-extraction-before-resource-lookup 교훈). [BoardQuickFilterController] 의 동일 헬퍼를
     * 복제한다 — 이 task 의 허용 파일이 이 파일 하나라 컨트롤러 간 공유 컴포넌트로 추출하지 않는다.
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
