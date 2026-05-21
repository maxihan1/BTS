// Refresh Token rotation 서비스 — 새 access/refresh 발급 + 옛 refresh 무효화 + EC-22/23 보안 처리 (FR-AU-09 Task 17)

package com.atlas.bts.identity.session

import com.atlas.bts.identity.jwt.JwtIssuer
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Refresh Token rotation 서비스 (FR-AU-09 Task 17 / SDD §19.5).
 *
 * ## Rotation 흐름
 * 1. token_hash 로 기존 refresh token 조회
 * 2. replay 감지 (EC-23): [RefreshToken.isReplaced] 또는 [RefreshToken.usedAt] != null → 세션 전체 revoke + [RotateResult.Failure]
 * 3. 만료 검사 (EC-04): [RefreshToken.isExpired] → [RotateResult.Failure]
 * 4. 세션 활성 검사: [SessionService.lookup] → [Session.isActive] false → [RotateResult.Failure]
 * 5. 신규 refresh token INSERT
 * 6. [RefreshTokenRepository.markUsedAndChain] — DB 원자적 UPDATE (EC-22 race-safe optimistic locking)
 *    - race winner: oldId 반환 → access token 발급 → [RotateResult.Success]
 *    - race loser: null 반환 → 세션 전체 revoke → [RotateResult.Failure]
 *
 * ## 보안 정책
 * - token_hash 는 SHA-256 hex 64자. raw token 은 [RotateResult.Success.newRefreshTokenRaw] 에만 노출.
 * - **로그에 token_hash / raw token 절대 기록 금지** (DEVELOPMENT.md §1.1 규칙 2).
 * - EC-23: 이미 사용된(replaced) 토큰 재제출 = 탈취 시도로 간주 → 세션 전체 revoke.
 * - EC-22: 동시 rotate 경쟁 = race loser 도 탈취 위험 → 세션 전체 revoke.
 *
 * ## 트랜잭션 (DATA.md §6)
 * [rotate] 는 `@Transactional` REQUIRED — 신규 토큰 INSERT + markUsedAndChain 단일 트랜잭션.
 *
 * @param repo [RefreshTokenRepository] — refresh_tokens 테이블 접근
 * @param sessionService [SessionService] — 세션 조회/폐기
 * @param jwtIssuer [JwtIssuer] — access token 발급
 * @param clock 시각 주입 (테스트 가용성)
 */
