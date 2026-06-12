// IssueController — POST /api/v1/issues 이슈 생성, GET 단건/목록, PATCH 수정, POST 전이, DELETE 소프트 삭제 REST 엔드포인트

package com.bts.issue.adapter.inbound.rest

import com.bts.issue.application.AppChangeAssigneeRequest
import com.bts.issue.application.AppChangeComponentsRequest
import com.bts.issue.application.AppChangeVersionsRequest
import com.bts.issue.application.IssueApplicationService
import com.bts.issue.application.IssueChangelogService
import com.bts.issue.application.SecurityLevelPatch
import com.bts.issue.domain.IssueKey
import com.bts.issue.pdf.IssuePdfRenderer
import com.bts.issue.pdf.IssuePdfTemplate
import com.bts.shared.issue.IssueTypeId
import jakarta.validation.Valid
import org.openapitools.jackson.nullable.JsonNullable
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
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
import com.bts.issue.application.CloneIssueRequest as AppCloneIssueRequest
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
 * - GET    /api/v1/issues/{key}/changelog — 변경 이력 페이지 조회 (FR-HS-02 T B3)
 * - PATCH  /api/v1/issues/{key}/assignee — 담당자 변경/해제 (FR-IS-03 T8)
 * - PATCH  /api/v1/issues/{key}/components — 컴포넌트 목록 교체 (FR-CM-02 T5)
 * - PATCH  /api/v1/issues/{key}/affects-versions — 영향 버전 목록 교체 (FR-VR-03 T5)
 * - PATCH  /api/v1/issues/{key}/fix-versions — 수정 예정 버전 목록 교체 (FR-VR-03 T5)
 * - DELETE /api/v1/issues/{key} — 이슈 소프트 삭제 (T16)
 * - GET    /api/v1/issues/{key}/pdf — 이슈 PDF 내보내기 (FR-IS-08)
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
 * ### ActorId 결선 (FR-PM-06 PR-B)
 * 각 엔드포인트는 [CurrentActor.current] 로 [SecurityContextHolder] 의 인증 주체를 actor 로 추출한다.
 * 미인증·익명·비-UUID·nil-UUID 주체는 401(UNAUTHORIZED)로 거부한다.
 * actor 추출은 리소스 조회(404)보다 앞서 수행하여 미인증자가 404 로 리소스 존재를 probe 하지 못하게 한다.
 *
 * TooManyFunctions: 이슈 CRUD + 전이 + 클론 REST 엔드포인트를 단일 컨트롤러가 담당하므로 함수 수 임계치(11)를 초과한다.
 * 책임 분리보다 이슈 리소스 응집이 더 적합한 구조이므로 Suppress 처리.
 *
 * @param service 이슈 유스케이스 서비스
 * @param pdfRenderer 이슈 PDF 바이너리 렌더러
 * @param changelogService 이슈 변경 이력 조회 서비스. 기본값은 기존 슬라이스 테스트 호환을 위한 null.
 *   Spring production 컨텍스트에서는 항상 Bean 이 주입된다.
 *   [changelog] 엔드포인트는 이 서비스가 non-null 일 때만 정상 동작한다.
 */
