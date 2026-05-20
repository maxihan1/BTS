// JwtKeyProviderConfig @Profile 분기 단위 테스트 — prod/!prod 분리 + EC-30 fail-fast 검증

package com.atlas.bts.identity.jwt

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * JwtKeyProviderConfig @Profile 분기 검증.
 *
 * ApplicationContextRunner (Spring Test 유틸리티 — 전체 서버 기동 없이 ApplicationContext만 띄워
 * Bean 등록/실패를 검증하는 경량 도구)를 사용한다.
 *
 * ## 검증 항목
 * - dev 프로필 → DevMemoryKeyProvider Bean 등록
 * - prod 프로필 + bts.auth.jwt.private-key-pem-path 미설정 → ApplicationContext 기동 실패 (EC-30)
 * - prod 프로필 + 존재하지 않는 PEM 경로 → load() IllegalStateException → 기동 실패 (EC-19)
 */
class JwtKeyProviderConfigTest {
    private val runner =
        ApplicationContextRunner()
            .withUserConfiguration(JwtKeyProviderConfig::class.java)

    @Test
    fun `dev 프로필에서 JwtKeyProvider Bean 은 DevMemoryKeyProvider 이다`() {
        runner
            .withPropertyValues("spring.profiles.active=dev")
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                val bean = ctx.getBean(JwtKeyProvider::class.java)
                assertThat(bean).isInstanceOf(DevMemoryKeyProvider::class.java)
            }
    }

    @Test
    fun `dev 프로필 JwtKeyProvider 는 RSA 키와 kid 를 정상 제공한다`() {
        runner
            .withPropertyValues("spring.profiles.active=dev")
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                val bean = ctx.getBean(JwtKeyProvider::class.java)
                assertThat(bean.privateKey).isNotNull
                assertThat(bean.publicKey).isNotNull
                assertThat(bean.kid).startsWith("dev-")
            }
    }

    @Test
    fun `prod 프로필에서 PEM 경로 env var 누락 시 ApplicationContext 기동 실패한다 (EC-30)`() {
        // bts.auth.jwt.private-key-pem-path 미설정 → @Value placeholder 해소 실패 → 기동 실패
        runner
            .withPropertyValues("spring.profiles.active=prod")
            .run { ctx ->
                assertThat(ctx).hasFailed()
            }
    }

    @Test
    fun `prod 프로필에서 존재하지 않는 PEM 경로는 기동 실패를 유발하고 경로가 오류에 포함된다 (EC-19)`() {
        runner
            .withPropertyValues(
                "spring.profiles.active=prod",
                "bts.auth.jwt.private-key-pem-path=/non-existent/key.pem",
            )
            .run { ctx ->
                assertThat(ctx).hasFailed()
                val causeMessage =
                    generateSequence(ctx.startupFailure) { it.cause }
                        .mapNotNull { it.message }
                        .firstOrNull { it.contains("/non-existent/key.pem") }
                assertThat(causeMessage)
                    .`as`("오류 메시지에 PEM 경로가 포함되어야 한다 (EC-19)")
                    .isNotNull()
            }
    }
}
