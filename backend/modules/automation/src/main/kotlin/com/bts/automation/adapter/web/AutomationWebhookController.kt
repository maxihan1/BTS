// 웹훅 인바운드 트리거 REST 컨트롤러 — 불투명 토큰 조회 → 실행 큐 enqueue (FR-AT-01 Task 9)

package com.bts.automation.adapter.web

import com.bts.automation.adapter.AutomationExecutionEnqueuer
import com.bts.automation.adapter.AutomationRuleRepository
import com.bts.automation.domain.TriggerType
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.security.MessageDigest
import java.time.Instant

/**
 * WEBHOOK 트리거 인바운드 엔드포인트 (FR-AT-01 Task 9, ADR D1/D4, 스펙 S7/G2/G5/NFR2).
 *
 * 엔드포인트.
 * - `POST /api/v1/automation/webhooks/{token}` — 외부 시스템이 임의 JSON payload 로 호출 → 202.
 *
 * ## ★ 이 컨트롤러가 인증 주체다 — permitAll(토큰이 인증 수단)
 * 이 컨트롤러는 [org.springframework.security.core.context.SecurityContextHolder] 를 일절 참조하지
 * 않는다. 접근 제어는 오직 경로의 불투명 웹훅 토큰 소지로만 이뤄진다(`PublicDashboardController`
 * 직교 토큰 선례 동형). 이 경로는 중앙 `SecurityConfig` 의 `INBOUND_WEBHOOK_PATHS` 에 등록돼
 * **prod 필터체인에서 permitAll** 이다(FR-AT-07 PR-C Task 12, ADR
 * `2026-07-17-git-webhook-inbound-permitall`). 따라서 **토큰이 틀린 요청도 이 핸들러까지 도달**하며,
 * 필터가 걸러줄 것이라는 가정은 성립하지 않는다.
 *
 * permitAll 도달성은 두 겹으로 검증한다. BC test-boot 의 `AutomationTestSecurityConfig` 는 인가 경계를
 * 재현할 뿐 `@TestConfiguration` 이라 prod 조립에 존재하지 않으므로, 그것만으로는 중앙과의 divergence 를
 * 원리적으로 잡지 못한다 — **prod 조립 HTTP 테스트**(`GitWebhookInboundPermitAllTest` 의 T15-2)가
 * 중앙 필터체인 통과를 실 HTTP 로 못 박는 유일한 관문이다.
 *
 * ## 토큰 조회 — 평문 미저장 (DEVELOPMENT.md §1.1)
 * 경로 토큰 원문은 저장·로그 출력하지 않는다. [sha256Hex] 로 해싱한 값으로만
 * [AutomationRuleRepository.findByWebhookTokenHash] 조회한다. 룰 생성 시(Task 6) 저장하는
 * `webhook_token_hash` 와 **동일 알고리즘**(원문의 SHA-256 hex)이어야 매칭된다.
 *
 * ## 미존재/비활성/삭제 룰 → 404 (존재 숨김, G5)
 * [AutomationRuleRepository.findByWebhookTokenHash] 는 `enabled=true AND deleted_at IS NULL` 룰만
 * 반환하므로, 토큰 자체 미존재·룰 비활성화·룰 소프트 삭제를 구분하지 않고 균일하게 404 를 반환한다
 * (탐지 방지 — 셋 중 어느 사유인지 응답으로 노출하지 않는다).
 *
 * ## payload 크기 상한 — 256KB (G2)
 * 서블릿 컨테이너의 멀티파트 전용 기본 크기 제한(`spring.servlet.multipart.max-file-size`)은 순수
 * JSON 본문(`@RequestBody`)에는 적용되지 않아 앱 정책 없이는 무제한 본문을 허용하게 된다
 * ([[multipart-default-limit-app-policy-false-green]] 의 반면교사 — 이번엔 반대로 "서블릿이 대신
 * 막아주지 않는다"). 그래서 애플리케이션 계층에서 직접 이중 검증한다.
 * 1. [rejectIfDeclaredTooLarge] — `Content-Length` 헤더 사전 검사(빠른 거절, 헤더 위조/누락 시 통과).
 * 2. [readBoundedBody] — [MAX_PAYLOAD_BYTES] + 1 바이트까지만 스트리밍으로 읽어 실제 초과분을 판정
 *    (Content-Length 누락 시나리오·청크 전송에도 유효한 방어심층).
 * `@RequestBody` 자동 바인딩 대신 [HttpServletRequest] 를 직접 받아 두 검사를 body 파싱 전에 강제한다.
 *
 * ## 동기 경로 — 조회 + enqueue 만 (NFR2 < 200ms)
 * 실제 액션 실행은 FR-AT-02 가 `q_automation_execution` 큐를 비동기로 소비한다. 이 컨트롤러는 룰 조회와
 * enqueue 만 수행하고 즉시 202 를 반환한다.
 *
 * @param ruleRepository 웹훅 토큰 해시로 활성 룰을 조회하는 리포지토리.
 * @param enqueuer 발화 결과를 실행 큐에 적재하는 아웃바운드 어댑터.
 * @param objectMapper 요청 본문(JSON)을 [JsonNode] 로 파싱하는 Jackson 매퍼(Spring 자동 구성 빈).
 */
