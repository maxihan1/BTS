// BulkOperationController — POST 일괄 작업 접수(202) + GET 조회(200) + POST 가용 전환 조회(200) REST 엔드포인트

package com.bts.issue.bulk.web

import com.bts.issue.adapter.inbound.rest.CurrentActor
import com.bts.issue.adapter.inbound.rest.DataResponse
import com.bts.issue.bulk.application.BulkAvailableTransitionsService
import com.bts.issue.bulk.application.BulkEditPayload
import com.bts.issue.bulk.application.BulkOperationApplicationService
import com.bts.issue.bulk.application.BulkTransitionPayload
import com.bts.issue.bulk.application.BulkUpdateRequest
import com.bts.issue.bulk.domain.BULK_OPERATION_MAX_SIZE
import com.bts.issue.bulk.domain.BulkOperationId
import com.bts.issue.bulk.domain.BulkOperationType
import com.bts.issue.bulk.repository.BulkOperationRepository
import com.bts.issue.config.BEARER_AUTH_SCHEME
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 일괄 작업 REST API 컨트롤러.
 *
 * 엔드포인트 목록.
 * - POST  /api/v1/issues/bulk-update — 일괄 작업 접수 (PR1 Task 8)
 * - GET   /api/v1/bulk-operations/{id} — 일괄 작업 조회 (PR1 Task 8)
 * - POST  /api/v1/issues/bulk-transitions/available — 일괄 가용 전환 조회 (FR-IS-05 Task 3)
 *
 * ### 트랜잭션 정책
 * 컨트롤러는 트랜잭션 경계를 담당하지 않는다.
 * 쓰기는 [BulkOperationApplicationService], 읽기는 [BulkOperationRepository] 가 담당한다.
 *
 * ### ActorId 결선
 * 세 엔드포인트 모두 [CurrentActor.current] 로 SecurityContext 의 인증 주체를 actor 로 쓴다.
 * 미인증이면 401 이다.
 *
 * actor 추출은 리소스 조회(404)보다 **앞서** 수행한다. 순서가 뒤집히면 미인증자가 404·403 차이로
 * 작업 존재를 probe 할 수 있다.
 *
 * 고정 sentinel UUID 를 쓰던 시기에는 접수와 조회를 **같은 컨트롤러가 해서** 양쪽 값이 자기들끼리
 * 맞아 소유자 판정이 통과했다. 컨트롤러 밖(`WorkflowStatusMigrationAdapter`)에서 실제 발행자 UUID 로
 * 큐잉되는 `STATUS_MIGRATION` 이 생기면서 그 조회가 항상 403 이 됐다.
 *
 * ### PR1 범위
 * 접수(submit) + 조회만 구현한다. 처리/워커(PR2) 호출 금지.
 *
 * @param service 일괄 작업 접수 유스케이스 서비스.
 * @param repo 일괄 작업 Repository (조회 전용).
 * @param bulkAvailableTransitionsService 일괄 가용 전환 조회 서비스.
 */
