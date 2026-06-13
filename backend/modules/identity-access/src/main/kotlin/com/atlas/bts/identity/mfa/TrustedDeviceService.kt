// 신뢰 디바이스 등록/우회검증·갱신/목록/취소 오케스트레이션 서비스 — Clock 주입 + 감사 emit (FR-MF-05 Task 4)

package com.atlas.bts.identity.mfa

import com.atlas.bts.identity.audit.AuthAuditLog
import com.atlas.bts.identity.audit.AuthAuditLogService
import com.atlas.bts.identity.audit.AuthEventType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * 신뢰 디바이스(30일 MFA 면제) 등록/우회검증·갱신/목록/취소 오케스트레이션 서비스 (FR-MF-05 Task 4).
 * ADR 2026-06-13 / SDD §19.7.3.
 *
 * 사용자가 MFA(다단계 인증)를 통과한 뒤 "이 기기 30일 면제"에 동의하면 서버 불투명 토큰을 발급하고
 * [TrustedDeviceRepository] 에 행을 INSERT 한다. 다음 로그인 때 쿠키 토큰이 일치하고 미만료면 2차 요소
 * 챌린지를 생략한다([verifyAndTouch] true). 토큰 생성/해시([TrustedDeviceToken]), 영속
 * ([TrustedDeviceRepository]), 감사([AuthAuditLogService]), 시각 주입([Clock])을 조립한다.
 *
 * @param repo 신뢰 디바이스 영속 포트.
 * @param clock 시각 출처. 만료·등록·갱신 시각을 모두 이 Clock 으로 산출한다(time-bomb 회피).
 * @param auditLog 인증 감사 로그(등록/취소 emit).
 */
@Service
@Transactional
class TrustedDeviceService(
    private val repo: TrustedDeviceRepository,
    private val clock: Clock,
    private val auditLog: AuthAuditLogService,
) {
    fun trust(
        userId: UUID,
        userAgent: String?,
    ): String {
        val token = TrustedDeviceToken.generate()
        val createdAt = clock.instant()
        repo.insert(
            TrustedDevice(
                id = UUID.randomUUID(),
                userId = userId,
                tokenHash = token.hash,
                label = userAgent,
                createdAt = createdAt,
                expiresAt = createdAt.plus(Duration.ofDays(TRUST_TTL_DAYS)),
                lastUsedAt = null,
            ),
        )
        emit(userId, AuthEventType.TRUSTED_DEVICE_ADDED)
        return token.rawToken
    }

    @Suppress("ReturnCount")
    fun verifyAndTouch(
        userId: UUID,
        rawToken: String,
    ): Boolean {
        val device = repo.findByTokenHash(TrustedDeviceToken.hash(rawToken)) ?: return false
        if (device.userId != userId) return false
        if (device.isExpired(clock.instant())) return false
        repo.updateLastUsedAt(device.id, clock.instant())
        return true
    }

    @Transactional(readOnly = true)
    fun list(userId: UUID): List<TrustedDevice> = repo.listByUser(userId, clock.instant())

    fun revoke(
        userId: UUID,
        id: UUID,
    ): Boolean {
        val deleted = repo.deleteByIdAndUser(userId, id)
        if (deleted) {
            emit(userId, AuthEventType.TRUSTED_DEVICE_REVOKED)
        }
        return deleted
    }

    fun revokeAll(userId: UUID): Int {
        val count = repo.deleteAllByUser(userId)
        if (count > 0) {
            emit(userId, AuthEventType.TRUSTED_DEVICE_REVOKED, mapOf("count" to count.toString()))
        }
        return count
    }

    private fun emit(
        userId: UUID,
        eventType: AuthEventType,
        metadata: Map<String, String> = emptyMap(),
    ) {
        auditLog.record(
            AuthAuditLog(
                userId = userId,
                eventType = eventType,
                providerId = AUDIT_PROVIDER_ID,
                metadata = metadata,
            ),
        )
    }

    private companion object {
        const val TRUST_TTL_DAYS = 30L
        const val AUDIT_PROVIDER_ID = "mfa"
    }
}