@RestController
@RequestMapping("/api/v1/automation/webhooks")
class AutomationWebhookController(
    private val ruleRepository: AutomationRuleRepository,
    private val enqueuer: AutomationExecutionEnqueuer,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 불투명 웹훅 토큰으로 룰을 조회해 발화(실행 큐 enqueue)시킨다.
     *
     * @param token 경로 세그먼트의 원문 토큰(해시로만 조회, 로그 미노출).
     * @param request 크기 검증 + payload 스트리밍 읽기용 [HttpServletRequest].
     * @return 202 Accepted(본문 없음).
     * @throws AutomationWebhookPayloadTooLargeException [MAX_PAYLOAD_BYTES] 초과(G2, 413).
     * @throws AutomationWebhookNotFoundException 토큰 미존재·비활성·삭제 룰(G5, 404).
     * @throws AutomationWebhookInvalidPayloadException 본문이 유효한 JSON이 아님(400).
     */
    @PostMapping("/{token}")
    fun receive(
        @PathVariable token: String,
        request: HttpServletRequest,
    ): ResponseEntity<Unit> {
        rejectIfDeclaredTooLarge(request)
        val body = readBoundedBody(request)
        val triggerEvent = parsePayload(body)
        val rule =
            ruleRepository.findByWebhookTokenHash(sha256Hex(token))
                ?: throw AutomationWebhookNotFoundException()
        enqueuer.enqueue(rule.id, TriggerType.WEBHOOK, triggerEvent)
        log.info("automation_webhook_enqueued ruleId={} bytes={}", rule.id, body.size)
        return ResponseEntity.status(HttpStatus.ACCEPTED).build()
    }

    /** `Content-Length` 사전 검사(빠른 거절). 미선언(-1)이거나 위조된 낮은 값이면 [readBoundedBody] 가 방어. */
    private fun rejectIfDeclaredTooLarge(request: HttpServletRequest) {
        if (request.contentLengthLong > MAX_PAYLOAD_BYTES) {
            throw AutomationWebhookPayloadTooLargeException()
        }
    }

    /** [MAX_PAYLOAD_BYTES] + 1 바이트까지만 읽어 실제 초과 여부를 판정한다(청크 전송 대비 스트리밍 방어). */
    private fun readBoundedBody(request: HttpServletRequest): ByteArray {
        val bytes = request.inputStream.readNBytes(MAX_PAYLOAD_BYTES + 1)
        if (bytes.size > MAX_PAYLOAD_BYTES) {
            throw AutomationWebhookPayloadTooLargeException()
        }
        return bytes
    }

    /** 본문 바이트를 JSON 트리로 파싱한다. 실패 시 [AutomationWebhookInvalidPayloadException]. */
    private fun parsePayload(body: ByteArray): JsonNode =
        try {
            objectMapper.readTree(body)
        } catch (ex: JsonProcessingException) {
            throw AutomationWebhookInvalidPayloadException(ex)
        }

    // ── 예외 → HTTP 매핑 (컨트롤러 로컬 핸들러 — catch-all 오포착 방지) ────────────

    /**
     * 웹훅 토큰 미존재·비활성·삭제 룰 — 404(존재 숨김, G5).
     *
     * @return `AUTOMATION_WEBHOOK_NOT_FOUND` [ProblemDetail].
     */
    @ExceptionHandler(AutomationWebhookNotFoundException::class)
    fun handleNotFound(): ProblemDetail {
        log.info("AUTOMATION_404 webhook_not_found")
        return problem(
            status = HttpStatus.NOT_FOUND,
            type = "webhook-not-found",
            title = "Webhook Not Found",
            errorCode = "AUTOMATION_WEBHOOK_NOT_FOUND",
            detail = "유효한 웹훅 토큰이 아닙니다.",
        )
    }

    /**
     * payload 크기 상한 초과 — 413(G2).
     *
     * @return `AUTOMATION_WEBHOOK_PAYLOAD_TOO_LARGE` [ProblemDetail].
     */
    @ExceptionHandler(AutomationWebhookPayloadTooLargeException::class)
    fun handleTooLarge(): ProblemDetail {
        log.info("AUTOMATION_413 webhook_payload_too_large")
        return problem(
            status = HttpStatus.PAYLOAD_TOO_LARGE,
            type = "webhook-payload-too-large",
            title = "Payload Too Large",
            errorCode = "AUTOMATION_WEBHOOK_PAYLOAD_TOO_LARGE",
            detail = "요청 본문이 최대 허용 크기(${MAX_PAYLOAD_BYTES / BYTES_PER_KB}KB)를 초과했습니다.",
        )
    }

    /**
     * 본문이 유효한 JSON이 아님 — 400.
     *
     * @return `AUTOMATION_WEBHOOK_INVALID_PAYLOAD` [ProblemDetail].
     */
    @ExceptionHandler(AutomationWebhookInvalidPayloadException::class)
    fun handleInvalidPayload(): ProblemDetail {
        log.info("AUTOMATION_400 webhook_invalid_payload")
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "webhook-invalid-payload",
            title = "Invalid Payload",
            errorCode = "AUTOMATION_WEBHOOK_INVALID_PAYLOAD",
            detail = "요청 본문이 유효한 JSON이 아닙니다.",
        )
    }

    /**
     * [ProblemDetail](RFC 7807) 인스턴스를 생성하는 헬퍼(`AttachmentExceptionHandler` 동형).
     *
     * ## ★ `instance` 를 반드시 명시한다 — 비우면 Spring 이 **원문 토큰**을 응답에 싣는다
     * `RequestResponseBodyMethodProcessor` 는 반환된 [ProblemDetail] 의 `instance` 가 `null` 이면 요청
     * URI 로 자동 채운다. 이 엔드포인트의 요청 URI 에는 **경로 세그먼트에 원문 토큰**이 들어 있으므로,
     * 비워 두면 404·413·400 **모든** 에러 응답 본문에 평문 토큰이 실려 나간다 — 보낸 사람이야 이미
     * 아는 값이지만, 그 본문이 응답 로그·프록시 캐시·에러 트래커에 적재되는 순간 그것이 **평문 토큰
     * 저장/로깅**이다(DEVELOPMENT.md §1.1-1·§1.1-2). 위 클래스 KDoc "토큰 조회 — 평문 미저장" 의
     * 불변식은 조회 경로만으로는 성립하지 않고 **이 한 줄이 있어야** 완성된다.
     * [GitWebhookController] 가 같은 기전에 대해 세운 원칙의 동형 적용이며,
     * `AutomationWebhookControllerTest` 가 세 에러 경로 전부를 실 HTTP 로 못 박는다.
     *
     * @param status HTTP 응답 상태 코드.
     * @param type type suffix.
     * @param title 문제 유형 요약.
     * @param errorCode BTS 에러 코드(`AUTOMATION_` prefix).
     * @param detail 상세 설명(요청 값·내부 사정을 싣지 않는 고정 문구).
     */
    private fun problem(
        status: HttpStatus,
        type: String,
        title: String,
        errorCode: String,
        detail: String,
    ): ProblemDetail {
        val pd = ProblemDetail.forStatus(status)
        pd.type = URI.create("https://bts.example.com/problems/$type")
        pd.instance = URI.create(INSTANCE_PATH)
        pd.title = title
        pd.detail = detail
        pd.setProperty("errorCode", errorCode)
        pd.setProperty("timestamp", Instant.now().toString())
        return pd
    }

    private companion object {
        /** payload 크기 상한(G2) — 256KB. */
        const val MAX_PAYLOAD_BYTES = 256 * 1024

        /** 상한 안내 메시지의 KB 환산 상수. */
        const val BYTES_PER_KB = 1024

        /**
         * ProblemDetail `instance` 고정값 — **토큰 세그먼트를 뺀** 엔드포인트 경로.
         * 비워 두면 Spring 이 원문 토큰이 든 요청 URI 로 채운다([problem] KDoc ★ 참조).
         */
        const val INSTANCE_PATH = "/api/v1/automation/webhooks"
    }
}

