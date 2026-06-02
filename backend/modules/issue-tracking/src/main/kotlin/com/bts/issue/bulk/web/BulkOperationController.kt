// BulkOperationController — POST 일괄 작업 접수(202) + GET 조회(200) REST 엔드포인트

package com.bts.issue.bulk.web

import com.bts.issue.adapter.inbound.rest.DataResponse
import com.bts.issue.bulk.application.BulkEditPayload
import com.bts.issue.bulk.application.BulkOperationApplicationService
import com.bts.issue.bulk.application.BulkTransitionPayload
import com.bts.issue.bulk.application.BulkUpdateRequest
import com.bts.issue.bulk.domain.BulkOperationId
import com.bts.issue.bulk.domain.BulkOperationType
import com.bts.issue.bulk.repository.BulkOperationRepository
import com.bts.issue.domain.ActorId
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
 *
 * ### 트랜잭션 정책
 * 컨트롤러는 트랜잭션 경계를 담당하지 않는다.
 * 쓰기는 [BulkOperationApplicationService], 읽기는 [BulkOperationRepository] 가 담당한다.
 *
 * ### ActorId 임시 처리
 * security context 연동 전까지 고정 UUID 를 사용한다.
 * 인증 연동은 이후 security-engineer wave 에서 SecurityContextHolder 로 교체 예정.
 *
 * ### PR1 범위
 * 접수(submit) + 조회만 구현한다. 처리/워커(PR2) 호출 금지.
 *
 * @param service 일괄 작업 접수 유스케이스 서비스.
 * @param repo 일괄 작업 Repository (조회 전용).
 */
@RestController
class BulkOperationController(
    private val service: BulkOperationApplicationService,
    private val repo: BulkOperationRepository,
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
    @PostMapping("/api/v1/issues/bulk-update")
    fun submit(
        @Valid @RequestBody request: BulkUpdateWebRequest,
    ): ResponseEntity<DataResponse<BulkOperationAcceptedResponse>> {
        log.info(
            "BulkOperationController.submit operationType={} issueKeysSize={}",
            request.operationType,
            request.issueKeys.size,
        )

        // 임시 fallback — security-engineer wave 에서 SecurityContextHolder 의 인증된 UUID 로 교체 예정.
        val actor = ActorId(SYSTEM_ACTOR_UUID)
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
    @GetMapping("/api/v1/bulk-operations/{id}")
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<DataResponse<BulkOperationResponse>> {
        log.info("BulkOperationController.get id={}", id)

        // 임시 fallback — security-engineer wave 에서 SecurityContextHolder 의 인증된 UUID 로 교체 예정.
        val actor = ActorId(SYSTEM_ACTOR_UUID)
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

    companion object {
        /** 인증 연동 전 임시 사용하는 시스템 행위자 UUID. */
        private val SYSTEM_ACTOR_UUID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
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
