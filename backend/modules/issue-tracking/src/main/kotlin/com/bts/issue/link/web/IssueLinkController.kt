// 이슈 링크 CRUD + parent-child 설정/해제 REST 컨트롤러 (FR-LK-01)

package com.bts.issue.link.web

import com.bts.issue.adapter.inbound.rest.CurrentActor
import com.bts.issue.adapter.inbound.rest.DataResponse
import com.bts.issue.config.BEARER_AUTH_SCHEME
import com.bts.issue.domain.IssueKey
import com.bts.issue.link.application.IssueParentService
import com.bts.issue.link.application.LinkApplicationService
import com.bts.issue.link.web.dto.CreateLinkRequest
import com.bts.issue.link.web.dto.IssueLinkResponse
import com.bts.issue.link.web.dto.IssueParentResponse
import com.bts.issue.link.web.dto.LinkListResponse
import com.bts.issue.link.web.dto.SetParentRequest
import com.bts.issue.repository.IssueRepository
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * 이슈 링크 / parent-child REST API 컨트롤러.
 *
 * 엔드포인트 — 모두 `/api/v1/issues/{key}` 하위.
 * - POST   `/links`            — 링크 생성(blocks/relates/duplicates/clones) → 201
 * - GET    `/links`            — outward/inward 링크 목록 → 200
 * - DELETE `/links/{linkId}`   — 링크 해제(물리 삭제) → 204
 * - PATCH  `/parent`           — 부모 설정(parentKey) / 해제(null) → 200
 *
 * ### 트랜잭션 정책
 * 컨트롤러는 트랜잭션 경계를 담당하지 않는다. 모든 트랜잭션은
 * [LinkApplicationService] / [IssueParentService] 의 `@Transactional` 이 담당한다.
 *
 * ### 오류 매핑
 * 도메인 예외 → HTTP 상태/`errorCode` 변환은 [LinkExceptionHandler] 가 담당한다
 * (link 패키지 스코프 한정 — 타 컨트롤러 경로 예외를 잡지 않음).
 *
 * ### actorId
 * **모든 핸들러가 [CurrentActor.current] 로 actor 를 먼저 추출한다** — 리소스 조회보다 앞이다.
 * 뒤에 두면 미인증자가 404/200 차이로 이슈 실재를 열거한다.
 *
 * ⚠️ 2026-07-27 이전 이 자리에는 *"issue_links / parent_id 는 created_by 를 저장하지 않으므로
 * actor 추출이 불필요하다"* 라고 적혀 있었고, 실제로 **권한 검사가 0건**이었다.
 * 「누가 만들었는지 기록 안 함」 과 「누가 만들어도 되는지 검사 안 해도 됨」 은 다른 진술이다 —
 * 감사 흔적의 부재는 권한 검사 면제의 근거가 아니다. 그 한 문장이 리뷰에서 "의도된 설계" 로
 * 읽히게 만들어 무가드 상태를 오래 살렸다.
 * SecurityFilterChain 이 인증 없는 요청에 401 을 보장한다.
 *
 * @param linkApplicationService 링크 생성/조회/해제 Application Service.
 * @param issueParentService parent-child 설정/해제 Application Service.
 * @param issueRepository 응답 DTO 의 상대/부모 이슈 요약 조회용(서비스가 존재는 이미 검증).
 */
