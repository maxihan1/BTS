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
 * ## 보안 불변식 (DEVELOPMENT.md §1.1)
 * - **비밀값 미저장/미로깅(§1.1.1·§1.1.2)**: rawToken 평문은 DB·로그·감사 metadata 에 담지 않는다.
 *   DB 에는 [TrustedDeviceToken.hash](SHA-256) 만 저장하고, rawToken 은 [trust] 반환값으로만 1회 노출된다.
 *   취소 감사 metadata 에는 count 만 기록한다([revokeAll]).
 * - **user-bound 우회([verifyAndTouch])**: 토큰 해시가 일치해도 소유 user 가 다르면 거부한다 — 타인 토큰으로
 *   남의 세션을 우회 발급하지 못하게 한다.
 * - **fail-safe**: 조회 부재·만료·user 불일치는 모두 false(거부)로 수렴한다. 불명은 우회하지 않고 챌린지로 폴백한다.
 * - **시각 주입**: 등록/만료/갱신 비교는 모두 주입 [Clock] 기준이라 특정 날짜에 깨지는 time-bomb 을 피한다
 *   (authcontroller-revokesession-timebomb 교훈).
 *
 * @param repo 신뢰 디바이스 영속 포트.
 * @param clock 시각 출처. 만료·등록·갱신 시각을 모두 이 Clock 으로 산출한다(time-bomb 회피).
 * @param auditLog 인증 감사 로그(등록/취소 emit).
 */
@Service
@Transactional
class TrustedDeviceService(
    private val repo: TrustedDeviceRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val auditLog: AuthAuditLogService,
) {
    /**
     * 신뢰 디바이스를 등록하고 식별용 raw 토큰을 반환한다.
     *
     * 불투명 토큰([TrustedDeviceToken.generate])을 만들어 그 SHA-256 해시·등록 시각·만료 시각
     * ([TRUST_TTL_DAYS]일 고정, sliding 아님)·User-Agent 라벨로 한 행을 INSERT 하고
     * [AuthEventType.TRUSTED_DEVICE_ADDED] 를 emit 한다. raw 토큰 평문은 저장하지 않고 반환값으로만 노출된다.
     *
     * @param userId 신뢰 등록 주체.
     * @param userAgent 표시용 라벨(nullable, 비밀값 아님).
     * @return 발급된 raw 토큰(hex 64자). 호출 측이 HttpOnly 쿠키로 클라이언트에 1회 전달한다.
     */
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
                // User-Agent 는 신뢰할 수 없는 클라이언트 입력 — D6 UI 저장형 XSS 표면을 줄이려 길이를 cap 한다(§1.1).
                label = userAgent?.take(LABEL_MAX_LENGTH),
                createdAt = createdAt,
                expiresAt = createdAt.plus(Duration.ofDays(TRUST_TTL_DAYS)),
                lastUsedAt = null,
            ),
        )
        emit(userId, AuthEventType.TRUSTED_DEVICE_ADDED)
        return token.rawToken
    }

    /**
     * raw 토큰이 호출 user 의 미만료 신뢰 디바이스와 일치하면 마지막 사용 시각을 갱신하고 true 를 반환한다.
     *
     * 토큰을 SHA-256 해시해 조회하고, (1) 행 존재 (2) `device.userId == userId`(user-bound) (3) 미만료
     * (`expiresAt > now`) 세 조건을 모두 만족할 때만 true 다. 하나라도 어긋나면 — 부재·타인 토큰·만료 — 갱신 없이
     * false 로 거부한다(fail-safe — 불명은 우회하지 않고 챌린지로 폴백). 만료는 연장하지 않는다(고정 TTL).
     *
     * **TOCTOU race 닫기.** findByTokenHash 읽기와 [TrustedDeviceRepository.updateLastUsedAt] 쓰기 사이에
     * revoke/revokeAll(DELETE)이 커밋되면 그 행이 사라진다. 이때 updateLastUsedAt 의 영향 행 수가 0이 되며,
     * 본 메서드는 false 로 거부한다 — 읽은 값만 보고 무조건 true 를 돌려주던 1회 우회창을 닫는다.
     *
     * @param userId 우회를 시도하는 로그인 주체.
     * @param rawToken 클라이언트 쿠키의 raw 토큰 평문. **로그 기록 금지.**
     * @return user 일치 + 미만료 + 갱신 1행이면 true(챌린지 생략 가능), 아니면 false.
     *
     * ReturnCount 억제 — 부재/타인/만료/0행 guard early-return 이 본문보다 명확하다.
     */
    @Suppress("ReturnCount")
    fun verifyAndTouch(
        userId: UUID,
        rawToken: String,
    ): Boolean {
        val device = repo.findByTokenHash(TrustedDeviceToken.hash(rawToken)) ?: return false
        if (device.userId != userId) return false
        if (device.isExpired(clock.instant())) return false
        // 읽기와 쓰기 사이 삭제됐다면(0행) 우회 불가 — fail-safe 로 거부한다(TOCTOU 1회 우회창 차단).
        return repo.updateLastUsedAt(device.id, clock.instant()) > 0
    }

    /** 사용자의 **미만료** 신뢰 디바이스 목록(관리 화면용). 만료 행은 [TrustedDeviceRepository.listByUser] 가 제외한다. */
    @Transactional(readOnly = true)
    fun list(userId: UUID): List<TrustedDevice> = repo.listByUser(userId, clock.instant())

    /**
     * 소유 검증 단건 취소. 소유가 일치해 실제 삭제됐을 때만 [AuthEventType.TRUSTED_DEVICE_REVOKED] 를 emit 한다.
     *
     * 타인/미존재(삭제 0행)는 IDOR 차단을 위해 false 를 반환하고 감사도 남기지 않는다(존재 probe 방지).
     *
     * @return 소유 일치 삭제 시 true, 타인/미존재 시 false.
     */
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

    /**
     * 사용자의 모든 신뢰 디바이스를 전량 취소한다(비밀번호 변경·TOTP 비활성 등 보안 이벤트 자동 폐기 경로).
     *
     * 1건 이상 삭제됐을 때만 [AuthEventType.TRUSTED_DEVICE_REVOKED] 를 emit 한다 — 감사 metadata 에는
     * 삭제 건수(`count`)만 담고 비밀값은 담지 않는다(§1.1.2). 0건이면 noise 를 피해 emit 하지 않는다.
     *
     * @return 삭제된 행 수.
     */
    fun revokeAll(userId: UUID): Int {
        val count = repo.deleteAllByUser(userId)
        if (count > 0) {
            emit(userId, AuthEventType.TRUSTED_DEVICE_REVOKED, mapOf("count" to count.toString()))
        }
        return count
    }

    /** 신뢰 디바이스 감사 이벤트를 기록한다. providerId 는 `"mfa"`, metadata 엔 비밀값을 담지 않는다(§1.1.2). */
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
        /** 신뢰 만료 = `createdAt + 30일`(고정, sliding 아님 — ADR 2026-06-13 D4). */
        const val TRUST_TTL_DAYS = 30L

        /** MFA 감사 이벤트의 providerId 라벨(SSO provider 아님 — 백업 코드 선례와 동일). */
        const val AUDIT_PROVIDER_ID = "mfa"

        /** User-Agent 라벨 최대 길이 — 신뢰할 수 없는 입력의 저장형 XSS 표면 축소(defense-in-depth). */
        const val LABEL_MAX_LENGTH = 256
    }
}
