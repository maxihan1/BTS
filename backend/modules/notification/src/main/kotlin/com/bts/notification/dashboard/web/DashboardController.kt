// 대시보드 CRUD REST API 컨트롤러 — POST·GET 목록·GET 단건·PATCH·DELETE 엔드포인트 (FR-DB-01)

package com.bts.notification.dashboard.web

import com.bts.notification.dashboard.application.DashboardService
import com.bts.notification.dashboard.domain.DashboardDomainException
import com.bts.notification.dashboard.domain.DashboardVisibility
import com.bts.notification.dashboard.web.dto.CreateDashboardRequest
import com.bts.notification.dashboard.web.dto.DashboardPageResponse
import com.bts.notification.dashboard.web.dto.DashboardResponse
import com.bts.notification.dashboard.web.dto.PatchDashboardRequest
import com.bts.notification.web.DataResponse
import com.bts.notification.web.currentActorId
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 대시보드 CRUD REST API 컨트롤러.
 *
 * 엔드포인트 목록.
 * - POST   /api/v1/dashboards           — 대시보드 생성 (201)
 * - GET    /api/v1/dashboards?limit&offset — 접근 가능 목록 (200)
 * - GET    /api/v1/dashboards/{id}      — 단건 조회 (200 / 404)
 * - PATCH  /api/v1/dashboards/{id}      — 부분 수정 (200 / 400 / 403 / 404 / 409)
 * - DELETE /api/v1/dashboards/{id}      — 소프트 삭제 (204 / 403 / 404)
 *
 * 인증 정책: currentActorId() 헬퍼로 SecurityContext 에서 UUID 주체를 추출한다.
 * 미인증·비-UUID 주체는 401 로 거부한다.
 * actor 추출은 리소스 조회보다 앞서 수행해 미인증자가 리소스 존재를 probe 하지 못하게 한다
 * (memory: auth-extraction-before-resource-lookup 교훈).
 *
 * @param service 대시보드 CRUD 서비스
 */
@RestController
@RequestMapping("/api/v1/dashboards")
class DashboardController(
    private val service: DashboardService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 새 대시보드를 생성한다.
     *
     * @param request 생성 요청 바디 (Jakarta Validation 적용)
     * @return 201 Created + DashboardResponse
     * @throws DashboardDomainException 도메인 불변식 위반 -> 400 (ExceptionHandler 처리)
     */
    @PostMapping
    fun create(
        @RequestBody request: CreateDashboardRequest,
    ): ResponseEntity<DataResponse<DashboardResponse>> {
        val actorId = currentActorId()

        val visibility = parseVisibility(request.visibility)

        log.info("DashboardController.create actorId={}, visibility={}", actorId, visibility)

        val dashboard =
            service.create(
                actorId = actorId,
                name = request.name,
                description = request.description,
                visibility = visibility,
                layout = request.layout ?: "[]",
                sharedUserIds = request.sharedUserIds?.toSet() ?: emptySet(),
            )

        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(DataResponse(data = DashboardResponse.from(dashboard)))
    }

    /**
     * actorId 기준 접근 가능 대시보드 목록을 페이지네이션으로 반환한다.
     *
     * 접근 범위 = owned UNION shared-to-me(TEAM) UNION ORG.
     * limit 기본 50, 상한 100 (Service 레이어에서 클램프).
     *
     * @param limit 페이지 크기 (기본 50)
     * @param offset 건너뛸 항목 수 (기본 0)
     * @return 200 OK + DashboardPageResponse
     */
    @GetMapping
    fun list(
        @RequestParam(defaultValue = "50") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
    ): ResponseEntity<DataResponse<DashboardPageResponse>> {
        val actorId = currentActorId()

        log.debug("DashboardController.list actorId={}, limit={}, offset={}", actorId, limit, offset)

        val page = service.list(actorId, limit, offset)
        val response =
            DashboardPageResponse(
                items = page.items.map { DashboardResponse.from(it) },
                total = page.total,
                limit = limit,
                offset = offset,
            )

        return ResponseEntity.ok(DataResponse(data = response))
    }

    /**
     * 단건 대시보드를 조회한다.
     *
     * 존재하지 않거나 접근 불가이면 404 (존재 숨김 정책).
     *
     * @param id 대시보드 식별자
     * @return 200 OK + DashboardResponse / 404
     */
    @GetMapping("/{id}")
    fun getOne(
        @PathVariable id: UUID,
    ): ResponseEntity<DataResponse<DashboardResponse>> {
        val actorId = currentActorId()

        log.debug("DashboardController.getOne actorId={}, id={}", actorId, id)

        val dashboard = service.get(actorId, id)
        return ResponseEntity.ok(DataResponse(data = DashboardResponse.from(dashboard)))
    }

    /**
     * 대시보드를 부분 수정한다.
     *
     * version 이 불일치하면 409(OCC 충돌).
     * 비소유자이면 403.
     * 도메인 불변식 위반이면 400.
     *
     * @param id 수정 대상 대시보드 식별자
     * @param request 부분 수정 요청 바디 (version 필수)
     * @return 200 OK + DashboardResponse / 400 / 403 / 404 / 409
     */
    @PatchMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @RequestBody request: PatchDashboardRequest,
    ): ResponseEntity<DataResponse<DashboardResponse>> {
        val actorId = currentActorId()

        log.info("DashboardController.update actorId={}, id={}, version={}", actorId, id, request.version)

        val visibility = request.visibility?.let { parseVisibility(it) }

        val updated =
            service.update(
                actorId = actorId,
                id = id,
                name = request.name,
                description = request.description,
                visibility = visibility,
                layout = request.layout,
                sharedUserIds = request.sharedUserIds?.toSet(),
                version = request.version,
            )

        return ResponseEntity.ok(DataResponse(data = DashboardResponse.from(updated)))
    }

    /**
     * 대시보드를 소프트 삭제한다.
     *
     * 소유자가 아닌 경우 403.
     * 존재하지 않는 경우 404.
     *
     * @param id 삭제 대상 대시보드 식별자
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @PathVariable id: UUID,
    ) {
        val actorId = currentActorId()
        log.info("DashboardController.delete actorId={}, id={}", actorId, id)
        service.delete(actorId, id)
    }

    /**
     * visibility 문자열을 DashboardVisibility enum 으로 파싱한다.
     *
     * 알 수 없는 값이면 DashboardDomainException -> 400 (ExceptionHandler 처리).
     *
     * @param value 파싱할 문자열
     * @return DashboardVisibility 인스턴스
     * @throws DashboardDomainException 알 수 없는 visibility 값
     */
    private fun parseVisibility(value: String): DashboardVisibility =
        runCatching { DashboardVisibility.valueOf(value) }
            .getOrElse {
                throw DashboardDomainException("알 수 없는 visibility 값입니다. 허용 값: PRIVATE, TEAM, ORG")
            }
}
