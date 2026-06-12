// WebAuthn 설정(@ConfigurationProperties 바인딩 + WebAuthnManager/ObjectConverter 빈) 격리 부팅 검증 (FR-MF-03 Task 1)

package com.atlas.bts.identity.mfa

import com.webauthn4j.WebAuthnManager
import com.webauthn4j.converter.util.ObjectConverter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * [WebAuthnConfig] / [WebAuthnProperties] 격리 부팅 검증 (FR-MF-03 Task 1).
 *
 * ## 검증 범위
 * - `bts.webauthn.{rp-id,rp-name,origin}` 프로퍼티가 [WebAuthnProperties] 로 바인딩된다(relaxed binding).
 * - [WebAuthnManager] 빈이 컨텍스트에 등록된다(후속 task 의 등록/인증 검증 의존).
 * - [ObjectConverter] 빈이 컨텍스트에 등록된다(webauthn4j JSON/CBOR 직렬화 의존).
 *
 * ## 부팅 방식 — ApplicationContextRunner
 * 전체 `@SpringBootTest`(Testcontainers/PostgreSQL/PEM) 대신 [ApplicationContextRunner] 로
 * [WebAuthnConfig] 만 격리 부팅한다. 프로퍼티 바인딩과 빈 등록만 검증하므로 인프라 의존이 없는
 * 가벼운 슬라이스로 충분하다(부팅 안전성·속도).
 */
class WebAuthnConfigTest {
    private val contextRunner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration::class.java))
            .withUserConfiguration(WebAuthnConfig::class.java)

    /** `bts.webauthn.*` 프로퍼티가 [WebAuthnProperties] data class 로 바인딩된다. */
    @Test
    fun `bts_webauthn 프로퍼티가 WebAuthnProperties로 바인딩된다`() {
        contextRunner
            .withPropertyValues(
                "bts.webauthn.rp-id=bts.example.com",
                "bts.webauthn.rp-name=BTS Workspace",
                "bts.webauthn.origin=https://bts.example.com",
            ).run { context ->
                val props = context.getBean(WebAuthnProperties::class.java)
                assertThat(props.rpId).isEqualTo("bts.example.com")
                assertThat(props.rpName).isEqualTo("BTS Workspace")
                assertThat(props.origin).isEqualTo("https://bts.example.com")
            }
    }

    /** [WebAuthnManager] 빈이 컨텍스트에 등록된다(non-strict — attestation none). */
    @Test
    fun `WebAuthnManager 빈이 등록된다`() {
        contextRunner.run { context ->
            assertThat(context).hasSingleBean(WebAuthnManager::class.java)
        }
    }

    /** [ObjectConverter] 빈이 컨텍스트에 등록된다(webauthn4j 직렬화용). */
    @Test
    fun `ObjectConverter 빈이 등록된다`() {
        contextRunner.run { context ->
            assertThat(context).hasSingleBean(ObjectConverter::class.java)
        }
    }
}
