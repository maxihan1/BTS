// Resolution REST API 컨트롤러 — GET 활성 목록 조회 (FR-IS-07 Task B4)

package com.bts.issue.resolution.web

import com.bts.issue.adapter.inbound.rest.DataResponse
import com.bts.issue.resolution.application.ResolutionApplicationService
import com.bts.issue.resolution.web.dto.ResolutionResponse
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Resolution REST API 컨트롤러.
 *
 * 엔드포인트 목록.
 * - GET /api/v1/resolutions — 활성 Resolution 전체 목록 조회 (FR-IS-07)
 *
 * ### 트랜잭션 정책
 * 컨트롤러는 트랜잭션 경계를 담당하지 않는다.
 * [ResolutionApplicationService.listActive] 의 `@Transactional(readOnly = true)` 가 담당한다.
 *
 * ### 인증
 * 기존 issue-tracking 보안 설정에 따른다. 별도 경로 추가 없이 기존 인증 필터가 적용된다.
 *
 * @param service Resolution 조회 Application Service
 */
@RestController
@RequestMapping("/api/v1/resolutions")
class ResolutionController(
    private val service: ResolutionApplicationService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 활성 Resolution 목록을 반환한다.
     *
     * V011 seed 직후에는 표준 5종(fixed/wontfix/duplicate/cannotreproduce/done)이 displayOrder ASC 로 반환된다.
     * `deleted_at IS NULL` 인 Resolution 만 포함된다.
     *
     * @return 200 OK + `{ "data": [ { id, key, name, description, displayOrder, isStandard }, ... ] }`
     */
    @GetMapping
    fun listResolutions(): ResponseEntity<DataResponse<List<ResolutionResponse>>> {
        log.debug("ResolutionController.listResolutions")
        val resolutions = service.listActive().map { ResolutionResponse.from(it) }
        return ResponseEntity.ok(DataResponse(data = resolutions))
    }
}
