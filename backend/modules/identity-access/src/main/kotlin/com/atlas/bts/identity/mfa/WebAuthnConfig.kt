// webauthn4j WebAuthnManager / ObjectConverter 빈과 WebAuthnProperties 바인딩을 등록하는 @Configuration (FR-MF-03)

package com.atlas.bts.identity.mfa

import com.webauthn4j.WebAuthnManager
import com.webauthn4j.converter.util.ObjectConverter
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

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

    /**
     * prod 프로필에서 RP 설정이 기본 localhost 로 남아 있으면 부팅 시 1회 WARN 으로 알리는 안전망 빈
     * (FR-MF-03 CONCERN-1).
     *
     * base `application.yml` 은 `BTS_WEBAUTHN_RP_ID`/`BTS_WEBAUTHN_ORIGIN` 미설정 시
     * `localhost`/`http://localhost:5173` 로 silent fallback 한다. prod 에서 이 값이 그대로면 실제
     * 기기 인증이 origin 불일치로 거부되므로, misconfiguration 을 관측 가능하게 만든다.
     *
     * ## fail-fast 가 아닌 WARN 인 이유 + 향후 전환
     * fail-fast(부팅 거부)로 막으면 issue-tracking 없이 단독 부팅하는 `@ActiveProfiles("prod")`
     * 통합테스트(22건)가 환경변수 미주입으로 전부 회귀한다. 따라서 부팅은 항상 성공시키고 WARN 만
     * 남긴다([NonProdSensitiveProjectResolver] 의 loud-fail 폐기 사유와 동일 — 부팅 가용성 우선).
     * **D6(프론트) prod 배포 전 fail-fast 전환 예정** — 현 시점 WebAuthn 프론트 부재로 prod 실사용이
     * 0 이라 안전망(WARN)으로 충분하다.
     *
     * ## 실행 시점 — SmartInitializingSingleton
     * [SmartInitializingSingleton.afterSingletonsInstantiated] 는 모든 싱글톤 빈 생성 후 1회 실행되어
     * '부팅 1회 점검' 의미에 부합한다(별도 DB/인프라 의존 없음, 슬라이스 부팅에서도 동작).
     *
     * ## 로깅 내용 — 설정 메타(비밀값 아님)
     * rp-id/origin 은 도메인/스킴 메타로 PII·비밀값이 아니다. 정적 안내 메시지만 출력한다.
     *
     * @param environment 활성 프로필 판정용([Environment.activeProfiles]).
     * @param properties 검사 대상 [WebAuthnProperties](rp-id/origin).
     * @return prod+localhost 감지 시 부팅 WARN 을 남기는 [SmartInitializingSingleton].
     */
    @Bean
    fun webAuthnProdConfigCheck(
        environment: Environment,
        properties: WebAuthnProperties,
    ): SmartInitializingSingleton =
        SmartInitializingSingleton {
            val prodActive = environment.activeProfiles.any { it == "prod" }
            val usesLocalhost =
                properties.rpId == "localhost" || properties.origin.contains("localhost")
            if (prodActive && usesLocalhost) {
                log.warn(
                    "WebAuthn RP가 기본 localhost로 설정됨 — prod 환경에서는 " +
                        "BTS_WEBAUTHN_RP_ID/BTS_WEBAUTHN_ORIGIN을 실제 도메인으로 설정해야 함. " +
                        "현재 설정으로는 실제 기기 인증이 origin 불일치로 거부됨.",
                )
            }
        }

    private companion object {
        private val log = LoggerFactory.getLogger(WebAuthnConfig::class.java)
    }
}
