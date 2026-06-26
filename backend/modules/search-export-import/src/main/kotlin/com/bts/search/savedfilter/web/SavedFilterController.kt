// 저장된 필터 CRUD REST 컨트롤러 — /api/v1/filters (FR-SR-03 PR2)

package com.bts.search.savedfilter.web

import com.bts.search.savedfilter.application.SavedFilterService
import com.bts.search.savedfilter.domain.SavedFilterShare
import com.bts.search.savedfilter.web.dto.SavedFilterCreateRequest
import com.bts.search.savedfilter.web.dto.SavedFilterResponse
import com.bts.search.savedfilter.web.dto.SavedFilterUpdateRequest
import com.bts.search.savedfilter.web.dto.ShareRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * 저장된 필터 CRUD REST 컨트롤러.
 *
 * 엔드포인트(`/api/v1/filters`).
 * - `POST` 생성 / `GET` 소유 목록 / `GET /shared` 공유받은 목록 / `GET /{id}` 단건 /
 *   `PUT /{id}` 수정 / `DELETE /{id}` 삭제.
 *
 * actor 추출은 [SavedFilterActorExtractor]로 리소스 조회보다 먼저 수행한다(probe 차단).
 * 가시성·소유권 게이트와 예외 발생은 [SavedFilterService]가 담당하고,
 * 예외→HTTP 매핑은 [SavedFilterExceptionHandler]가 처리한다.
 *
 * @param service 저장된 필터 애플리케이션 서비스.
 */
