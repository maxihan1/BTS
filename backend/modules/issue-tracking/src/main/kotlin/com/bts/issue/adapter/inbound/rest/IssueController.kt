// IssueController — POST /api/v1/issues 이슈 생성, GET 단건/목록, PATCH 수정, POST 전이, DELETE 소프트 삭제 REST 엔드포인트

package com.bts.issue.adapter.inbound.rest

import com.bts.issue.adapter.inbound.rest.cursor.CursorCodec
import com.bts.issue.adapter.inbound.rest.dto.RerankIssueRequest
import com.bts.issue.application.AppChangeAssigneeRequest
import com.bts.issue.application.AppChangeComponentsRequest
import com.bts.issue.application.AppChangeVersionsRequest
import com.bts.issue.application.BacklogRankService
import com.bts.issue.application.ChangelogCursorCodec
import com.bts.issue.application.DatePatch
import com.bts.issue.application.EstimatePatch
import com.bts.issue.application.IssueApplicationService
import com.bts.issue.application.IssueChangelogService
import com.bts.issue.application.SecurityLevelPatch
import com.bts.issue.config.BEARER_AUTH_SCHEME
import com.bts.issue.domain.IssueKey
import com.bts.issue.pdf.IssuePdfRenderer
import com.bts.issue.pdf.IssuePdfTemplate
import com.bts.shared.issue.IssueTypeId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
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
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.time.LocalDate
import java.util.UUID
import com.bts.issue.application.CloneIssueRequest as AppCloneIssueRequest
import com.bts.issue.application.CreateIssueRequest as AppCreateIssueRequest
import com.bts.issue.application.TransitionIssueRequest as AppTransitionIssueRequest
import com.bts.issue.application.UpdateIssueRequest as AppUpdateIssueRequest

/** cursor 모드 기본 limit. */
private const val DEFAULT_CURSOR_LIMIT = 20

/**
 * cursor 모드 + offset 모드 공통 최대 페이지 크기.
 *
 * ★2026-07-27 이전까지 이 KDoc 은 사실이 아니었다 — cursor 모드만 `limit` 초과를 400 으로 막고
 * **offset 모드에는 어떤 애플리케이션 상한도 없었다**. `@PageableDefault(size = 20)` 은 기본값일 뿐
 * `?size=` 로 얼마든지 올릴 수 있고, 남는 것은 Spring Data Web 프레임워크 기본값
 * (`spring.data.web.pageable.max-page-size`, 기본 2000)뿐인데 그 키는 이 저장소 설정에 **없다**.
 *
 * 이력 조회에서는 증폭 계수가 크다 — 댓글 수정 이력 1건이 `from_value`(이전 본문) +
 * `to_value`(새 본문) = 최대 32,000자 × 2 를 싣는다([`CommentApplicationService.MAX_BODY_LENGTH`]).
 * 프레임워크 상한까지 긁으면 한 응답이 2,000행 × 64,000자가 된다.
 *
 * 지금은 [requirePageSizeWithinLimit] 가 두 offset 지점 모두에 주입돼 KDoc 이 참이 됐다.
 */
private const val MAX_CURSOR_LIMIT = 100

/**
 * offset 모드 페이지 크기 상한을 강제한다 — 초과 시 400.
 *
 * cursor 모드의 `limit` 가드와 **같은 상한·같은 응답 코드**를 쓴다. 두 모드가 다른 상한을 가지면
 * 소비자는 어느 쪽을 믿을지 알 수 없다.
 *
 * 무음 절단(요청한 만큼 안 주고 조용히 자르기)이 아니라 400 을 택한 이유 — 무음 절단은
 * **API 를 직접 호출하는 소비자**에게 "덜 받았다" 를 알리지 않아, 페이지네이션을 직접 도는
 * 스크립트가 데이터를 조용히 누락한다. 이 저장소의 다수 선례도 400 이다.
 */
