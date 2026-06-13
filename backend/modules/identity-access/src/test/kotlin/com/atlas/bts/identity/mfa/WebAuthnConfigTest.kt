// WebAuthn 설정(@ConfigurationProperties 바인딩 + WebAuthnManager/ObjectConverter 빈) 격리 부팅 검증 (FR-MF-03 Task 1)

package com.atlas.bts.identity.mfa

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.webauthn4j.WebAuthnManager
import com.webauthn4j.converter.util.ObjectConverter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
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

    // ── prod 프로필 + 기본 localhost 설정 → 부팅 WARN (CONCERN-1 경량 안전망) ─────────

    /**
     * 부팅 시점 발생한 WARN 로그를 캡처해 반환한다.
     *
     * [WebAuthnConfig] 의 로거에 [ListAppender] 를 부착한 뒤 주어진 프로퍼티로 컨텍스트를 부팅하고,
     * 부팅 중 누적된 WARN 레벨 로그의 포맷 메시지 목록을 돌려준다(컨텍스트는 run 블록 종료 시 닫힘).
     */
    private fun captureBootWarnings(vararg properties: String): List<String> {
        val logger = LoggerFactory.getLogger(WebAuthnConfig::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        return try {
            contextRunner
                .withPropertyValues(*properties)
                .run { context ->
                    // 컨텍스트가 정상 부팅되어야 한다(fail-fast 금지 — WARN 안전망).
                    assertThat(context).hasNotFailed()
                }
            appender.list
                .filter { it.level == Level.WARN }
                .map { it.formattedMessage }
        } finally {
            logger.detachAppender(appender)
        }
    }

    /** prod 프로필 + 기본 localhost rp-id → 부팅 시 WARN 로그가 발생한다(부팅은 성공). */
    @Test
    fun `prod 프로필에서 rp-id가 localhost면 부팅 WARN이 발생한다`() {
        val warnings =
            captureBootWarnings(
                "spring.profiles.active=prod",
                "bts.webauthn.rp-id=localhost",
                "bts.webauthn.origin=https://bts.example.com",
            )

        assertThat(warnings)
            .anySatisfy { msg ->
                assertThat(msg).contains("WebAuthn")
                assertThat(msg).contains("localhost")
            }
    }

    /** prod 프로필 + origin 에 localhost 포함 → 부팅 시 WARN 로그가 발생한다. */
    @Test
    fun `prod 프로필에서 origin에 localhost가 포함되면 부팅 WARN이 발생한다`() {
        val warnings =
            captureBootWarnings(
                "spring.profiles.active=prod",
                "bts.webauthn.rp-id=bts.example.com",
                "bts.webauthn.origin=http://localhost:5173",
            )

        assertThat(warnings).isNotEmpty()
    }

    /** prod 프로필 + 실제 도메인 설정 → WARN 없음(정상 설정). */
    @Test
    fun `prod 프로필에서 실제 도메인이 설정되면 WARN이 없다`() {
        val warnings =
            captureBootWarnings(
                "spring.profiles.active=prod",
                "bts.webauthn.rp-id=bts.example.com",
                "bts.webauthn.origin=https://bts.example.com",
            )

        assertThat(warnings)
            .noneSatisfy { msg -> assertThat(msg).contains("WebAuthn") }
    }

    /** non-prod 프로필 + 기본 localhost 설정 → WARN 없음(dev/test 정상). */
    @Test
    fun `non-prod 프로필에서는 localhost여도 WARN이 없다`() {
        val warnings =
            captureBootWarnings(
                "spring.profiles.active=test",
                "bts.webauthn.rp-id=localhost",
                "bts.webauthn.origin=http://localhost:5173",
            )

        assertThat(warnings)
            .noneSatisfy { msg -> assertThat(msg).contains("WebAuthn") }
    }
}
