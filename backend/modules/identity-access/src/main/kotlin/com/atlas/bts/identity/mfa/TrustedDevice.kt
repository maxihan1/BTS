// trusted_devices 테이블 행 매핑 도메인 엔티티 — 30일 MFA 면제 신뢰 디바이스 + 만료 판정 (FR-MF-05)

package com.atlas.bts.identity.mfa

import java.time.Instant
import java.util.UUID

/**
 * 신뢰 디바이스 도메인 엔티티. FR-MF-05 §V026 `trusted_devices` 테이블에 대응한다.
 *
 * 사용자가 MFA(다단계 인증)를 통과한 뒤 "이 기기 30일 면제"에 동의하면, 서버 불투명 토큰으로
 * 식별되는 신뢰 디바이스 한 행이 만들어진다. 다음 로그인 시 토큰이 일치하고 미만료면 2차 요소
 * 챌린지를 생략한다(ADR 2026-06-13 D1·D3).
 *
 * ## 보안 정책
 * - [tokenHash] 는 `SHA-256(rawToken)` 소문자 hex [HASH_LENGTH]자. raw token 은 발급 시 쿠키로만
 *   클라이언트에 내려가고 **DB·로그에 평문 절대 저장/기록 금지**(DEVELOPMENT.md §1.1.1 — 비밀값 미저장,
 *   토큰은 해시 저장). 평문 발급은 [TrustedDeviceToken.generate] 가 담당한다.
 * - 만료는 고정 30일(sliding 아님, ADR D4). 신뢰 우회 로그인이 창을 연장하지 않는다.
 *
 * ## 필드
 * - [id]: 신뢰 디바이스 고유 식별자 (PK). UUID v4.
 * - [userId]: 소유 사용자 FK (`users.id`). 우회 판정은 항상 user 일치를 함께 검사한다(user-bound).
 * - [tokenHash]: SHA-256 hex [HASH_LENGTH]자. init 블록에서 길이 + 소문자 hex 포맷을 강제한다.
 * - [label]: User-Agent 파생 표시명(관리 화면용, nullable).
 * - [createdAt]: 신뢰 등록 시각.
 * - [expiresAt]: 만료 시각(`createdAt + 30일`). [isExpired] 의 기준 시각.
 * - [lastUsedAt]: 신뢰 우회 로그인 시 갱신되는 마지막 사용 시각(표시용, nullable).
 */
data class TrustedDevice(
    val id: UUID,
    val userId: UUID,
    val tokenHash: String,
    val label: String?,
    val createdAt: Instant,
    val expiresAt: Instant,
    val lastUsedAt: Instant?,
) {
    init {
        require(tokenHash.length == HASH_LENGTH && tokenHash.all { it.isDigit() || it in 'a'..'f' }) {
            "tokenHash 는 ${HASH_LENGTH}자 소문자 hex 이어야 한다. 실제 길이: ${tokenHash.length}"
        }
    }

    /**
     * 만료 여부를 판정한다. `expiresAt <= now` 이면 만료(true) — 만료 정각도 만료로 본다(EC10 경계).
     * `now.isBefore(expiresAt)`(즉 `expiresAt > now`) 일 때만 유효하다.
     */
    fun isExpired(now: Instant): Boolean = !now.isBefore(expiresAt)

    internal companion object {
        const val HASH_LENGTH = 64
    }
}
