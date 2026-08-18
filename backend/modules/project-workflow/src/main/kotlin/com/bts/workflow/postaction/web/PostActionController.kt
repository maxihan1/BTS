// 워크플로우 전환 post-action CRUD REST 컨트롤러 — MANAGE_SCHEME+Global Guard

package com.bts.workflow.postaction.web

import com.bts.shared.permission.WorkflowSchemePermission
import com.bts.shared.permission.WorkflowSchemePermissionResolver
import com.bts.shared.permission.WorkflowSchemeScope
import com.bts.workflow.port.outbound.toUuid
import com.bts.workflow.postaction.PostActionAdminService
import com.bts.workflow.scheme.web.DataEnvelope
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
 * 워크플로우 전환 post-action CRUD REST 컨트롤러.
 *
 * 경로: `GET/POST /api/v1/workflows/{workflowKey}/transitions/{transitionKey}/post-actions`
 *       `PUT/DELETE .../post-actions/{id}`
 *
 * ### 권한 Guard
 * 모든 엔드포인트(GET 포함) 는 컨트롤러 진입 직후 [WorkflowSchemePermissionResolver.requirePermission]
 * 으로 MANAGE_SCHEME + Global 권한을 검증한다. 리소스 조회 이전에 호출해 존재 probe 를 방지한다.
 * (메모리 auth-extraction-before-resource-lookup 준수)
 *
 * ### 권한 예외 누출 방지
 * [WorkflowSchemeAccessDeniedException] 메시지에 actorId/permission/scope 등 내부 정보가
 * 포함되므로, [WorkflowSchemeExceptionHandler] 가 detail 을 일반 메시지로 교체해 HTTP 응답에서
 * 내부 식별자가 누출되지 않도록 한다 (메모리 fr-pm-04-guard-exception-message-http-leak).
 *
 * @param service post-action 관리 서비스.
 * @param permissionResolver MANAGE_SCHEME 권한 평가 outbound port.
 */
@RestController
@RequestMapping("/api/v1/workflows/{workflowKey}/transitions/{transitionKey}/post-actions")
class PostActionController(
    private val service: PostActionAdminService,
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
     * 전환에 속한 post-action 목록을 반환한다.
     *
     * @param workflowKey 워크플로우 식별 키.
     * @param transitionKey `fromStateKey__toStateKey` 형식의 전환 자연키.
     * @return 200 OK + [PostActionResponse] 목록.
     */
    @GetMapping
    fun list(
        @PathVariable workflowKey: String,
        @PathVariable transitionKey: String,
    ): ResponseEntity<DataEnvelope<List<PostActionResponse>>> {
        requireManageScheme()
        log.debug("PostActionController.list workflowKey={} transitionKey={}", workflowKey, transitionKey)
        val rows = service.listForTransition(workflowKey, transitionKey)
        return ResponseEntity.ok(DataEnvelope(rows.map { PostActionResponse.from(it) }))
    }

    /**
     * post-action 을 생성한다.
     *
     * @param workflowKey 워크플로우 식별 키.
     * @param transitionKey 전환 자연키.
     * @param request 생성 요청 바디.
     * @return 201 Created + 생성된 [PostActionResponse].
     */
    @PostMapping
    fun create(
        @PathVariable workflowKey: String,
        @PathVariable transitionKey: String,
        @RequestBody request: PostActionRequest,
    ): ResponseEntity<DataEnvelope<PostActionResponse>> {
        requireManageScheme()
        log.info(
            "PostActionController.create workflowKey={} transitionKey={} type={}",
            workflowKey,
            transitionKey,
            request.type,
        )
        val row = service.create(workflowKey, transitionKey, request.type, request.config, request.displayOrder)
        return ResponseEntity.status(HttpStatus.CREATED).body(DataEnvelope(PostActionResponse.from(row)))
    }

    /**
     * post-action 을 수정한다.
     *
     * @param workflowKey 워크플로우 식별 키.
     * @param transitionKey 전환 자연키.
     * @param id 수정할 post-action UUID.
     * @param request 수정 요청 바디.
     * @return 200 OK + 수정된 [PostActionResponse].
     */
    @PutMapping("/{id}")
    fun update(
        @PathVariable workflowKey: String,
        @PathVariable transitionKey: String,
        @PathVariable id: UUID,
        @RequestBody request: PostActionRequest,
    ): ResponseEntity<DataEnvelope<PostActionResponse>> {
        requireManageScheme()
        log.info(
            "PostActionController.update workflowKey={} transitionKey={} id={} type={}",
            workflowKey,
            transitionKey,
            id,
            request.type,
        )
        val row = service.update(workflowKey, transitionKey, id, request.type, request.config, request.displayOrder)
        return ResponseEntity.ok(DataEnvelope(PostActionResponse.from(row)))
    }

    /**
     * post-action 을 삭제한다.
     *
     * @param workflowKey 워크플로우 식별 키.
     * @param transitionKey 전환 자연키.
     * @param id 삭제할 post-action UUID.
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
            "PostActionController.delete workflowKey={} transitionKey={} id={}",
            workflowKey,
            transitionKey,
            id,
        )
        service.delete(workflowKey, transitionKey, id)
    }
}
