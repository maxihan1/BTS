// SlackAsyncConfig 단위 테스트 — 경계 executor 빈 등록·설정값 검증 (FR-SL-03 Task 9)

package com.bts.slack.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

class SlackAsyncConfigTest {
    @Test
    fun `slackUnfurlExecutor 빈이 ThreadPoolTaskExecutor 로 등록된다`() {
        AnnotationConfigApplicationContext(SlackAsyncConfig::class.java).use { context ->
            val executor =
                context.getBean(
                    SlackAsyncConfig.SLACK_UNFURL_EXECUTOR_BEAN_NAME,
                    ThreadPoolTaskExecutor::class.java,
                )

            assertThat(executor).isNotNull()
        }
    }

    @Test
    fun `core, max, queue 경계값이 명시적으로 설정된다(무한 스레드 방지)`() {
        AnnotationConfigApplicationContext(SlackAsyncConfig::class.java).use { context ->
            val executor =
                context.getBean(
                    SlackAsyncConfig.SLACK_UNFURL_EXECUTOR_BEAN_NAME,
                    ThreadPoolTaskExecutor::class.java,
                )

            assertThat(executor.corePoolSize).isPositive()
            assertThat(executor.maxPoolSize).isPositive().isGreaterThanOrEqualTo(executor.corePoolSize)
            assertThat(executor.queueCapacity).isPositive()
        }
    }
}