@Tag(
    name = "Bulk Operations",
    description = "이슈 일괄 작업 접수·조회 및 일괄 가용 전환 조회 API (FR-IS-05)",
)
@RestController
class BulkOperationController(
    private val service: BulkOperationApplicationService,
    private val repo: BulkOperationRepository,
    private val bulkAvailableTransitionsService: BulkAvailableTransitionsService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 일괄 작업을 접수한다.
     *
     * 요청이 유효하면 [BulkOperationApplicationService.submit] 을 호출하여 작업을 영속·enqueue 하고
     * 202 Accepted 와 함께 접수 응답을 반환한다.
     *
     * @param request 일괄 작업 접수 요청 바디.
     * @return 202 Accepted + [BulkOperationAcceptedResponse] body
     * @throws IllegalArgumentException issueKeys 비어있음/1000 초과/payload 불일치 시 → 400 (핸들러 처리)
     */
    @Operation(
        summary = "이슈 일괄 작업 접수",
        description =
            "여러 이슈에 일괄 수정(BULK_EDIT) 또는 일괄 전환(BULK_TRANSITION)를 비동기로 접수한다. " +
                "202 Accepted 와 함께 추적용 bulkOperationId 를 반환하며, 실제 처리는 백그라운드 워커가 수행한다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "202", description = "접수 완료 — bulkOperationId 반환"),
        ApiResponse(responseCode = "400", description = "issueKeys 비어있음/상한 초과/payload 불일치", content = [Content()]),
        ApiResponse(responseCode = "401", description = "미인증", content = [Content()]),
    )
    @SecurityRequirement(name = BEARER_AUTH_SCHEME)
    @PostMapping("/api/v1/issues/bulk-update")
    fun submit(
        @Valid @RequestBody request: BulkUpdateWebRequest,
    ): ResponseEntity<DataResponse<BulkOperationAcceptedResponse>> {
        log.info(
            "BulkOperationController.submit operationType={} issueKeysSize={}",
            request.operationType,
            request.issueKeys.size,
        )

        val actor = CurrentActor.current()
        val appRequest =
            BulkUpdateRequest(
                operationType = request.operationType,
                issueKeys = request.issueKeys,
                editPayload = request.editPayload,
                transitionPayload = request.transitionPayload,
            )
        val operationId = service.submit(actor, appRequest)

        val response =
            BulkOperationAcceptedResponse(
                bulkOperationId = operationId.value,
                status = "PENDING",
                totalCount = request.issueKeys.distinct().size,
            )
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(DataResponse(data = response))
    }

    /**
     * 일괄 작업 단건을 조회한다.
     *
     * 조회 권한: 작업을 접수한 actor 만 허용한다.
     * 다른 actor 가 조회하면 [BulkOperationForbiddenException] → 403.
     * 작업이 없으면 [BulkOperationNotFoundException] → 404.
     * items 는 cartesian product 방지를 위해 별쿼리로 조회한다 (learnings: jOOQ-cartesian-product).
     *
     * @param id path variable 작업 UUID 문자열.
     * @return 200 OK + [BulkOperationResponse] body
     * @throws BulkOperationNotFoundException id 에 해당하는 작업이 없을 때 → 404
     * @throws BulkOperationForbiddenException 요청 actor 가 작업 owner 가 아닐 때 → 403
     */
    @Operation(
        summary = "일괄 작업 단건 조회",
        description = "bulkOperationId 로 일괄 작업의 상태·진행 항목을 조회한다. 작업을 접수한 actor 만 조회할 수 있다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "조회 성공"),
        ApiResponse(responseCode = "401", description = "미인증", content = [Content()]),
        ApiResponse(responseCode = "403", description = "작업 owner 가 아님", content = [Content()]),
        ApiResponse(responseCode = "404", description = "작업 없음", content = [Content()]),
    )
    @SecurityRequirement(name = BEARER_AUTH_SCHEME)
    @GetMapping("/api/v1/bulk-operations/{id}")
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<DataResponse<BulkOperationResponse>> {
        log.info("BulkOperationController.get id={}", id)

        val actor = CurrentActor.current()
        val operationId = BulkOperationId(id)

        val operation =
            repo.findById(operationId)
                ?: throw BulkOperationNotFoundException(id)

        if (operation.actorId != actor.value) {
            throw BulkOperationForbiddenException(id, actor.value)
        }

        val items = repo.findItemsByOperationId(operationId)
        val response = BulkOperationResponse.from(operation, items)
        return ResponseEntity.ok(DataResponse(data = response))
    }

    /**
     * 여러 이슈에 공통으로 적용 가능한 전환 목록을 조회한다.
     *
     * issueKeys 의 각 이슈에 대해 best-effort 로 가용 전환을 조회한 후 교집합을 반환한다.
     * 조회에 실패한 이슈(미존재·워크플로우 미설정·접근 불가)는 unresolvedIssueKeys 에 포함한다.
     *
     * @param request 조회 대상 이슈 키 목록.
     * @return 200 OK + [BulkAvailableTransitionsResponse] body
     * @throws IllegalArgumentException issueKeys 비어있음 또는 1000 초과 시 → 400 (핸들러 처리)
     */
    @Operation(
        summary = "일괄 가용 전환 조회",
        description =
            "여러 이슈에 공통으로 적용 가능한 워크플로우 전환 목록(교집합)을 조회한다. " +
                "조회에 실패한 이슈(미존재·워크플로우 미설정·접근 불가)는 unresolvedIssueKeys 로 반환한다.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "공통 가용 전환 + 미해결 이슈 키 반환"),
        ApiResponse(responseCode = "400", description = "issueKeys 비어있음/상한 초과", content = [Content()]),
        ApiResponse(responseCode = "401", description = "미인증", content = [Content()]),
    )
    @SecurityRequirement(name = BEARER_AUTH_SCHEME)
    @PostMapping("/api/v1/issues/bulk-transitions/available")
    fun availableTransitions(
        @RequestBody request: BulkAvailableTransitionsRequest,
    ): ResponseEntity<DataResponse<BulkAvailableTransitionsResponse>> {
        require(request.issueKeys.isNotEmpty()) {
            "issueKeys must not be empty"
        }
        require(request.issueKeys.size <= BULK_OPERATION_MAX_SIZE) {
            "issueKeys must not exceed $BULK_OPERATION_MAX_SIZE, but was ${request.issueKeys.size}"
        }

        log.info(
            "BulkOperationController.availableTransitions issueKeysSize={}",
            request.issueKeys.size,
        )

        val actor = CurrentActor.current()
        val result = bulkAvailableTransitionsService.availableCommonTransitions(actor, request.issueKeys)
        val response = BulkAvailableTransitionsResponse.from(result)
        return ResponseEntity.ok(DataResponse(data = response))
    }
}

/**
 * 일괄 작업 접수 HTTP 요청 바디 DTO.
 *
 * [com.bts.issue.bulk.application.BulkUpdateRequest] (application 커맨드) 와 분리된 transport 계층 DTO.
 *
 * @property operationType 작업 유형.
 * @property issueKeys 처리 대상 이슈 키 목록.
 * @property editPayload BULK_EDIT 전용 페이로드.
 * @property transitionPayload BULK_TRANSITION 전용 페이로드.
 */
data class BulkUpdateWebRequest(
    @field:NotNull
    val operationType: BulkOperationType,
    @field:NotNull
    val issueKeys: List<String>,
    val editPayload: BulkEditPayload?,
    val transitionPayload: BulkTransitionPayload?,
)
