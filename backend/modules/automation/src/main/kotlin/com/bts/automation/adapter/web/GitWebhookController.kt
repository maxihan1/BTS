// Git 인바운드 웹훅(GitHub/GitLab) 수신 컨트롤러 — 크기상한 → 토큰조회 → 서명검증 → 파싱 → 202 (FR-AT-07 PR-C Task 10)

package com.bts.automation.adapter.web

import com.bts.automation.adapter.GitWebhookRepository
import com.bts.automation.application.GitWebhookService
import com.bts.automation.domain.GitProvider
import com.bts.automation.domain.GitWebhook
import com.bts.automation.security.GitWebhookSignatureVerifier
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.InvalidMediaTypeException
import org.springframework.http.MediaType
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
 * GitHub/GitLab 이 PR 머지를 통지하는 인바운드 엔드포인트 (FR-AT-07 PR-C, spec §3.5·§5-1).
 *
 * ## ★ 이 컨트롤러가 인증 주체다 (DEVELOPMENT.md §1.4 정식 예외)
 * `POST /api/v1/webhooks/git/{token}` 은 Spring Security 필터에서 permitAll 이다 — GitHub/GitLab 은 BTS
 * 세션을 가질 수 없기 때문이다(ADR `2026-07-17-git-webhook-inbound-permitall.md`, 2026-07-17 승인).
 * 따라서 **서명이 틀린 요청도 이 핸들러까지 도달**하며, 필터가 걸러줄 것이라는 가정은 성립하지 않는다.
 * [org.springframework.security.core.context.SecurityContextHolder] 를 일절 참조하지 않고, 접근 제어는
 * 경로의 불투명 토큰 + provider 서명 검증으로만 이뤄진다.
 *
 * ## ★ [GitWebhookService] 의 "호출 전제"를 지키는 주체 — 서명 미검증 요청은 DB 쓰기 0 (DEC-23)
 * [GitWebhookService] 는 서명을 재검증하지 않는다(그 클래스 KDoc "호출 전제"). dedup INSERT·enqueue 가
 * 전부 그 안에 있으므로, **서명 검증을 통과하기 전에는 서비스를 절대 호출하지 않는다**는 이 컨트롤러의
 * 순서가 곧 "미인증 요청은 `git_webhook_deliveries` 에 아무 흔적도 남기지 않는다"는 불변식(spec §3.8)이다.
 *
 * ## 처리 순서 — 순서 자체가 계약이다
 * 1. [readBoundedBody] — 크기 상한(413). **서명 검증 이전**(§7 EC5) + **토큰 조회 이전**(아래 ★ 참조)
 * 2. [findWebhookOrReject] — 토큰 SHA-256 조회(401). **payload 파싱 이전**(아래 ★★ 참조)
 * 3. [verifySignatureOrReject] — provider 별 서명 검증(401). **여기까지 통과해야 DB 쓰기 0**
 * 4. [parsePayload] — JSON 파싱(400)
 * 5. [GitWebhookService.handleInboundEvent] — 판정·dedup·enqueue → 202
 *
 * ## ★ 크기 상한이 토큰 조회보다 먼저인 이유 — 상태코드가 존재 오라클이 되지 않게
 * spec §3.5 의 파이프라인 번호는 ①토큰조회 → ②크기상한이지만, **그 순서로 구현하면 `413` vs `401`
 * 자체가 토큰 존재 오라클**이 된다(초과 본문 + 미존재 토큰 → 401 / + 실재 토큰 → 413). [contentLengthLong]
 * 사전검사까지 있어 공격자는 **바이트를 하나도 보내지 않고** `Content-Length` 만 부풀려 후보 토큰의 실재를
 * 판별할 수 있다 — 아래 §401 단일화가 404 를 포기하면서까지 막은 바로 그 오라클이다. §7 EC5 가 요구하는
 * 불변식은 "413 은 **서명 검증** 이전"이고 plan Task 10 이 요구하는 불변식은 "토큰 조회는 **payload 파싱**
 * 보다 먼저"이므로, 크기 상한을 맨 앞에 두면 **두 불변식을 모두 지키면서** 오라클이 사라진다.
 * DoS 델타도 없다 — [readBoundedBody] 가 힙을 [MAX_PAYLOAD_BYTES] 로 캡하므로 조회 전후 어느 쪽이든
 * 요청당 상한은 256KB 로 동일하고, 미존재 토큰에 대한 추가 비용은 인덱스 조회 1회가 아니라 그 반대다.
 *
 * ## ★★ 토큰 조회는 payload 파싱보다 먼저 (G14 — 선례의 순서 결함을 답습하지 않는다)
 * [AutomationWebhookController.receive] 는 파싱 → 조회 순서라 **유효 토큰 없이도 256KB Jackson 파싱
 * CPU** 를 소모시킬 수 있다. 여기서는 조회·서명 검증을 모두 통과한 뒤에만 파싱한다.
 * (라인 번호로 가리키지 않는다 — 그 파일이 한 줄만 밀려도 주석이 조용히 거짓이 된다.)
 *
 * ## ★ `@RequestBody` 금지 (NFR-2)
 * `@RequestBody` 는 Spring 이 **핸들러 진입 전** 본문 전체를 힙에 역직렬화하므로, 메서드 안의 어떤 크기
 * 검사도 이미 늦는다. nginx `client_max_body_size 110m` + `mem_limit 1536m` 조합에서 미인증 permitAll
 * 경로로 110MB 를 적재당하면 9-BC 모놀리스 전체가 OOM 으로 내려간다(#275 선례).
 * [HttpServletRequest] 를 직접 받아 [MAX_PAYLOAD_BYTES] + 1 까지만 스트리밍으로 읽는다.
 * **`@RequestParam`/`@ModelAttribute` 병용도 금지** — 서블릿 form 파싱이 본문 스트림을 소진해 서명 검증
 * 대상이 빈 바이트가 되고, 검증이 **조용히** 무력화된다(`bearer-token-resolver-drains-form-body` 동류).
 * 이 시그니처는 `GitWebhookControllerTest` 가 화이트리스트로 못 박는다.
 *
 * ## ★ 401 응답 본문 단일화 (B3-sec, spec §5-1)
 * EC1(토큰 미존재/삭제)·EC2/EC3(헤더 교차)·EC4(SHA-1 레거시)·EC9(복호화 실패)·EC15(blank secret) 를
 * **전부 같은 [ERROR_CODE_UNAUTHORIZED]** 로 응답한다. errorCode·detail 이 사유별로 갈리면, secret 은
 * 모르고 토큰만 아는 공격자가 "이 토큰이 실재하는가"를 응답으로 판별할 수 있다 — 404 대신 401 을 쓰면서까지
 * 막은 존재 오라클이 응답 본문으로 부활한다. **사유 구분은 구조화 로그에서만** 한다
 * ([GitWebhookSignatureVerifier] 가 `git_webhook_decrypt_failed`/`git_webhook_signature_rejected` 로
 * 이벤트명을 가르고, 이 컨트롤러는 EC1 을 [LOG_UNKNOWN_TOKEN] 으로 가른다). 저장소에 메트릭 인프라가
 * 없으므로(micrometer 미도입 — §7 EC9) 관측성은 로그 단일 수단이다.
 *
 * ## 로그 — webhookId·projectKey 만 (DEVELOPMENT.md §1.1-2, NFR-3)
 * 경로 토큰 원문·secret 평문/암호문·요청 본문은 **어떤 로그에도 남기지 않는다**. EC1 은 애초에 웹훅을
 * 특정하지 못하므로 식별자 없이 이벤트명만 남긴다(토큰을 남기면 그것이 곧 평문 토큰 로깅이다).
 *
 * @param gitWebhookRepository 토큰 해시로 활성 등록행을 조회하는 리포지토리(Task 6).
 * @param signatureVerifier provider 별 서명 검증기(Task 7). 복호화 실패까지 `false` 로 수렴시키므로 이
 *   컨트롤러는 `false` → 401 매핑만 한다(예외 → 500 변질 경로를 만들지 않는다).
 * @param gitWebhookService 검증을 통과한 이벤트의 판정·dedup·enqueue 파이프라인(Task 9).
 * @param objectMapper 요청 본문(JSON)을 [JsonNode] 로 파싱하는 Jackson 매퍼(Spring 자동 구성 빈).
 */
@RestController
@RequestMapping("/api/v1/webhooks/git")
@Suppress("TooManyFunctions") // 파이프라인 5단계(§처리 순서) + 예외→HTTP 매핑 5개 + 헬퍼 — 책임은 단일(인바운드 웹훅 방어선)
class GitWebhookController(
    private val gitWebhookRepository: GitWebhookRepository,
    private val signatureVerifier: GitWebhookSignatureVerifier,
    private val gitWebhookService: GitWebhookService,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 인바운드 Git 웹훅 배달 1건을 수신한다. 정상 처리와 "머지 이벤트가 아니라 무시"를 구분하지 않고
     * 모두 202 로 응답한다(처리 여부는 구조화 로그로만 구분 — spec §5-1).
     *
     * GitHub UI 의 form-urlencoded 옵션은 **415 로 명시 거부**한다(§7 EC6) — [rejectIfNotJson].
     * 허용하면 form 본문이 파싱 실패로 흘러 202 조용한 무시가 되고, 운영자가 "202 인데 아무 일도 없음"을
     * 디버깅하게 된다.
     *
     * @param token 경로 세그먼트의 원문 토큰(해시로만 조회하며 저장·로그 출력하지 않는다).
     * @param request 미디어타입·크기 검증·원문 바이트 읽기·provider 헤더 조회용 [HttpServletRequest].
     *   **`@RequestBody` 로 대체 금지** — 클래스 KDoc 참조.
     * @return 202 Accepted(본문 없음).
     * @throws GitWebhookUnsupportedMediaTypeException Content-Type 이 JSON 이 아님(EC6, 415).
     * @throws GitWebhookPayloadTooLargeException 본문이 [MAX_PAYLOAD_BYTES] 초과(EC5, 413).
     * @throws GitWebhookUnauthorizedException 토큰 미존재·삭제 또는 서명 검증 실패(EC1~EC4·EC9·EC15, 401).
     * @throws GitWebhookInvalidPayloadException 본문이 유효한 JSON 이 아님(EC7, 400).
     */
    @PostMapping("/{token}")
    fun receive(
        @PathVariable token: String,
        request: HttpServletRequest,
    ): ResponseEntity<Unit> {
        rejectIfNotJson(request)
        val body = readBoundedBody(request)
        val webhook = findWebhookOrReject(token)
        verifySignatureOrReject(webhook, request, body)
        val payload = parsePayload(body)

        val (eventHeader, deliveryHeader) = inboundHeadersOf(webhook.provider)
        gitWebhookService.handleInboundEvent(
            webhook = webhook,
            eventTypeHeader = request.getHeader(eventHeader),
            deliveryId = request.getHeader(deliveryHeader),
            payload = payload,
        )
        return ResponseEntity.status(HttpStatus.ACCEPTED).build()
    }

    /**
     * Content-Type 이 JSON 이 아니면 415 로 거부한다(EC6).
     *
     * ## ★ 왜 `consumes` 가 아니라 손수 검사인가 — `consumes` 의 415 는 prod 에서 **401 로 변질된다**
     * `consumes` 조건 불일치는 Spring 이 **핸들러 매핑 단계**(`RequestMappingInfoHandlerMapping.handleNoMatch`)
     * 에서 예외를 던진다. 그 시점엔 핸들러 메서드가 아직 바인딩되지 않아
     * (`ExceptionHandlerExceptionResolver` 가 handlerMethod=null 로 호출됨) **이 컨트롤러의
     * `@ExceptionHandler` 는 후보에조차 오르지 않는다**(셀렉터가 붙은 `@ControllerAdvice` 도 마찬가지 —
     * 무선택자 전역 advice 만 도달한다). 결국 `DefaultHandlerExceptionResolver` 가 sendError(415) 로
     * 처리하고, 서블릿이 error 경로로 **ERROR 디스패치**하는데 그 경로는 중앙 `SecurityConfig` 에서
     * `anyRequest().authenticated()` 라 필터가 **빈 401** 을 덮어쓴다(실측 — `consumes` 를 둔 채
     * 415 핸들러를 추가해도 응답은 그대로 401 이었다).
     *
     * 이 엔드포인트에서 401 은 "secret 이 틀렸다"로 읽힌다. content type 을 잘못 고른 운영자가 secret 을
     * 돌리며 헤매게 되므로, `consumes` 를 넣은 목적(진단성)이 정확히 무너진다. 그래서 미디어타입 판정을
     * **핸들러 메서드 안**으로 들여와 컨트롤러 로컬 핸들러가 415 ProblemDetail 을 직접 응답하게 한다.
     * 부수효과로 415 가 error 경로를 **아예 타지 않으므로**, Spring Boot 기본 에러 본문의 `path` 필드(요청
     * URI 원문 = **경로 토큰 포함**)가 나갈 통로가 한 겹 사라진다(T15-6 이 지키던 간접 조건이 직접 조건이 된다).
     *
     * ## 판정 기준은 `consumes` 와 **정확히 동일**하게 유지한다
     * [MediaType.includes] 는 `ConsumesRequestCondition` 이 쓰는 바로 그 술어다. 따라서 동작이 그대로다.
     * - `application/json`, `application/json;charset=utf-8` → 통과
     * - Content-Type 부재 → 415(`consumes` 는 부재를 `application/octet-stream` 으로 간주해 거부했다)
     * - 파싱 불가능한 Content-Type → 415
     *
     * 이 검사는 **토큰과 무관**하므로 맨 앞에 둬도 존재 오라클이 되지 않는다(클래스 KDoc ★ 참조).
     * `@RequestParam`/`@ModelAttribute` 를 쓰지 않으므로 form 본문이라도 서블릿 파라미터 파싱이 트리거되지
     * 않는다 — 여기서 415 로 끊기므로 스트림은 애초에 읽히지도 않는다.
     */
    private fun rejectIfNotJson(request: HttpServletRequest) {
        // 파싱 불가능한 Content-Type 은 null 로 수렴시켜 아래 단일 지점에서 거부한다(`consumes` 와 동일 결과).
        val parsed =
            request.contentType?.let { declared ->
                try {
                    MediaType.parseMediaType(declared)
                } catch (ex: InvalidMediaTypeException) {
                    log.info("git_webhook_unparsable_content_type", ex)
                    null
                }
            }
        if (parsed == null || !MediaType.APPLICATION_JSON.includes(parsed)) {
            throw GitWebhookUnsupportedMediaTypeException()
        }
    }

    /**
     * 본문을 [MAX_PAYLOAD_BYTES] 이내로만 읽는다(EC5). 크기 판정은 **토큰 존재 여부에 의존하지 않는다**
     * (클래스 KDoc ★ 참조 — 의존하면 413/401 이 존재 오라클이 된다).
     *
     * 이중 방어다.
     * 1. `Content-Length` 사전검사 — 바이트를 읽기 전에 빠르게 거절한다. 헤더 위조·누락(-1) 시 무력하다.
     * 2. [java.io.InputStream.readNBytes] — 상한 + 1 바이트까지만 실제로 읽어 초과를 판정한다. 청크 전송
     *    (`Transfer-Encoding: chunked`)처럼 `Content-Length` 가 없는 요청에도 유효한 방어선이다.
     *
     * ## ★ 2번 분기는 MockMvc 로 검증할 수 없다 — 실서블릿 테스트가 유일한 관문
     * MockMvc 는 본문 바이트에서 `Content-Length` 를 **파생시켜** 항상 1번 분기로 흡수되므로, 2번 분기는
     * MockMvc 테스트를 아무리 늘려도 실행되지 않는다(`readNBytes` 를 `readAllBytes` 로 바꿔도 전 스위트가
     * 초록이던 이유 — 실측). 2번 분기를 실제로 태우는 것은 조립 테스트 `GitWebhookInboundPermitAllTest` 의
     * **T15-10** 뿐이다(chunked 전송으로 `Content-Length` 자체를 없앤다). 이 클래스의 크기 상한을 손대면
     * 그 테스트를 함께 확인할 것.
     */
    private fun readBoundedBody(request: HttpServletRequest): ByteArray {
        if (request.contentLengthLong > MAX_PAYLOAD_BYTES) {
            throw GitWebhookPayloadTooLargeException()
        }
        val bytes = request.inputStream.readNBytes(MAX_PAYLOAD_BYTES + 1)
        if (bytes.size > MAX_PAYLOAD_BYTES) {
            throw GitWebhookPayloadTooLargeException()
        }
        return bytes
    }

    /**
     * 원문 토큰의 SHA-256 해시로 활성 등록행을 조회한다(EC1). 미존재·소프트 삭제를 구분하지 않는다
     * ([GitWebhookRepository.findByTokenHash] 가 `deleted_at IS NULL` 로 이미 걸러 같은 `null` 로 수렴).
     *
     * 로그에 토큰을 남기지 않는다 — 남기면 그것이 곧 평문 토큰 로깅(DEVELOPMENT.md §1.1-2)이고, 로그를
     * 읽을 수 있는 사람이 그대로 웹훅을 발화시킬 수 있다.
     */
    private fun findWebhookOrReject(token: String): GitWebhook =
        gitWebhookRepository.findByTokenHash(sha256Hex(token)) ?: run {
            log.warn(LOG_UNKNOWN_TOKEN)
            throw GitWebhookUnauthorizedException()
        }

    /**
     * 등록행 provider 기준으로 서명을 검증한다(EC2~EC4·EC9·EC15). 검증기가 모든 실패를 `false` 로
     * 수렴시키므로 여기서는 401 매핑만 한다.
     *
     * **두 헤더를 모두 넘기되 provider 분기는 검증기가 등록행 값으로만 한다** — GITHUB 등록이면
     * [HEADER_GITHUB_SIGNATURE] 만, GITLAB 등록이면 [HEADER_GITLAB_TOKEN] 만 보고, 기대 헤더가 없어도
     * 다른 provider 방식으로 폴백하지 않는다(혼동 공격 차단). 레거시 `X-Hub-Signature`(SHA-1)는 **여기서
     * 아예 읽지 않으므로** 검증기에 `null` 로 도달해 거부된다(EC4).
     */
    private fun verifySignatureOrReject(
        webhook: GitWebhook,
        request: HttpServletRequest,
        body: ByteArray,
    ) {
        val valid =
            signatureVerifier.isValid(
                webhookId = webhook.id,
                projectKey = webhook.projectKey,
                provider = webhook.provider.name,
                secretEncrypted = webhook.secretEncrypted,
                githubSignatureHeader = request.getHeader(HEADER_GITHUB_SIGNATURE),
                gitlabTokenHeader = request.getHeader(HEADER_GITLAB_TOKEN),
                rawBody = body,
            )
        if (!valid) {
            throw GitWebhookUnauthorizedException()
        }
    }

    /**
     * 본문 바이트를 JSON 트리로 파싱한다(EC7). **원문 [ByteArray] 를 그대로** 넘긴다 — String 왕복은
     * 서명 대상 바이트를 U+FFFD 로 치환하고 힙 복사본을 하나 더 만든다.
     */
    private fun parsePayload(body: ByteArray): JsonNode =
        try {
            objectMapper.readTree(body)
        } catch (ex: JsonProcessingException) {
            throw GitWebhookInvalidPayloadException(ex)
        }

    /**
     * provider 별 (이벤트 종류, 배달 식별자) 헤더명 쌍 — [GitWebhookService.handleInboundEvent] 의
     * `eventTypeHeader`/`deliveryId` 에 대응한다. 두 헤더의 provider 분기를 **한 곳**에 모아 둔다
     * (따로 두면 한쪽만 provider 를 추가하는 어긋남이 생긴다).
     *
     * 서명 헤더는 여기 넣지 않는다 — 그쪽은 [GitWebhookSignatureVerifier] 가 등록행 provider 로 직접
     * 분기해야 하는 **보안 판정**이고, 이건 단순 메타데이터 조회다.
     */
    private fun inboundHeadersOf(provider: GitProvider): Pair<String, String> =
        when (provider) {
            GitProvider.GITHUB -> HEADER_GITHUB_EVENT to HEADER_GITHUB_DELIVERY
            GitProvider.GITLAB -> HEADER_GITLAB_EVENT to HEADER_GITLAB_EVENT_UUID
        }

    // ── 예외 → HTTP 매핑 (컨트롤러 로컬 핸들러 — catch-all 오포착 방지) ────────────

    /**
     * 토큰 미존재·삭제 또는 서명 검증 실패 — 401 **단일** errorCode(클래스 KDoc §401 단일화).
     *
     * 사유별 분기를 **여기에 추가하지 말 것**. 어떤 형태로든(errorCode·detail·title·type) 사유가 응답에
     * 드러나면 토큰 존재 오라클이 부활한다. 사유는 이미 로그로 갈려 있다.
     *
     * @return [ERROR_CODE_UNAUTHORIZED] [ProblemDetail].
     */
    @ExceptionHandler(GitWebhookUnauthorizedException::class)
    fun handleUnauthorized(): ProblemDetail =
        problem(
            status = HttpStatus.UNAUTHORIZED,
            type = "git-webhook-unauthorized",
            title = "Unauthorized",
            errorCode = ERROR_CODE_UNAUTHORIZED,
            detail = "요청을 인증할 수 없습니다.",
        )

    /**
     * payload 크기 상한 초과 — 413(EC5).
     *
     * @return [ERROR_CODE_PAYLOAD_TOO_LARGE] [ProblemDetail].
     */
    @ExceptionHandler(GitWebhookPayloadTooLargeException::class)
    fun handleTooLarge(): ProblemDetail {
        log.info("git_webhook_payload_too_large")
        return problem(
            status = HttpStatus.PAYLOAD_TOO_LARGE,
            type = "git-webhook-payload-too-large",
            title = "Payload Too Large",
            errorCode = ERROR_CODE_PAYLOAD_TOO_LARGE,
            detail = "요청 본문이 최대 허용 크기(${MAX_PAYLOAD_BYTES / BYTES_PER_KB}KB)를 초과했습니다.",
        )
    }

    /**
     * 본문이 유효한 JSON 이 아님 — 400(EC7).
     *
     * detail 은 고정 문구다. 파서 예외 메시지를 실으면 요청 본문 조각이 응답으로 되돌아 나간다.
     *
     * @return [ERROR_CODE_INVALID_PAYLOAD] [ProblemDetail].
     */
    @ExceptionHandler(GitWebhookInvalidPayloadException::class)
    fun handleInvalidPayload(): ProblemDetail {
        log.info("git_webhook_invalid_payload")
        return problem(
            status = HttpStatus.BAD_REQUEST,
            type = "git-webhook-invalid-payload",
            title = "Invalid Payload",
            errorCode = ERROR_CODE_INVALID_PAYLOAD,
            detail = "요청 본문이 유효한 JSON 이 아닙니다.",
        )
    }

    /**
     * Content-Type 이 JSON 이 아님 — 415(EC6). 상세 근거는 [rejectIfNotJson] KDoc ★ 참조.
     *
     * @return [ERROR_CODE_UNSUPPORTED_MEDIA_TYPE] [ProblemDetail].
     */
    @ExceptionHandler(GitWebhookUnsupportedMediaTypeException::class)
    fun handleUnsupportedMediaType(): ProblemDetail {
        log.info("git_webhook_unsupported_media_type")
        return problem(
            status = HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            type = "git-webhook-unsupported-media-type",
            title = "Unsupported Media Type",
            errorCode = ERROR_CODE_UNSUPPORTED_MEDIA_TYPE,
            detail = "Content-Type 은 application/json 이어야 합니다.",
        )
    }

    /**
     * 분류되지 않은 모든 예외 — 500([GitWebhookRegistrationController] 의 동명 핸들러와 동형).
     *
     * ## ★ 없으면 500 이 error 경로로 새고, **그 요청 URI 에는 원문 토큰이 있다**
     * 이 핸들러가 없으면 미포착 [RuntimeException](예: [GitWebhookRepository] 의 `GitProvider.valueOf`
     * 가 손상된 DB 행을 만났을 때·DB 장애)이 sendError(500) → error 경로 **ERROR 디스패치**로 넘어간다.
     * 지금은 그 경로가 authenticated 라 빈 401 로 덮이지만, 그 안전은 중앙 `SecurityConfig` 의 한 줄에
     * 얹혀 있는 **간접 조건**이다(T15-6 참조). 컨트롤러가 자기 예외를 자기가 응답하면 그 통로 자체가 없다.
     *
     * ## ★ 더 구체적인 핸들러가 항상 먼저 이긴다 — 401/413/400 을 500 으로 변질시키지 않는다
     * Spring 의 `ExceptionHandlerMethodResolver` 는 `ExceptionDepthComparator` 로 **예외 계층상 가장
     * 가까운** 핸들러를 고른다. 위 4개 핸들러는 각자의 예외 타입을 정확히(depth 0) 매치하므로 이
     * catch-all 보다 항상 우선한다. 과거 catch-all 이 `ResponseStatusException` 을 삼켜 401 을 500 으로
     * 바꾼 사고가 있었으므로([[catch-all-exceptionhandler-swallows-responsestatusexception]]),
     * 401·413·400·415 가 그대로 나오는지는 `GitWebhookControllerTest` 가 전 경로로 못 박는다.
     *
     * detail 은 고정 문구이고 스택트레이스는 서버 로그 전용이다 — 내부 사정이 응답으로 새면 그것이 곧
     * 정찰 정보다. 401 단일화의 취지와 같다.
     *
     * @param ex 미포착 예외(로그 전용 — 응답에 싣지 않는다).
     * @return [ERROR_CODE_INTERNAL_ERROR] [ProblemDetail].
     */
    @ExceptionHandler(Exception::class)
    fun handleInternal(ex: Exception): ProblemDetail {
        log.error("git_webhook_internal_error", ex)
        return problem(
            status = HttpStatus.INTERNAL_SERVER_ERROR,
            type = "git-webhook-internal-error",
            title = "Internal Server Error",
            errorCode = ERROR_CODE_INTERNAL_ERROR,
            detail = "서버 내부 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.",
        )
    }

    /**
     * [ProblemDetail](RFC 7807) 인스턴스를 생성하는 헬퍼([AutomationWebhookController] 의 동명 private
     * 헬퍼와 형식이 같지만, 그쪽은 파일 비공개라 재사용할 공개 API 가 없다 — 값만 복제).
     *
     * ## ★ `instance` 를 반드시 명시한다 — 비우면 Spring 이 **원문 토큰**을 응답에 싣는다
     * `RequestResponseBodyMethodProcessor` 는 반환된 [ProblemDetail] 의 `instance` 가 `null` 이면 요청
     * URI 로 자동 채운다. 이 엔드포인트의 요청 URI 에는 **경로 세그먼트에 원문 토큰**이 들어 있으므로,
     * 비워 두면 모든 에러 응답 본문에 평문 토큰이 실려 나간다 — 보낸 사람이야 이미 알지만, 그 본문이
     * 응답 로그·프록시 캐시·에러 트래커에 적재되는 순간 그것이 **평문 토큰 저장/로깅**이다
     * (DEVELOPMENT.md §1.1-1·§1.1-2). [GitWebhookRegistrationController] 가 secret 에 대해 세운 원칙
     * ("요청자가 방금 보낸 값이라도 되돌려 싣지 않는다")과 같은 이유로 토큰 없는 고정 경로로 덮어쓴다.
     *
     * @param status HTTP 응답 상태 코드.
     * @param type type suffix.
     * @param title 문제 유형 요약.
     * @param errorCode BTS 에러 코드.
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
        /** payload 크기 상한(EC5) — 256KB. [AutomationWebhookController] 의 동명 상수와 같은 값. */
        const val MAX_PAYLOAD_BYTES = 256 * 1024

        /** 상한 안내 메시지의 KB 환산 상수. */
        const val BYTES_PER_KB = 1024

        /**
         * ProblemDetail `instance` 고정값 — **토큰 세그먼트를 뺀** 엔드포인트 경로.
         * 비워 두면 Spring 이 원문 토큰이 든 요청 URI 로 채운다([problem] KDoc ★ 참조).
         */
        const val INSTANCE_PATH = "/api/v1/webhooks/git"

        /**
         * ★ EC1~EC4·EC9·EC15 공용 — 사유별로 가르지 말 것(클래스 KDoc §401 단일화).
         *
         * `AUTOMATION_` prefix 는 모듈 공통 관례다([GitWebhookRegistrationController] 의 에러코드 상수
         * 오브젝트 KDoc·[AutomationWebhookController] 의 `AUTOMATION_WEBHOOK_` 계열과 동일 규칙).
         */
        const val ERROR_CODE_UNAUTHORIZED = "AUTOMATION_GIT_WEBHOOK_UNAUTHORIZED"
        const val ERROR_CODE_PAYLOAD_TOO_LARGE = "AUTOMATION_GIT_WEBHOOK_PAYLOAD_TOO_LARGE"
        const val ERROR_CODE_INVALID_PAYLOAD = "AUTOMATION_GIT_WEBHOOK_INVALID_PAYLOAD"
        const val ERROR_CODE_UNSUPPORTED_MEDIA_TYPE = "AUTOMATION_GIT_WEBHOOK_UNSUPPORTED_MEDIA_TYPE"

        /** 미분류 예외 — 엔드포인트 고유가 아닌 일반 실패라 형제 컨트롤러와 같은 일반 코드를 쓴다. */
        const val ERROR_CODE_INTERNAL_ERROR = "AUTOMATION_INTERNAL_ERROR"

        /** GitHub 서명 헤더. 레거시 `X-Hub-Signature`(SHA-1)는 의도적으로 읽지 않는다(EC4). */
        const val HEADER_GITHUB_SIGNATURE = "X-Hub-Signature-256"
        const val HEADER_GITHUB_EVENT = "X-GitHub-Event"
        const val HEADER_GITHUB_DELIVERY = "X-GitHub-Delivery"

        /** GitLab 평문 토큰 헤더(HMAC 미제공 — 보안 등급 차이는 [GitWebhookSignatureVerifier] KDoc). */
        const val HEADER_GITLAB_TOKEN = "X-Gitlab-Token"
        const val HEADER_GITLAB_EVENT = "X-Gitlab-Event"
        const val HEADER_GITLAB_EVENT_UUID = "X-Gitlab-Event-UUID"

        /**
         * EC1 — 등록행을 특정할 수 없으므로 식별자 없이 이벤트명만 남긴다. 토큰은 절대 남기지 않는다.
         * 사유 구분이 필요한 나머지 401 은 [GitWebhookSignatureVerifier] 가 자체 이벤트명으로 가른다.
         */
        const val LOG_UNKNOWN_TOKEN = "git_webhook_unknown_token"
    }
}

