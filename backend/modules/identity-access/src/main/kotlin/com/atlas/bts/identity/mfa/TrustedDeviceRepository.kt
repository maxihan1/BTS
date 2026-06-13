// trusted_devices 테이블 접근 포트 인터페이스 — 등록/해시조회/사용시각갱신/목록/소유삭제/전체폐기 (FR-MF-05)

package com.atlas.bts.identity.mfa

import java.time.Instant
import java.util.UUID

/**
 * `trusted_devices` 테이블 접근 포트 (FR-MF-05 Task 3). SDD §19 / ADR 2026-06-13.
 *
 * 구현체: [JdbcTrustedDeviceRepository].
 *
 * ## 시각 주입
 * 만료 판정에 쓰는 `now` 는 **항상 호출 측이 전달**한다(repo 는 `Instant.now()`/`Clock` 을 직접 참조하지
 * 않는다). 시각 의존 로직을 핸들러에서 하드코딩하면 특정 날짜에 깨지는 time-bomb 이 되므로, 상위 서비스가
 * 주입 [java.time.Clock] 으로 산출한 `now` 를 파라미터로 넘긴다(authcontroller-revokesession-timebomb 교훈).
 *
 * ## 보안 정책
 * - [TrustedDevice.tokenHash](SHA-256 hex) 는 로그에 절대 기록하지 않는다(DEVELOPMENT.md §1.1 규칙 2).
 * - raw token 평문은 DB 에 저장하지 않으며 발급 시 쿠키로만 1회 노출된다([TrustedDeviceToken]).
 * - 소유 검증([deleteByIdAndUser])은 `WHERE id AND user_id` 복합 조건으로 타인 디바이스 삭제(IDOR)를 차단한다.
 */
interface TrustedDeviceRepository {
    /**
     * 신규 신뢰 디바이스 INSERT.
     *
     * 호출 측이 [TrustedDevice.id](UUID v4)·`createdAt`·`expiresAt`(주입 Clock 기반)을 생성해 전달한다.
     * [TrustedDevice.tokenHash] UNIQUE 제약 위반 시 예외가 전파된다(극저확률 충돌은 호출 측에서 best-effort 처리).
     */
    fun insert(device: TrustedDevice)

    /**
     * token_hash (SHA-256 hex 64자) 로 단건 조회.
     *
     * 만료 여부와 무관하게(만료 행도) 반환한다 — 만료 판정은 [TrustedDevice.isExpired] 로 호출 측이 수행한다.
     *
     * @param tokenHash SHA-256 hex 64자. **로그 기록 금지.**
     * @return 존재하면 [TrustedDevice], 없으면 null
     */
    fun findByTokenHash(tokenHash: String): TrustedDevice?

    /**
     * 신뢰 우회 로그인 성공 시 마지막 사용 시각을 갱신한다(`expires_at` 은 갱신하지 않음 — 고정 30일).
     *
     * @param id 대상 신뢰 디바이스 PK
     * @param now 갱신할 시각(주입 Clock 기반, 호출 측 전달)
     */
    fun updateLastUsedAt(
        id: UUID,
        now: Instant,
    )

    /**
     * 사용자별 **미만료** 신뢰 디바이스 목록 조회(`expires_at > :now` — 만료 행 제외).
     *
     * @param userId 소유 사용자
     * @param now 만료 기준 시각(주입 Clock 기반). 정각(`expires_at == now`)은 만료로 본다(EC10 경계).
     * @return 미만료 신뢰 디바이스 목록(없으면 빈 목록)
     */
    fun listByUser(
        userId: UUID,
        now: Instant,
    ): List<TrustedDevice>

    /**
     * 소유 검증 단건 삭제 — `WHERE user_id AND id` 복합 조건으로 타인 디바이스 삭제(IDOR)를 차단한다.
     *
     * @return 삭제된 행이 있으면(소유 일치) true, 없으면(타인/미존재) false
     */
    fun deleteByIdAndUser(
        userId: UUID,
        id: UUID,
    ): Boolean

    /**
     * 사용자의 모든 신뢰 디바이스 전량 폐기(비밀번호 변경·TOTP 비활성 등 보안 이벤트 시 자동 호출).
     *
     * 만료 행도 포함해 삭제한다.
     *
     * @return 삭제된 행 수
     */
    fun deleteAllByUser(userId: UUID): Int
}
