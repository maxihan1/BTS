// 즐겨찾기 CRUD REST API 컨트롤러 — POST·DELETE·GET 엔드포인트 (FR-UX-02)

package com.bts.notification.favorite.web

import com.bts.notification.favorite.application.FavoriteService
import com.bts.notification.favorite.web.dto.CreateFavoriteRequest
import com.bts.notification.favorite.web.dto.FavoriteListResponse
import com.bts.notification.favorite.web.dto.FavoriteResponse
import com.bts.notification.web.DataResponse
import com.bts.notification.web.currentActorId
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * 즐겨찾기 CRUD REST API 컨트롤러.
 *
 * 엔드포인트 목록.
 * - POST   /api/v1/favorites                          — 즐겨찾기 등록 (201 신규 / 200 멱등)
 * - DELETE /api/v1/favorites?targetType=&targetId=   — 즐겨찾기 삭제 (204 멱등)
 * - GET    /api/v1/favorites[?targetType=]            — 즐겨찾기 목록 조회 (200)
 *
 * 인증 정책: currentActorId() 헬퍼로 SecurityContext 에서 UUID 주체를 추출한다.
 * 미인증·비-UUID 주체는 401 로 거부한다.
 * actor 추출은 리소스 조회보다 앞서 수행해 미인증자가 리소스 존재를 probe 하지 못하게 한다
 * (memory: auth-extraction-before-resource-lookup 교훈).
 *
 * @param service 즐겨찾기 CRUD 서비스
 */
@RestController
@RequestMapping("/api/v1/favorites")
class FavoriteController(
    private val service: FavoriteService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 즐겨찾기를 등록한다.
     *
     * 동일한 (userId, targetType, targetId) 가 이미 존재하면 200 으로 멱등 응답한다.
     * 신규 등록이면 201 을 반환한다.
     *
     * @param request 등록 요청 바디 (targetType, targetId)
     * @return 201 Created(신규) 또는 200 OK(멱등) + FavoriteResponse
     * @throws com.bts.notification.favorite.domain.FavoriteDomainException 타입·식별자 검증 실패 -> 400 (ExceptionHandler 처리)
     */
    @PostMapping
    fun add(
        @RequestBody request: CreateFavoriteRequest,
    ): ResponseEntity<DataResponse<FavoriteResponse>> {
        val actorId = currentActorId()

        log.info("FavoriteController.add actorId={}, targetType={}, targetId={}", actorId, request.targetType, request.targetId)

        val result = service.addFavorite(actorId, request.targetType, request.targetId)
        val status = if (result.created) HttpStatus.CREATED else HttpStatus.OK
        return ResponseEntity
            .status(status)
            .body(DataResponse(data = FavoriteResponse.from(result.favorite)))
    }

    /**
     * 즐겨찾기를 삭제한다.
     *
     * 대상이 존재하지 않아도 예외 없이 204 를 반환한다 (멱등).
     *
     * @param targetType 삭제 대상 타입 문자열
     * @param targetId 삭제 대상 식별자
     * @throws com.bts.notification.favorite.domain.FavoriteDomainException targetType 무효 -> 400 (ExceptionHandler 처리)
     */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun remove(
        @RequestParam targetType: String,
        @RequestParam targetId: String,
    ) {
        val actorId = currentActorId()

        log.info("FavoriteController.remove actorId={}, targetType={}, targetId={}", actorId, targetType, targetId)

        service.removeFavorite(actorId, targetType, targetId)
    }

    /**
     * 본인의 즐겨찾기 목록을 조회한다.
     *
     * targetType 파라미터로 타입 필터링이 가능하다. 지정하지 않으면 전체를 반환한다.
     * 결과는 created_at DESC 정렬이다.
     *
     * @param targetType 필터할 대상 타입 문자열 (선택)
     * @return 200 OK + FavoriteListResponse(items)
     * @throws com.bts.notification.favorite.domain.FavoriteDomainException targetType 지정 시 무효 -> 400 (ExceptionHandler 처리)
     */
    @GetMapping
    fun list(
        @RequestParam(required = false) targetType: String?,
    ): ResponseEntity<DataResponse<FavoriteListResponse>> {
        val actorId = currentActorId()

        log.debug("FavoriteController.list actorId={}, targetType={}", actorId, targetType)

        val favorites = service.listFavorites(actorId, targetType)
        val response = FavoriteListResponse(items = favorites.map { FavoriteResponse.from(it) })
        return ResponseEntity.ok(DataResponse(data = response))
    }
}