@RestController
@RequestMapping("/api/v1/filters")
class SavedFilterController(
    private val service: SavedFilterService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 새 필터를 생성한다.
     *
     * @param request 생성 요청 바디(shares 포함 가능).
     * @return 201 Created + [SavedFilterResponse].
     */
    @PostMapping
    fun create(
        @RequestBody request: SavedFilterCreateRequest,
    ): ResponseEntity<SavedFilterResponse> {
        val actorId = SavedFilterActorExtractor.extract()
        val name = required(request.name, "name")
        val aqlQuery = required(request.aqlQuery, "aqlQuery")
        val projectKey = required(request.projectKey, "projectKey")
        val domainShares = parseShares(request.shares)
        log.info("SavedFilterController.create actor={} name={} projectKey={}", actorId, name, projectKey)
        val filter = service.create(actorId, name, aqlQuery, projectKey, domainShares)
        val response = SavedFilterResponse.from(filter, domainShares ?: emptyList(), actorId)
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
    }

    /**
     * 내가 소유한 필터 목록을 공유 정보와 함께 반환한다.
     *
     * @return 200 OK + [SavedFilterResponse] 목록.
     */
    @GetMapping
    fun list(): ResponseEntity<List<SavedFilterResponse>> {
        val actorId = SavedFilterActorExtractor.extract()
        val list =
            service.listOwnedWithShares(actorId).map { ws ->
                SavedFilterResponse.from(ws.filter, ws.shares, actorId)
            }
        return ResponseEntity.ok(list)
    }

    /**
     * 나에게 공유된 비소유 필터 목록을 페이지네이션으로 반환한다.
     *
     * @param page 0-based 페이지 번호(기본 0).
     * @param size 페이지 크기(기본 [DEFAULT_PAGE_SIZE], 1..[MAX_PAGE_SIZE] 범위).
     * @return 200 OK + [SavedFilterResponse] 목록.
     */
    @GetMapping("/shared")
    fun listShared(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "$DEFAULT_PAGE_SIZE") size: Int,
    ): ResponseEntity<List<SavedFilterResponse>> {
        val actorId = SavedFilterActorExtractor.extract()
        validatePaging(page, size)
        val list =
            service.listSharedWith(actorId, page, size).map { ws ->
                SavedFilterResponse.from(ws.filter, ws.shares, actorId)
            }
        return ResponseEntity.ok(list)
    }

    /**
     * 필터 단건을 조회한다(가시성 게이트 — 소유자 또는 공유받은 actor).
     *
     * @param id 필터 식별자.
     * @return 200 OK + [SavedFilterResponse](shares 포함).
     */
    @GetMapping("/{id}")
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<SavedFilterResponse> {
        val actorId = SavedFilterActorExtractor.extract()
        val ws = service.getVisibleById(id, actorId)
        return ResponseEntity.ok(SavedFilterResponse.from(ws.filter, ws.shares, actorId))
    }

    /**
     * 필터를 수정한다(소유자만, OCC). projectKey는 변경 불가(FR-10).
     *
     * @param id 필터 식별자.
     * @param request 수정 요청 바디(shares 포함 가능 — null이면 기존 공유 유지).
     * @return 200 OK + [SavedFilterResponse].
     */
    @PutMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @RequestBody request: SavedFilterUpdateRequest,
    ): ResponseEntity<SavedFilterResponse> {
        val actorId = SavedFilterActorExtractor.extract()
        val name = required(request.name, "name")
        val aqlQuery = required(request.aqlQuery, "aqlQuery")
        val version = request.version ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "version은 필수입니다.")
        val domainShares = parseShares(request.shares)
        log.info("SavedFilterController.update id={} actor={}", id, actorId)
        val ws = service.update(id, actorId, name, aqlQuery, version, domainShares)
        // FIXME(C1): ws.shares 를 사용해야 함 — 현재 domainShares ?: emptyList() 는 shares=null 시 빈 배열.
        val response = SavedFilterResponse.from(ws.filter, domainShares ?: emptyList(), actorId)
        return ResponseEntity.ok(response)
    }

    /**
     * 필터를 삭제한다(소유자만, 하드 삭제).
     *
     * @param id 필터 식별자.
     * @return 204 No Content.
     */
    @DeleteMapping("/{id}")
    fun delete(
        @PathVariable id: UUID,
    ): ResponseEntity<Void> {
        val actorId = SavedFilterActorExtractor.extract()
        log.info("SavedFilterController.delete id={} actor={}", id, actorId)
        service.delete(id, actorId)
        return ResponseEntity.noContent().build()
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * 필수 문자열 필드가 null/blank가 아님을 보장한다.
     *
     * @param value 검증할 값.
     * @param field 필드 이름(오류 메시지용).
     * @return trim되지 않은 원본 값(도메인 팩토리가 trim/길이 검증 수행).
     * @throws ResponseStatusException 400 null 또는 blank 시.
     */
    private fun required(
        value: String?,
        field: String,
    ): String =
        value?.takeIf { it.isNotBlank() }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "$field 은(는) 필수입니다.")

    /**
     * 요청 [ShareRequest] 목록을 도메인 [SavedFilterShare] 목록으로 변환한다.
     *
     * `null`이면 공유를 건드리지 않는 신호로 `null`을 그대로 반환한다.
     * 빈 리스트이면 공유 전체 제거 신호다.
     * 변환 중 [IllegalArgumentException]이 발생하면 상위 핸들러가 400으로 매핑한다.
     *
     * @param requests 변환할 요청 공유 목록. `null`이면 유지.
     * @return 도메인 공유 목록. `null`이면 서비스에 유지 신호.
     */
    private fun parseShares(requests: List<ShareRequest>?): List<SavedFilterShare>? =
        requests?.let { list ->
            val domainList = list.map { it.toDomain() }
            SavedFilterShare.normalize(domainList)
        }

    /**
     * 페이지네이션 파라미터를 검증한다(DoS 방어 — [MAX_PAGE_SIZE] 초과 거부).
     *
     * @param page 0-based 페이지 번호(0 이상).
     * @param size 페이지 크기([MIN_PAGE_SIZE]..[MAX_PAGE_SIZE]).
     * @throws ResponseStatusException 400 page<0 또는 size 범위 밖.
     */
    private fun validatePaging(
        page: Int,
        size: Int,
    ) {
        if (page < 0) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "page는 0 이상이어야 합니다.")
        }
        if (size < MIN_PAGE_SIZE || size > MAX_PAGE_SIZE) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "size는 ${MIN_PAGE_SIZE}~${MAX_PAGE_SIZE} 사이여야 합니다.",
            )
        }
    }

    private companion object {
        /** GET /shared 기본 페이지 크기. */
        const val DEFAULT_PAGE_SIZE = 20

        /** 페이지 크기 최솟값. */
        const val MIN_PAGE_SIZE = 1

        /** 페이지 크기 최댓값(DoS 방어 — SavedFilterSearchController와 동일 정책). */
        const val MAX_PAGE_SIZE = 100
    }
}
