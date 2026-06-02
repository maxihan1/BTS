// FR-IS-05 비동기 워커가 필요로 하는 @EnableScheduling 설정 — 진입점과 분리하여 단일 책임 유지

package com.bts.issue

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * 스케줄링 활성화 설정 클래스.
 *
 * ## 왜 진입점과 분리하는가
 * `@SpringBootApplication` 에 `@EnableScheduling` 을 직접 붙이면 진입점 클래스가 인프라 관심사를
 * 함께 담게 된다. 별도 `@Configuration` 으로 격리하면 테스트에서 이 설정만 제외하거나 교체하기 쉽다.
 *
 * ## ADR 참조
 * ADR 2026-06-02-bulk-operation-async-architecture
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SchedulingConfiguration
