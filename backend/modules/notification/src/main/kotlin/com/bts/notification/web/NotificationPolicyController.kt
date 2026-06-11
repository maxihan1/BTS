// 알림 정책 REST API 컨트롤러 — 정책 카탈로그 조회 + CRUD 엔드포인트 (FR-NT-01)

package com.bts.notification.web

import com.bts.notification.application.NotificationPolicyService
import com.bts.notification.domain.Channel
import com.bts.notification.domain.NotificationEventType
import com.bts.notification.domain.RecipientRole
import com.bts.notification.web.dto.CreatePolicyRequest
import com.bts.notification.web.dto.NotificationPolicyResponse
import com.bts.notification.web.dto.PolicyCatalogResponse
import com.bts.notification.web.dto.TogglePolicyRequest
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
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
import java.util.UUID

/**
 * 알림 정책 REST API 컨트롤러.
 *
 * 엔드포인트 목록.
 * - GET    /api/v1/notification-policies/catalog — enum 카탈로그 조회
 * - GET    /api/v1/notification-policies?projectKey= — 정책 목록 조회
 * - POST   /api/v1/notification-policies — 정책 생성
 * - PATCH  /api/v1/notification-policies/{id} — 정책 토글(활성/비활성)
 * - DELETE /api/v1/notification-policies/{id} — 정책 삭제
 *
 * ### ActorId 추출 정책
 * [currentActorId] 헬퍼가 [SecurityContextHolder] 에서 UUID 인증 주체를 추출한다.
 * 미인증·익명·비-UUID·nil-UUID 주체는 401(UNAUTHORIZED)로 거부한다.
 * actor 추출은 리소스 조회보다 앞서 수행하여 미인증자가 리소스 존재를 probe 하지 못하게 한다
 * (memory: auth-extraction-before-resource-lookup 교훈).
 *
 * ### enum 파싱 정책
 * [CreatePolicyRequest] 의 eventType / recipientRole / channel 은 문자열로 수신하여
 * 서비스 호출 직전에 enum 으로 파싱한다. 알 수 없는 값이면 [IllegalArgumentException] 을 던지고,
 * [NotificationExceptionHandler] 가 400 응답으로 변환한다.
 *
 * @param service 알림 정책 CRUD 서비스
 */
