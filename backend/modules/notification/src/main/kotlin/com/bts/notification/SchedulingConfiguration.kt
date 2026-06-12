// NotificationWorker 의 @Scheduled 폴링이 필요로 하는 @EnableScheduling 설정 — 진입점과 분리하여 단일 책임 유지

package com.bts.notification

import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * notification 모듈 스케줄링 활성화 설정 클래스.
 *
 * ## 왜 필요한가
 * [com.bts.notification.worker.NotificationWorker.pollAndProcess] 의 `@Scheduled` 는
 * `@EnableScheduling` 이 활성화된 ApplicationContext 에서만 동작한다. notification 은
 * `@SpringBootApplication` 진입점이 없는 라이브러리 모듈이므로, 이 설정 클래스가 스캔되는
 * 조립 컨텍스트에서 워커 폴링이 활성화된다. 이 클래스가 없으면 워커 빈은 생성되지만 폴링이
 * 영영 일어나지 않아 알림이 0건 발송된다(issue-tracking [com.bts.issue.SchedulingConfiguration] 동형).
 *
 * ## 왜 `@Profile("!test")` 인가
 * `test` 프로파일에서는 스케줄링을 비활성화한다. notification 통합 테스트 중 하나
 * ([NotificationPolicyEndToEndIntegrationTest])는 pgmq 미탑재 컨테이너로 정책 HTTP 경로만
 * 검증하므로, 워커가 폴링하면 `pgmq.read` 가 실패한다. 반면 빠른 폴링이 필요한 워커 E2E
 * ([NotificationDeliveryEndToEndIntegrationTest])는 별도 `@EnableScheduling` 설정
 * ([NotificationDeliverySchedulingConfig])을 명시적으로 주입한다. prod 조립(비-test 프로파일)에서는
 * 이 설정이 스캔되어 워커 폴링이 활성화된다. 이 클래스는 빈을 주입하지 않으므로 비활성 시
 * fallback 빈이 필요 없다(memory: profile-scoped-bean-boot-failure 와 무관).
 *
 * ## ADR 참조
 * ADR 2026-06-12-notification-inapp-channel-delivery (결정 5 — NotificationWorker = pgmq consumer)
 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
@EnableScheduling
class SchedulingConfiguration