private fun requirePageSizeWithinLimit(pageable: Pageable) {
    if (pageable.pageSize > MAX_CURSOR_LIMIT) {
        throw ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "size 는 $MAX_CURSOR_LIMIT 이하여야 합니다.",
        )
    }
}

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
 * - PATCH  /api/v1/issues/{key}/rank — 백로그 rank 변경 (FR-BL-01 Task 5)
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
 * TooManyFunctions: 이슈 CRUD + 전이 + 클론 + 랭크 REST 엔드포인트를 단일 컨트롤러가 담당하므로 함수 수 임계치(11)를 초과한다.
 * 책임 분리보다 이슈 리소스 응집이 더 적합한 구조이므로 Suppress 처리.
 *
 * @param service 이슈 유스케이스 서비스
 * @param pdfRenderer 이슈 PDF 바이너리 렌더러
 * @param changelogService 이슈 변경 이력 조회 서비스. 기본값은 기존 슬라이스 테스트 호환을 위한 null.
 *   Spring production 컨텍스트에서는 항상 Bean 이 주입된다.
 *   [changelog] 엔드포인트는 이 서비스가 non-null 일 때만 정상 동작한다.
 * @param backlogRankService 백로그 rank 변경 서비스 (FR-BL-01). 기존 슬라이스 테스트 호환을 위해 null 기본값.
 */
