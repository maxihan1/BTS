// search-export-import 모듈 @Scheduled 워커 폴링 활성화 설정 — 비-test 프로파일 전용

package com.bts.search

import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * search-export-import 모듈 스케줄링 활성화 설정 클래스.
 *
 * ## 왜 필요한가
 * [com.bts.search.export.job.worker.ExportJobWorker.pollAndProcess] 와
 * [com.bts.search.export.job.worker.ExportJobCleanupWorker.cleanupExpired] 의 `@Scheduled` 는
 * `@EnableScheduling` 이 활성화된 ApplicationContext 에서만 동작한다.
 * search-export-import 는 `@SpringBootApplication` 진입점이 없는 라이브러리 모듈이므로,
 * 이 설정 클래스가 스캔되는 조립 컨텍스트에서 워커 폴링이 활성화된다.
 * 이 클래스가 없으면 워커 빈은 생성되지만 폴링이 영영 일어나지 않아
 * Export 작업이 PENDING 상태로 영구 고착된다
 * (notification [com.bts.notification.SchedulingConfiguration] 동형).
 *
 * ## 왜 `@Profile("!test")` 인가
 * `test` 프로파일에서는 스케줄링을 비활성화한다.
 * [com.bts.search.export.job.ExportJobEndToEndIntegrationTest] 는 Spring 컨텍스트 없이
 * 직접 인스턴스화 방식으로 동작하여 이 설정 클래스와 무관하다.
 * [com.bts.search.web.ExportJobControllerTest] 도 한정된 컨텍스트를 사용하므로 충돌 없음.
 * prod 조립(비-test 프로파일)에서는 이 설정이 스캔되어 워커 폴링이 활성화된다.
 * 이 클래스는 빈을 주입하지 않으므로 비활성 시 fallback 빈이 필요 없다
 * (memory: profile-scoped-bean-boot-failure 와 무관).
 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
@EnableScheduling
class SchedulingConfiguration
