// automation 워커(@Scheduled)의 폴링을 활성화하는 @EnableScheduling 설정 — 배포조립 시 opt-in (FR-AT-01 Task 11)

package com.bts.automation

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * automation 모듈 스케줄링 활성화 설정 (FR-AT-01 Task 11).
 *
 * ## 왜 필요한가
 * [com.bts.automation.worker.AutomationEventWorker] · [com.bts.automation.worker.AutomationScheduleWorker]
 * 의 `@Scheduled` 폴링은 `@EnableScheduling` 이 활성화된 ApplicationContext 에서만 동작한다. automation
 * 은 `@SpringBootApplication` 진입점이 없는 라이브러리 모듈(test-assembled)이므로, 이 설정이 스캔되는
 * 조립 컨텍스트에서만 폴링이 켜진다(notification [com.bts.notification.SchedulingConfiguration] 동형 역할).
 *
 * ## 왜 `@ConditionalOnProperty`(기본 OFF)인가
 * automation 은 아직 prod 배포 조립이 없다(memory: no-cross-bc-deployment-assembly — test-assembled 현 표준).
 * 통합 테스트는 워커 폴링 메서드를 **직접 호출**해 검증하므로(plan-eng-review E5), 테스트 컨텍스트에서
 * 스케줄러가 자동 폴링하면 셋업과 경쟁해 flaky 를 유발한다. 따라서 `bts.automation.scheduling.enabled=true`
 * 로 **명시 opt-in** 할 때만 활성화한다. 기본값(미설정)에서는 조건 미충족으로 이 설정이 등록되지 않아
 * 테스트·현재 상태 어디서도 스케줄러가 켜지지 않는다. 실제 폴링 결선은 배포 조립 시점(후속 ADR)에
 * 이 프로퍼티를 켜서 활성화한다.
 *
 * ## ADR 참조
 * ADR 2026-07-10-fr-at-01-automation-triggers (D4 — 트리거 감지→q_automation_execution enqueue).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["bts.automation.scheduling.enabled"], havingValue = "true")
@EnableScheduling
class AutomationSchedulingConfig
