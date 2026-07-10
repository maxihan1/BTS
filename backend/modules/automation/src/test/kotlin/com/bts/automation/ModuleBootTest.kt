// automation 모듈 test-boot Spring 컨텍스트 로드 검증 (FR-AT-01 Task 1)

package com.bts.automation

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.EnableTransactionManagement

/**
 * automation 모듈 통합 테스트 전용 부트 클래스.
 *
 * prod 코드에 `@SpringBootApplication`이 없는 라이브러리 모듈이므로 test source 에 부트 클래스를
 * 두어 `@SpringBootTest`가 컨텍스트를 기동할 수 있게 한다.
 * (memory: no-cross-bc-deployment-assembly — test-assembled 현 표준.)
 *
 * ## 컴포넌트 스캔
 * `com.bts.automation` 패키지 하위 전체를 스캔한다. Task 1 시점에는 main 소스가 스켈레톤뿐이라
 * 빈 컨텍스트를 로드한다.
 *
 * ## DataSource / Flyway 자동 구성 제외
 * [DataSourceAutoConfiguration]/[FlywayAutoConfiguration]을 제외하고, [AutomationTestcontainersBase]
 * 가 Testcontainers 기반 DataSource/JdbcTemplate 빈을 직접 제공한다(plan-eng-review E6 — Task 4의
 * 첫 `@Repository` 도입 시 `NoSuchBeanDefinitionException` 회귀를 예방하기 위해 Task 1부터 배선).
 *
 * ## 트랜잭션 관리
 * [EnableTransactionManagement]로 `@Transactional` AOP 프록시가 실제로 동작하게 한다.
 * (memory: 트랜잭션 self-invocation REQUIRES_NEW — prod 코드가 올바른 구성 요건.)
 */
@SpringBootApplication(
    scanBasePackages = ["com.bts.automation"],
    exclude = [
        FlywayAutoConfiguration::class,
        DataSourceAutoConfiguration::class,
    ],
)
@EnableTransactionManagement(proxyTargetClass = true)
open class AutomationTestBootApplication

/**
 * automation 모듈 골격이 정상 컴파일·부팅되는지, Testcontainers 기반 DataSource/JdbcTemplate 배선이
 * 처음부터 유효한지 검증하는 최소 통합 테스트 (FR-AT-01 Task 1).
 */
@SpringBootTest(
    classes = [AutomationTestBootApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
)
@Import(AutomationTestcontainersBase::class)
class ModuleBootTest {
    @Autowired
    private lateinit var applicationContext: ApplicationContext

    @Test
    fun `automation test-boot 컨텍스트가 정상 로드된다`() {
        assertThat(applicationContext.getBean(AutomationTestBootApplication::class.java)).isNotNull()
    }

    @Test
    fun `Testcontainers DataSource 기반 JdbcTemplate 빈이 처음부터 배선된다`() {
        assertThat(applicationContext.getBean(JdbcTemplate::class.java)).isNotNull()
    }
}
