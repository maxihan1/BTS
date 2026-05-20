// personal_access_tokens 테이블 행 매핑 도메인 엔티티 — scope 평가 + 만료/폐기 상태 판별 (SDD 19.5, V006)

package com.atlas.bts.identity.pat

import java.time.Instant
import java.util.UUID

/**
 * Personal Access Token (PAT) 도메인 엔티티.
 *
 * API 클라이언트가 사용하는 장기 토큰. UI 로그인 없이 CI/CD, 스크립트 등 프로그래밍
 * 방식으로 BTS API 를 호출할 때 사용된다.
 *
 * ## 보안 정책 (EC-26)
 * - [tokenHash] 는 `SHA-256("pat_" + body)` 64자 소문자 hex. prefix 포함 전체 토큰을 해시한다.
 * - raw token (`pat_` + 48자 base62 body) 은 발급 응답에 한 번만 포함되고 DB 에는 저장하지 않는다.
 * - [toString] 에서 [tokenHash] 는 `***` 로 마스킹된다.
 *
 * ## 무기한 정책 (EC-27)
 * - [expiresAt] 은 nullable. `null` 이면 무기한 유효 토큰이다.
 * - 운영 관리 차원의 강제 만료 정책 (1년 default 등) 은 후속 PR 검토 예정.
 *
 * ## 상태 메서드
 * - [hasScope] — 요청 scope 가 이 PAT 에 허용되어 있는지 확인한다. `*` 와일드카드 지원.
 * - [isExpired] — 만료 여부를 확인한다. [expiresAt] 이 null 이면 항상 false.
 * - [isActive] — revoke 되지 않고 만료되지 않은 사용 가능 상태인지 확인한다.
 *
 * ## V006 스키마 매핑
 * | 컬럼 | 필드 | 비고 |
 * |---|---|---|
 * | id | [id] | UUID PK |
 * | user_id | [userId] | users.id FK ON DELETE CASCADE |
 * | name | [name] | 사용자 지정 레이블 |
 * | token_hash | [tokenHash] | SHA-256 hex 64자 UNIQUE |
 * | scopes | [scopes] | JSONB `[]` default |
 * | expires_at | [expiresAt] | TIMESTAMPTZ nullable (무기한 허용) |
 * | last_used_at | [lastUsedAt] | TIMESTAMPTZ nullable |
 * | revoked_at | [revokedAt] | TIMESTAMPTZ nullable |
 * | created_at | [createdAt] | TIMESTAMPTZ NOT NULL |
 *
 * @see com.atlas.bts.identity.session.Session 세션 엔티티 (로그인 단위)
 */
data class PersonalAccessToken(
    val id: UUID,
    val userId: UUID,
    val name: String,
    val tokenHash: String,
    val scopes: List<String>,
    val expiresAt: Instant?,
    val lastUsedAt: Instant?,
    val revokedAt: Instant?,
    val createdAt: Instant,
) {
    /**
     * 요청된 [scope] 가 이 PAT 에 허용되어 있는지 확인한다.
     *
     * `*` (와일드카드) 가 scopes 에 포함된 경우 모든 scope 를 허용한다.
     */
    fun hasScope(scope: String): Boolean = scopes.contains(scope) || scopes.contains("*")

    /**
     * 만료 여부를 확인한다.
     *
     * [expiresAt] 이 null 이면 무기한 토큰이므로 false 를 반환한다 (EC-27).
     * [expiresAt] <= [now] 이면 만료된 것으로 간주한다.
     */
    fun isExpired(now: Instant): Boolean = expiresAt != null && !expiresAt.isAfter(now)

    /**
     * 사용 가능한 활성 상태인지 확인한다.
     *
     * revoke 되지 않고 만료되지 않은 경우에만 true 를 반환한다.
     */
    fun isActive(now: Instant): Boolean = revokedAt == null && !isExpired(now)

    override fun toString(): String =
        "PersonalAccessToken(" +
            "id=$id, userId=$userId, name=$name, tokenHash=***, " +
            "scopes=$scopes, expiresAt=$expiresAt, lastUsedAt=$lastUsedAt, " +
            "revokedAt=$revokedAt, createdAt=$createdAt)"

    internal companion object {
        /** raw token 의 prefix. `pat_` + [TOKEN_BODY_LENGTH] 자 base62 body 로 구성된다. */
        const val TOKEN_PREFIX = "pat_"

        /** raw token body 길이 (base62). token_hash = SHA-256(TOKEN_PREFIX + body 48자). */
        const val TOKEN_BODY_LENGTH = 48
    }
}
