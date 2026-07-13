// AlwaysAllowSlackChannelMappingPermissionResolver 단위 테스트 — 항상 허용 + @Profile("!prod") 격리 + Bean 프로파일 배타 검증

package com.bts.slack.config

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.bts.shared.permission.SlackChannelMappingPermissionResolver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Profile
import java.util.UUID

/**
 * [AlwaysAllowSlackChannelMappingPermissionResolver] 단위 테스트.
 *
 * 검증 항목.
 * 1. 임의 actorId + 임의 projectKey 에 대해 hasManageChannelMapping 이 `true` 를 반환한다(dev/test 편의).
 * 2. 클래스가 `@Profile("!prod")` 를 보유한다(리플렉션) — prod 에서는 절대 로드되지 않는다.
 * 3. 호출 시 WARN 레벨 bypass 로그가 발생하며 stub + FR-SL-06 을 참조한다(비-prod 우회 관측).
 * 4. test profile 에서 Bean 이 [SlackChannelMappingPermissionResolver] 로 등록된다.
 * 5. prod profile 에서 Bean 이 등록되지 않는다 — allow-all stub 의 운영 노출 차단(보안 위협 가드).
 */
class AlwaysAllowSlackChannelMappingPermissionResolverTest {
    private val actor = UUID.fromString("33333333-3333-3333-3333-333333333333")

    private val contextRunner =
        ApplicationContextRunner()
            .withUserConfiguration(AlwaysAllowSlackChannelMappingPermissionResolver::class.java)

    // ── 1. 항상 허용 ──────────────────────────────────────────────────────────

    @Test
    fun `hasManageChannelMapping 은 임의 actor 와 임의 projectKey 에 대해 true 를 반환한다`() {
        val resolver = AlwaysAllowSlackChannelMappingPermissionResolver()

        assertThat(resolver.hasManageChannelMapping(actor, "ANY")).isTrue()
        assertThat(resolver.hasManageChannelMapping(UUID.randomUUID(), "ATLAS")).isTrue()
    }

    // ── 2. @Profile("!prod") 어노테이션 보유 (리플렉션) ───────────────────────

    @Test
    fun `클래스는 @Profile("!prod") 를 보유한다`() {
        val profile =
            AlwaysAllowSlackChannelMappingPermissionResolver::class.java
                .getAnnotation(Profile::class.java)

        assertThat(profile).isNotNull()
        assertThat(profile.value).containsExactly("!prod")
    }

    // ── 3. WARN bypass 로그 발생 + stub/FR-SL-06 참조 ─────────────────────────

    @Test
    fun `hasManageChannelMapping 호출 시 stub 과 FR-SL-06 을 참조하는 WARN 로그가 발생한다`() {
        val resolver = AlwaysAllowSlackChannelMappingPermissionResolver()

        val logger =
            LoggerFactory.getLogger(AlwaysAllowSlackChannelMappingPermissionResolver::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)

        try {
            resolver.hasManageChannelMapping(actor, "ATLAS")

            val warnLogs = appender.list.filter { it.level == Level.WARN }
            assertThat(warnLogs).isNotEmpty()

            val formattedMessage = warnLogs.first().formattedMessage
            assertThat(formattedMessage).contains("stub")
            assertThat(formattedMessage).contains("FR-SL-06")
            // 이메일 등 PII 가 포함되지 않아야 한다 (actorId=UUID, projectKey 는 PII 아님)
            assertThat(formattedMessage)
                .doesNotContainPattern("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}")
        } finally {
            logger.detachAppender(appender)
        }
    }

    // ── 4. test profile: Bean 등록 확인 ─────────────────────────────────────

    @Test
    fun `test profile 에서 stub 은 SlackChannelMappingPermissionResolver 로 등록된다`() {
        contextRunner
            .withPropertyValues("spring.profiles.active=test")
            .run { ctx ->
                assertThat(ctx).hasSingleBean(SlackChannelMappingPermissionResolver::class.java)
                assertThat(ctx.getBean(SlackChannelMappingPermissionResolver::class.java))
                    .isInstanceOf(AlwaysAllowSlackChannelMappingPermissionResolver::class.java)
            }
    }

    // ── 5. prod profile: Bean 미등록 (운영 allow-all 차단) ────────────────────

    @Test
    fun `prod profile 에서 stub 은 Bean 으로 등록되지 않는다`() {
        contextRunner
            .withPropertyValues("spring.profiles.active=prod")
            .run { ctx ->
                assertThat(ctx).doesNotHaveBean(AlwaysAllowSlackChannelMappingPermissionResolver::class.java)
                assertThat(ctx).doesNotHaveBean(SlackChannelMappingPermissionResolver::class.java)
            }
    }
}