@Tag(name = "Issue Links", description = "이슈 링크 CRUD 및 parent-child 설정 API (FR-LK-01)")
@RestController
@RequestMapping("/api/v1/issues/{key}")
class IssueLinkController(
    private val linkApplicationService: LinkApplicationService,
    private val issueParentService: IssueParentService,
    private val issueRepository: IssueRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 이슈 링크를 생성한다.
     *
     * @param key 출발(source) 이슈 키.
     * @param request 도착 이슈 키 + 링크 유형 코드.
     * @return 201 Created + [IssueLinkResponse](outward 방향).
     * @throws com.bts.issue.link.domain.LinkedIssueNotFoundException source/target 미존재 → 404
     * @throws com.bts.issue.link.domain.LinkSelfReferenceException 자기 링크 → 422
     * @throws com.bts.issue.link.domain.DuplicateLinkException 중복 링크 → 409
     * @throws com.bts.issue.link.domain.LinkCycleException blocks 순환 → 409
     * @throws com.bts.issue.link.domain.InvalidLinkTypeCodeException 잘못된 linkType → 400
     */
    @Operation(operationId = "createLink", summary = "이슈 링크 생성")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "생성 성공"),
        ApiResponse(responseCode = "400", description = "잘못된 linkType", content = [Content()]),
        ApiResponse(responseCode = "401", description = "미인증", content = [Content()]),
        ApiResponse(responseCode = "404", description = "이슈 미존재", content = [Content()]),
        ApiResponse(responseCode = "409", description = "중복 링크 또는 순환 감지", content = [Content()]),
    )
    @SecurityRequirement(name = BEARER_AUTH_SCHEME)
    @PostMapping("/links")
    fun createLink(
        @PathVariable key: String,
        @Valid @RequestBody request: CreateLinkRequest,
    ): ResponseEntity<DataResponse<IssueLinkResponse>> {
        val actor = CurrentActor.current()
        log.info("createLink source={} target={} type={}", key, request.targetKey, request.linkType)
        val targetKey = IssueKey(request.targetKey)
        val result = linkApplicationService.createLink(actor, IssueKey(key), targetKey, request.linkType)
        // 상대(target) 이슈 요약 — createLink 가 존재를 이미 검증했으므로 non-null 보장.
        val targetIssue =
            requireNotNull(issueRepository.findByKey(targetKey)) {
                "createLink 성공 후 target 이슈는 존재해야 한다: ${targetKey.value}"
            }
        val body =
            IssueLinkResponse.fromResult(
                result = result,
                otherIssueKey = targetKey.value,
                otherIssueSummary = targetIssue.summary,
                otherCurrentStateKey = targetIssue.currentStateKey,
            )
        return ResponseEntity.status(HttpStatus.CREATED).body(DataResponse(data = body))
    }

    /**
     * 이슈에 연결된 모든 링크(outward + inward)를 조회한다.
     *
     * @param key 링크를 조회할 이슈 키.
     * @return 200 OK + [LinkListResponse].
     * @throws com.bts.issue.link.domain.LinkedIssueNotFoundException 이슈 미존재 → 404
     */
    @Operation(operationId = "listLinks", summary = "이슈 링크 목록 조회")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "링크 목록 (outward + inward)"),
        ApiResponse(responseCode = "401", description = "미인증", content = [Content()]),
        ApiResponse(responseCode = "404", description = "이슈 미존재", content = [Content()]),
    )
    @SecurityRequirement(name = BEARER_AUTH_SCHEME)
    @GetMapping("/links")
    fun listLinks(
        @PathVariable key: String,
    ): ResponseEntity<DataResponse<LinkListResponse>> {
        val actor = CurrentActor.current()
        val result = linkApplicationService.listLinks(actor, IssueKey(key))
        return ResponseEntity.ok(DataResponse(data = LinkListResponse.from(result)))
    }

    /**
     * 링크를 해제(물리 삭제)한다.
     *
     * @param key 링크 대상 이슈 키(존재 확인용).
     * @param linkId 삭제할 링크 BIGINT id.
     * @throws com.bts.issue.link.domain.LinkedIssueNotFoundException 이슈 미존재 → 404
     * @throws com.bts.issue.link.domain.LinkNotFoundException 링크 미존재 → 404
     */
    @Operation(operationId = "deleteLink", summary = "이슈 링크 해제")
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "해제 성공"),
        ApiResponse(responseCode = "401", description = "미인증", content = [Content()]),
        ApiResponse(responseCode = "404", description = "이슈 또는 링크 미존재", content = [Content()]),
    )
    @SecurityRequirement(name = BEARER_AUTH_SCHEME)
    @DeleteMapping("/links/{linkId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteLink(
        @PathVariable key: String,
        @PathVariable linkId: Long,
    ) {
        val actor = CurrentActor.current()
        log.info("deleteLink key={} linkId={}", key, linkId)
        linkApplicationService.deleteLink(actor, IssueKey(key), linkId)
    }

    /**
     * 이슈의 부모를 설정(parentKey)하거나 해제(null)한다.
     *
     * @param key 부모를 지정/해제할 자식 이슈 키.
     * @param request `parentKey` — 부모 이슈 키, null 이면 해제.
     * @return 200 OK + [IssueParentResponse].
     * @throws com.bts.issue.link.domain.LinkedIssueNotFoundException child/parent 미존재 → 404
     * @throws com.bts.issue.link.domain.ParentSelfReferenceException 자기 부모 → 422
     * @throws com.bts.issue.link.domain.ParentCycleException 조상 순환 → 409
     */
    @Operation(operationId = "setParent", summary = "부모 이슈 설정/해제")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(responseCode = "401", description = "미인증", content = [Content()]),
        ApiResponse(responseCode = "404", description = "이슈 미존재", content = [Content()]),
        ApiResponse(responseCode = "409", description = "부모 순환 감지", content = [Content()]),
    )
    @SecurityRequirement(name = BEARER_AUTH_SCHEME)
    @PatchMapping("/parent")
    fun setParent(
        @PathVariable key: String,
        @RequestBody request: SetParentRequest,
    ): ResponseEntity<DataResponse<IssueParentResponse>> {
        val actor = CurrentActor.current()
        val childKey = IssueKey(key)
        val parentKeyValue = request.parentKey
        val body =
            if (parentKeyValue == null) {
                log.info("clearParent child={}", key)
                issueParentService.clearParent(actor, childKey)
                IssueParentResponse.from(child = requireChild(childKey), parentIssue = null)
            } else {
                val parentKey = IssueKey(parentKeyValue)
                log.info("setParent child={} parent={}", key, parentKeyValue)
                issueParentService.setParent(actor, childKey, parentKey)
                val parentIssue =
                    requireNotNull(issueRepository.findByKey(parentKey)) {
                        "setParent 성공 후 parent 이슈는 존재해야 한다: ${parentKey.value}"
                    }
                IssueParentResponse.from(child = requireChild(childKey), parentIssue = parentIssue)
            }
        return ResponseEntity.ok(DataResponse(data = body))
    }

    /** setParent/clearParent 성공 후 자식 이슈 재조회 — 서비스가 존재를 이미 검증했으므로 non-null 보장. */
    private fun requireChild(childKey: IssueKey) =
        requireNotNull(issueRepository.findByKey(childKey)) {
            "parent 변경 성공 후 child 이슈는 존재해야 한다: ${childKey.value}"
        }
}
