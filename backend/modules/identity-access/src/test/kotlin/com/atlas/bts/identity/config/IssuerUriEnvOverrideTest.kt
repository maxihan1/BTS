// application.yml jwk-set-uri 환경변수 override 검증 테스트 — FR-09-17 issuer-uri Keycloak 제거 확인

package com.atlas.bts.identity.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.io.ClassPathResource

/**
 * application.yml 의 oauth2ResourceServer.jwt 설정 검증 (FR-09-17).
 *
 * ## 검증 항목
 * - (a) issuer-uri 가 완전히 제거됐는지 — Keycloak hardcoded URI 잔존 여부
 * - (b) jwk-set-uri 가 bts.auth.issuer-uri placeholder 형식인지
 *       → `${bts.auth.issuer-uri:http://localhost:8080}/.well-known/jwks.json`
 *
 * ## 왜 @SpringBootTest 대신 YamlPropertySourceLoader?
 * SecurityConfig 가 JwtDecoder Bean 을 요구해 전체 컨텍스트 기동이 무거우므로
 * YAML 파일만 직접 파싱해 설정 값을 단위 검증한다.
 * 이 방식은 환경변수 resolve 전 raw placeholder 문자열을 읽는다.
 *
 * ## RED → GREEN 순서
 * RED. issuer-uri 가 아직 application.yml 에 존재하므로 (b) 검증이 실패한다.
 * GREEN. application.yml 에서 issuer-uri 제거 + jwk-set-uri 추가 후 통과.
 */
class IssuerUriEnvOverrideTest {
    private fun loadApplicationYml(): List<EnumerablePropertySource<*>> {
        val loader = YamlPropertySourceLoader()
        val resource = ClassPathResource("application.yml")
        return loader.load("application.yml", resource)
            .filterIsInstance<EnumerablePropertySource<*>>()
    }

    @Test
    fun `application yml 에 spring security oauth2 resourceserver jwt issuer-uri 가 존재하지 않는다 (FR-09-17)`() {
        val sources = loadApplicationYml()
        val issuerUriKey = "spring.security.oauth2.resourceserver.jwt.issuer-uri"

        val value = sources.mapNotNull { it.getProperty(issuerUriKey) }.firstOrNull()

        assertThat(value)
            .`as`("FR-09-17: issuer-uri (Keycloak hardcoded) 가 application.yml 에 남아있다 — 제거 필요")
            .isNull()
    }

    @Test
    fun `application yml 의 jwk-set-uri 가 bts auth issuer-uri placeholder 형식이다 (FR-09-17)`() {
        val sources = loadApplicationYml()
        val jwkSetUriKey = "spring.security.oauth2.resourceserver.jwt.jwk-set-uri"

        val rawValue = sources.mapNotNull { it.getProperty(jwkSetUriKey) }.firstOrNull()

        assertThat(rawValue)
            .`as`("jwk-set-uri 가 application.yml 에 없다 — jwk-set-uri 추가 필요")
            .isNotNull()

        assertThat(rawValue.toString())
            .`as`("jwk-set-uri 가 bts.auth.issuer-uri placeholder 를 포함해야 한다")
            .contains("bts.auth.issuer-uri")

        assertThat(rawValue.toString())
            .`as`("jwk-set-uri 가 /.well-known/jwks.json 경로를 포함해야 한다 (Task 23 JwksController)")
            .contains("/.well-known/jwks.json")
    }
}
