// IssueMoveController — POST /api/v1/issues/{key}/move/preview + move 이슈 이동 엔드포인트 (FR-MV-01)

package com.bts.issue.adapter.inbound.rest

import com.bts.issue.adapter.inbound.rest.dto.MovePreviewRequest
import com.bts.issue.adapter.inbound.rest.dto.MoveRequest
import com.bts.issue.adapter.inbound.rest.dto.MoveResponse
import com.bts.issue.application.IssueMoveRequest
import com.bts.issue.application.IssueMoveService
import com.bts.issue.application.MovePreview
import com.bts.issue.application.MovePreviewService
import com.bts.issue.domain.IssueKey
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 이슈 프로젝트 간 이동 REST 컨트롤러.
 *
 * 엔드포인트 목록.
 * - POST /api/v1/issues/{key}/move/preview — 이동 사전 검토 (Jira 마법사 1단계)
 * - POST /api/v1/issues/{key}/move         — 이동 실행 (Jira 마법사 2단계)
 *
 * ### 트랜잭션 정책
 * 컨트롤러는 트랜잭션 경계를 담당하지 않는다.
 * 트랜잭션 개시는 [MovePreviewService] / [IssueMoveService] 가 담당한다 (@Transactional 선언).
 *
 * ### ActorId 결선
 * [CurrentActor.current] 로 [org.springframework.security.core.context.SecurityContextHolder] 의
 * 인증 주체를 actor 로 추출한다.
 * actor 추출은 이슈 조회(404)보다 앞서 수행하여 미인증자가 리소스 존재를 probe 하지 못하게 한다.
 *
 * @param previewService 이동 preview 서비스
 * @param moveService 이동 실행 서비스
 */
@RestController
@RequestMapping("/api/v1/issues")
class IssueMoveController(
    private val previewService: MovePreviewService,
    private val moveService: IssueMoveService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 이슈를 대상 프로젝트로 이동할 때 필요한 매핑 정보를 미리 계산한다 (Jira 마법사 1단계).
     *
     * 읽기 전용 유스케이스로 실제 이동(write) 은 수행하지 않는다.
     *
     * @param key path variable 이슈 키 문자열. 예: `"ATLAS-1"`
     * @param request preview 요청 바디 (Jakarta Validation 적용).
     * @return 200 OK + [MovePreview] body
     * @throws com.bts.issue.domain.IssueNotFoundException 이슈가 없거나 소프트 삭제된 경우 → 404
     * @throws com.bts.issue.domain.IssueAccessDeniedException 원본 UPDATE 또는 대상 CREATE 권한 없을 때 → 403
     * @throws com.bts.issue.domain.IssueProjectNotFoundException 대상 프로젝트 미존재 → 404
     */
    @PostMapping("/{key}/move/preview")
    fun preview(
        @PathVariable key: String,
        @Valid @RequestBody request: MovePreviewRequest,
    ): ResponseEntity<DataResponse<MovePreview>> {
        log.info("IssueMoveController.preview key={} targetProject={}", key, request.targetProjectKey)

        val actor = CurrentActor.current()
        val issueKey = IssueKey(key)
        val result = previewService.preview(actor, issueKey, request.targetProjectKey)
        return ResponseEntity.ok(DataResponse(data = result))
    }

    /**
     * 이슈를 대상 프로젝트로 이동한다 (Jira 마법사 2단계).
     *
     * 이동 후 새 이슈 키 + 이전 키를 반환한다.
     * 기존 키로의 접근은 [com.bts.issue.domain.IssueMovedException] 308 redirect 로 처리된다 (DATA.md §2).
     *
     * @param key path variable 이슈 키 문자열. 예: `"ATLAS-1"`
     * @param request 이동 요청 바디 (Jakarta Validation 적용).
     * @return 200 OK + [MoveResponse] body
     * @throws com.bts.issue.domain.IssueNotFoundException 이슈가 없거나 소프트 삭제된 경우 → 404
     * @throws com.bts.issue.domain.IssueVersionConflictException OCC 충돌 → 409
     * @throws com.bts.issue.domain.IssueAccessDeniedException 권한 없음 → 403
     * @throws com.bts.issue.domain.MoveSameProjectException 같은 프로젝트 이동 → 422
     * @throws com.bts.issue.domain.IssueHasSubtasksException 서브태스크 보유 이슈 이동 → 422
     * @throws com.bts.issue.domain.InvalidTargetStateException 대상 상태 결정 불가 → 422
     * @throws com.bts.issue.domain.InvalidTargetMappingException 매핑 대상 미존재 → 422
     * @throws com.bts.issue.domain.RequiredFieldMissingException 필수 커스텀필드 누락 → 422
     */
    @PostMapping("/{key}/move")
    fun move(
        @PathVariable key: String,
        @Valid @RequestBody request: MoveRequest,
    ): ResponseEntity<DataResponse<MoveResponse>> {
        log.info("IssueMoveController.move key={} targetProject={}", key, request.targetProjectKey)

        val actor = CurrentActor.current()
        val issueKey = IssueKey(key)
        val appRequest = IssueMoveRequest(
            targetProjectKey = request.targetProjectKey,
            expectedVersion = request.expectedVersion,
            targetStateKey = request.targetStateKey,
            targetStateIsDone = request.targetStateIsDone,
            componentMapping = request.componentMapping,
            affectsVersionMapping = request.affectsVersionMapping,
            fixVersionMapping = request.fixVersionMapping,
            additionalCustomFields = request.customFieldValues,
        )
        val newKey = moveService.move(actor, issueKey, appRequest)
        val response = MoveResponse(
            issueKey = newKey.value,
            previousKey = issueKey.value,
        )
        return ResponseEntity.ok(DataResponse(data = response))
    }
}
