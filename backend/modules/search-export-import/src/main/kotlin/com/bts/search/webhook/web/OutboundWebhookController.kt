// 아웃바운드 webhook 구독 CRUD REST 컨트롤러 — /api/v1/webhooks (전 엔드포인트 SYSTEM_ADMIN) (FR-API-03 PR2)

package com.bts.search.webhook.web

import com.bts.search.webhook.application.OutboundWebhookService
import com.bts.search.webhook.web.dto.CreateWebhookRequest
import com.bts.search.webhook.web.dto.UpdateWebhookRequest
import com.bts.search.webhook.web.dto.WebhookResponse
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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * 아웃바운드 webhook 구독 CRUD REST 컨트롤러.
 *
 * 엔드포인트(`/api/v1/webhooks`).
 * - `POST` 생성(201) / `GET` 목록(offset paging) / `GET /{id}` 단건 / `PUT /{id}` 전체 교체(OCC) /
 *   `DELETE /{id}` 소프트 삭제(204).
 *
 * ## 보안 경계
 * 모든 엔드포인트는 SYSTEM_ADMIN 전용이다. actor 추출([OutboundWebhookActorExtractor])을 서비스
 * 호출(권한 판정·리소스 조회)보다 **먼저** 수행해 존재 probe 를 차단한다. admin 판정·SSRF 검증·
 * secret 암호화·OCC 는 [OutboundWebhookService] 가 담당하며, 예외→HTTP 매핑(식별자·비밀값 미노출
 * 일반 메시지 치환)은 [OutboundWebhookExceptionHandler] 가 처리한다.
 *
 * secret 평문은 요청 바디로만 받고 로그/응답에 절대 남기지 않는다 — 로깅은 설정 여부(hasSecret)만 남긴다.
 *
 * @param service 아웃바운드 webhook 구독 애플리케이션 서비스.
 */
@RestController
@RequestMapping("/api/v1/webhooks")
class OutboundWebhookController(
    private val service: OutboundWebhookService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 새 webhook 구독을 생성한다.
     *
     * @param request 생성 요청 바디.
     * @return 201 Created + [WebhookResponse](secret 원문 미포함).
     */
    @PostMapping
    fun create(
        @RequestBody request: CreateWebhookRequest,
    ): ResponseEntity<WebhookResponse> {
        val actorId = OutboundWebhookActorExtractor.extract()
        val name = required(request.name, "name")
        val url = required(request.url, "url")
        log.info(
            "OutboundWebhookController.create actor={} name={} hasSecret={}",
            actorId,
            name,
            !request.secret.isNullOrBlank(),
        )
        val created =
            service.create(
                actorId = actorId,
                name = name,
                url = url,
                eventFilter = request.eventFilter.orEmpty(),
                secret = request.secret,
                projectKey = request.projectKey,
                enabled = request.enabled ?: true,
            )
        return ResponseEntity.status(HttpStatus.CREATED).body(WebhookResponse.from(created))
    }

    /**
     * webhook 구독 목록을 offset 페이지네이션으로 반환한다.
     *
     * @param page 0-based 페이지 번호(기본 0).
     * @param size 페이지 크기(기본 [DEFAULT_PAGE_SIZE], [MIN_PAGE_SIZE]..[MAX_PAGE_SIZE] 범위).
     * @return 200 OK + [WebhookResponse] 목록.
     */
    @GetMapping
    fun list(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "$DEFAULT_PAGE_SIZE") size: Int,
    ): ResponseEntity<List<WebhookResponse>> {
        val actorId = OutboundWebhookActorExtractor.extract()
        validatePaging(page, size)
        val list = service.list(actorId, page, size).map { WebhookResponse.from(it) }
        return ResponseEntity.ok(list)
    }

    /**
     * webhook 구독 단건을 조회한다.
     *
     * @param id 구독 식별자.
     * @return 200 OK + [WebhookResponse].
     */
    @GetMapping("/{id}")
    fun get(
        @PathVariable id: UUID,
    ): ResponseEntity<WebhookResponse> {
        val actorId = OutboundWebhookActorExtractor.extract()
        val webhook = service.get(actorId, id)
        return ResponseEntity.ok(WebhookResponse.from(webhook))
    }

    /**
     * webhook 구독을 전체 교체(PUT)로 수정한다(OCC — [UpdateWebhookRequest.version] 필수).
     *
     * @param id 구독 식별자.
     * @param request 수정 요청 바디.
     * @return 200 OK + [WebhookResponse].
     */
    @PutMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @RequestBody request: UpdateWebhookRequest,
    ): ResponseEntity<WebhookResponse> {
        val actorId = OutboundWebhookActorExtractor.extract()
        val name = required(request.name, "name")
        val url = required(request.url, "url")
        val version =
            request.version
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "version 은(는) 필수입니다.")
        log.info(
            "OutboundWebhookController.update id={} actor={} hasSecret={}",
            id,
            actorId,
            !request.secret.isNullOrBlank(),
        )
        val updated =
            service.update(
                actorId = actorId,
                id = id,
                name = name,
                url = url,
                eventFilter = request.eventFilter.orEmpty(),
                version = version,
                secret = request.secret,
                projectKey = request.projectKey,
                enabled = request.enabled ?: true,
            )
        return ResponseEntity.ok(WebhookResponse.from(updated))
    }

    /**
     * webhook 구독을 소프트 삭제한다.
     *
     * @param id 구독 식별자.
     * @return 204 No Content.
     */
    @DeleteMapping("/{id}")
    fun delete(
        @PathVariable id: UUID,
    ): ResponseEntity<Unit> {
        val actorId = OutboundWebhookActorExtractor.extract()
        log.info("OutboundWebhookController.delete id={} actor={}", id, actorId)
        service.delete(actorId, id)
        return ResponseEntity.noContent().build()
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * 필수 문자열 필드가 null/blank 가 아님을 보장한다.
     *
     * @param value 검증할 값.
     * @param field 필드 이름(오류 메시지용).
     * @return 원본 값(도메인 팩토리가 trim/길이 검증 수행).
     * @throws ResponseStatusException 400 null 또는 blank 시.
     */
    private fun required(
        value: String?,
        field: String,
    ): String =
        value?.takeIf { it.isNotBlank() }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "$field 은(는) 필수입니다.")

    /**
     * 페이지네이션 파라미터를 검증한다(DoS 방어 — [MAX_PAGE_SIZE] 초과 거부).
     *
     * @param page 0-based 페이지 번호(0 이상).
     * @param size 페이지 크기([MIN_PAGE_SIZE]..[MAX_PAGE_SIZE]).
     * @throws ResponseStatusException 400 page<0 또는 size 범위 밖.
     */
    private fun validatePaging(
        page: Int,
        size: Int,
    ) {
        if (page < 0) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "page는 0 이상이어야 합니다.")
        }
        if (size < MIN_PAGE_SIZE || size > MAX_PAGE_SIZE) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "size는 ${MIN_PAGE_SIZE}~${MAX_PAGE_SIZE} 사이여야 합니다.",
            )
        }
    }

    private companion object {
        /** GET 목록 기본 페이지 크기. */
        const val DEFAULT_PAGE_SIZE = 20

        /** 페이지 크기 최솟값. */
        const val MIN_PAGE_SIZE = 1

        /** 페이지 크기 최댓값(DoS 방어 — 형제 SavedFilterController 와 동일 정책). */
        const val MAX_PAGE_SIZE = 100
    }
}