@Service
class RefreshTokenService(
    private val repo: RefreshTokenRepository,
    private val sessionService: SessionService,
    private val jwtIssuer: JwtIssuer,
    private val clock: Clock = Clock.systemUTC(),
) {

    /**
     * Refresh Token rotation — 새 access token + 새 refresh token 발급 + 옛 refresh token 무효화.
     *
     * ## EC-23 replay 감지
     * [RefreshToken.isReplaced] (replacedBy != null) 또는 [RefreshToken.usedAt] != null 인 경우,
     * 이 토큰은 이미 rotation 에 사용됐거나 비정상 재제출이다.
     * **세션 전체를 즉시 revoke** 하고 [RotateResult.Failure] ([FailureReason.Replay]) 를 반환한다.
     *
     * ## EC-22 race-safe
     * [RefreshTokenRepository.markUsedAndChain] 이 null 을 반환하면 race loser 로 판단한다.
     * 동시 rotate 요청이 들어온 상황은 replay 위험과 동일하게 취급 — 세션 전체 revoke.
     *
     * @param oldTokenHash SHA-256 hex 64자 (raw token 의 hash). **로그 기록 금지.**
     * @return [RotateResult.Success] (새 토큰 쌍) 또는 [RotateResult.Failure] (사유 포함)
     */
    @Transactional
    fun rotate(oldTokenHash: String): RotateResult {
        val now = Instant.now(clock)

        // 1. 조회 — 존재하지 않으면 즉시 실패
        val old = repo.findByTokenHash(oldTokenHash)
            ?: return RotateResult.Failure(FailureReason.NotFound)

        // 2. EC-23 replay 감지 — replaced 또는 used 상태면 세션 전체 revoke
        //    replacedBy != null (isReplaced) 는 정상 rotation 이후 재사용 시도.
        //    usedAt != null 은 markUsedAndChain 이 성공한 이후의 상태.
        //    어느 쪽이든 탈취/재사용 시도로 간주한다.
        if (old.isReplaced() || old.usedAt != null) {
            repo.revokeChainFromSession(old.sessionId)
            sessionService.revoke(old.sessionId, REVOKE_REASON_REPLAY)
            return RotateResult.Failure(FailureReason.Replay)
        }

        // 3. EC-04 만료 검사 — 만료 토큰은 조용히 거부 (세션 revoke 불필요)
        if (old.isExpired(now)) {
            return RotateResult.Failure(FailureReason.Expired)
        }

        // 4. 세션 활성 검사 — 세션이 이미 폐기된 경우 (별도 로그아웃/어드민 revoke)
        val session = sessionService.lookup(old.sessionId)
        if (session == null || !session.isActive(now)) {
            return RotateResult.Failure(FailureReason.Revoked)
        }

        // 5. 신규 refresh token 생성 + INSERT
        //    markUsedAndChain 전에 먼저 저장해야 chain 연결 시 FK 제약이 통과한다.
        val rawNewToken = generateRawToken()
        val newTokenHash = sha256Hex(rawNewToken)
        val newToken = RefreshToken(
            id = UUID.randomUUID(),
            sessionId = old.sessionId,
            tokenHash = newTokenHash,
            issuedAt = now,
            expiresAt = now.plus(REFRESH_TTL_DAYS, ChronoUnit.DAYS),
            usedAt = null,
            replacedBy = null,
        )
        repo.save(newToken)

        // 6. EC-22 race-safe — 단일 UPDATE + RETURNING (DB optimistic locking)
        //    race winner: oldId 반환 → 계속 진행
        //    race loser: null 반환 → 세션 전체 revoke (동시 요청 = 탈취 위험)
        val winner = repo.markUsedAndChain(oldId = old.id, newId = newToken.id)
        if (winner == null) {
            repo.revokeChainFromSession(old.sessionId)
            sessionService.revoke(old.sessionId, REVOKE_REASON_REPLAY)
            return RotateResult.Failure(FailureReason.Race)
        }

        // 7. Access token 발급
        val accessToken = jwtIssuer.issue(
            userId = session.userId,
            sessionId = session.id,
            providerId = session.providerId,
            scopes = emptyList(),
        )

        return RotateResult.Success(
            accessToken = accessToken,
            newRefreshTokenRaw = rawNewToken,
        )
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /** [TOKEN_BYTES] 바이트 CSPRNG 난수를 hex 문자열로 인코딩한다. */
    private fun generateRawToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** SHA-256(input) hex 소문자 64자. */
    private fun sha256Hex(input: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    // ── 결과 타입 ─────────────────────────────────────────────────────────────

    /**
     * [rotate] 결과 sealed class.
     *
     * - [Success]: rotation 성공. [Success.accessToken] + [Success.newRefreshTokenRaw] 포함.
     *   **[Success.newRefreshTokenRaw] 는 응답에 한 번만 포함되며 DB에 저장되지 않는다.**
     * - [Failure]: rotation 실패. [Failure.reason] 으로 사유 구분.
     */
    sealed class RotateResult {
        data class Success(
            /** RS256 서명 JWT — 15분 유효 */
            val accessToken: String,
            /** raw refresh token — HttpOnly Cookie 로 전달. DB 에는 hash 만 저장. */
            val newRefreshTokenRaw: String,
        ) : RotateResult()

        data class Failure(val reason: FailureReason) : RotateResult()
    }

    /**
     * [RotateResult.Failure] 사유.
     *
     * | 값 | 설명 | 대응 케이스 |
     * |---|---|---|
     * | [NotFound] | token_hash 미존재 | — |
     * | [Expired] | 토큰 만료 | EC-04 |
     * | [Revoked] | 세션 폐기됨 | — |
     * | [Replay] | 이미 사용된 토큰 재제출 — 탈취 시도 의심 | EC-23 |
     * | [Race] | concurrent rotate race loser | EC-22 |
     */
    enum class FailureReason { NotFound, Expired, Revoked, Replay, Race }

    internal companion object {
        /**
         * Refresh Token 유효 기간 — 14일 (SDD §19.5 세션 저장 스펙).
         * Session TTL 과 동일. Session 만료 = 마지막 Refresh Token 도 무효화.
         */
        internal const val REFRESH_TTL_DAYS: Long = 14L

        /**
         * raw token CSPRNG 바이트 수 — 32바이트 = 256비트 엔트로피.
         * hex 인코딩 결과 64자 문자열 (= [RefreshToken.HASH_LENGTH]).
         */
        internal const val TOKEN_BYTES: Int = 32

        /**
         * EC-23 replay 감지 / EC-22 race loser 시 세션 폐기 사유.
         * sessions.revoke_reason 컬럼에 기록된다.
         * "REFRESH_REPLAY" — 감사 로그 검색 키. 변경 시 운영 알림 쿼리도 함께 수정.
         */
        internal const val REVOKE_REASON_REPLAY = "REFRESH_REPLAY"
    }
}
