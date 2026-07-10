// SlackDeliveryWorker 의 @Scheduled 폴링을 위한 @EnableScheduling 설정 — 진입점과 분리 (FR-SL-02 Task 7)

package com.bts.slack.worker

import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * slack-integration 모듈 스케줄링 활성화 설정 (FR-SL-02 Task 7).
 *
 * ## 왜 필요한가
 * [SlackDeliveryWorker.pollAndProcess] 의 `@Scheduled` 는 `@EnableScheduling` 이 활성화된
 * ApplicationContext 에서만 동작한다. slack-integration 은 `@SpringBootApplication` 진입점이 없는
 * 라이브러리 모듈이므로, 이 설정이 스캔되는 조립 컨텍스트에서만 폴링이 활성화된다. 이 클래스가 없으면
 * 워커 빈은 생성되지만 폴링이 영영 일어나지 않아 알림이 0건 발송된다
 * (notification [com.bts.notification.SchedulingConfiguration] 동형).
 *
 * ## 왜 `@Profile("!test")` 인가
 * `test` 프로파일에서는 스케줄링을 비활성화한다. slack 슬라이스/컨텍스트 로드 테스트는 pgmq 폴링을
 * 필요로 하지 않으며, 워커 통합 테스트([SlackDeliveryWorkerIntegrationTest])는 스케줄러 없이 `pollAndProcess`
 * 를 직접 호출해 결정적으로 검증한다. prod 조립(비-test 프로파일)에서는 이 설정이 스캔되어 폴링이 켜진다.
 * 이 클래스는 빈을 주입하지 않으므로 비활성 시 fallback 빈이 필요 없다.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
@EnableScheduling
class SlackSchedulingConfiguration
