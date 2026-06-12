// SchedulingConfiguration 이 비-test 프로파일에서만 @EnableScheduling 을 활성화하는지 검증

package com.bts.notification

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor

/**
 * [SchedulingConfiguration] 프로파일 게이팅 검증 (A1 회귀).
 *
 * NotificationWorker 의 `@Scheduled` 폴링이 조립 컨텍스트에서 실제로 동작하려면
 * `@EnableScheduling` 후처리기(ScheduledAnnotationBeanPostProcessor)가 등록되어야 한다.
 * `@Profile("!test")` 라 비-test 프로파일에서만 활성화된다(pgmq 미탑재 정책 통합 테스트 보호).
 *
 * 경량 [ApplicationContextRunner] 로 검증한다(풀 SpringBootTest 부트 불필요).
 */
class SchedulingConfigurationTest {
    private val contextRunner =
        ApplicationContextRunner().withUserConfiguration(SchedulingConfiguration::class.java)

    @Test
    fun `비-test 프로파일에서 SchedulingConfiguration 이 @EnableScheduling 을 활성화한다`() {
        contextRunner.run { context ->
            assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor::class.java)
        }
    }

    @Test
    fun `test 프로파일에서는 SchedulingConfiguration 이 비활성이다`() {
        contextRunner
            .withInitializer { it.environment.setActiveProfiles("test") }
            .run { context ->
                assertThat(context).doesNotHaveBean(SchedulingConfiguration::class.java)
                assertThat(context).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor::class.java)
            }
    }
}