/**
 * 웹훅 원문 토큰의 SHA-256 hex 다이제스트를 계산한다(평문 미저장 조회 전용).
 *
 * Task 6(`AutomationRuleController`/`AutomationRuleService`)가 웹훅 토큰 발급 시 저장하는
 * `webhook_token_hash` 와 **동일 알고리즘**(원문의 SHA-256 hex, `PersonalAccessTokenService` 선례
 * 동형)이어야 이 컨트롤러의 조회가 매칭된다. automation 모듈은 identity-access 내부를 import 할 수
 * 없으므로(BC 격리) 공유 유틸을 새로 만들지 않고 이 파일에 인라인한다.
 *
 * @param raw 경로 세그먼트로 받은 원문 토큰.
 * @return 64자 소문자 hex 다이제스트.
 */
private fun sha256Hex(raw: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { "%02x".format(it) }

/** 웹훅 토큰 미존재·비활성·삭제 룰(G5, 존재 숨김 — 사유를 구분해 노출하지 않는다). */
private class AutomationWebhookNotFoundException : RuntimeException()

/** payload 크기 상한(G2) 초과. */
private class AutomationWebhookPayloadTooLargeException : RuntimeException()

/** payload 가 유효한 JSON 이 아님. */
private class AutomationWebhookInvalidPayloadException(cause: Throwable) : RuntimeException(cause)