@Suppress("TooManyFunctions")
@Tag(name = "Issues", description = "이슈 CRUD, 상태 전이, 클론, 변경 이력, 백로그 랭크 API (FR-IS·FR-HS·FR-BL)")
@RestController
@RequestMapping("/api/v1/issues")
class IssueController(
    private val service: IssueApplicationService,
    // 기본값은 Spring이 관리하지 않는 컨텍스트(기존 슬라이스 테스트 호환)를 위한 fallback이다.
    // Spring production 컨텍스트에서는 항상 @Component Bean이 주입된다.
    private val pdfRenderer: IssuePdfRenderer = IssuePdfRenderer(IssuePdfTemplate()),
    // changelog 엔드포인트(FR-HS-02) 전용. 기존 슬라이스 테스트는 이 파라미터를 주입하지 않으므로 null 기본값.
    private val changelogService: IssueChangelogService? = null,
    // rank 엔드포인트(FR-BL-01) 전용. 기존 슬라이스 테스트는 이 파라미터를 주입하지 않으므로 null 기본값.
    private val backlogRankService: BacklogRankService? = null,
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
    @Operation(
        operationId = "createIssue",
        summary = "이슈 생성",
        description = "새 이슈를 생성한다. 응답 Location 헤더에 생성된 이슈 URI 가 포함된다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "이슈 생성 성공"),
        ApiResponse(responseCode = "400", description = "요청 형식 오류 (projectKey 누락 등)", content = [Content()]),
        ApiResponse(responseCode = "401", description = "미인증", content = [Content()]),
        ApiResponse(responseCode = "403", description = "이슈 생성 권한 없음", content = [Content()]),
    )
    @SecurityRequirement(name = BEARER_AUTH_SCHEME)
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
    @Operation(
        operationId = "getIssue",
        summary = "이슈 단건 조회",
        description = "이슈 키로 단건을 조회한다. 소프트 삭제된 이슈는 404 로 응답한다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "이슈 조회 성공"),
        ApiResponse(responseCode = "401", description = "미인증", content = [Content()]),
        ApiResponse(responseCode = "403", description = "이슈 조회 권한 없음", content = [Content()]),
        ApiResponse(responseCode = "404", description = "이슈 미존재 또는 소프트 삭제", content = [Content()]),
    )
    @SecurityRequirement(name = BEARER_AUTH_SCHEME)
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
     * 프로젝트 이슈 목록을 조회한다.
     *
     * ### cursor 모드 / offset 모드 분기 (FR-API-01 Task 3)
     * - **cursor 모드**: `?cursor=<token|빈문자열>` 지정 시. keyset seek, [CursorPageResponse] envelope 반환.
     * - **offset 모드**: cursor 파라미터 미지정 시. 기존 `?page&size`, [Page]<[IssueResponse]> 반환 (무회귀).
     * - **모드 충돌**: cursor + page/size 동시 지정 → [PaginationModeConflictException] (Task 4 에서 400 매핑).
     * - **limit 초과**: cursor 모드에서 limit > [MAX_CURSOR_LIMIT] → 400.
     *
     * ### offset 모드 무회귀 보장
     * cursor 파라미터가 null 이면 기존 offset 경로를 그대로 실행한다. 기존 API 클라이언트는 영향 없음.
     *
     * @param projectKey 프로젝트 키. 생략 가능하며 생략 시 빈 문자열로 위임한다.
     * @param pageable 페이지 정보. offset 모드에서 사용. 기본값 size=20, page=0.
     * @param status 워크플로우 상태 키 목록 (OR 조건). 생략 시 필터 미적용.
     * @param assignee 담당자 UUID 목록. "unassigned" 센티널 허용. 생략 시 필터 미적용.
     * @param label 라벨 이름 목록 (OR 조건). 생략 시 필터 미적용.
     * @param component 컴포넌트 UUID 목록 (OR 조건). 생략 시 필터 미적용.
     * @param cursor cursor 토큰. 빈 문자열=첫 페이지. null=offset 모드.
     * @param limit cursor 모드에서의 최대 건수. 기본값 [DEFAULT_CURSOR_LIMIT], 최대 [MAX_CURSOR_LIMIT].
     * @param explicitPage 모드 충돌 감지용 — ?page 파라미터가 명시됐는지 검출.
     * @param explicitSize 모드 충돌 감지용 — ?size 파라미터가 명시됐는지 검출.
     * @return 200 OK + cursor 모드: [CursorPageResponse], offset 모드: [Page]<[IssueResponse]>
     * @throws PaginationModeConflictException cursor + page/size 동시 지정 시.
     * @throws org.springframework.web.server.ResponseStatusException 400 — limit 초과 또는 UUID 형식 오류.
     */
    @Operation(
        operationId = "listIssues",
        summary = "이슈 목록 조회",
        description = """이슈 목록을 페이지 단위로 조회한다.

### 페이지네이션 모드 (CONCERN-4)

두 모드가 동시에 지원되며, cursor 파라미터 존재 여부로 모드가 결정된다.

**cursor 모드 (권장)**
- 파라미터: `cursor=<token|빈문자열>`, `limit=N` (기본 20, 최대 100)
- 응답: `{data:[...], meta:{page:{next, limit}}}` envelope
- `next` 토큰을 다음 요청 `?cursor=<next>` 에 그대로 전달하면 끊김 없이 순회한다.
- **forward-only** — 이전 페이지(prev)로의 역방향 이동 미지원.
- `cursor=` (빈 문자열) = 첫 페이지.
- `next=null` = 마지막 페이지.

**offset 모드 (호환용 — 후속 deprecate 예정)**
- cursor 파라미터 미지정 시. `page`, `size` 파라미터 사용.
- 응답: Spring Page (content/pageable/totalElements 구조) — 기존 프론트 클라이언트 무회귀.
- offset 방식은 깊은 페이지에서 성능 저하가 있으며 동시 삽입 시 누락/중복 가능성이 있다.

**모드 충돌**: `cursor` + `page`/`size` 동시 지정 시 400 `ISSUE_PAGINATION_MODE_CONFLICT`.""",
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "cursor 모드: CursorPageResponse envelope / offset 모드: Spring Page",
            content = [Content(schema = Schema(implementation = CursorPageResponse::class))],
        ),
        ApiResponse(responseCode = "400", description = "cursor 형식 오류 또는 모드 충돌", content = [Content()]),
        ApiResponse(responseCode = "401", description = "미인증", content = [Content()]),
        ApiResponse(responseCode = "403", description = "프로젝트 BROWSE 권한 없음", content = [Content()]),
    )
    @SecurityRequirement(name = BEARER_AUTH_SCHEME)
    @GetMapping
    @Suppress("LongParameterList") // cursor 모드(cursor/limit)와 offset 모드(pageable/page/size)의 REST 쿼리 파라미터 집합 — 분리 불가
    fun list(
        @RequestParam projectKey: String?,
        @PageableDefault(size = 20) pageable: Pageable,
        @RequestParam(required = false) status: List<String> = emptyList(),
        @RequestParam(required = false) assignee: List<String> = emptyList(),
        @RequestParam(required = false) label: List<String> = emptyList(),
        @RequestParam(required = false) component: List<String> = emptyList(),
        @RequestParam(required = false) cursor: String? = null,
        @RequestParam(required = false) limit: Int? = null,
        @RequestParam(name = "page", required = false) explicitPage: Int? = null,
        @RequestParam(name = "size", required = false) explicitSize: Int? = null,
    ): ResponseEntity<Any> {
        val actor = CurrentActor.current()
        val filter = IssueFilterQueryParser.parse(status, assignee, label, component)
        return if (cursor != null) {
            if (explicitPage != null || explicitSize != null) throw PaginationModeConflictException()
            val effectiveLimit = limit ?: DEFAULT_CURSOR_LIMIT
            if (effectiveLimit > MAX_CURSOR_LIMIT) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "limit 은 $MAX_CURSOR_LIMIT 이하여야 합니다.",
                )
            }
            log.info(
                "IssueController.list cursor모드 projectKey={} limit={}",
                projectKey,
                effectiveLimit,
            )
            val cursorPosition = CursorCodec.decode(cursor)
            val result =
                service.listIssuesByCursor(
                    actor,
                    projectKey ?: "",
                    cursorPosition,
                    effectiveLimit,
                    filter,
                )
            ResponseEntity.ok<Any>(
                CursorPageResponse(
                    data = result.items,
                    meta = PageMeta(PageCursor(next = result.next, limit = effectiveLimit)),
                ),
            )
        } else {
            requirePageSizeWithinLimit(pageable)
            log.info("IssueController.list offset모드 projectKey={} pageable={}", projectKey, pageable)
            val page = service.listIssues(actor, projectKey ?: "", pageable, filter)
            ResponseEntity.ok<Any>(page)
        }
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
    @Operation(
        operationId = "updateIssue",
        summary = "이슈 수정",
        description = "RFC 7396 JSON Merge Patch 시맨틱으로 이슈 필드를 수정한다. null 필드는 변경하지 않는다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "이슈 수정 성공"),
        ApiResponse(responseCode = "400", description = "요청 형식 오류", content = [Content()]),
        ApiResponse(responseCode = "401", description = "미인증", content = [Content()]),
        ApiResponse(responseCode = "403", description = "이슈 수정 권한 없음", content = [Content()]),
        ApiResponse(responseCode = "404", description = "이슈 미존재", content = [Content()]),
        ApiResponse(responseCode = "409", description = "낙관적 잠금 충돌 (버전 불일치)", content = [Content()]),
    )
    @SecurityRequirement(name = BEARER_AUTH_SCHEME)
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
                startDate = toDatePatch(request.startDate),
                dueDate = toDatePatch(request.dueDate),
                targetDate = toDatePatch(request.targetDate),
                originalEstimate = toEstimatePatch(request.originalEstimateSeconds),
                remainingEstimate = toEstimatePatch(request.remainingEstimateSeconds),
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
    @Operation(
        operationId = "transitionIssue",
        summary = "이슈 상태 전이",
        description = "워크플로우 정의에 따라 이슈 상태를 전이한다. 허용되지 않는 전이는 409 로 거부한다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "전이 성공"),
        ApiResponse(responseCode = "400", description = "요청 형식 오류", content = [Content()]),
        ApiResponse(responseCode = "401", description = "미인증", content = [Content()]),
        ApiResponse(responseCode = "403", description = "전이 권한 없음", content = [Content()]),
        ApiResponse(responseCode = "404", description = "이슈 미존재", content = [Content()]),
        ApiResponse(responseCode = "409", description = "전이 불허 또는 낙관적 잠금 충돌", content = [Content()]),
    )
    @SecurityRequirement(name = BEARER_AUTH_SCHEME)
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
    @Operation(
        operationId = "availableTransitions",
        summary = "가용 전이 목록 조회",
        description = "현재 이슈 상태에서 이동 가능한 전이 목록을 반환한다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "가용 전이 목록"),
        ApiResponse(responseCode = "401", description = "미인증", content = [Content()]),
        ApiResponse(responseCode = "403", description = "조회 권한 없음", content = [Content()]),
        ApiResponse(responseCode = "404", description = "이슈 미존재", content = [Content()]),
    )
    @SecurityRequirement(name = BEARER_AUTH_SCHEME)
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
     * 이슈 변경 이력을 조회한다 (FR-HS-02 + FR-API-01 Task 5).
     *
     * ### cursor 모드 / offset 모드 분기
     * - **cursor 모드**: `?cursor=<token|빈문자열>` 지정 시. keyset seek, [CursorPageResponse] envelope 반환.
     * - **offset 모드**: cursor 파라미터 미지정 시. 기존 `?page&size`, [Page]<[IssueChangelogResponse]> 반환(무회귀).
     * - **모드 충돌**: cursor + page 동시 지정 → [PaginationModeConflictException] → 400.
     * - **limit 초과**: cursor 모드에서 limit > [MAX_CURSOR_LIMIT] → 400.
     *
     * ### 권한 정책
     * [IssueChangelogService.findChangelog] / [IssueChangelogService.findChangelogByCursor] 가
     * 내부에서 VIEW 권한 + 이슈 존재를 검증한다. 미존재·소프트삭제·VIEW 미인가 = 404.
     *
     * ### 라벨 박제
     * items[].fromLabel/toLabel 에는 PR #120 [com.bts.issue.history.IssueChangeLabelResolver] 가
     * 변경 기록 시점에 박제한 라벨이 그대로 반환된다.
     *
     * @param key path variable 이슈 키. 예: `"ATLAS-1"`
     * @param pageable 페이지 정보. offset 모드에서 사용. 기본값 size=20, page=0.
     * @param cursor cursor 토큰. 빈 문자열=첫 페이지. null=offset 모드.
     * @param limit cursor 모드에서의 최대 건수. 기본값 [DEFAULT_CURSOR_LIMIT], 최대 [MAX_CURSOR_LIMIT].
     * @param explicitPage 모드 충돌 감지용 — ?page 파라미터 명시 여부.
     * @return 200 OK + cursor 모드: [CursorPageResponse], offset 모드: [Page]<[IssueChangelogResponse]>
     * @throws PaginationModeConflictException cursor + page 동시 지정 시.
     * @throws com.bts.issue.domain.IssueNotFoundException 이슈 미존재·소프트 삭제·VIEW 미인가 → 404
     */
    @Operation(
        operationId = "listChangelog",
        summary = "이슈 변경 이력 조회",
        description = """이슈 변경 이력을 조회한다. list 와 동일한 cursor / offset 이중 모드를 지원한다.

cursor 모드: `cursor=<token|빈문자열>`, `limit=N` → CursorPageResponse envelope.
offset 모드: cursor 파라미터 미지정 → Spring Page (무회귀).
**forward-only** — 역방향 이동 미지원.""",
    )
    @ApiResponses(
        ApiResponse(
            responseCode = "200",
            description = "cursor 모드: CursorPageResponse envelope / offset 모드: Spring Page",
            content = [Content(schema = Schema(implementation = CursorPageResponse::class))],
        ),
        ApiResponse(responseCode = "400", description = "cursor 형식 오류 또는 모드 충돌", content = [Content()]),
        ApiResponse(responseCode = "401", description = "미인증", content = [Content()]),
        ApiResponse(responseCode = "404", description = "이슈 미존재 또는 VIEW 권한 없음", content = [Content()]),
    )
    @SecurityRequirement(name = BEARER_AUTH_SCHEME)
    @GetMapping("/{key}/changelog")
    // ThrowsCount: cursor 모드(PaginationModeConflict + limit초과 400 + 형식오류 400 = 3개) 필연적으로 임계치 초과
    // SwallowedException: IllegalArgumentException → ResponseStatusException 재포장이므로 원본 정보 보존
    @Suppress("LongParameterList", "ThrowsCount", "SwallowedException")
    fun changelog(
        @PathVariable key: String,
        @PageableDefault(size = 20) pageable: Pageable,
        @RequestParam(required = false) cursor: String? = null,
        @RequestParam(required = false) limit: Int? = null,
        @RequestParam(name = "page", required = false) explicitPage: Int? = null,
    ): ResponseEntity<Any> {
        val actor = CurrentActor.current()
        val issueKey = IssueKey(key)
        val svc =
            requireNotNull(changelogService) {
                "IssueChangelogService 가 주입되지 않았습니다. Spring 컨텍스트 구성을 확인하세요."
            }

        return if (cursor != null) {
            if (explicitPage != null) throw PaginationModeConflictException()
            val effectiveLimit = limit ?: DEFAULT_CURSOR_LIMIT
            if (effectiveLimit > MAX_CURSOR_LIMIT) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "limit 은 $MAX_CURSOR_LIMIT 이하여야 합니다.",
                )
            }
            log.info("IssueController.changelog cursor모드 key={} limit={}", key, effectiveLimit)
            // ChangelogCursorCodec.decode 는 CursorDecodeException 을 던진다 → handleCursorDecodeException 으로
            // 흘러 이슈 목록(CursorCodec)과 동일하게 400 ISSUE_INVALID_CURSOR 로 통일된다 (표준화 FR-4/FR-6).
            val cursorPosition = ChangelogCursorCodec.decode(cursor)
            val result = svc.findChangelogByCursor(actor, issueKey, cursorPosition, effectiveLimit)
            ResponseEntity.ok<Any>(
                CursorPageResponse(
                    data = result.items.map { IssueChangelogResponse.from(it) },
                    meta = PageMeta(PageCursor(next = result.next, limit = effectiveLimit)),
                ),
            )
        } else {
            requirePageSizeWithinLimit(pageable)
            log.info("IssueController.changelog offset모드 key={} pageable={}", key, pageable)
            val page = svc.findChangelog(actor, issueKey, pageable).map { IssueChangelogResponse.from(it) }
            ResponseEntity.ok<Any>(page)
        }
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
    @Operation(operationId = "changeAssignee", summary = "담당자 변경/해제", description = "이슈 담당자를 변경하거나 해제한다.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(responseCode = "401", description = "미인증", content = [Content()]),
        ApiResponse(responseCode = "403", description = "권한 없음", content = [Content()]),
        ApiResponse(responseCode = "404", description = "이슈 미존재", content = [Content()]),
        ApiResponse(responseCode = "409", description = "낙관적 잠금 충돌", content = [Content()]),
    )
    @SecurityRequirement(name = BEARER_AUTH_SCHEME)
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
    @Operation(operationId = "changeComponents", summary = "컴포넌트 목록 교체")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(responseCode = "401", description = "미인증", content = [Content()]),
        ApiResponse(responseCode = "404", description = "이슈 또는 컴포넌트 미존재", content = [Content()]),
        ApiResponse(responseCode = "409", description = "낙관적 잠금 충돌", content = [Content()]),
    )
    @SecurityRequirement(name = BEARER_AUTH_SCHEME)
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
    @Operation(operationId = "changeAffectsVersions", summary = "영향 버전 목록 교체")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(responseCode = "401", description = "미인증", content = [Content()]),
        ApiResponse(responseCode = "404", description = "이슈 또는 버전 미존재", content = [Content()]),
        ApiResponse(responseCode = "409", description = "낙관적 잠금 충돌", content = [Content()]),
    )
    @SecurityRequirement(name = BEARER_AUTH_SCHEME)
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
    @Operation(operationId = "changeFixVersions", summary = "수정 예정 버전 목록 교체")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "성공"),
        ApiResponse(responseCode = "401", description = "미인증", content = [Content()]),
        ApiResponse(responseCode = "404", description = "이슈 또는 버전 미존재", content = [Content()]),
        ApiResponse(responseCode = "409", description = "낙관적 잠금 충돌", content = [Content()]),
    )
    @SecurityRequirement(name = BEARER_AUTH_SCHEME)
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
    @Operation(operationId = "exportIssuePdf", summary = "이슈 PDF 내보내기", description = "이슈를 PDF 바이너리로 내보낸다.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "PDF 바이너리", content = [Content(mediaType = "application/pdf")]),
        ApiResponse(responseCode = "401", description = "미인증", content = [Content()]),
        ApiResponse(responseCode = "404", description = "이슈 미존재", content = [Content()]),
    )
    @SecurityRequirement(name = BEARER_AUTH_SCHEME)
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
    @Operation(
        operationId = "deleteIssue",
        summary = "이슈 소프트 삭제",
        description = "이슈를 소프트 삭제한다. 삭제 후 해당 키로 조회하면 404 가 반환된다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "204", description = "삭제 성공"),
        ApiResponse(responseCode = "401", description = "미인증", content = [Content()]),
        ApiResponse(responseCode = "403", description = "삭제 권한 없음", content = [Content()]),
        ApiResponse(responseCode = "404", description = "이슈 미존재", content = [Content()]),
    )
    @SecurityRequirement(name = BEARER_AUTH_SCHEME)
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
    @Operation(operationId = "cloneIssue", summary = "이슈 클론", description = "원본 이슈를 복제하여 새 이슈를 생성한다.")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "클론 성공"),
        ApiResponse(responseCode = "401", description = "미인증", content = [Content()]),
        ApiResponse(responseCode = "403", description = "권한 없음", content = [Content()]),
        ApiResponse(responseCode = "404", description = "원본 이슈 미존재", content = [Content()]),
    )
    @SecurityRequirement(name = BEARER_AUTH_SCHEME)
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

    /**
     * 이슈 백로그 rank 를 변경한다 (FR-BL-01).
     *
     * 이웃 이슈 키를 받아 대상 이슈를 두 이웃 사이에 배치한다.
     * 서버가 이웃 rank 를 조회하고 LexoRank between 을 계산한다.
     * rank 변경은 no-bump(version 불변, updated_at 미갱신) last-write-wins.
     *
     * 응답.
     * 200 OK + { data: { key, rank, version } }
     *
     * @param key path variable 이슈 키 문자열. 예: "BTS-3"
     * @param request 이웃 이슈 키. previousIssueKey/nextIssueKey 최소 하나는 non-null.
     * @return 200 OK + [IssueRankResponse] body
     * @throws com.bts.issue.domain.IssueAccessDeniedException UPDATE 권한 미보유 → 403
     * @throws com.bts.issue.domain.IssueNotFoundException 대상 또는 이웃 이슈 미존재/소프트삭제 → 404
     * @throws com.bts.issue.application.InvalidRankNeighborException 이웃 검증 실패 → 400
     */
    @Operation(operationId = "rerankIssue", summary = "백로그 rank 변경", description = "이슈 백로그 rank 를 이웃 이슈 기준으로 변경한다.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "rank 변경 성공"),
        ApiResponse(responseCode = "400", description = "이웃 이슈 검증 실패", content = [Content()]),
        ApiResponse(responseCode = "401", description = "미인증", content = [Content()]),
        ApiResponse(responseCode = "403", description = "권한 없음", content = [Content()]),
        ApiResponse(responseCode = "404", description = "이슈 미존재", content = [Content()]),
    )
    @SecurityRequirement(name = BEARER_AUTH_SCHEME)
    @PatchMapping("/{key}/rank")
    fun rerank(
        @PathVariable key: String,
        @RequestBody request: RerankIssueRequest,
    ): ResponseEntity<DataResponse<IssueRankResponse>> {
        log.info("IssueController.rerank key={} prev={} next={}", key, request.previousIssueKey, request.nextIssueKey)

        val actor = CurrentActor.current()
        val issueKey = IssueKey(key)
        val rankService =
            requireNotNull(backlogRankService) {
                "BacklogRankService 가 주입되지 않았습니다. Spring 컨텍스트 구성을 확인하세요."
            }

        rankService.rerank(
            actor,
            issueKey,
            request.previousIssueKey?.let { IssueKey(it) },
            request.nextIssueKey?.let { IssueKey(it) },
        )

        // no-bump 라 version 불변 — rank/version 은 BacklogRankService.findRerankResult 로 한 번에 조회한다.
        val result = rankService.findRerankResult(issueKey)
        val version =
            result.version
                ?: error("rerank 직후 이슈(${issueKey.value})의 version 조회 실패 — 동시 삭제가 발생했을 수 있습니다.")

        return ResponseEntity.ok(
            DataResponse(data = IssueRankResponse(key = issueKey.value, rank = result.rank, version = version)),
        )
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

    /**
     * `JsonNullable<LocalDate>` 의 presence 를 [DatePatch] 3-state 로 매핑한다 (FR-PL-01).
     *
     * Jira Cloud 방식 — 필드 부재(undefined)=무변경, 명시 null=날짜 해제, 값=날짜 지정.
     * application 계층이 웹 직렬화 라이브러리(JsonNullable)에 결합되지 않도록 transport 계층에서 변환한다.
     * startDate / dueDate / targetDate 세 필드가 동일 변환 함수를 공용으로 사용한다.
     *
     * @param raw PATCH 요청의 날짜 필드 JsonNullable 값.
     * @return 대응하는 [DatePatch].
     */
    private fun toDatePatch(raw: JsonNullable<LocalDate>): DatePatch =
        when {
            !raw.isPresent -> DatePatch.Unchanged
            raw.get() == null -> DatePatch.Clear
            else -> DatePatch.Set(raw.get())
        }

    /**
     * `JsonNullable<Int>` 의 presence 를 [EstimatePatch] 3-state 로 매핑한다 (FR-TT-01).
     *
     * Jira Cloud 방식 — 필드 부재(undefined)=무변경, 명시 null=해제, 값=지정.
     * application 계층이 웹 직렬화 라이브러리(JsonNullable)에 결합되지 않도록 transport 계층에서 변환한다.
     * originalEstimateSeconds / remainingEstimateSeconds 두 필드가 동일 변환 함수를 공용으로 사용한다.
     *
     * 음수 값은 400 Bad Request 로 거부한다 (비즈니스 불변식 — 추정 시간은 0 이상).
     *
     * @param raw PATCH 요청의 추정 시간 필드 JsonNullable 값.
     * @return 대응하는 [EstimatePatch].
     * @throws org.springframework.web.server.ResponseStatusException (400) 값이 음수인 경우.
     */
    private fun toEstimatePatch(raw: JsonNullable<Int>): EstimatePatch =
        when {
            !raw.isPresent -> EstimatePatch.Unchanged
            raw.get() == null -> EstimatePatch.Clear
            else -> {
                val value = raw.get()
                if (value < 0) {
                    throw org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST,
                        "추정 시간(초)은 0 이상이어야 합니다.",
                    )
                }
                EstimatePatch.Set(value)
            }
        }
}

