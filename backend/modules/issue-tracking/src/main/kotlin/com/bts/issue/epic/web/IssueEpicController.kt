// 에픽-자식 연결/해제/조회/진행률 REST 컨트롤러 (FR-EP-01 Task 6, FR-EP-02 Task 3)

package com.bts.issue.epic.web

import com.bts.issue.adapter.inbound.rest.CurrentActor
import com.bts.issue.adapter.inbound.rest.DataResponse
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueKey
import com.bts.issue.epic.application.IssueEpicService
import com.bts.issue.epic.web.dto.CreateEpicChildRequest
import com.bts.issue.epic.web.dto.EpicChildListResponse
import com.bts.issue.epic.web.dto.EpicChildSummaryResponse
import com.bts.issue.epic.web.dto.EpicProgressResponse
import com.bts.issue.repository.IssueRepository
import com.bts.issue.type.domain.IssueType
import com.bts.issue.type.repository.IssueTypeRepository
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * 에픽-자식 연결/해제/조회 REST API 컨트롤러 (FR-EP-01 Task 6).
 *
 * 엔드포인트 — 모두 `/api/v1/issues/{key}` 하위.
 * - POST   `/epic-children`            — 자식 연결 → 201 Created + [EpicChildSummaryResponse]
 * - DELETE `/epic-children/{childKey}` — 자식 연결 해제 → 204 No Content
 * - GET    `/epic-children`            — 자식 목록 조회 → 200 OK + [EpicChildListResponse]
 *
 * ### ActorId 결선
 * [CurrentActor.current] 로 SecurityContextHolder 의 인증 주체를 actor 로 추출한다.
 * 미인증·익명·비-UUID·nil-UUID 주체는 401(UNAUTHORIZED) 로 거부한다.
 * **actor 추출은 이슈 조회보다 앞서 수행** — 미인증자가 404 로 리소스 존재를 probe 하지 못하게 한다
 * (auth-extraction-before-resource-lookup 교훈).
 *
 * ### 트랜잭션 정책
 * 컨트롤러는 트랜잭션 경계를 담당하지 않는다. 모든 트랜잭션은 [IssueEpicService] 의 `@Transactional` 이 담당한다.
 *
 * ### 오류 매핑
 * 도메인 예외 → HTTP 상태/errorCode 변환은 [EpicChildExceptionHandler] 가 담당한다
 * ([IssueEpicController] 스코프 한정 — 타 컨트롤러 경로 예외를 잡지 않음).
 *
 * @param service 에픽-자식 연결/해제/조회 Application Service.
 * @param issueRepository 자식 이슈 단건 조회용. connect 성공 후 응답 빌드에 사용.
 * @param issueTypeRepository 자식 이슈 타입 조회용. EpicChildSummaryResponse.typeKey 에 사용.
 */
