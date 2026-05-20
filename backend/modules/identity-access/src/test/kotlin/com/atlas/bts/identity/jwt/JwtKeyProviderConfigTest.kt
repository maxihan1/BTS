// JwtKeyProviderConfig @Profile 분기 단위 테스트 — prod/!prod 분리 + EC-30 fail-fast 검증

package com.atlas.bts.identity.jwt

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

/**
 * JwtKeyProviderConfig @Profile 분기 통합 테스트.
 *
 * - @Profile("!prod") → DevMemoryKeyProvider Bean 이 등록됨
 * - @Profile("prod") 활성 시 bts.auth.jwt.private-key-pem-path 누락 →
 *   PlaceholderResolutionException 으로 ApplicationContext 기동 실패 (EC-30)
 *
 * prod 분기 fail-fast (EC-30) 는 별도 중첩 클래스로 검증한다.
 */
class JwtKeyProviderConfigTest {
    /**
     * !prod 프로필 (기본 dev) — DevMemoryKeyProvider Bean 이 주입되어야 한다.
     */
    @SpringBootTest(
        classes = [JwtKeyProviderConfig::class],
        properties = [
            "spring.main.allow-bean-definition-overriding=true",
            // oauth2 resource server 자동 구성 제외 (JwtKeyProviderConfig만 테스트)
        ],
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
    )
    @ActiveProfiles("dev")
    class DevProfileTest {
        @Autowired
        private lateinit var jwtKeyProvider: JwtKeyProvider

        @Test
        fun `dev 프로필에서 JwtKeyProvider Bean 은 DevMemoryKeyProvider 이다`() {
            assertThat(jwtKeyProvider).isInstanceOf(DevMemoryKeyProvider::class.java)
        }

        @Test
        fun `dev 프로필 JwtKeyProvider 는 RSA 키를 정상 제공한다`() {
            assertThat(jwtKeyProvider.privateKey).isNotNull
            assertThat(jwtKeyProvider.publicKey).isNotNull
            assertThat(jwtKeyProvider.kid).startsWith("dev-")
        }
    }

    /**
     * prod 프로필 + PEM 경로 env var 누락 → ApplicationContext 기동 실패 (EC-30).
     *
     * Spring @Value placeholder 가 해소되지 않으면
     * PlaceholderResolutionException / BeanCreationException 을 던지며 기동 실패한다.
     * 이 동작이 EC-30 "silent dev fallback 방지" 보장의 핵심이다.
     */
    @Test
    fun `prod 프로필에서 PEM 경로 env var 누락 시 ApplicationContext 기동 실패한다 (EC-30)`() {
        val ex = runCatching {
            org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(JwtKeyProviderConfig::class.java)
                .withPropertyValues("spring.profiles.active=prod")
                // bts.auth.jwt.private-key-pem-path 미설정 (고의)
                .run { ctx ->
                    // Bean 접근 시 또는 컨텍스트 구성 시 예외 발생 기대
                    ctx.getBean(JwtKeyProvider::class.java)
                }
        }

        // ApplicationContextRunner 내부에서 예외가 발생했거나,
        // run 블록 내에서 getBean 시 예외가 발생해야 한다.
        // 어느 경로든 예외가 발생해야 EC-30 이 보장된다.
        assertThat(ex.isFailure).isTrue()
    }
}
