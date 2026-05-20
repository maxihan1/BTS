// issuer-uri 환경변수 override 검증 테스트 — application.yml placeholder 형식 확인

package com.atlas.bts.identity.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.io.ClassPathResource

/**
 * application.yml의 issuer-uri가 환경변수 override placeholder 형식인지 검증.
 *
 * CONCERN-NEW-2: PoC #2의 issuer-uri 하드코딩을
 * `${BTS_KEYCLOAK_ISSUER_URI:http://localhost:8180/realms/bts}` 로 변경해야 함.
 *
 * SecurityConfig가 JwtDecoder를 요구하여 @SpringBootTest로 전체 컨텍스트 로드가 불가능하므로,
 * YamlPropertySourceLoader로 application.yml을 직접 파싱하여 placeholder 존재 여부를 검증.
 */
class IssuerUriEnvOverrideTest {
    @Test
    fun `application yml issuer-uri uses BTS_KEYCLOAK_ISSUER_URI placeholder`() {
        val loader = YamlPropertySourceLoader()
        val resource = ClassPathResource("application.yml")
        val propertySources = loader.load("application.yml", resource)

        val key = "spring.security.oauth2.resourceserver.jwt.issuer-uri"

        // EnumerablePropertySource로 프로퍼티 값 직접 추출
        val rawValue =
            propertySources
                .filterIsInstance<EnumerablePropertySource<*>>()
                .mapNotNull { it.getProperty(key) }
                .firstOrNull()

        assertThat(rawValue)
            .`as`("issuer-uri는 BTS_KEYCLOAK_ISSUER_URI 환경변수 placeholder를 포함해야 한다 (CONCERN-NEW-2)")
            .isNotNull()
            .asString()
            .contains("BTS_KEYCLOAK_ISSUER_URI")
    }
}
