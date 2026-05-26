// IssueController — POST /api/v1/issues 이슈 생성, GET 단건/목록 REST 엔드포인트

package com.bts.issue.adapter.inbound.rest

import com.bts.issue.application.CreateIssueRequest as AppCreateIssueRequest
import com.bts.issue.application.IssueApplicationService
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueKey
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.util.UUID

/**
 * 이슈 REST API 컨트롤러.
 *
 * 엔드포인트 목록.
 * - POST /api/v1/issues — 이슈 생성 (T13)
 * - GET  /api/v1/issues/{key} — 이슈 단건 조회 (T14)
 * - GET  /api/v1/issues — 이슈 목록 조회 (페이지) (T14)
 *
 * ### 트랜잭션 정책
 * 컨트롤러는 트랜잭션 경계를 담당하지 않는다.
 * 트랜잭션 개시는 [IssueApplicationService] 가 담당한다 (@Transactional 클래스 레벨 선언).
 *
 * ### ActorId 임시 처리
 * security context 연동 전까지 고정 UUID 를 사용한다.
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

    /**
     * 이슈 단건을 조회한다.
     *
     * @param key path variable 이슈 키 문자열. 예: `"ATLAS-1"`
     * @return 200 OK + [IssueResponse] body
     * @throws com.bts.issue.domain.IssueNotFoundException 이슈가 없거나 소프트 삭제된 경우 → 404
     */
    @GetMapping("/{key}")
    fun get(
        @PathVariable key: String,
    ): ResponseEntity<DataResponse<IssueResponse>> {
        log.info("IssueController.get key={}", key)

        val actor = ActorId(SYSTEM_ACTOR_UUID)
        val issueKey = IssueKey(key)
        val response = service.findByKey(actor, issueKey)
        return ResponseEntity.ok(DataResponse(data = response))
    }

    /**
     * 프로젝트 이슈 목록을 페이지로 조회한다.
     *
     * @param projectKey 프로젝트 키. 생략 가능하며 생략 시 빈 문자열로 위임한다.
     * @param pageable 페이지 정보. 기본값 size=20, page=0.
     * @return 200 OK + [Page]<[IssueResponse]>
     */
    @GetMapping
    fun list(
        @RequestParam projectKey: String?,
        @PageableDefault(size = 20) pageable: Pageable,
    ): ResponseEntity<Page<IssueResponse>> {
        log.info("IssueController.list projectKey={} pageable={}", projectKey, pageable)

        val actor = ActorId(SYSTEM_ACTOR_UUID)
        val page = service.listIssues(actor, projectKey ?: "", pageable)
        return ResponseEntity.ok(page)
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
