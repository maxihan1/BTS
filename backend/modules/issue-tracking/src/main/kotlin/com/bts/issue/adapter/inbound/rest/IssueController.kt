// IssueController — POST /api/v1/issues 이슈 생성 REST 엔드포인트

package com.bts.issue.adapter.inbound.rest

import com.bts.issue.application.CreateIssueRequest as AppCreateIssueRequest
import com.bts.issue.application.IssueApplicationService
import com.bts.issue.domain.ActorId
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.util.UUID

/**
 * 이슈 REST API 컨트롤러.
 *
 * 엔드포인트 목록 (T13 범위 — POST 만).
 * - POST /api/v1/issues — 이슈 생성
 *
 * ### 트랜잭션 정책
 * 컨트롤러는 트랜잭션 경계를 담당하지 않는다.
 * 트랜잭션 개시는 [IssueApplicationService] 가 담당한다 (@Transactional 클래스 레벨 선언).
 *
 * ### ActorId 임시 처리
 * T13 범위에서는 security context 연동 대신 고정 UUID 를 임시 사용한다.
 * 인증 연동은 이후 security-engineer wave 에서 처리한다.
 *
 * @param service 이슈 유스케이스 서비스
 */
@RestController
@RequestMapping("/api/v1/issues")
class IssueController(
    private val service: IssueApplicationService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 새 이슈를 생성한다.
     *
     * @param request 이슈 생성 요청 바디 (Jakarta Validation 적용)
     * @return 201 Created + [IssueResponse] body + `Location: /api/v1/issues/{key}` 헤더
     */
    @PostMapping
    fun create(
        @Valid @RequestBody request: CreateIssueRequest,
    ): ResponseEntity<DataResponse<IssueResponse>> {
        log.info("IssueController.create projectKey={}", request.projectKey)

        // TODO: security context 연동 후 실제 인증된 사용자 UUID 로 교체 (security-engineer wave)
        val actor = ActorId(SYSTEM_ACTOR_UUID)
        val appRequest = AppCreateIssueRequest(
            projectKey = request.projectKey,
            summary = request.summary,
            reporterId = actor,
        )
        val issue = service.createIssue(actor, appRequest)
        val response = IssueResponse.from(issue, issue.key.projectPrefix)

        val location = buildLocation(response.key)
        return ResponseEntity.created(location).body(DataResponse(data = response))
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private fun buildLocation(issueKey: String): URI = URI.create("/api/v1/issues/$issueKey")

    companion object {
        /** 인증 연동 전 임시 사용하는 시스템 행위자 UUID. */
        private val SYSTEM_ACTOR_UUID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    }
}

/**
 * 성공 응답 래퍼.
 *
 * @param T 응답 데이터 타입
 * @property data 응답 페이로드
 */
data class DataResponse<T>(val data: T)
