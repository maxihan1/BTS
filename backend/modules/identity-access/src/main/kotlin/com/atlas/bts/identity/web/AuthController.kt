// 인증 엔드포인트 — login / logout / refresh / sessions / revokeSession (FR-AU-09 Task 21 / Task 2 / Task 3)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.jwt.JwtIssuer
import com.atlas.bts.identity.session.RefreshToken
import com.atlas.bts.identity.session.RefreshTokenRepository
import com.atlas.bts.identity.session.RefreshTokenService
import com.atlas.bts.identity.session.RefreshTokenService.FailureReason
import com.atlas.bts.identity.session.RefreshTokenService.RotateResult
import com.atlas.bts.identity.session.Session
import com.atlas.bts.identity.session.SessionService
import com.atlas.bts.identity.spi.AuthnResult
import com.atlas.bts.identity.spi.Credential
import com.atlas.bts.identity.spi.ProviderRegistry
import com.atlas.bts.identity.web.dto.SessionResponse
import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 인증 엔드포인트 컨트롤러 (FR-AU-09 Task 21 / SDD §19.5).
 *
 * ## 엔드포인트
 * - [login]: POST /api/v1/auth/login — ProviderRegistry 로 인증 → Session 생성 → RefreshToken 발급 → JWT 발급
 * - [logout]: POST /api/v1/auth/logout — sid 로 Session revoke + refresh chain revoke + Cookie 만료
 * - [refresh]: POST /api/v1/auth/refresh — Cookie 의 refresh_token → RefreshTokenService.rotate
 * - [listSessions]: GET /api/v1/auth/sessions — 본인 활성 세션 목록 조회 (JWT 전용, PAT 403)
 * - [revokeSession]: DELETE /api/v1/auth/sessions/{sid} — 본인 다른 활성 세션 강제 종료 (JWT 전용, PAT 403)
 *
 * ## 트랜잭션 경계
 * @Transactional 없음 — service layer(SessionService, RefreshTokenService) 가 각자 @Transactional 보장.
 * (FR-09-21 Brainstorming 발견 #21 — AuthController 는 @Transactional 부착 안 함)
 *
 * ## Cookie 속성 (FR-09-21 정정 — Cookie Path 통일)
 * login Set-Cookie / logout 만료 / refresh rotation 모두 `Path=/api/v1/auth` 로 일관 (RFC 6265).
 * HttpOnly; Secure; SameSite=Strict.
 *
 * ## CSRF 처리
 * - login: SecurityConfig 에서 CSRF skip (/api/v1/auth/login ignoringRequestMatchers)
 * - logout: Authorization Bearer 인증 사용 — Spring Security Bearer stateless CSRF 면제
 * - refresh: Cookie 기반 (Bearer 없음) — CSRF skip 추가 필요 시 SecurityConfig 에서 처리
 *
 * ## EC-04/22/23 처리
 * - EC-04 (refresh 만료): RotateResult.Failure(Expired) → 401 + "refresh_token_expired"
 * - EC-23 (replay 탐지): RotateResult.Failure(Replay) → 401 + "refresh_token_reused"
 * - EC-22 (race loser): RotateResult.Failure(Race) → 401 + "refresh_token_reused" (replay 와 동일 응답)
 * - EC-18 (logout idempotent): Authorization 헤더 없으면 Spring Security 가 401 반환 (Controller 미도달)
 */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val providerRegistry: ProviderRegistry,
    private val sessionService: SessionService,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val refreshTokenService: RefreshTokenService,
    private val jwtIssuer: JwtIssuer,
) {
    /**
     * POST /api/v1/auth/login — username/password 자격증명 인증 후 토큰 발급.
     *
     * 1. ProviderRegistry.findFor(UsernamePassword) 로 담당 Provider 탐색
     * 2. Provider.authenticate 호출
     * 3. SessionService.create → Session row INSERT
     * 4. 신규 RefreshToken 생성 → RefreshTokenRepository.save
     * 5. JwtIssuer.issue → Access Token 발급
     * 6. 응답 body + Set-Cookie refresh_token HttpOnly Secure SameSite=Strict Max-Age=1209600
     *
     * EC-04 대응: 실패 시 session/token INSERT 없음.
     * 로그에 password 절대 미기록 (DEVELOPMENT.md §1.1 규칙 2).
     *
     * @param req HTTP 요청 (IP/UserAgent 추출용)
     * @param body 로그인 요청 body
     * @return 200 + TokenResponse / 401 + {"error": "invalid_credentials"} / 503 LDAP unavailable
     */
    @PostMapping("/login")
    fun login(
        request: HttpServletRequest,
        @RequestBody body: LoginRequest,
    ): ResponseEntity<*> {
        val credential = Credential.UsernamePassword(
            username = body.username,
            password = body.password.toCharArray(),
        )

        val provider = providerRegistry.findFor(credential)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(mapOf("error" to "invalid_credentials"))

        return when (val result = provider.authenticate(credential)) {
            is AuthnResult.Success -> {
                val principal = result.principal
                val ipAddress = request.remoteAddr.takeIf { it.isNotBlank() }
                val userAgent = request.getHeader(HttpHeaders.USER_AGENT)

                val session = sessionService.create(
                    userId = principal.userId,
                    providerId = principal.providerType.name.lowercase(),
                    ipAddress = ipAddress,
                    userAgent = userAgent,
                )

                val rawToken = generateRawToken()
                val tokenHash = sha256Hex(rawToken)
                val now = Instant.now()
                val refreshToken = RefreshToken(
                    id = UUID.randomUUID(),
                    sessionId = session.id,
                    tokenHash = tokenHash,
                    issuedAt = now,
                    expiresAt = now.plus(REFRESH_TTL_DAYS, ChronoUnit.DAYS),
                    usedAt = null,
                    replacedBy = null,
                )
                refreshTokenRepository.save(refreshToken)

                val accessToken = jwtIssuer.issue(
                    userId = session.userId,
                    sessionId = session.id,
                    providerId = session.providerId,
                    scopes = emptyList(),
                )

                ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, buildRefreshCookie(rawToken, REFRESH_MAX_AGE))
                    .body(TokenResponse(accessToken = accessToken))
            }

            is AuthnResult.Failure ->
                ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(mapOf("error" to "invalid_credentials"))

            is AuthnResult.RequiresMfa ->
                ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(mapOf("error" to "mfa_required"))
        }
    }

    /**
     * POST /api/v1/auth/logout — 현재 디바이스 세션 폐기.
     *
     * Authorization Bearer 의 `sid` 클레임으로 Session 을 특정하여:
     * 1. SessionService.revoke(sid, "logout")
     * 2. RefreshTokenRepository.revokeChainFromSession(sid) — 해당 세션의 미사용 refresh 토큰 전체 무효화
     * 3. Set-Cookie refresh_token= Max-Age=0 Path=/api/v1/auth — 브라우저 Cookie 삭제 (RFC 6265 EC-28)
     *
     * EC-18 (idempotent): Authorization 헤더 없으면 Spring Security 가 401 반환. Controller 미도달.
     * 이미 폐기된 세션 revoke 는 SessionService.revoke 가 멱등으로 처리 (0 row 영향).
     *
     * @param jwt 인증된 JWT (Spring Security @AuthenticationPrincipal 주입)
     * @return 204 No Content + Set-Cookie 만료
     */
    @PostMapping("/logout")
    fun logout(
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<Void> {
        val sidStr = jwt.getClaimAsString("sid")
        if (!sidStr.isNullOrBlank()) {
            val sid = UUID.fromString(sidStr)
            sessionService.revoke(sid, REVOKE_REASON_LOGOUT)
            refreshTokenRepository.revokeChainFromSession(sid)
        }

        return ResponseEntity.noContent()
            .header(HttpHeaders.SET_COOKIE, buildRefreshCookie("", 0))
            .build()
    }

    /**
     * POST /api/v1/auth/refresh — Refresh Token rotation.
     *
     * Cookie 의 refresh_token raw 값 → SHA-256 hash → RefreshTokenService.rotate.
     * rotation 성공 시 새 access token + 새 refresh_token Cookie 응답.
     *
     * ## 실패 매핑 (EC-04/22/23)
     * | FailureReason | HTTP | error body |
     * |---|---|---|
     * | Expired | 401 | refresh_token_expired |
     * | Replay | 401 | refresh_token_reused |
     * | Race | 401 | refresh_token_reused |
     * | NotFound / Revoked | 401 | refresh_token_invalid |
     *
     * @param request HTTP 요청 (refresh_token Cookie 추출용)
     * @return 200 + TokenResponse + 새 Set-Cookie / 401
     */
    @PostMapping("/refresh")
    fun refresh(request: HttpServletRequest): ResponseEntity<*> {
        val rawToken = request.cookies
            ?.firstOrNull { it.name == REFRESH_COOKIE_NAME }
            ?.value
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(mapOf("error" to "refresh_token_invalid"))

        val tokenHash = sha256Hex(rawToken)

        return when (val result = refreshTokenService.rotate(tokenHash)) {
            is RotateResult.Success ->
                ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, buildRefreshCookie(result.newRefreshTokenRaw, REFRESH_MAX_AGE))
                    .body(TokenResponse(accessToken = result.accessToken))

            is RotateResult.Failure -> {
                val errorBody = mapOf("error" to result.reason.toErrorCode())
                ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorBody)
            }
        }
    }

    /**
     * GET /api/v1/auth/sessions — 본인 활성 세션 목록 조회 (FR-AU-09 Task 2 / spec §FR-1/FR-2/FR-6b).
     *
     * ## PAT 차단 (FR-6b / EC-8)
     * PAT 인증 시 principal 이 [Jwt] 타입이 아닌 `UsernamePasswordAuthenticationToken` 이다.
     * PAT 는 stateless 자격증명이므로 `sid` ("현재 세션") 개념이 없어 세션 관리가 불가하다.
     * [Jwt] 타입이 아니면 **403 Forbidden** + `session_management_requires_interactive_login` 반환.
     * ([WhoamiController] 의 `Jwt?` nullable + PAT 분기 선례와 동일 원칙.)
     *
     * ## current 플래그 (spec §FR-2)
     * JWT 의 `sid` 클레임과 세션 ID 가 일치하는 세션만 `current = true`.
     * 현재 세션을 사용자에게 명시적으로 표시해 강제종료 버튼을 비활성화할 수 있게 한다.
     *
     * ## 미인증
     * Spring Security 필터가 401 반환. Controller 미도달.
     *
     * @param jwt Spring Security 가 주입한 JWT principal. PAT 인증 시 null (nullable 선언)
     * @return 200 + `{ "sessions": [SessionResponse, ...] }` / 403 PAT / 401 미인증
     */
    @GetMapping("/sessions")
    fun listSessions(
        @AuthenticationPrincipal jwt: Jwt?,
    ): ResponseEntity<*> {
        val claims = resolveJwtClaims(jwt) ?: return PAT_FORBIDDEN_RESPONSE

        val sessions = sessionService.findActiveByUser(claims.userId)
        val sessionResponses = sessions.map { toSessionResponse(it, claims.currentSid) }

        return ResponseEntity.ok(mapOf("sessions" to sessionResponses))
    }

    /**
     * DELETE /api/v1/auth/sessions/{sid} — 본인 다른 활성 세션 강제 종료 (FR-AU-09 Task 3 / spec §FR-3/S-2).
     *
     * ## PAT 차단 (FR-6b / EC-8)
     * [listSessions] 와 동일 원칙 — PAT principal 은 [Jwt] 타입이 아니므로 **403 Forbidden** 반환.
     *
     * ## IDOR 방어 (NFR-1 / FR-4 / S-3)
     * `sessionService.lookup(sid)` 로 세션을 조회한 뒤 `session.userId == 인증 userId` 를 검증한다.
     * 조회 결과가 null 이거나 userId 불일치인 경우 **모두 404 Not Found** 로 응답한다.
     * 타인 세션의 존재 여부를 노출하지 않기 위해 404 를 단일 응답코드로 사용한다 (OWASP IDOR 권고).
     * 검증은 이 메서드 단일 지점에서만 수행한다 (NFR-1 — 분산 방지).
     *
     * ## 현재 세션 차단 (FR-5 / S-4)
     * sid == 요청 JWT 의 `sid` 클레임인 경우 **409 Conflict** + `cannot_revoke_current_session`.
     * 자기 세션 종료는 기존 POST /logout 로 유도한다.
     *
     * ## revoke 처리 (FR-3)
     * logout 선례(`AuthController.kt:169-170`)와 동일하게:
     * 1. `sessionService.revoke(sid, "user_revoke")` — sessions 테이블 UPDATE
     * 2. `refreshTokenRepository.revokeChainFromSession(sid)` — 귀속 refresh chain 즉시 무효화
     *
     * @param jwt Spring Security 가 주입한 JWT principal. PAT 인증 시 null (nullable 선언)
     * @param sid 강제 종료할 세션 ID (Spring 이 UUID 바인딩 실패 시 400 자동 반환 — EC-7)
     * @return 204 No Content / 400 UUID 형식 오류 / 403 PAT / 404 IDOR/미존재 / 409 현재 세션
     */
    // ReturnCount 억제 — HTTP 상태별 guard clause early return(403/404/409/204)이
    // 중첩 if 보다 가독성 우수 (DEVELOPMENT.md §2.3 Early return 권장).
    @Suppress("ReturnCount")
    @DeleteMapping("/sessions/{sid}")
    fun revokeSession(
        @AuthenticationPrincipal jwt: Jwt?,
        @PathVariable sid: UUID,
    ): ResponseEntity<*> {
        val claims = resolveJwtClaims(jwt) ?: return PAT_FORBIDDEN_RESPONSE

        // 미존재 / 타인 소유(IDOR) / 이미 비활성(revoked·만료, EC-2) 세션은 모두 404 (존재 비노출 + 멱등 재폐기 방지).
        val session = sessionService.lookup(sid)
        if (session == null || session.userId != claims.userId || !session.isActive(Instant.now())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build<Void>()
        }

        if (sid == claims.currentSid) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(mapOf("error" to "cannot_revoke_current_session"))
        }

        sessionService.revoke(sid, REVOKE_REASON_USER)
        refreshTokenRepository.revokeChainFromSession(sid)

        return ResponseEntity.noContent().build<Void>()
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * JWT principal 에서 userId(subject) 와 currentSid(sid 클레임) 를 추출한다.
     *
     * PAT 인증 시 [jwt] 가 null 이므로 null 을 반환한다 (호출 측에서 403 반환).
     * JWT 의 subject 가 유효한 UUID 가 아닌 경우에도 null 을 반환한다.
     * sid 클레임이 없거나 UUID 파싱 실패인 경우 [JwtClaims.currentSid] 는 null 이다.
     *
     * @param jwt nullable JWT principal ([Jwt] 타입 아니면 PAT)
     * @return [JwtClaims] 또는 null (PAT/invalid_token)
     */
    // ReturnCount 억제 — null guard early return(PAT/invalid subject)이 가독성 우수.
    @Suppress("ReturnCount")
    private fun resolveJwtClaims(jwt: Jwt?): JwtClaims? {
        if (jwt == null) return null
        val userId = runCatching { UUID.fromString(jwt.subject) }.getOrNull() ?: return null
        val currentSid = jwt.getClaimAsString("sid")?.let {
            runCatching { UUID.fromString(it) }.getOrNull()
        }
        return JwtClaims(userId = userId, currentSid = currentSid)
    }

    /**
     * [Session] 도메인 엔티티를 [SessionResponse] DTO 로 변환한다.
     *
     * `deviceFingerprint` 는 의도적으로 제외한다 (NFR-2 — 내부 식별자 비노출).
     *
     * @param session 변환할 세션 엔티티
     * @param currentSid 요청 JWT 의 `sid` 클레임 값. null 이면 current = false
     * @return [SessionResponse]
     */
    private fun toSessionResponse(session: Session, currentSid: UUID?): SessionResponse =
        SessionResponse(
            sid = session.id,
            providerId = session.providerId,
            userAgent = session.userAgent,
            ipAddress = session.ipAddress,
            lastSeenAt = session.lastSeenAt,
            createdAt = session.createdAt,
            current = session.id == currentSid,
        )

    /**
     * refresh_token Set-Cookie 헤더 값을 생성한다.
     *
     * HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth (FR-09-21 Cookie Path 통일).
     * logout 시 value="" + maxAge=0 으로 브라우저 쿠키 삭제를 요청한다 (EC-28).
     *
     * ResponseCookie 대신 수동 문자열 조합 — SameSite 속성 지원 보장 (Spring 6.x ResponseCookie 이슈 우회).
     */
    private fun buildRefreshCookie(value: String, maxAge: Int): String =
        "$REFRESH_COOKIE_NAME=$value; HttpOnly; Secure; SameSite=Strict; Path=$COOKIE_PATH; Max-Age=$maxAge"

    /** [TOKEN_BYTES] 바이트 CSPRNG 난수를 hex 문자열로 인코딩한다. */
    private fun generateRawToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** SHA-256(input) hex 소문자 64자. */
    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val REFRESH_COOKIE_NAME = "refresh_token"

        /** Cookie Path — login / logout / refresh 모두 동일 Path (RFC 6265 EC-28 회귀 가드) */
        const val COOKIE_PATH = "/api/v1/auth"

        /** refresh_token Cookie Max-Age 14일 (초) */
        const val REFRESH_MAX_AGE = 1209600

        /** Refresh Token 유효 기간 — 14일 (RefreshTokenService 와 동일) */
        const val REFRESH_TTL_DAYS = 14L

        /** CSPRNG 토큰 바이트 수 — 32 바이트 = 256비트 엔트로피 */
        const val TOKEN_BYTES = 32

        /** logout 세션 폐기 사유 — 감사 로그 검색 키 */
        const val REVOKE_REASON_LOGOUT = "logout"

        /** 사용자 강제종료 세션 폐기 사유 — 감사 로그 검색 키 (spec §EC 소문자 snake 관례) */
        const val REVOKE_REASON_USER = "user_revoke"

        /** PAT 인증 시 세션 관리 불가 응답 — listSessions / revokeSession 공용 (FR-6b / EC-8) */
        val PAT_FORBIDDEN_RESPONSE: ResponseEntity<Map<String, String>> =
            ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(mapOf("error" to "session_management_requires_interactive_login"))
    }
}