/**
 * 웹훅 원문 토큰의 SHA-256 hex 다이제스트를 계산한다(평문 미저장 조회 전용).
 *
 * [AutomationWebhookController] 의 동명 함수를 **의도적으로 복제**했다 — 그쪽은 `private` top-level 이라
 * 파일 밖에서 재사용할 수 없고, automation 은 BC 격리로 identity-access 의
 * `PersonalAccessTokenService` 를 import 할 수 없다(`AutomationWebhookController.kt` 의 동명 top-level
 * `sha256Hex` KDoc 과 동일 근거 — 라인 번호로 가리키면 그 파일이 밀릴 때 조용히 거짓이 된다).
 *
 * **알고리즘이 같아야 한다** — Task 11 (`GitWebhookRegistrationService`)이 발급 시 저장하는
 * `git_webhooks.token_hash` 와 동일한 "원문의 SHA-256 hex" 여야 이 조회가 매칭된다.
 *
 * @param raw 경로 세그먼트로 받은 원문 토큰.
 * @return 64자 소문자 hex 다이제스트.
 */
private fun sha256Hex(raw: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(raw.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { "%02x".format(it) }

/**
 * 토큰 미존재·삭제 또는 서명 검증 실패(EC1~EC4·EC9·EC15).
 *
 * ★ **사유별 하위 타입을 만들지 말 것** — 타입이 갈리면 핸들러가 갈리고, 핸들러가 갈리면 응답이 갈려
 * 토큰 존재 오라클이 부활한다. 사유 구분은 로그 이벤트명으로만 한다.
 */
private class GitWebhookUnauthorizedException : RuntimeException()

/** payload 크기 상한(EC5) 초과. */
private class GitWebhookPayloadTooLargeException : RuntimeException()

/** payload 가 유효한 JSON 이 아님(EC7). */
private class GitWebhookInvalidPayloadException(cause: Throwable) : RuntimeException(cause)

/** Content-Type 이 JSON 이 아님(EC6). `consumes` 를 쓰지 않는 이유는 [GitWebhookController] KDoc 참조. */
private class GitWebhookUnsupportedMediaTypeException : RuntimeException()