@RestController
@RequestMapping("/api/v1/notification-policies")
class NotificationPolicyController(
    private val service: NotificationPolicyService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 알림 정책 생성에 사용할 수 있는 enum 카탈로그를 반환한다.
     *
     * SYSTEM_ADMIN 권한이 없어도 조회 가능하다 — 생성 폼 UI 에서 선택 목록을 렌더링하는 데 사용한다.
     * actor 추출은 catalog 조회에도 적용하여 미인증 요청을 차단한다.
     *
     * @return 200 OK + [PolicyCatalogResponse] (eventTypes / recipientRoles / channels 목록)
     */
    @GetMapping("/catalog")
    fun getCatalog(): ResponseEntity<DataResponse<PolicyCatalogResponse>> {
        currentActorId() // 인증 검증 — 리소스 노출 전에 수행
        log.debug("NotificationPolicyController.getCatalog")
        return ResponseEntity.ok(DataResponse(data = PolicyCatalogResponse.fromEnums()))
    }

    /**
     * 범위별 알림 정책 목록을 조회한다.
     *
     * [projectKey] 가 없으면 전역 기본 정책 목록을 반환한다.
     *
     * @param projectKey 프로젝트 키 (null = 전역)
     * @return 200 OK + [NotificationPolicyResponse] 목록
     */
    @GetMapping
    fun listPolicies(
        @RequestParam(required = false) projectKey: String?,
    ): ResponseEntity<DataResponse<List<NotificationPolicyResponse>>> {
        val actorId = currentActorId()
        log.debug("NotificationPolicyController.listPolicies projectKey={}", projectKey)
        val policies = service.list(actorId, projectKey)
        return ResponseEntity.ok(DataResponse(data = policies.map { NotificationPolicyResponse.from(it) }))
    }

    /**
     * 새 알림 정책을 생성한다.
     *
     * [CreatePolicyRequest.eventType] / [recipientRole] / [channel] 문자열을 enum 으로 파싱한다.
     * 알 수 없는 값이면 [IllegalArgumentException] → 400.
     *
     * @param request 생성 요청 바디 (Jakarta Validation 적용)
     * @return 201 Created + [NotificationPolicyResponse]
     */
    @PostMapping
    @Suppress("ThrowsCount") // enum 파싱 3종 검증 throw — 분리보다 응집이 적합
    fun createPolicy(
        @Valid @RequestBody request: CreatePolicyRequest,
    ): ResponseEntity<DataResponse<NotificationPolicyResponse>> {
        val actorId = currentActorId()

        val eventType =
            NotificationEventType.fromWire(request.eventType)
                ?: throw IllegalArgumentException("알 수 없는 eventType: ${request.eventType}")
        val recipientRole =
            RecipientRole.fromWire(request.recipientRole)
                ?: throw IllegalArgumentException("알 수 없는 recipientRole: ${request.recipientRole}")
        val channel =
            Channel.fromWire(request.channel)
                ?: throw IllegalArgumentException("알 수 없는 channel: ${request.channel}")

        log.info(
            "NotificationPolicyController.createPolicy actorId={}, eventType={}, role={}, channel={}",
            actorId,
            request.eventType,
            request.recipientRole,
            request.channel,
        )

        val created =
            service.create(
                actorId = actorId,
                projectKey = request.projectKey,
                eventType = eventType,
                recipientRole = recipientRole,
                channel = channel,
                enabled = request.enabled,
            )
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(DataResponse(data = NotificationPolicyResponse.from(created)))
    }

    /**
     * 알림 정책의 활성/비활성 상태를 전환한다.
     *
     * @param id 전환할 정책 UUID
     * @param request 토글 요청 바디
     */
    @PatchMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun togglePolicy(
        @PathVariable id: UUID,
        @Valid @RequestBody request: TogglePolicyRequest,
    ) {
        val actorId = currentActorId()
        log.info("NotificationPolicyController.togglePolicy id={}, enabled={}", id, request.enabled)
        service.toggle(actorId, id, request.enabled)
    }

    /**
     * 알림 정책을 삭제한다.
     *
     * @param id 삭제할 정책 UUID
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deletePolicy(
        @PathVariable id: UUID,
    ) {
        val actorId = currentActorId()
        log.info("NotificationPolicyController.deletePolicy id={}", id)
        service.delete(actorId, id)
    }

    // ── private helpers ────────────────────────────────────────────────────────

    /**
     * [SecurityContextHolder] 에서 인증 주체 UUID 를 추출한다.
     *
     * 미인증·익명·비-UUID 주체는 401(UNAUTHORIZED)로 거부한다.
     * issue-tracking BC 의 `CurrentActor` 와 동일한 패턴을 따른다.
     *
     * @return 인증 주체 UUID
     * @throws ResponseStatusException 인증이 없거나 주체가 유효한 UUID 가 아닐 때 (401)
     */
    private fun currentActorId(): UUID {
        val authentication =
            SecurityContextHolder.getContext().authentication
                ?.takeIf { it.isAuthenticated && it !is AnonymousAuthenticationToken }
                ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required")
        return try {
            UUID.fromString(authentication.name)
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required", e)
        }
    }
}

/**
 * 성공 응답 래퍼 — `{ "data": T }` 형태로 직렬화된다.
 *
 * issue-tracking BC 의 [com.bts.issue.adapter.inbound.rest.DataResponse] 와 동일한 구조다.
 * notification BC 는 shared-kernel 에 의존할 수 없으므로 독립 선언한다.
 *
 * @param data 실제 응답 페이로드
 */
data class DataResponse<T>(
    val data: T,
)
