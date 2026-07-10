// 본인 Slack 계정 연결 상태 조회/연결/해제 me-scope REST 컨트롤러 (FR-SL-02 D6 Task 4)

package com.bts.slack.web

import com.bts.slack.application.ConnectionStatus
import com.bts.slack.application.SlackUserConnectionService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * 본인 Slack 계정 연결 상태 조회/연결/해제 me-scope REST 컨트롤러 (FR-SL-02 D6 Task 4).
 *
 * [SlackInstallQueryController](관리자 전역 설치 상태)와 달리, 이 세 엔드포인트는 **사용자 본인** 매핑
 * 상태만 다룬다(me-scope, 타인 조회/조작 불가) — ADR `docs/decisions/2026-07-10-fr-sl-02-d6-slack-user-connection.md` 참고.
 *
 * ## 엔드포인트 ([RequestMapping] `/api/v1/slack/me/connection`)
 * - [getConnection] GET    — 본인 연결 상태 조회.
 * - [connect]       POST   — 본인 BTS 이메일로 Slack 사용자를 자동 해석해 연결(이메일 자동해석, 옵션 C).
 * - [disconnect]    DELETE — 본인 연결 해제(멱등 — 미연결이어도 200).
 *
 * ## 현재 사용자 식별 — `@AuthenticationPrincipal Jwt?` 타입 기반 (PAT 401)
 * identity-access `UserProfileController` 선례를 그대로 따른다.
 * [AuthenticationPrincipal] 로 주입된 [Jwt] 의 subject(UUID)로 사용자를 식별하며([currentUserId]),
 * principal 이 [Jwt] 가 아니면(PAT 등) `null` 이 주입되어 401 로 거부한다.
 *
 * **`SlackActorExtractor` 를 재사용하지 않는다** — 그것은 `Authentication.name` 을 UUID 로 파싱할 뿐이라
 * PAT 인증 주체(UUID subject)도 통과시켜 200 을 반환한다(리뷰 BLOCKER). me-scope 는 JWT 세션만 허용해야
 * PAT 토큰으로 타인을 사칭한 연결/해제를 막을 수 있다.
 *
 * ## 예외 → HTTP 매핑
 * 예외는 [SlackConnectionExceptionHandler] (이 컨트롤러 전용 스코프)가 상태코드로 변환한다.
 *
 * ## 비밀값 미노출 (§1.1.2)
 * 응답([SlackConnectionResponse])은 connected/workspaceName/linkedAt 만 담는다 — `slack_user_id`·연결에
 * 쓰인 이메일·봇 토큰은 [ConnectionStatus] 에 애초에 로드되지 않아 새어 나갈 수 없다.
 *
 * @param service 이메일 자동해석 연결 오케스트레이션 서비스(조회/연결/해제).
 */
@RestController
@RequestMapping("/api/v1/slack/me/connection")
class SlackConnectionController(
    private val service: SlackUserConnectionService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * GET `/api/v1/slack/me/connection` — 본인 Slack 연결 상태를 조회한다.
     *
     * @param jwt 인증 JWT principal. PAT 등 미지원 인증이면 401.
     * @return 200 [SlackConnectionResponse].
     */
    @GetMapping
    fun getConnection(
        @AuthenticationPrincipal jwt: Jwt?,
    ): SlackConnectionResponse {
        val userId = currentUserId(jwt)
        return toResponse(service.getStatus(userId))
    }

    /**
     * POST `/api/v1/slack/me/connection` — 본인 BTS 이메일로 Slack 계정을 자동 연결한다.
     *
     * @param jwt 인증 JWT principal. PAT 등 미지원 인증이면 401.
     * @return 200 연결 완료 후 [SlackConnectionResponse].
     */
    @PostMapping
    fun connect(
        @AuthenticationPrincipal jwt: Jwt?,
    ): SlackConnectionResponse {
        val userId = currentUserId(jwt)
        val status = toResponse(service.connect(userId))
        log.info("SLACK_CONNECTION_CONNECT userId={}", userId)
        return status
    }

    /**
     * DELETE `/api/v1/slack/me/connection` — 본인 Slack 연결을 해제한다(멱등).
     *
     * @param jwt 인증 JWT principal. PAT 등 미지원 인증이면 401.
     * @return 200 `connected:false` [SlackConnectionResponse].
     */
    @DeleteMapping
    fun disconnect(
        @AuthenticationPrincipal jwt: Jwt?,
    ): SlackConnectionResponse {
        val userId = currentUserId(jwt)
        service.disconnect(userId)
        log.info("SLACK_CONNECTION_DISCONNECT userId={}", userId)
        return SlackConnectionResponse(connected = false, workspaceName = null, linkedAt = null)
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * JWT subject(UUID) 로 현재 사용자를 식별한다(identity-access `UserProfileController` 와 동일 원칙).
     *
     * @param jwt [AuthenticationPrincipal] 로 주입된 JWT. PAT 등 미지원 인증이면 null.
     * @return 현재 사용자 UUID.
     * @throws ResponseStatusException subject 가 없거나 UUID 형식이 아니면 401.
     */
    private fun currentUserId(jwt: Jwt?): UUID =
        jwt?.subject?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)

    /** [ConnectionStatus] → [SlackConnectionResponse]. */
    private fun toResponse(status: ConnectionStatus): SlackConnectionResponse =
        SlackConnectionResponse(
            connected = status.connected,
            workspaceName = status.workspaceName,
            linkedAt = status.linkedAt,
        )
}
