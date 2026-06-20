// agile-planning 모듈 통합 테스트 전용 Spring Boot 부트 클래스

package com.bts.agileplanning

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.transaction.annotation.EnableTransactionManagement

/**
 * agile-planning 모듈 통합 테스트 전용 부트 클래스.
 *
 * prod 코드에 `@SpringBootApplication` 이 없는 라이브러리 모듈이므로
 * test source 에 부트 클래스를 두어 `@SpringBootTest` 가 컨텍스트를 기동할 수 있게 한다.
 * (memory: identity-access-prod-randomport-boot-recipe / no-cross-bc-deployment-assembly — test-assembled 현 표준.)
 *
 * ## 컴포넌트 스캔
 * `com.bts.agileplanning` 패키지 하위 전체를 스캔한다.
 * - prod 빈: [com.bts.agileplanning.application.BoardApplicationService],
 *   [com.bts.agileplanning.repository.BoardRepository]
 * - test 전용 빈: [AgilePlanningTestcontainersConfig] — cross-BC 포트 stub 빈
 *
 * ## Flyway 자동 구성 제외
 * Flyway 마이그레이션은 [AgilePlanningTestcontainersConfig] 에서 직접 실행하므로
 * Spring Boot FlywayAutoConfiguration 을 비활성화한다.
 *
 * ## 트랜잭션 관리
 * [EnableTransactionManagement] 로 `@Transactional` AOP 프록시가 실제로 동작하게 한다.
 * (memory: 트랜잭션 self-invocation REQUIRES_NEW — prod 코드가 올바른 구성 요건.)
 */
@SpringBootApplication(
    scanBasePackages = ["com.bts.agileplanning"],
    exclude = [
        FlywayAutoConfiguration::class,
        DataSourceAutoConfiguration::class,
    ],
)
@EnableTransactionManagement(proxyTargetClass = true)
open class AgilePlanningTestBootApplication
