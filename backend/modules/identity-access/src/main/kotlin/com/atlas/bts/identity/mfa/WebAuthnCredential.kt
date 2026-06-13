// WebAuthn(Passkey) 자격증명 엔티티(사용자당 N건) — webauthn_credentials 테이블 영속 모델. SDD §19.8

package com.atlas.bts.identity.mfa

import java.time.Instant
import java.util.UUID

/**
 * `webauthn_credentials` 테이블의 영속 모델 — 사용자당 N건(FR-MF-03). SDD §19.8.
 *
 * WebAuthn(웹 인증, Passkey)은 인증기(authenticator)에 저장된 공개키 기반 2차 인증이다.
 * 검증(assertion) 시 [attestedCredentialData] 의 공개키로 서명을 확인하며,
 * [signCount] 는 인증기 서명 카운터로 assertion 마다 단조 증가한다 — 역행/정체 시
 * 복제 인증기(clone)를 의심해 거부한다(clone 방어).
 *
 * 본 모델이 다루는 값은 공개키/카운터/메타데이터뿐이며 비밀값(개인키)은 인증기에만 존재해
 * 서버로 전송되지 않는다. 따라서 단방향 해시/암호문 대상이 아니다.
 *
 * @property id 자격증명 행 PK(`webauthn_credentials.id`).
 * @property userId BTS 사용자 식별자(`users.id`). FK ON DELETE CASCADE.
 * @property credentialId `base64url(rawId)`. WebAuthn 명세상 전역 UNIQUE.
 * @property attestedCredentialData `base64(AttestedCredentialDataConverter 직렬화)`. 공개키/AAGUID/credentialId 포함.
 * @property signCount 인증기 서명 카운터(단조 증가). 등록 시 0, assertion 성공마다 전진.
 * @property name 사용자 지정 별칭(예: "회사 노트북 Touch ID"). 다중 자격증명 식별용. null 허용.
 * @property aaguid 인증기 모델 식별자(Authenticator Attestation GUID). 기기 종류 표시용. null 허용.
 * @property lastUsedAt 마지막 assertion(로그인) 성공 시각. null=등록 후 미사용.
 * @property createdAt 자격증명 등록(registration) 시각.
 * @property updatedAt 마지막 갱신 시각(sign_count/last_used_at 변경 등).
 */
data class WebAuthnCredential(
    val id: UUID,
    val userId: UUID,
    val credentialId: String,
    val attestedCredentialData: String,
    val signCount: Long,
    val name: String?,
    val aaguid: String?,
    val lastUsedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)
