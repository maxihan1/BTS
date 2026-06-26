// 저장된 필터 CRUD REST 컨트롤러 — /api/v1/filters (FR-SR-03 PR1, PRIVATE 전용)

package com.bts.search.savedfilter.web

import com.bts.search.savedfilter.application.SavedFilterService
import com.bts.search.savedfilter.web.dto.SavedFilterCreateRequest
import com.bts.search.savedfilter.web.dto.SavedFilterResponse
import com.bts.search.savedfilter.web.dto.SavedFilterUpdateRequest
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
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * 저장된 필터 CRUD REST 컨트롤러.
 *
 * 엔드포인트(`/api/v1/filters`).
 * - `POST` 생성 / `GET` 목록 / `GET {id}` 단건 / `PUT {id}` 수정 / `DELETE {id}` 삭제.
 *
 * actor 추출은 [SavedFilterActorExtractor]로 리소스 조회보다 먼저 수행한다(probe 차단).
 * 가시성·소유권 게이트와 예외 발생은 [SavedFilterService]가 담당하고,
 * 예외→HTTP 매핑은 [SavedFilterExceptionHandler]가 처리한다.
 *
 * PR1은 PRIVATE 전용(공유 없음)이므로 요청에 `shares`가 없다.
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
     * @param request 생성 요청 바디.
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
        log.info("SavedFilterController.create actor={} name={} projectKey={}", actorId, name, projectKey)
        val filter = service.create(actorId, name, aqlQuery, projectKey)
        return ResponseEntity.status(HttpStatus.CREATED).body(SavedFilterResponse.from(filter, actorId))
    }

    /**
     * 내가 소유한 필터 목록을 반환한다.
     *
     * @return 200 OK + [SavedFilterResponse] 목록.
     */
    @GetMapping
    fun list(): ResponseEntity<List<SavedFilterResponse>> {
        val actorId = SavedFilterActorExtractor.extract()
        val filters = service.listByOwner(actorId).map { SavedFilterResponse.from(it, actorId) }
        return ResponseEntity.ok(filters)
    }

    /**
     * 필터 단건을 조회한다(가시성 게이트).
     *
     * @param id 필터 식별자.
     * @return 200 OK + [SavedFilterResponse].
     */
    @GetMapping("/{id}")
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<SavedFilterResponse> {
        val actorId = SavedFilterActorExtractor.extract()
        val filter = service.getByIdForOwner(id, actorId)
        return ResponseEntity.ok(SavedFilterResponse.from(filter, actorId))
    }

    /**
     * 필터를 수정한다(소유자만, OCC). projectKey는 변경 불가(FR-10).
     *
     * @param id 필터 식별자.
     * @param request 수정 요청 바디.
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
        log.info("SavedFilterController.update id={} actor={}", id, actorId)
        val filter = service.update(id, actorId, name, aqlQuery, version)
        return ResponseEntity.ok(SavedFilterResponse.from(filter, actorId))
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
}
