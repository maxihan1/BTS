// slack-integration 모듈 통합 테스트 전용 Spring Boot 부트 클래스

package com.bts.slack

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.transaction.annotation.EnableTransactionManagement

/**
 * slack-integration 모듈 통합 테스트 전용 부트 클래스.
 *
 * prod 코드에 `@SpringBootApplication`이 없는 라이브러리 모듈이므로 test source에 부트 클래스를 두어
 * `@SpringBootTest`가 컨텍스트를 기동할 수 있게 한다.
 * (memory: no-cross-bc-deployment-assembly — test-assembled 현 표준. ADR D1.)
 *
 * ## 컴포넌트 스캔
 * `com.bts.slack` 패키지 하위 전체를 스캔한다. Task 1 시점에는 main 소스가 없어 빈 컨텍스트를 로드한다.
 *
 * ## DataSource / Flyway 자동 구성 제외
 * Task 1은 순수 스캐폴딩(영속성 미도입)이므로 [DataSourceAutoConfiguration]/[FlywayAutoConfiguration]을
 * 제외해 실 DB 없이 컨텍스트 로드를 검증한다. 후속 태스크(Testcontainers 기반 영속성 통합 테스트)는
 * 이 부트 클래스를 변경하지 않고 자체 `@TestConfiguration`으로 DataSource/Flyway를 직접 배선한다
 * (notification `NotificationTestcontainersConfig` 선례).
 *
 * ## 트랜잭션 관리
 * [EnableTransactionManagement]로 `@Transactional` AOP 프록시가 실제로 동작하게 한다.
 * (memory: 트랜잭션 self-invocation REQUIRES_NEW — prod 코드가 올바른 구성 요건.)
 */
@SpringBootApplication(
    scanBasePackages = ["com.bts.slack"],
    exclude = [
        FlywayAutoConfiguration::class,
        DataSourceAutoConfiguration::class,
    ],
)
@EnableTransactionManagement(proxyTargetClass = true)
open class SlackIntegrationTestBootApplication
