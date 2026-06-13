// WebAuthn Relying Party 설정(rpId/rpName/origin)을 bts.webauthn.* 프로퍼티에서 바인딩하는 @ConfigurationProperties (FR-MF-03)

package com.atlas.bts.identity.mfa

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * WebAuthn Relying Party(RP) 식별/검증에 필요한 설정값 (FR-MF-03 Task 1).
 *
 * WebAuthn 은 패스키/보안키 표준으로, "이 인증을 어느 서비스(RP)가 요청했는가"를
 * [rpId] · [origin] 으로 식별·검증한다. 등록(attestation)/인증(assertion) 응답의 origin 이
 * 이 값과 일치해야 하므로, 배포 환경별로 환경변수(`BTS_WEBAUTHN_*`)로 주입한다(Spring relaxed binding).
 *
 * ## 부팅 안전성 — 기본값 + 사용 시점 검증
 * 모든 필드에 빈 문자열 기본값을 둔다. 프로퍼티 미설정(슬라이스/테스트) 환경에서도 바인딩이
 * NPE 없이 성공해 빈이 항상 생성된다. 실제 값 유효성은 사용 시점(등록/인증 검증)에 위임한다.
 * 이 '항상 등록 + 사용 시점 검증' 정책은 [MfaEncryptionConfig] 의 키/salt 기본값 패턴과 동일하다.
 *
 * @property rpId Relying Party ID. 통상 서비스 도메인(예: `bts.example.com`). 포트/스킴 미포함.
 * @property rpName 사용자에게 표시되는 RP 이름(인증기 UI 에 노출).
 * @property origin 허용 origin. 등록/인증 응답의 origin 이 이 값과 일치해야 한다(스킴+호스트+포트).
 */
@ConfigurationProperties("bts.webauthn")
data class WebAuthnProperties(
    val rpId: String = "",
    val rpName: String = "",
    val origin: String = "",
)
