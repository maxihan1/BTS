// 라벨 자동완성 REST 엔드포인트 — GET /api/v1/labels (FR-IS-09 Task 3)

package com.bts.issue.adapter.inbound.rest

import com.bts.issue.application.LabelApplicationService
import com.bts.issue.domain.ActorId
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 라벨 자동완성 REST 컨트롤러.
 *
 * 엔드포인트.
 * - GET /api/v1/labels — prefix 기반 라벨 자동완성 (FR-IS-09)
 *
 * ### ActorId 임시 처리
 * security context 연동 전까지 고정 UUID를 사용한다.
 * IssueController와 동일한 임시 패턴이며, 인증 연동은 security-engineer wave에서 처리한다.
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

        // IssueController와 동일 임시 액터, security wave 후 SecurityContext 연동
        val actor = ActorId(SYSTEM_ACTOR_UUID)
        val result = service.completeLabels(actor, q)
        return ResponseEntity.ok(DataResponse(data = result))
    }

    companion object {
        /** 인증 연동 전 임시 사용하는 시스템 행위자 UUID. IssueController와 동일 리터럴. */
        private val SYSTEM_ACTOR_UUID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    }
}
