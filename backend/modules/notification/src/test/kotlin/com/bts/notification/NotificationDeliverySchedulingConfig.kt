// STOMP E2E 통합테스트 전용 — @EnableScheduling + 빠른 poll-interval 설정

package com.bts.notification

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler

/**
 * NotificationDeliveryEndToEndIntegrationTest 전용 스케줄링 활성화 설정.
 *
 * [NotificationTestBootApplication] 이 @EnableScheduling 을 선언하지 않으므로,
 * NotificationWorker.pollAndProcess 의 @Scheduled 가 자동 실행되지 않는다.
 * 이 TestConfiguration 을 @SpringBootTest classes 에 포함해 스케줄링을 활성화한다.
 *
 * ## poll-interval
 * 프로덕션 기본값은 500ms 이지만, E2E 테스트에서는 50ms 로 설정해 대기 시간을 줄인다.
 * application.properties 방식 대신 [ThreadPoolTaskScheduler] 빈과 시스템 프로퍼티로 구성한다.
 * (memory: flaky 방지 — 타임아웃을 넉넉히, poll-interval 을 짧게.)
 */
@TestConfiguration
@EnableScheduling
class NotificationDeliverySchedulingConfig {
    /**
     * 스케줄러 스레드 풀 빈.
     *
     * 기본 단일 스레드로 NotificationWorker 의 @Scheduled 를 실행한다.
     * 빈 이름을 "taskScheduler" 로 지정해 Spring 의 기본 스케줄러로 사용되게 한다.
     */
    @Bean(name = ["taskScheduler"])
    fun taskScheduler(): ThreadPoolTaskScheduler =
        ThreadPoolTaskScheduler().apply {
            poolSize = 1
            setThreadNamePrefix("test-notif-scheduler-")
            initialize()
        }

    companion object {
        /**
         * 테스트 시작 전 poll-interval-ms 를 50ms 로 설정한다.
         *
         * Spring @SpringBootTest 컨텍스트 로드 이전에 시스템 프로퍼티를 설정해야
         * @Scheduled fixedDelayString 이 이 값을 읽는다.
         */
        init {
            System.setProperty("bts.notification.worker.poll-interval-ms", "50")
        }
    }
}
