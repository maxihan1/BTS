// webauthn4j WebAuthnManager / ObjectConverter 빈과 WebAuthnProperties 바인딩을 등록하는 @Configuration (FR-MF-03)

package com.atlas.bts.identity.mfa

import com.webauthn4j.WebAuthnManager
import com.webauthn4j.converter.util.ObjectConverter
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * WebAuthn(패스키/보안키) 등록·인증 검증에 필요한 webauthn4j 빈을 등록하는 설정 (FR-MF-03 Task 1).
 *
 * ## 등록 빈
 * - [WebAuthnManager] : 등록(attestation)/인증(assertion) 응답을 검증하는 핵심 엔진. 후속 task 가
 *   등록/인증 검증에 사용한다.
 * - [ObjectConverter] : webauthn4j 의 JSON/CBOR 직렬화/역직렬화 유틸. 후속 task 가
 *   `PublicKeyCredentialCreationOptions` 직렬화 등에 사용한다.
 *
 * ## attestation 미검증(none)
 * [WebAuthnManager.createNonStrictWebAuthnManager] 는 attestation statement 를 검증하지 않는
 * non-strict 매니저를 생성한다(attestation conveyance = none). 사내 워크스페이스 환경에서는
 * 특정 인증기 제조사 검증(strict attestation)이 불필요하므로 none 정책을 사용한다.
 *
 * ## 부팅 안전성 — 항상 등록 + @ConfigurationProperties 국소 등록
 * [WebAuthnProperties] 를 [EnableConfigurationProperties] 로 이 설정에 국소 등록한다(전역
 * `@ConfigurationPropertiesScan` 미도입 — 모듈 전역 영향 차단). 빈은 항상 등록되며 값 유효성은
 * 사용 시점에 검증한다(슬라이스/미설정 환경 부팅 안전성). MfaEncryptionConfig 의 '항상 등록' 패턴과 동일.
 *
 * @see WebAuthnProperties
 */
@Configuration
@EnableConfigurationProperties(WebAuthnProperties::class)
class WebAuthnConfig {
    /**
     * webauthn4j 등록/인증 검증 엔진 [WebAuthnManager] 빈 (attestation 미검증 non-strict).
     *
     * @return non-strict [WebAuthnManager] (attestation conveyance = none).
     */
    @Bean
    fun webAuthnManager(): WebAuthnManager = WebAuthnManager.createNonStrictWebAuthnManager()

    /**
     * webauthn4j JSON/CBOR 직렬화 유틸 [ObjectConverter] 빈.
     *
     * 후속 task 가 `PublicKeyCredentialCreationOptions` 등 WebAuthn 옵션 직렬화에 사용한다.
     *
     * @return Jackson 2 기반 [ObjectConverter].
     */
    @Bean
    fun webAuthnObjectConverter(): ObjectConverter = ObjectConverter()
}