@RestController
@RequestMapping("/api/v1/issues/{key}", "/api/v1/epics/{key}")
class IssueEpicController(
    private val service: IssueEpicService,
    private val issueRepository: IssueRepository,
    private val issueTypeRepository: IssueTypeRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 이슈를 에픽의 자식으로 연결한다.
     *
     * @param key 에픽 이슈 키 (path variable).
     * @param request 자식 이슈 키를 담은 요청 본문.
     * @return 201 Created + [EpicChildSummaryResponse](연결된 자식 이슈 요약).
     * @throws com.bts.issue.epic.domain.EpicChildNotFoundException epic/child 미존재 시 → 404
     * @throws com.bts.issue.domain.IssueAccessDeniedException UPDATE(child) 권한 미보유 시 → 403
     * @throws com.bts.issue.epic.domain.EpicChildSelfReferenceException 자기 참조 시 → 422
     * @throws com.bts.issue.epic.domain.EpicChildInvalidTypeException child 유형 오류 시 → 422
     * @throws com.bts.issue.epic.domain.EpicTargetNotEpicException epic 유형 오류 시 → 422
     * @throws com.bts.issue.epic.domain.EpicChildCrossProjectException 다른 프로젝트 시 → 422
     * @throws com.bts.issue.epic.domain.EpicChildAlreadyLinkedException 이미 연결됨 시 → 409
     */
    @PostMapping("/epic-children")
    fun connectChild(
        @PathVariable key: String,
        @Valid @RequestBody request: CreateEpicChildRequest,
    ): ResponseEntity<DataResponse<EpicChildSummaryResponse>> {
        val actor = CurrentActor.current()
        val epicKey = IssueKey(key)
        val childKey = IssueKey(request.childKey)
        log.info("connectChild epicKey={} childKey={} actor={}", key, request.childKey, actor.value)

        service.connect(epicKey = epicKey, childKey = childKey, actor = actor)

        // connect 성공 후 자식 이슈를 재조회한다 — service 가 존재를 이미 검증했으므로 non-null 보장.
        val child =
            requireNotNull(issueRepository.findByKey(childKey)) {
                "connect 성공 후 자식 이슈는 존재해야 한다: ${childKey.value}"
            }
        val response = EpicChildSummaryResponse.from(issue = child, issueType = resolveType(child))
        return ResponseEntity.status(HttpStatus.CREATED).body(DataResponse(data = response))
    }

    /**
     * 이슈의 에픽 연결을 해제한다.
     *
     * @param key 에픽 이슈 키 (path variable).
     * @param childKey 에픽 연결을 해제할 자식 이슈 키 (path variable).
     * @throws com.bts.issue.epic.domain.EpicChildNotFoundException child 미존재 또는 이 에픽의 자식이 아닐 때 → 404
     * @throws com.bts.issue.domain.IssueAccessDeniedException UPDATE(child) 권한 미보유 시 → 403
     */
    @DeleteMapping("/epic-children/{childKey}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun disconnectChild(
        @PathVariable key: String,
        @PathVariable childKey: String,
    ) {
        val actor = CurrentActor.current()
        log.info("disconnectChild epicKey={} childKey={} actor={}", key, childKey, actor.value)
        service.disconnect(epicKey = IssueKey(key), childKey = IssueKey(childKey), actor = actor)
    }

    /**
     * 에픽에 속한 자식 이슈 목록을 조회한다.
     *
     * @param key 에픽 이슈 키 (path variable).
     * @return 200 OK + [EpicChildListResponse](활성 자식 이슈 목록, created_at 오름차순).
     * @throws com.bts.issue.epic.domain.EpicChildNotFoundException epic 미존재 시 → 404
     * @throws com.bts.issue.domain.IssueAccessDeniedException BROWSE(Project) 권한 미보유 시 → 403
     */
    @GetMapping("/epic-children")
    fun listChildren(
        @PathVariable key: String,
    ): ResponseEntity<DataResponse<EpicChildListResponse>> {
        val actor = CurrentActor.current()
        log.info("listChildren epicKey={} actor={}", key, actor.value)

        val children = service.listChildren(epicKey = IssueKey(key), actor = actor)
        val response =
            EpicChildListResponse(
                children = children.map { EpicChildSummaryResponse.from(issue = it, issueType = resolveType(it)) },
            )
        return ResponseEntity.ok(DataResponse(data = response))
    }

    /**
     * 에픽의 자식 이슈 진행률을 카테고리별로 집계해 반환한다.
     *
     * `/api/v1/epics/{key}/progress` 경로로 응답한다.
     * 클래스 레벨 `@RequestMapping` 에 `/api/v1/epics/{key}` 가 포함되어 있어
     * 이 메서드가 에픽 전용 base path 에서도 동작한다.
     *
     * actor 추출은 서비스 호출 전 선행 — 미인증자가 404 로 존재를 probe 하지 못하게 한다
     * (auth-extraction-before-resource-lookup 교훈).
     *
     * @param key 에픽 이슈 키 (path variable).
     * @return 200 OK + [EpicProgressResponse](진행률 집계).
     * @throws com.bts.issue.epic.domain.EpicChildNotFoundException epic 미존재·소프트삭제 시 → 404
     * @throws com.bts.issue.domain.IssueAccessDeniedException BROWSE(Project) 권한 미보유 시 → 403
     */
    @GetMapping("/progress")
    fun getProgress(
        @PathVariable key: String,
    ): ResponseEntity<DataResponse<EpicProgressResponse>> {
        val actor = CurrentActor.current()
        log.info("getProgress epicKey={} actor={}", key, actor.value)

        val progress = service.progress(epicKey = IssueKey(key), actor = actor)
        return ResponseEntity.ok(DataResponse(data = EpicProgressResponse.from(progress)))
    }

    private fun resolveType(issue: Issue): IssueType? = issueTypeRepository.findById(issue.typeId)
}
