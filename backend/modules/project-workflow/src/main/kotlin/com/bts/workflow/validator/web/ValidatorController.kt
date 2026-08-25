// 워크플로우 전환 validator CRUD REST 컨트롤러 — MANAGE_SCHEME+Global Guard 선행

package com.bts.workflow.validator.web

import com.bts.shared.permission.WorkflowSchemePermission
import com.bts.shared.permission.WorkflowSchemePermissionResolver
import com.bts.shared.permission.WorkflowSchemeScope
import com.bts.workflow.port.outbound.toUuid
import com.bts.workflow.scheme.web.DataEnvelope
import com.bts.workflow.validator.ValidatorAdminService
import com.bts.workflow.web.CurrentActor
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 워크플로우 전환 validator CRUD REST 컨트롤러.
 *
 * 경로: `GET/POST /api/v1/workflows/{workflowKey}/transitions/{transitionKey}/validators`
 *       `PUT/DELETE .../validators/{id}`
 *
 * 형제인 `PostActionController` 와 세그먼트 규칙이 같다. `transitionKey` 는 전환 id(UUID) 이거나
 * 종전 `fromStateKey__toStateKey` 합성 키이며, 해석은 [ValidatorAdminService] 가 맡는다.
 *
 * ### 권한 Guard 가 리소스 조회보다 먼저다
 * 모든 엔드포인트(**GET 포함**) 는 진입 직후 [requireManageScheme] 로 MANAGE_SCHEME + Global 을
 * 검증한다. 순서가 뒤집히면 403 이 거짓말을 한다 — 없는 워크플로우에는 404, 있는 워크플로우에는
 * 403 이 나가서 권한 없는 사용자가 전환의 실재 여부를 응답만으로 알아낸다(존재 probe).
 * (메모리 permission-assert-before-existence-makes-403-lie · auth-extraction-before-resource-lookup)
 *
 * ### 권한 예외 누출 방지
 * [com.bts.shared.permission.WorkflowSchemeAccessDeniedException] 메시지에는 actorId·permission·
 * scope 가 들어 있다. [ValidatorExceptionHandler] 가 그 상세를 로그로만 남기고 응답 본문은 일반
 * 메시지로 바꾼다 (메모리 fr-pm-04-guard-exception-message-http-leak).
 *
 * ### 트랜잭션
 * 컨트롤러는 경계를 열지 않는다. `@Transactional` 은 [ValidatorAdminService] 쪽에만 있다.
 *
 * @param service validator 관리 서비스.
 * @param permissionResolver MANAGE_SCHEME 권한 평가 outbound port.
 */
@RestController
@RequestMapping("/api/v1/workflows/{workflowKey}/transitions/{transitionKey}/validators")
class ValidatorController(
    private val service: ValidatorAdminService,
    private val permissionResolver: WorkflowSchemePermissionResolver,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private fun requireManageScheme() {
        val actor = CurrentActor.current()
        permissionResolver.requirePermission(
            actor.toUuid(),
            WorkflowSchemePermission.MANAGE_SCHEME,
            WorkflowSchemeScope.Global,
        )
    }

    /**
     * 전환에 속한 validator 목록을 반환한다.
     *
     * 편집할 수 없는 type 의 행도 숨기지 않는다 — 반쪽 목록은 관리자를 속인다.
     *
     * @param workflowKey 워크플로우 식별 키.
     * @param transitionKey 전환 id(UUID) 또는 종전 `fromStateKey__toStateKey` 합성 키.
     * @return 200 OK + [ValidatorResponse] 목록 (displayOrder ASC).
     */
    @GetMapping
    fun list(
        @PathVariable workflowKey: String,
        @PathVariable transitionKey: String,
    ): ResponseEntity<DataEnvelope<List<ValidatorResponse>>> {
        requireManageScheme()
        log.debug("ValidatorController.list workflowKey={} transitionKey={}", workflowKey, transitionKey)
        val rows = service.listForTransition(workflowKey, transitionKey)
        return ResponseEntity.ok(DataEnvelope(rows.map { ValidatorResponse.from(it) }))
    }

    /**
     * validator 를 생성한다.
     *
     * @param workflowKey 워크플로우 식별 키.
     * @param transitionKey 전환 id(UUID) 또는 종전 합성 키.
     * @param request 생성 요청 바디.
     * @return 201 Created + 생성된 [ValidatorResponse].
     */
    @PostMapping
    fun create(
        @PathVariable workflowKey: String,
        @PathVariable transitionKey: String,
        @RequestBody request: ValidatorRequest,
    ): ResponseEntity<DataEnvelope<ValidatorResponse>> {
        requireManageScheme()
        log.info(
            "ValidatorController.create workflowKey={} transitionKey={} type={}",
            workflowKey,
            transitionKey,
            request.type,
        )
        val row = service.create(workflowKey, transitionKey, request.type, request.config, request.displayOrder)
        return ResponseEntity.status(HttpStatus.CREATED).body(DataEnvelope(ValidatorResponse.from(row)))
    }

    /**
     * validator 를 수정한다.
     *
     * @param workflowKey 워크플로우 식별 키.
     * @param transitionKey 전환 id(UUID) 또는 종전 합성 키.
     * @param id 수정할 validator UUID.
     * @param request 수정 요청 바디.
     * @return 200 OK + 수정된 [ValidatorResponse].
     */
    @PutMapping("/{id}")
    fun update(
        @PathVariable workflowKey: String,
        @PathVariable transitionKey: String,
        @PathVariable id: UUID,
        @RequestBody request: ValidatorRequest,
    ): ResponseEntity<DataEnvelope<ValidatorResponse>> {
        requireManageScheme()
        log.info(
            "ValidatorController.update workflowKey={} transitionKey={} id={} type={}",
            workflowKey,
            transitionKey,
            id,
            request.type,
        )
        val row = service.update(workflowKey, transitionKey, id, request.type, request.config, request.displayOrder)
        return ResponseEntity.ok(DataEnvelope(ValidatorResponse.from(row)))
    }

    /**
     * validator 를 삭제한다.
     *
     * @param workflowKey 워크플로우 식별 키.
     * @param transitionKey 전환 id(UUID) 또는 종전 합성 키.
     * @param id 삭제할 validator UUID.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @PathVariable workflowKey: String,
        @PathVariable transitionKey: String,
        @PathVariable id: UUID,
    ) {
        requireManageScheme()
        log.info(
            "ValidatorController.delete workflowKey={} transitionKey={} id={}",
            workflowKey,
            transitionKey,
            id,
        )
        service.delete(workflowKey, transitionKey, id)
    }
}