/**
 * cursor 모드와 offset 모드가 동시에 지정될 때 발생하는 예외 (FR-API-01 Task 3).
 *
 * cursor 모드: `?cursor=` 파라미터 존재. offset 모드: `?page` 또는 `?size` 파라미터.
 * HTTP 400 매핑은 [IssueExceptionHandler] (Task 4) 에서 담당한다.
 */
class PaginationModeConflictException : RuntimeException(
    "cursor 모드와 offset 모드를 동시에 지정할 수 없습니다. cursor 또는 page/size 중 하나만 사용하세요.",
)

/**
 * cursor 페이지네이션 응답 envelope (SDD 11.3 §3.1, FR-API-01 FR-5).
 *
 * cursor 모드([IssueController.list] cursor 파라미터 지정 시)에서만 반환된다.
 * offset 모드는 기존 Spring [org.springframework.data.domain.Page]<[IssueResponse]> 구조를 유지한다.
 *
 * 직렬화 결과: `{ "data": [...], "meta": { "page": { "next": "...|null", "limit": N } } }`.
 *
 * @param T 응답 데이터 타입.
 * @property data 조회된 이슈 목록.
 * @property meta 페이지네이션 메타.
 */
data class CursorPageResponse<T>(
    val data: List<T>,
    val meta: PageMeta,
)

/**
 * cursor 페이지네이션 응답 메타.
 *
 * @property page cursor 정보.
 */
data class PageMeta(val page: PageCursor)

/**
 * cursor 페이지 위치 정보.
 *
 * @property next 다음 페이지 cursor 토큰 (opaque Base64URL). 마지막 페이지이면 null.
 * @property limit 이 페이지에서 요청한 최대 건수.
 */
data class PageCursor(
    val next: String?,
    val limit: Int,
)

/**
 * 성공 응답 래퍼.
 *
 * @param T 응답 데이터 타입
 * @property data 응답 페이로드
 */
data class DataResponse<T>(val data: T)

/**
 * 이슈 rank 변경 응답 DTO (FR-BL-01 spec #14).
 *
 * rank 변경은 no-bump 이므로 version 이 불변임을 반영한다.
 *
 * @property key 이슈 키 문자열. 예: "BTS-3"
 * @property rank 변경된 rank 값. 옵션 B lazy 미부여 상태면 null 일 수 있다.
 * @property version 이슈 낙관적 잠금 버전. no-bump 이므로 rerank 전후 동일.
 */
data class IssueRankResponse(
    val key: String,
    val rank: String?,
    val version: Long,
)
