// IssueController — POST /api/v1/issues 이슈 생성, GET 단건/목록, PATCH 수정, POST 전이, DELETE 소프트 삭제 REST 엔드포인트

package com.bts.issue.adapter.inbound.rest

import com.bts.issue.application.IssueApplicationService
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueKey
import com.bts.shared.issue.IssueTypeId
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
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
import java.net.URI
import java.util.UUID
import com.bts.issue.application.AppChangeAssigneeRequest
import com.bts.issue.application.CreateIssueRequest as AppCreateIssueRequest
import com.bts.issue.application.TransitionIssueRequest as AppTransitionIssueRequest
import com.bts.issue.application.UpdateIssueRequest as AppUpdateIssueRequest

/**
 * 이슈 REST API 컨트롤러.
 *
 * 엔드포인트 목록.
 * - POST   /api/v1/issues — 이슈 생성 (T13)
 * - GET    /api/v1/issues/{key} — 이슈 단건 조회 (T14)
 * - GET    /api/v1/issues — 이슈 목록 조회 (페이지) (T14)
 * - PATCH  /api/v1/issues/{key} — 이슈 수정 (T15)
 * - POST   /api/v1/issues/{key}/transition — 이슈 상태 전이 실행 (T15, T6)
 * - GET    /api/v1/issues/{key}/transitions — 가용 전이 목록 조회 (T4)
 * - PATCH  /api/v1/issues/{key}/assignee — 담당자 변경/해제 (FR-IS-03 T8)
 * - DELETE /api/v1/issues/{key} — 이슈 소프트 삭제 (T16)
 *
 * ### 트랜잭션 정책
 * 컨트롤러는 트랜잭션 경계를 담당하지 않는다.
 * 트랜잭션 개시는 [IssueApplicationService] 가 담당한다 (@Transactional 클래스 레벨 선언).
 *
 * ### 워크플로우 키 결정 정책 (T6)
 * `transition` 메서드는 `toStateKey` 와 `expectedVersion` 만 서비스에 위임한다.
 * `workflowKey` 는 [IssueApplicationService] 가 [com.bts.shared.workflow.WorkflowKeyResolver] 를
 * 통해 프로젝트 스킴 설정에서 자동 결정한다. 컨트롤러(transport 계층) 는 workflow 결정 책임을 갖지 않는다.
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

        // 임시 fallback — security-engineer wave 에서 SecurityContextHolder 의 인증된 UUID 로 교체 예정.
        val actor = ActorId(SYSTEM_ACTOR_UUID)
        val appRequest =
            AppCreateIssueRequest(
                projectKey = request.projectKey,
                summary = request.summary,
                reporterId = actor,
                typeId = request.typeId?.let { IssueTypeId(it) },
            )
        val issue = service.createIssue(actor, appRequest)
        // createIssue 는 Issue 도메인 객체를 반환하므로, type 요약 포함 응답을 위해 findByKey 재조회한다.
        val response = service.findByKey(actor, issue.key)

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

    /**
     * 이슈 필드를 수정한다 (RFC 7396 JSON Merge Patch 시맨틱).
     *
     * [UpdateIssueRequest.expectedVersion] 이 DB 버전과 다르면 409 Version Conflict.
     * [UpdateIssueRequest.summary] null 이면 변경 안 함 (RFC 7396 JSON Merge Patch).
     * non-null 이면 새 값으로 변경한다.
     *
     * @param key path variable 이슈 키 문자열. 예: `"ATLAS-1"`
     * @param request 수정 요청 바디 (Jakarta Validation 적용).
     *   [UpdateIssueRequest.summary] null 이면 변경 안 함 (RFC 7396 JSON Merge Patch).
     *   [UpdateIssueRequest.typeId] null 이면 타입 변경 안 함. 양수 필수 (@Positive).
     * @return 200 OK + 수정된 [IssueResponse] body
     * @throws com.bts.issue.domain.IssueNotFoundException 이슈가 없거나 소프트 삭제된 경우 → 404
     * @throws com.bts.issue.type.domain.IssueTypeNotFoundException typeId 가 존재하지 않거나 비활성 → 404
     * @throws com.bts.issue.domain.IssueVersionConflictException 낙관락 충돌 → 409
     */
    @PatchMapping("/{key}")
    fun update(
        @PathVariable key: String,
        @Valid @RequestBody request: UpdateIssueRequest,
    ): ResponseEntity<DataResponse<IssueResponse>> {
        log.info("IssueController.update key={}", key)

        val actor = ActorId(SYSTEM_ACTOR_UUID)
        val issueKey = IssueKey(key)
        val appRequest =
            AppUpdateIssueRequest(
                summary = request.summary,
                typeId = request.typeId?.let { IssueTypeId(it) },
                expectedVersion = request.expectedVersion,
                description = request.description,
                priority = request.priority,
                labels = request.labels,
                environment = request.environment,
                impact = request.impact,
            )
        val response = service.updateIssue(actor, issueKey, appRequest)
        return ResponseEntity.ok(DataResponse(data = response))
    }

    /**
     * 이슈 상태를 전이한다.
     *
     * ### 책임 분리
     * - **컨트롤러 (transport)** — HTTP 요청을 받아 `toStateKey`, `expectedVersion` 만 [IssueApplicationService] 에 위임한다.
     *   `workflowKey` 결정은 컨트롤러 책임이 아니다.
     * - **[IssueApplicationService]** — [com.bts.shared.workflow.WorkflowKeyResolver] 를 통해
     *   프로젝트에 적합한 `workflowKey` 를 자동 결정한다 (T4 구현).
     *
     * 워크플로우 정의에 허용된 전이가 아닌 경우 409 Transition Not Allowed.
     * 프로젝트에 기본 워크플로우 스킴이 없는 경우 422 Workflow Not Configured.
     *
     * @param key path variable 이슈 키 문자열. 예: `"ATLAS-1"`
     * @param request 전이 요청 바디 (Jakarta Validation 적용)
     * @return 200 OK + 전이된 [IssueResponse] body
     * @throws com.bts.issue.domain.IssueNotFoundException 이슈가 없거나 소프트 삭제된 경우 → 404
     * @throws com.bts.issue.domain.IssueWorkflowNotConfiguredException 프로젝트에 워크플로우 미설정 → 422
     * @throws com.bts.issue.domain.IssueTransitionNotAllowedException 전이 거부 → 409
     * @throws com.bts.issue.domain.IssueVersionConflictException 낙관락 충돌 → 409
     */
    @PostMapping("/{key}/transition")
    fun transition(
        @PathVariable key: String,
        @Valid @RequestBody request: TransitionIssueRequest,
    ): ResponseEntity<DataResponse<IssueResponse>> {
        log.info("IssueController.transition key={} toStatusKey={}", key, request.toStatusKey)

        val actor = ActorId(SYSTEM_ACTOR_UUID)
        val issueKey = IssueKey(key)
        val appRequest =
            AppTransitionIssueRequest(
                toStateKey = request.toStatusKey,
                expectedVersion = request.expectedVersion,
            )
        val response = service.transitionIssue(actor, issueKey, appRequest)
        return ResponseEntity.ok(DataResponse(data = response))
    }

    /**
     * 현재 이슈 상태에서 이동 가능한 전이 목록을 반환한다.
     *
     * ### 복수/단수 명명 의도
     * - 이 엔드포인트 (`GET /{key}/transitions`) 는 **목록 조회** — 가용 전이 여러 건을 열거한다.
     * - 기존 엔드포인트 (`POST /{key}/transition`) 는 **단건 실행** — 특정 전이 한 건을 수행한다.
     * 복수형(`transitions`) vs 단수형(`transition`) 명명은 이 의도 차이를 명시한다.
     *
     * @param key path variable 이슈 키 문자열. 예: `"ATLAS-1"`
     * @return 200 OK + [AvailableTransitionsResponse] body
     * @throws com.bts.issue.domain.IssueNotFoundException 이슈가 없거나 소프트 삭제된 경우 → 404
     * @throws com.bts.issue.domain.IssueWorkflowNotConfiguredException 프로젝트에 워크플로우 미설정 → 422
     */
    @GetMapping("/{key}/transitions")
    fun availableTransitions(
        @PathVariable key: String,
    ): ResponseEntity<DataResponse<AvailableTransitionsResponse>> {
        log.info("IssueController.availableTransitions key={}", key)

        val actor = ActorId(SYSTEM_ACTOR_UUID)
        val issueKey = IssueKey(key)
        val views = service.availableTransitions(actor, issueKey)
        return ResponseEntity.ok(DataResponse(data = AvailableTransitionsResponse.from(views)))
    }

    /**
     * 이슈 담당자를 변경하거나 해제한다.
     *
     * ### 전용 서브리소스 설계 사유
     * RFC 7396 JSON Merge Patch 에서 null 은 "필드 삭제" 를 의미하나,
     * PATCH /{key} 는 null 을 "변경 없음" 으로 사용하는 partial-update 시맨틱을 따른다.
     * assignee 의 null=해제(3-state) 시맨틱과 충돌하므로 전용 서브리소스로 분리한다.
     *
     * @param key path variable 이슈 키 문자열. 예: `"ATLAS-1"`
     * @param request 담당자 변경 요청 바디 (Jakarta Validation 적용).
     *   [ChangeAssigneeRequest.assigneeId] null 이면 담당자 해제.
     *   non-null 이면 해당 사용자를 담당자로 지정한다.
     * @return 200 OK + 변경된 [IssueResponse] body
     * @throws com.bts.issue.domain.IssueNotFoundException 이슈가 없거나 소프트 삭제된 경우 → 404
     * @throws com.bts.issue.domain.AssigneeNotFoundException assigneeId 가 non-null 이지만 사용자가 존재하지 않을 때 → 422
     * @throws com.bts.issue.domain.IssueVersionConflictException 낙관락 충돌 → 409
     */
    @PatchMapping("/{key}/assignee")
    fun changeAssignee(
        @PathVariable key: String,
        @Valid @RequestBody request: ChangeAssigneeRequest,
    ): ResponseEntity<DataResponse<IssueResponse>> {
        log.info("IssueController.changeAssignee key={} assigneeId={}", key, request.assigneeId)

        val actor = ActorId(SYSTEM_ACTOR_UUID)
        val issueKey = IssueKey(key)
        val appRequest = AppChangeAssigneeRequest(
            assigneeId = request.assigneeId,
            expectedVersion = request.expectedVersion,
        )
        val response = service.changeAssignee(actor, issueKey, appRequest)
        return ResponseEntity.ok(DataResponse(data = response))
    }

    /**
     * 이슈를 소프트 삭제한다.
     *
     * 실제 DB 행을 제거하지 않고 삭제 플래그를 세운다 (soft delete).
     * 삭제 후 해당 이슈 키로 조회하면 [com.bts.issue.domain.IssueNotFoundException] 이 발생한다.
     *
     * @param key path variable 이슈 키 문자열. 예: `"ATLAS-1"`
     * @throws com.bts.issue.domain.IssueNotFoundException 이슈가 없거나 이미 삭제된 경우 → 404
     */
    @DeleteMapping("/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @PathVariable key: String,
    ) {
        log.info("IssueController.delete key={}", key)

        val actor = ActorId(SYSTEM_ACTOR_UUID)
        service.softDeleteIssue(actor, IssueKey(key))
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
