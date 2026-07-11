// SlackAsyncConfig 단위 테스트 — 경계 executor 빈 등록·설정값 검증 (FR-SL-03 Task 9)

package com.bts.slack.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

/**
 * [SlackAsyncConfig] 검증.
 *
 * `ApplicationContextRunner`(Spring Test 유틸리티 — 전체 서버 기동 없이 ApplicationContext만 띄워
 * Bean 등록/실패를 검증하는 경량 도구)로 `@Value` 기본값·오버라이드가 모두 정상 반영되는지 확인한다
 * ([SlackEncryptionConfigTest]와 동일 관례).
 */
class SlackAsyncConfigTest {
    private val runner =
        ApplicationContextRunner()
            .withUserConfiguration(SlackAsyncConfig::class.java)

    @Test
    fun `프로퍼티 미설정이어도 slackUnfurlExecutor 빈이 ThreadPoolTaskExecutor 로 등록된다(부팅 안전)`() {
        runner.run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx).hasBean(SlackAsyncConfig.SLACK_UNFURL_EXECUTOR_BEAN_NAME)
            val executor =
                ctx.getBean(SlackAsyncConfig.SLACK_UNFURL_EXECUTOR_BEAN_NAME, ThreadPoolTaskExecutor::class.java)
            assertThat(executor).isNotNull()
        }
    }

    @Test
    fun `core, max, queue 경계값이 명시적으로 설정된다(무한 스레드 방지)`() {
        runner.run { ctx ->
            val executor =
                ctx.getBean(SlackAsyncConfig.SLACK_UNFURL_EXECUTOR_BEAN_NAME, ThreadPoolTaskExecutor::class.java)

            assertThat(executor.corePoolSize).isPositive()
            assertThat(executor.maxPoolSize).isPositive().isGreaterThanOrEqualTo(executor.corePoolSize)
            assertThat(executor.queueCapacity).isPositive()
        }
    }

    @Test
    fun `bts_slack_unfurl-executor 프로퍼티로 경계값을 오버라이드할 수 있다`() {
        runner
            .withPropertyValues(
                "bts.slack.unfurl-executor.core-pool-size=5",
                "bts.slack.unfurl-executor.max-pool-size=10",
                "bts.slack.unfurl-executor.queue-capacity=50",
            ).run { ctx ->
                val executor =
                    ctx.getBean(SlackAsyncConfig.SLACK_UNFURL_EXECUTOR_BEAN_NAME, ThreadPoolTaskExecutor::class.java)

                assertThat(executor.corePoolSize).isEqualTo(5)
                assertThat(executor.maxPoolSize).isEqualTo(10)
                assertThat(executor.queueCapacity).isEqualTo(50)
            }
    }

    @Test
    fun `프로퍼티 미설정이어도 slackCommandExecutor 빈이 ThreadPoolTaskExecutor 로 등록된다(부팅 안전, FR-SL-04 Task 7)`() {
        runner.run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx).hasBean(SlackAsyncConfig.SLACK_COMMAND_EXECUTOR_BEAN_NAME)
            val executor =
                ctx.getBean(SlackAsyncConfig.SLACK_COMMAND_EXECUTOR_BEAN_NAME, ThreadPoolTaskExecutor::class.java)
            assertThat(executor).isNotNull()
        }
    }

    @Test
    fun `slackCommandExecutor 는 slackUnfurlExecutor 와 별개 빈이며 core, max, queue 경계값을 갖는다(무한 스레드 방지)`() {
        runner.run { ctx ->
            val executor =
                ctx.getBean(SlackAsyncConfig.SLACK_COMMAND_EXECUTOR_BEAN_NAME, ThreadPoolTaskExecutor::class.java)
            val unfurlExecutor =
                ctx.getBean(SlackAsyncConfig.SLACK_UNFURL_EXECUTOR_BEAN_NAME, ThreadPoolTaskExecutor::class.java)

            assertThat(executor.corePoolSize).isPositive()
            assertThat(executor.maxPoolSize).isPositive().isGreaterThanOrEqualTo(executor.corePoolSize)
            assertThat(executor.queueCapacity).isPositive()
            assertThat(executor).isNotSameAs(unfurlExecutor)
        }
    }

    @Test
    fun `bts_slack_command-executor 프로퍼티로 slackCommandExecutor 경계값을 오버라이드할 수 있다`() {
        runner
            .withPropertyValues(
                "bts.slack.command-executor.core-pool-size=3",
                "bts.slack.command-executor.max-pool-size=6",
                "bts.slack.command-executor.queue-capacity=30",
            ).run { ctx ->
                val executor =
                    ctx.getBean(SlackAsyncConfig.SLACK_COMMAND_EXECUTOR_BEAN_NAME, ThreadPoolTaskExecutor::class.java)

                assertThat(executor.corePoolSize).isEqualTo(3)
                assertThat(executor.maxPoolSize).isEqualTo(6)
                assertThat(executor.queueCapacity).isEqualTo(30)
            }
    }
}