@Suppress("TooManyFunctions")
@RestController
@RequestMapping("/api/v1/issues")
class IssueController(
    private val service: IssueApplicationService,
    // 기본값은 Spring이 관리하지 않는 컨텍스트(기존 슬라이스 테스트 호환)를 위한 fallback이다.
    // Spring production 컨텍스트에서는 항상 @Component Bean이 주입된다.
    private val pdfRenderer: IssuePdfRenderer = IssuePdfRenderer(IssuePdfTemplate()),
    // changelog 엔드포인트(FR-HS-02) 전용. 기존 슬라이스 테스트는 이 파라미터를 주입하지 않으므로 null 기본값.
    private val changelogService: IssueChangelogService? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 새 이슈를 생성한다.
     *
     * @param request 이슈 생성 요청 바디 (Jakarta Validation 적용).
     *   [CreateIssueRequest.componentIds] 를 app command에 그대로 전달한다 (FR-CM-03).
     *   componentIds 미포함 시 빈 목록으로 처리한다.
     * @return 201 Created + [IssueResponse] body + `Location: /api/v1/issues/{key}` 헤더
     */
    @PostMapping
    fun create(
        @Valid @RequestBody request: CreateIssueRequest,
    ): ResponseEntity<DataResponse<IssueResponse>> {
        log.info("IssueController.create projectKey={}", request.projectKey)

        val actor = CurrentActor.current()
        val appRequest =
            AppCreateIssueRequest(
                projectKey = request.projectKey,
                summary = request.summary,
                reporterId = actor,
                typeId = request.typeId?.let { IssueTypeId(it) },
                description = request.description,
                componentIds = request.componentIds,
                securityLevelId = request.securityLevelId,
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

        val actor = CurrentActor.current()
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

        val actor = CurrentActor.current()
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

        val actor = CurrentActor.current()
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
                securityLevel = toSecurityLevelPatch(request.securityLevelId),
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

        val actor = CurrentActor.current()
        val issueKey = IssueKey(key)
        val appRequest =
            AppTransitionIssueRequest(
                toStateKey = request.toStatusKey,
                expectedVersion = request.expectedVersion,
                resolutionId = request.resolutionId,
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

        val actor = CurrentActor.current()
        val issueKey = IssueKey(key)
        val views = service.availableTransitions(actor, issueKey)
        return ResponseEntity.ok(DataResponse(data = AvailableTransitionsResponse.from(views)))
    }

    /**
     * 이슈 변경 이력을 페이지 단위로 조회한다 (FR-HS-02).
     *
     * ### 권한 정책
     * [CurrentActor.current] 로 actor 를 추출한 뒤 [IssueChangelogService.findChangelog] 가
     * 내부적으로 [com.bts.issue.application.IssueApplicationService.findByKey] 를 호출해
     * VIEW 권한 + 이슈 존재 여부를 검증한다.
     * 미존재·소프트삭제·VIEW 미인가 모두 404 로 응답한다 (단건 조회와 동일한 동작).
     *
     * ### 페이지네이션
     * [Pageable] 파라미터로 `?page=0&size=20` 형태를 받는다.
     * 기본값은 [@PageableDefault] 로 size=20, page=0 이 적용된다.
     *
     * ### 라벨 박제
     * 응답의 items[].fromLabel/toLabel 에는 PR #120 [com.bts.issue.history.IssueChangeLabelResolver]
     * 가 변경 기록 시점에 박제한 라벨이 그대로 반환된다.
     *
     * @param key path variable 이슈 키 문자열. 예: `"ATLAS-1"`
     * @param pageable 페이지 정보. 기본값 size=20, page=0.
     * @return 200 OK + [Page]<[IssueChangelogResponse]>
     * @throws com.bts.issue.domain.IssueNotFoundException 이슈 미존재·소프트 삭제·VIEW 미인가 → 404
     */
    @GetMapping("/{key}/changelog")
    fun changelog(
        @PathVariable key: String,
        @PageableDefault(size = 20) pageable: Pageable,
    ): ResponseEntity<Page<IssueChangelogResponse>> {
        log.info("IssueController.changelog key={} pageable={}", key, pageable)

        val actor = CurrentActor.current()
        val issueKey = IssueKey(key)
        val service =
            requireNotNull(changelogService) {
                "IssueChangelogService 가 주입되지 않았습니다. Spring 컨텍스트 구성을 확인하세요."
            }
        val page =
            service.findChangelog(actor, issueKey, pageable)
                .map { IssueChangelogResponse.from(it) }
        return ResponseEntity.ok(page)
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

        val actor = CurrentActor.current()
        val issueKey = IssueKey(key)
        // @NotNull 검증이 통과한 뒤 호출되므로 expectedVersion 은 null 이 아님.
        // !! 금지 규칙에 따라 명시적 체크로 처리한다.
        val expectedVersion =
            request.expectedVersion
                ?: error("expectedVersion 은 @NotNull 검증 통과 후 null 일 수 없습니다.")
        val appRequest =
            AppChangeAssigneeRequest(
                assigneeId = request.assigneeId,
                expectedVersion = expectedVersion,
            )
        val response = service.changeAssignee(actor, issueKey, appRequest)
        return ResponseEntity.ok(DataResponse(data = response))
    }

    /**
     * 이슈에 연결된 컴포넌트 목록을 전체 교체한다.
     *
     * [ChangeComponentsRequest.componentIds] 에 명시된 UUID 목록으로 기존 컴포넌트 연결을 전부 교체한다.
     * 빈 목록이면 기존 컴포넌트를 전부 해제한다.
     * 비활성이거나 타 프로젝트 소속인 컴포넌트 ID 가 포함되면 422 Component Not Found 로 응답한다.
     *
     * @param key path variable 이슈 키 문자열. 예: `"ATLAS-1"`
     * @param request 컴포넌트 변경 요청 바디 (Jakarta Validation 적용).
     *   [ChangeComponentsRequest.componentIds] 빈 목록이면 전체 해제.
     *   [ChangeComponentsRequest.expectedVersion] 은 낙관적 잠금을 위해 필수.
     * @return 200 OK + 변경된 [IssueResponse] body
     * @throws com.bts.issue.domain.IssueNotFoundException 이슈가 없거나 소프트 삭제된 경우 → 404
     * @throws com.bts.issue.domain.IssueComponentNotFoundException 비활성 또는 타 프로젝트 컴포넌트 포함 시 → 422
     * @throws com.bts.issue.domain.IssueVersionConflictException 낙관락 충돌 → 409
     */
    @PatchMapping("/{key}/components")
    fun changeComponents(
        @PathVariable key: String,
        @Valid @RequestBody request: ChangeComponentsRequest,
    ): ResponseEntity<DataResponse<IssueResponse>> {
        log.info("IssueController.changeComponents key={} count={}", key, request.componentIds.size)

        val actor = CurrentActor.current()
        val issueKey = IssueKey(key)
        // @NotNull 검증이 통과한 뒤 호출되므로 expectedVersion 은 null 이 아님.
        val expectedVersion =
            request.expectedVersion
                ?: error("expectedVersion 은 @NotNull 검증 통과 후 null 일 수 없습니다.")
        val appRequest =
            AppChangeComponentsRequest(
                componentIds = request.componentIds,
                expectedVersion = expectedVersion,
            )
        val response = service.changeComponents(actor, issueKey, appRequest)
        return ResponseEntity.ok(DataResponse(data = response))
    }

    /**
     * 이슈에 연결된 영향 버전(affects) 목록을 전체 교체한다 (FR-VR-03).
     *
     * [ChangeVersionsRequest.versionIds] 에 명시된 UUID 목록으로 기존 영향 버전 연결을 전부 교체한다.
     * 빈 목록이면 기존 연결을 전부 해제한다.
     * 타 프로젝트 소속이거나 삭제된 버전 ID 가 포함되면 422 Linked Version Not Found 로 응답한다.
     *
     * @param key path variable 이슈 키 문자열. 예: `"ATLAS-1"`
     * @param request 버전 변경 요청 바디 (Jakarta Validation 적용).
     *   [ChangeVersionsRequest.versionIds] 빈 목록이면 전체 해제.
     *   [ChangeVersionsRequest.expectedVersion] 은 낙관적 잠금을 위해 필수.
     * @return 200 OK + 변경된 [IssueResponse] body
     * @throws com.bts.issue.domain.IssueNotFoundException 이슈가 없거나 소프트 삭제된 경우 → 404
     * @throws com.bts.issue.domain.IssueLinkedVersionNotFoundException 타 프로젝트/삭제 버전 포함 시 → 422
     * @throws com.bts.issue.domain.IssueVersionConflictException 낙관락 충돌 → 409
     */
    @PatchMapping("/{key}/affects-versions")
    fun changeAffectsVersions(
        @PathVariable key: String,
        @Valid @RequestBody request: ChangeVersionsRequest,
    ): ResponseEntity<DataResponse<IssueResponse>> {
        log.info("IssueController.changeAffectsVersions key={} count={}", key, request.versionIds.size)

        val actor = CurrentActor.current()
        val issueKey = IssueKey(key)
        // @NotNull 검증이 통과한 뒤 호출되므로 expectedVersion 은 null 이 아님.
        val expectedVersion =
            request.expectedVersion
                ?: error("expectedVersion 은 @NotNull 검증 통과 후 null 일 수 없습니다.")
        val appRequest =
            AppChangeVersionsRequest(
                versionIds = request.versionIds,
                expectedVersion = expectedVersion,
            )
        val response = service.changeAffectsVersions(actor, issueKey, appRequest)
        return ResponseEntity.ok(DataResponse(data = response))
    }

    /**
     * 이슈에 연결된 수정 예정 버전(fix) 목록을 전체 교체한다 (FR-VR-03).
     *
     * [ChangeVersionsRequest.versionIds] 에 명시된 UUID 목록으로 기존 수정 예정 버전 연결을 전부 교체한다.
     * 빈 목록이면 기존 연결을 전부 해제한다.
     * 타 프로젝트 소속이거나 삭제된 버전 ID 가 포함되면 422 Linked Version Not Found 로 응답한다.
     *
     * @param key path variable 이슈 키 문자열. 예: `"ATLAS-1"`
     * @param request 버전 변경 요청 바디 (Jakarta Validation 적용).
     *   [ChangeVersionsRequest.versionIds] 빈 목록이면 전체 해제.
     *   [ChangeVersionsRequest.expectedVersion] 은 낙관적 잠금을 위해 필수.
     * @return 200 OK + 변경된 [IssueResponse] body
     * @throws com.bts.issue.domain.IssueNotFoundException 이슈가 없거나 소프트 삭제된 경우 → 404
     * @throws com.bts.issue.domain.IssueLinkedVersionNotFoundException 타 프로젝트/삭제 버전 포함 시 → 422
     * @throws com.bts.issue.domain.IssueVersionConflictException 낙관락 충돌 → 409
     */
    @PatchMapping("/{key}/fix-versions")
    fun changeFixVersions(
        @PathVariable key: String,
        @Valid @RequestBody request: ChangeVersionsRequest,
    ): ResponseEntity<DataResponse<IssueResponse>> {
        log.info("IssueController.changeFixVersions key={} count={}", key, request.versionIds.size)

        val actor = CurrentActor.current()
        val issueKey = IssueKey(key)
        // @NotNull 검증이 통과한 뒤 호출되므로 expectedVersion 은 null 이 아님.
        val expectedVersion =
            request.expectedVersion
                ?: error("expectedVersion 은 @NotNull 검증 통과 후 null 일 수 없습니다.")
        val appRequest =
            AppChangeVersionsRequest(
                versionIds = request.versionIds,
                expectedVersion = expectedVersion,
            )
        val response = service.changeFixVersions(actor, issueKey, appRequest)
        return ResponseEntity.ok(DataResponse(data = response))
    }

    /**
     * 이슈를 PDF로 내보낸다.
     *
     * 이슈 키로 단건을 조회한 뒤 [IssuePdfRenderer]로 PDF 바이너리를 생성하여 반환한다.
     * 이슈가 없거나 소프트 삭제된 경우 [IssueExceptionHandler]가 [com.bts.issue.domain.IssueNotFoundException]을
     * 가로채 404로 변환한다.
     *
     * ### Content-Disposition filename 안전성
     * filename 값으로 사용하는 이슈 키(`{projectKey}-{sequence}`)는
     * [IssueKey] 생성 시 영숫자·하이픈만 허용하도록 검증된 형식이므로
     * HTTP 헤더 인젝션이나 경로 조작 위험이 없다.
     *
     * @param key path variable 이슈 키 문자열. 예: `"ATLAS-1"`
     * @return 200 OK + PDF 바이너리, Content-Type: application/pdf, Content-Disposition: attachment
     * @throws com.bts.issue.domain.IssueNotFoundException 이슈가 없거나 소프트 삭제된 경우 → 404
     */
    @GetMapping("/{key}/pdf", produces = [MediaType.APPLICATION_PDF_VALUE])
    fun exportPdf(
        @PathVariable key: String,
    ): ResponseEntity<ByteArray> {
        log.info("IssueController.exportPdf key={}", key)

        val actor = CurrentActor.current()
        val response = service.findByKey(actor, IssueKey(key))
        val pdf = pdfRenderer.render(response)
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"${response.key}.pdf\"")
            .body(pdf)
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

        val actor = CurrentActor.current()
        service.softDeleteIssue(actor, IssueKey(key))
    }

    /**
     * 기존 이슈를 복제하여 같은 프로젝트에 새 이슈를 생성한다 (FR-IS-06).
     *
     * 원본의 필드(summary/description/type/priority/labels/environment/impact/assignee)를 복사하고,
     * key/reporter/상태/version 은 새로 시작한다. body 는 선택적이며 생략 시 기본 옵션을 적용한다.
     *
     * @param key path variable 원본 이슈 키 문자열. 예: `"BTS-1"`
     * @param request 클론 옵션 (includeAssignee, summaryOverride). 생략 가능.
     * @return 201 Created + 클론본 [IssueResponse] body + `Location: /api/v1/issues/{newKey}` 헤더
     * @throws com.bts.issue.domain.IssueNotFoundException 원본이 없거나 삭제된 경우 → 404
     * @throws com.bts.issue.domain.IssueAccessDeniedException 권한이 없는 경우 → 403
     */
    @PostMapping("/{key}/clone")
    fun clone(
        @PathVariable key: String,
        @Valid @RequestBody(required = false) request: CloneIssueRequest?,
    ): ResponseEntity<DataResponse<IssueResponse>> {
        log.info("IssueController.clone sourceKey={}", key)

        val actor = CurrentActor.current()
        val webRequest = request ?: CloneIssueRequest()
        val appRequest =
            AppCloneIssueRequest(
                includeAssignee = webRequest.includeAssignee,
                summaryOverride = webRequest.summaryOverride,
            )
        val cloned = service.cloneIssue(actor, IssueKey(key), appRequest)
        // 단건 조회와 동일하게 type 요약 + descriptionHtml 포함 응답을 위해 재조회한다.
        val response = service.findByKey(actor, cloned.key)

        val location = buildLocation(response.key)
        return ResponseEntity.created(location).body(DataResponse(data = response))
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private fun buildLocation(issueKey: String): URI = URI.create("/api/v1/issues/$issueKey")

    /**
     * `JsonNullable<UUID>` 의 presence 를 [SecurityLevelPatch] 3-state 로 매핑한다 (FR-PM-06).
     *
     * Jira Cloud 방식 — 필드 부재(undefined)=무변경, 명시 null=해제, 값=지정.
     * application 계층이 웹 직렬화 라이브러리(JsonNullable)에 결합되지 않도록 transport 계층에서 변환한다.
     *
     * @param raw PATCH 요청의 securityLevelId JsonNullable 값.
     * @return 대응하는 [SecurityLevelPatch].
     */
    private fun toSecurityLevelPatch(raw: JsonNullable<UUID>): SecurityLevelPatch =
        when {
            !raw.isPresent -> SecurityLevelPatch.Unchanged
            raw.get() == null -> SecurityLevelPatch.Clear
            else -> SecurityLevelPatch.Assign(raw.get())
        }
}

/**
 * 성공 응답 래퍼.
 *
 * @param T 응답 데이터 타입
 * @property data 응답 페이로드
 */
data class DataResponse<T>(val data: T)
