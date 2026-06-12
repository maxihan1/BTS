// WebAuthn 자격증명(webauthn_credentials) 영속 연산 추상 — 등록/조회/소유삭제/카운터전진/사용시각 (FR-MF-03)

package com.atlas.bts.identity.mfa

import java.time.Instant
import java.util.UUID

/**
 * WebAuthn(Passkey) 자격증명의 영속 연산 추상(`webauthn_credentials`, FR-MF-03). SDD §19.8.
 *
 * 구현은 [JdbcWebAuthnCredentialRepository] 이며, 테스트 대역 교체와 의존 역전을 위해 인터페이스로 노출한다.
 */
interface WebAuthnCredentialRepository {
    /**
     * 새 WebAuthn 자격증명을 저장한다(등록 검증 통과 후 즉시 활성 — status 컬럼 없음).
     *
     * @param credential 저장할 자격증명. [WebAuthnCredential.credentialId] 는 전역 UNIQUE.
     */
    fun insert(credential: WebAuthnCredential)

    /**
     * 사용자의 모든 자격증명을 조회한다(사용자당 N건). 목록/관리 화면용.
     *
     * @param userId 사용자 식별자.
     * @return 해당 사용자의 자격증명 목록(없으면 빈 목록).
     */
    fun findByUser(userId: UUID): List<WebAuthnCredential>

    /**
     * 전역 UNIQUE credential_id 로 단건 조회한다(assertion 검증 진입점).
     *
     * @param credentialId `base64url(rawId)`.
     * @return 일치하는 자격증명, 없으면 `null`.
     */
    fun findByCredentialId(credentialId: String): WebAuthnCredential?

    /**
     * 소유자 검증과 함께 자격증명을 삭제한다(타인 id 삭제 차단).
     *
     * `WHERE id = :id AND user_id = :userId` 조건으로, 소유자가 일치할 때만 삭제된다.
     *
     * @param id 자격증명 PK.
     * @param userId 요청자(소유자) 식별자.
     * @return 실제로 삭제된 행이 있으면 `true`(소유 일치), 없으면 `false`(미존재/타인 소유).
     */
    fun deleteByIdAndUser(
        id: UUID,
        userId: UUID,
    ): Boolean

    /**
     * 서명 카운터를 조건부로 전진시킨다(복제 인증기 clone 방어).
     *
     * `WHERE id = :id AND (sign_count < :newCount OR (sign_count = 0 AND :newCount = 0))` 단일 atomic UPDATE 로,
     * 더 큰 값일 때만 전진하고 같거나 작은 값(=clone/replay 의심)은 0행이 갱신되어 `false` 로 수렴한다.
     * 예외적으로 항상 0 을 보고하는 정상 인증기는 `0→0` 갱신을 허용한다.
     * lock 밖에서 읽은 값으로 판단하지 않는다(advisory-lock-bigint-toctou 교훈 — read-then-write 경쟁 차단).
     *
     * @param id 자격증명 PK.
     * @param newCount assertion 이 보고한 새 서명 카운터.
     * @return 카운터가 전진(또는 0→0)해 1행이 갱신되면 `true`, 역행/정체(clone 의심)면 `false`.
     */
    fun advanceSignCount(
        id: UUID,
        newCount: Long,
    ): Boolean

    /**
     * 마지막 사용 시각(last_used_at)을 갱신한다(assertion 성공 시각 기록).
     *
     * @param id 자격증명 PK.
     * @param at 마지막 사용(로그인) 시각.
     */
    fun touchLastUsed(
        id: UUID,
        at: Instant,
    )
}
