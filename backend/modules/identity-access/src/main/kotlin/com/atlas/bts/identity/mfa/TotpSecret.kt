// TOTP secret 엔티티(사용자당 1건) + 설정 상태 enum — totp_secrets 테이블 영속 모델. SDD §19.7

package com.atlas.bts.identity.mfa

import java.time.Instant
import java.util.UUID

/**
 * TOTP(다중 요소 인증) 설정 상태 (FR-MF-01).
 *
 * - [PENDING] — secret 생성 후 enable 확인(첫 코드 검증) 전. 2단계 로그인 미적용.
 * - [ACTIVE] — enable 확인 완료. 로그인 시 2차 요소(TOTP)를 강제한다.
 *
 * DB `totp_secrets.status` CHECK 제약(`'PENDING'`/`'ACTIVE'`)과 1:1 대응한다.
 */
enum class TotpStatus {
    PENDING,
    ACTIVE,
}

/**
 * `totp_secrets` 테이블의 영속 모델 — 사용자당 1건(user_id PK). SDD §19.7.
 *
 * TOTP secret 은 검증 시 원본이 필요하므로 양방향 암호문([secretCipher])으로 저장한다
 * (비밀번호의 Argon2 단방향과 구분). 평문은 절대 보관/로그하지 않는다(DEVELOPMENT.md §1.1.1).
 *
 * @property userId BTS 사용자 식별자(`users.id`). PK 겸용 — 사용자당 TOTP 1개.
 * @property secretCipher TOTP secret 암호문(AES-256-GCM, MfaSecretEncryptor). 평문 금지.
 * @property status 설정 상태([PENDING]/[ACTIVE]).
 * @property lastVerifiedStep 마지막 검증 성공 time-step(RFC 6238). 코드 replay 차단용. NULL=미검증.
 * @property confirmedAt enable(ACTIVE 전이) 시각. PENDING 상태에서는 null.
 * @property createdAt secret 최초 생성 시각(setup 시점).
 * @property updatedAt 마지막 변경 시각(재setup/activate/step 갱신).
 */
data class TotpSecret(
    val userId: UUID,
    val secretCipher: String,
    val status: TotpStatus,
    val lastVerifiedStep: Long?,
    val confirmedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)
