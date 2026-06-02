// issue-tracking BC 스프링 부트 진입점 — FR-IS-05 비동기 일괄작업 워커(@Scheduled) 토대

package com.bts.issue

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * IssueTracking 모듈 부팅 진입점.
 *
 * ## 왜 이 클래스가 필요한가
 * FR-IS-05 일괄작업(Bulk Edit) 기능의 비동기 워커는 `@Scheduled` 로 주기적으로 작업 큐를 폴링한다.
 * `@Scheduled` 가 동작하려면 `@EnableScheduling` 이 활성화된 ApplicationContext 가 필요하다.
 * 이 진입점은 그 토대를 제공한다.
 *
 * `@EnableScheduling` 은 [SchedulingConfiguration] 으로 격리하여 진입점 클래스를 단순하게 유지한다.
 *
 * ## ADR 참조
 * ADR 2026-06-02-bulk-operation-async-architecture
 */
@SpringBootApplication
class IssueTrackingApplication

@Suppress("SpreadOperator")
fun main(args: Array<String>) {
    runApplication<IssueTrackingApplication>(*args)
}
