// refresh_tokens 테이블 행 매핑 도메인 엔티티 — rotation chain + isUsable 상태 판별 (SDD 19.5)

package com.atlas.bts.identity.session

import java.time.Instant
import java.util.UUID

/**
 * Refresh Token 도메인 엔티티. SDD 19.5 §V005 refresh_tokens 테이블에 대응한다.
 *
 * ## 보안 정책
 * - [tokenHash] 는 `SHA-256(raw token)` 64자 소문자 hex. raw token 은 발급 응답에만 한 번 포함된다.
 *   **DB에 raw token 절대 저장 금지** (DEVELOPMENT.md §1.1 규칙 2 — 토큰은 해시 저장).
 * - [replacedBy] 는 rotation chain — 토큰 사용 시 새 토큰 ID 를 기록하고 현재 토큰을 무효화한다.
 *   reuse detection: [replacedBy] 가 설정된 토큰이 재사용되면 세션 전체를 폐기한다.
 *
 * ## 필드
 * - [id]: 토큰 고유 식별자 (PK). UUID v4.
 * - [sessionId]: 상위 세션 FK (`sessions.id`). 세션 폐기 시 연관 토큰 전체가 무효화된다.
 * - [tokenHash]: SHA-256 hex 64자. [HASH_LENGTH] 상수로 길이 + 소문자 hex 포맷을 init 블록에서 강제한다.
 * - [issuedAt]: 토큰 최초 발급 시각.
 * - [expiresAt]: 토큰 만료 시각. [isUsable] / [isExpired] 의 기준 시각.
 * - [usedAt]: 토큰이 최초 사용된 시각. null 이면 미사용. 설정 후 [isUsable] 은 false 를 반환한다.
 * - [replacedBy]: rotation 으로 이 토큰을 대체한 새 토큰의 ID. null 이면 아직 교체되지 않았다.
 *
 * ## 상태 메서드
 * - [isUsable] — 아직 사용되지 않고 만료되지 않은 토큰인지 확인한다.
 * - [isExpired] — 만료 여부만 단독 확인한다.
 * - [isReplaced] — rotation 으로 교체된 토큰인지 확인한다.
 *
 * @see Session 상위 세션 (1 로그인 = 1 Session row)
 */
data class RefreshToken(
    val id: UUID,
    val sessionId: UUID,
    val tokenHash: String,
    val issuedAt: Instant,
    val expiresAt: Instant,
    val usedAt: Instant?,
    val replacedBy: UUID?,
) {
    init {
        require(tokenHash.length == HASH_LENGTH && tokenHash.all { it.isDigit() || it in 'a'..'f' }) {
            "tokenHash 는 ${HASH_LENGTH}자 소문자 hex 이어야 한다. 실제 길이: ${tokenHash.length}"
        }
    }

    /** 사용 가능한 토큰인지 확인한다. 한 번이라도 사용됐거나 만료됐으면 false. */
    fun isUsable(now: Instant): Boolean = usedAt == null && expiresAt > now

    /** 만료 여부를 확인한다. expiresAt <= now 이면 true. */
    fun isExpired(now: Instant): Boolean = expiresAt <= now

    /** rotation 으로 교체된 토큰인지 확인한다. */
    fun isReplaced(): Boolean = replacedBy != null

    internal companion object {
        const val HASH_LENGTH = 64
    }
}
