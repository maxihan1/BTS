// 세션 생명주기 관리 서비스 — create/lookup/findActiveByUser/revoke/revokeAllOfUser/markLastSeen (FR-AU-09)

package com.atlas.bts.identity.session

import com.atlas.bts.identity.audit.AuthAuditLog
import com.atlas.bts.identity.audit.AuthAuditLogService
import com.atlas.bts.identity.audit.AuthEventType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 세션 생명주기 관리 서비스 (FR-AU-09 Task 16 / SDD §19.5).
 *
 * 로그인 1회 = Session row 1개 원칙. 세션은 생성(로그인) → 활성 → 만료/폐기 순으로 전이한다.
 *
 * ## 트랜잭션 경계 (DATA.md §6)
 * - [create]: `@Transactional` (REQUIRED) — INSERT 단일 경계
 * - [revoke]: `@Transactional` (REQUIRED) — UPDATE 단일 경계
 * - [revokeAllOfUser]: `@Transactional` (REQUIRED) — bulk UPDATE 단일 경계
 * - [lookup]: `@Transactional(readOnly = true)` — 조회 전용
 * - [findActiveByUser]: `@Transactional(readOnly = true)` — self-service 세션 목록 조회
 * - [markLastSeen]: 트랜잭션 없음 (best-effort, SDD §3 lost update 허용)
 *
 * ## 디바이스 핑거프린트 (FR-09-23)
 * `SHA-256(userAgent + ipAddress)` hex 문자열의 앞 [FP_LENGTH]자.
 * ipAddress 또는 userAgent 가 null 이면 핑거프린트도 null 로 처리한다.
 *
 * ## GC (미구현 — 별도 후속 PR 예정)
 * `gcExpired()` — expires_at < NOW() 인 세션 + 귀속 RefreshToken 을 주기적으로 삭제한다.
 * RefreshToken 만료(14일) 이후 추가 30일 보존 후 제거 (감사 보존 정책).
 * 구현 위치: 별도 `SessionGcJob.kt` (@Scheduled) — Task 16 범위 외, 후속 PR 에서 처리.
 *
 * @see SessionRepository sessions 테이블 접근 인터페이스
 * @see Session 세션 도메인 엔티티
 */
