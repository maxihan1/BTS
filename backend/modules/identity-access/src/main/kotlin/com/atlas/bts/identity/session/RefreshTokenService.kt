// Refresh Token rotation 서비스 — 새 access/refresh 발급 + 옛 refresh 무효화 + EC-22/23 보안 처리 (FR-AU-09 Task 17)

package com.atlas.bts.identity.session

import com.atlas.bts.identity.audit.AuthAuditLog
import com.atlas.bts.identity.audit.AuthAuditLogService
import com.atlas.bts.identity.audit.AuthEventType
import com.atlas.bts.identity.jwt.JwtIssuer
import com.atlas.bts.identity.systemrole.SystemRoleAssignmentRepository
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
 * @param systemRoleAssignmentRepository [SystemRoleAssignmentRepository] — 전역 시스템 역할 조회 (FR-PM-08)
 * @param auditLog [AuthAuditLogService] — 인증 감사 로그 emit (FR-AU-10)
 * @param clock 시각 주입 (테스트 가용성)
 */
@Service
class RefreshTokenService(
    private val repo: RefreshTokenRepository,
    private val sessionService: SessionService,
    private val jwtIssuer: JwtIssuer,
    private val systemRoleAssignmentRepository: SystemRoleAssignmentRepository,
    private val auditLog: AuthAuditLogService,
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
     * ## 감사 emit (FR-AU-10, C-5)
     * - 성공: [RotateResult.Success] 직전 [AuthEventType.TOKEN_REFRESHED]
     *   (`metadata.oldTokenId`/`newTokenId`).
     * - replay 분기([FailureReason.Replay]): [AuthEventType.SUSPICIOUS_REFRESH_REPLAY]
     *   (`metadata.reason = "replay"`).
     * - race-loser 분기([FailureReason.Race]): [AuthEventType.SUSPICIOUS_REFRESH_REPLAY]
     *   (`metadata.reason = "race"`).
     * replay/race 둘 다 탈취 위험이므로 동일 이벤트 유형으로 기록하고 `metadata.reason` 으로 구분한다.
     * service 레이어 `@Transactional` 경계 내 동기 emit 이므로 체인 폐기 UPDATE 와 원자적으로 커밋된다
     * (rotate 는 예외가 아닌 [RotateResult.Failure] 를 반환하므로 트랜잭션이 커밋되며 감사 row 가 함께 남는다).
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
            // 감사 주체 식별을 위해 세션을 조회한다 (이미 폐기됐을 수 있으므로 nullable).
            val session = sessionService.lookup(old.sessionId)
            repo.revokeChainFromSession(old.sessionId)
            sessionService.revoke(old.sessionId, REVOKE_REASON_REPLAY)
            recordSuspiciousReplay(session, REPLAY_REASON_REPLAY)
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
            recordSuspiciousReplay(session, REPLAY_REASON_RACE)
            return RotateResult.Failure(FailureReason.Race)
        }

        // 7. Access token 발급 — 전역 시스템 역할(roles)을 함께 주입 (FR-PM-08)
        //    GAP-1 (FR-MF-01): 세션의 mfa_verified 를 그대로 전파해야 회전 후에도 2차 인증 상태가 보존된다.
        //    이를 누락하면 refresh 회전 시 mfa_verified 클레임이 기본 false 로 떨어진다.
        val roles = systemRoleAssignmentRepository.findRolesByUser(session.userId).map { it.name }
        val accessToken = jwtIssuer.issue(
            userId = session.userId,
            sessionId = session.id,
            providerId = session.providerId,
            scopes = emptyList(),
            roles = roles,
            mfaVerified = session.mfaVerified,
        )

        // FR-AU-10 — rotation 성공 감사. tokenId 만 기록(raw token / hash 절대 금지, §1.1 규칙 2).
        recordTokenRefreshed(session, oldTokenId = old.id, newTokenId = newToken.id)

        return RotateResult.Success(
            accessToken = accessToken,
            newRefreshTokenRaw = rawNewToken,
        )
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * TOKEN_REFRESHED 감사 이벤트를 기록한다 (FR-AU-10).
     *
     * raw token / token_hash 는 절대 기록하지 않고 식별용 tokenId(UUID)만 metadata 에 담는다 (§1.1 규칙 2).
     *
     * @param session    rotation 주체 세션
     * @param oldTokenId 무효화된 옛 refresh token id
     * @param newTokenId 새로 발급된 refresh token id
     */
    private fun recordTokenRefreshed(
        session: Session,
        oldTokenId: UUID,
        newTokenId: UUID,
    ) {
        val metadata =
            mapOf(
                METADATA_OLD_TOKEN_ID to oldTokenId.toString(),
                METADATA_NEW_TOKEN_ID to newTokenId.toString(),
            )
        auditLog.record(
            AuthAuditLog(
                userId = session.userId,
                eventType = AuthEventType.TOKEN_REFRESHED,
                providerId = session.providerId,
                metadata = metadata,
            ),
        )
    }

    /**
     * SUSPICIOUS_REFRESH_REPLAY 감사 이벤트를 기록한다 (FR-AU-10, C-5).
     *
     * replay 분기와 race-loser 분기가 공유하는 emit 지점이다. 주체 식별은 [session] 의 userId/providerId 로 하되,
     * 세션이 이미 폐기/소실된 경우 [session] 이 null 이므로 userId 는 null, providerId 는 [AUDIT_PROVIDER_FALLBACK] 로 둔다.
     *
     * @param session 감사 주체 식별용 세션 (nullable — 이미 폐기됐을 수 있음)
     * @param reason `metadata.reason` 값 — [REPLAY_REASON_REPLAY] 또는 [REPLAY_REASON_RACE]
     */
    private fun recordSuspiciousReplay(
        session: Session?,
        reason: String,
    ) {
        auditLog.record(
            AuthAuditLog(
                userId = session?.userId,
                eventType = AuthEventType.SUSPICIOUS_REFRESH_REPLAY,
                providerId = session?.providerId ?: AUDIT_PROVIDER_FALLBACK,
                metadata = mapOf(METADATA_REASON to reason),
            ),
        )
    }

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

        /** SUSPICIOUS_REFRESH_REPLAY 세션 소실 시 providerId fallback (FR-AU-10). */
        private const val AUDIT_PROVIDER_FALLBACK = "refresh"

        /** TOKEN_REFRESHED metadata 키 — 무효화된 옛 토큰 id (FR-AU-10). */
        private const val METADATA_OLD_TOKEN_ID = "oldTokenId"

        /** TOKEN_REFRESHED metadata 키 — 새로 발급된 토큰 id (FR-AU-10). */
        private const val METADATA_NEW_TOKEN_ID = "newTokenId"

        /** SUSPICIOUS_REFRESH_REPLAY metadata 키 — 의심 사유 구분 (FR-AU-10, C-5). */
        private const val METADATA_REASON = "reason"

        /** SUSPICIOUS_REFRESH_REPLAY reason — 이미 사용/교체된 토큰 재제출 (EC-23). */
        private const val REPLAY_REASON_REPLAY = "replay"

        /** SUSPICIOUS_REFRESH_REPLAY reason — 동시 rotate race loser (EC-22). */
        private const val REPLAY_REASON_RACE = "race"
    }
}