/**
 * JWT 로부터 추출된 인증 클레임 (listSessions / revokeSession 공용).
 *
 * @param userId JWT subject UUID — 인증 사용자 ID
 * @param currentSid JWT sid 클레임 UUID — 현재 요청 세션 ID. 클레임 부재/파싱 실패 시 null
 */
private data class JwtClaims(val userId: UUID, val currentSid: UUID?)

/** refresh_token_reused / refresh_token_expired / refresh_token_invalid 매핑 */
private fun FailureReason.toErrorCode(): String = when (this) {
    FailureReason.Expired -> "refresh_token_expired"
    FailureReason.Replay, FailureReason.Race -> "refresh_token_reused"
    FailureReason.NotFound, FailureReason.Revoked -> "refresh_token_invalid"
}

/**
 * POST /api/v1/auth/login 요청 body (FR-AU-09 §4.1).
 *
 * @param provider Provider 식별자 (예: "local", "ldap-corp")
 * @param username 사용자 이름
 * @param password 비밀번호 평문 — 메모리 즉시 사용 후 CharArray wipe 는 Provider 책임
 */
data class LoginRequest(
    val provider: String,
    val username: String,
    val password: String,
)

/**
 * login / refresh 응답 body (FR-AU-09 §4.1 / §4.2).
 *
 * @param accessToken RS256 서명 JWT (직렬화 키: access_token)
 * @param tokenType "Bearer" 고정 (직렬화 키: token_type)
 * @param expiresIn Access Token 유효 기간 (초) — 15분 = 900 (직렬화 키: expires_in)
 */
data class TokenResponse(
    @JsonProperty("access_token") val accessToken: String,
    @JsonProperty("token_type") val tokenType: String = "Bearer",
    @JsonProperty("expires_in") val expiresIn: Long = JwtIssuer.ACCESS_TOKEN_TTL_SECONDS,
)