@Service
class SessionService(
    private val repo: SessionRepository,
    private val auditLog: AuthAuditLogService,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * 신규 세션을 생성하고 DB 에 INSERT 한다.
     *
     * - [Session.id]: UUID v4 신규 생성
     * - [Session.expiresAt]: `now + SESSION_TTL_DAYS`
     * - [Session.deviceFingerprint]: `SHA-256(userAgent + ipAddress)` hex 앞 [FP_LENGTH]자.
     *   [ipAddress] 또는 [userAgent] 가 null 이면 null.
     *
     * @param userId    인증된 사용자 ID
     * @param providerId 인증 Provider 식별자 (예: "local", "ldap-corp")
     * @param ipAddress 클라이언트 IP (보안 감사 로그용). null 허용
     * @param userAgent User-Agent 헤더 (보안 감사 로그용). null 허용
     * @param mfaVerified 2차 요소(TOTP) 통과 여부 (FR-MF-01). 기본 `false` — 1차 인증만 거친
     *   기존 로그인 흐름은 미전달 시 `false` 가 유지된다(회귀 0). TOTP 검증 성공 후 발급되는
     *   정식 세션만 `true` 로 생성한다. [Session.mfaVerified] → JWT `mfa_verified` 클레임 원천.
     * @return 저장된 [Session]
     */
    @Transactional
    fun create(
        userId: UUID,
        providerId: String,
        ipAddress: String?,
        userAgent: String?,
        mfaVerified: Boolean = false,
    ): Session {
        val now = Instant.now(clock)
        val session =
            Session(
                id = UUID.randomUUID(),
                userId = userId,
                providerId = providerId,
                deviceFingerprint = computeFingerprint(userAgent, ipAddress),
                ipAddress = ipAddress,
                userAgent = userAgent,
                createdAt = now,
                expiresAt = now.plus(SESSION_TTL_DAYS, ChronoUnit.DAYS),
                lastSeenAt = now,
                revokedAt = null,
                revokeReason = null,
                mfaVerified = mfaVerified,
            )
        repo.save(session)
        return session
    }

    /**
     * 세션 ID 로 단건 조회한다.
     *
     * Access Token 검증 시 `sid` 클레임으로 이 메서드를 호출하여 [Session.isActive] 를 확인한다
     * (FR-09-11, Task 34 SidRevokeJwtConverter 가 호출).
     * 폐기([Session.revokedAt] 설정) 또는 만료([Session.expiresAt] 경과) 세션도 그대로 반환한다.
     * 상태 판별은 호출 측에서 [Session.isActive] / [Session.isRevoked] / [Session.isExpired] 로 수행한다.
     *
     * @return 세션이 존재하면 [Session], 없으면 null
     */
    @Transactional(readOnly = true)
    fun lookup(sid: UUID): Session? = repo.findById(sid)

    /**
     * 단일 세션을 명시적으로 폐기한다.
     *
     * 이미 폐기된 세션이나 존재하지 않는 sid 는 0 행 영향으로 조용히 무시된다 (멱등).
     *
     * @param sid    폐기할 세션 ID
     * @param reason 폐기 사유 (예: "logout", "replay_detected", "password_changed")
     */
    @Transactional
    fun revoke(
        sid: UUID,
        reason: String,
    ) {
        repo.markRevoked(sid, reason)
    }

    /**
     * 특정 사용자의 모든 활성 세션을 일괄 폐기한다.
     *
     * 패스워드 변경, 계정 잠금, 전체 로그아웃 시 사용한다.
     *
     * ## 감사 emit (FR-AU-10)
     * 실제로 폐기된 세션이 1개 이상이면 [AuthEventType.LOGOUT_ALL_DEVICES] 를 감사 로그에 기록한다.
     * 폐기된 세션이 0개(이미 모두 만료/폐기 상태)이면 기록하지 않는다 — 의미 없는 이벤트 방지.
     * service 레이어 `@Transactional` 경계 내 동기 기록이므로 폐기 UPDATE 와 원자적으로 커밋/롤백된다
     * (NFR-3, best-effort 아님 — 감사 기록 실패 시 폐기도 롤백).
     *
     * @param userId 폐기 대상 사용자 ID
     * @param reason 폐기 사유 (예: "logout_all", "password_changed")
     * @return 영향받은 세션 수
     */
    @Transactional
    fun revokeAllOfUser(
        userId: UUID,
        reason: String,
    ): Int {
        val revoked = repo.revokeAllByUserId(userId, reason)
        if (revoked > NO_SESSIONS_REVOKED) {
            auditLog.record(
                AuthAuditLog(
                    userId = userId,
                    eventType = AuthEventType.LOGOUT_ALL_DEVICES,
                    providerId = AUDIT_PROVIDER_ID,
                    metadata = mapOf("revokedSessionCount" to revoked.toString()),
                ),
            )
        }
        return revoked
    }

    /**
     * 사용자의 활성 세션 목록을 조회한다.
     *
     * `GET /api/v1/auth/sessions` 엔드포인트가 호출하는 self-service 세션 목록 조회 메서드.
     * [SessionRepository.findActiveByUserId] 에 위임한 뒤 [Session.lastSeenAt] 내림차순으로 정렬하여 반환한다.
     * 정렬은 Service 레이어에서 수행한다 — repo SQL 에 ORDER BY 가 없으며 세션 수가 수십 개 이하라 성능 무관 (NFR-4).
     *
     * @param userId 조회할 사용자 ID
     * @return 활성 세션 목록 (lastSeenAt DESC). 없으면 빈 리스트
     */
    @Transactional(readOnly = true)
    fun findActiveByUser(userId: UUID): List<Session> =
        repo.findActiveByUserId(userId).sortedByDescending { it.lastSeenAt }

    /**
     * 세션의 마지막 활동 시각을 현재 시각으로 갱신한다 (best-effort).
     *
     * SDD §3: lost update 허용 — 동시 요청에서 덮어쓰기가 발생해도 통계 목적 데이터이므로 무방.
     * partial index `idx_sessions_user_active` 활용 쿼리는 [SessionRepository.updateLastSeen] 참고.
     *
     * @param sid 갱신할 세션 ID
     */
    fun markLastSeen(sid: UUID) {
        repo.updateLastSeen(sid)
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * [userAgent] + [ipAddress] 의 SHA-256 hex 앞 [FP_LENGTH] 자를 반환한다 (FR-09-23).
     *
     * 어느 쪽이든 null 이면 null 을 반환한다.
     */
    private fun computeFingerprint(
        userAgent: String?,
        ipAddress: String?,
    ): String? {
        if (userAgent == null || ipAddress == null) return null
        val digest = MessageDigest.getInstance("SHA-256")
        val raw = digest.digest((userAgent + ipAddress).toByteArray(Charsets.UTF_8))
        return raw.joinToString("") { "%02x".format(it) }.take(FP_LENGTH)
    }

    private companion object {
        /** 세션 만료 기간 — 14일 (SDD §19.5 세션 저장 스펙) */
        internal const val SESSION_TTL_DAYS: Long = 14L

        /** device_fingerprint hex 길이 (FR-09-23) */
        internal const val FP_LENGTH: Int = 12

        /** LOGOUT_ALL_DEVICES emit 임계값 — 폐기 세션 수가 이 값을 초과해야 감사 기록 (FR-AU-10) */
        internal const val NO_SESSIONS_REVOKED: Int = 0

        /** 전체 로그아웃 감사 이벤트의 providerId — 특정 Provider 가 아닌 세션 일괄 폐기 동작 (FR-AU-10) */
        internal const val AUDIT_PROVIDER_ID: String = "session"
    }
}
