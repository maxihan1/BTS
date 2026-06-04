// 라벨 자동완성 REST 엔드포인트 — GET /api/v1/labels (FR-IS-09)

package com.bts.issue.adapter.inbound.rest

import com.bts.issue.application.LabelApplicationService
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 라벨 자동완성 REST 컨트롤러.
 *
 * 엔드포인트.
 * - GET /api/v1/labels — prefix 기반 라벨 자동완성 (FR-IS-09)
 *
 * 라벨 자동완성은 인증 사용자 공통 접근으로 별도 권한 가드를 적용하지 않는다.
 * 권한 정교화는 FR-PM-05 위임.
 * (ADR docs/adr/2026-06-04-issue-label-freeform-tag-model.md §권한)
 *
 * @param service 라벨 자동완성 유스케이스 서비스
 */
@RestController
@RequestMapping("/api/v1/labels")
class LabelController(
    private val service: LabelApplicationService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * prefix로 시작하는 라벨 후보를 반환한다.
     *
     * @param q 자동완성 prefix. null/공백-only이면 전체 인기순 반환.
     * @return 200 OK + 빈도 내림차순 라벨 목록 (최대 10개)
     */
    @GetMapping
    fun complete(
        @RequestParam(required = false) q: String?,
    ): ResponseEntity<DataResponse<List<String>>> {
        log.info("LabelController.complete q={}", q)
        val result = service.completeLabels(q)
        return ResponseEntity.ok(DataResponse(data = result))
    }
}
