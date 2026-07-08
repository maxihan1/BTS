// slack-integration 모듈 test-boot Spring 컨텍스트 로드 검증 (FR-SL-01 Task 1)

package com.bts.slack

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import

/**
 * slack-integration 모듈 골격이 정상 컴파일·부팅되는지 검증하는 최소 통합 테스트.
 *
 * prod 코드에 `@SpringBootApplication`이 없는 라이브러리 모듈이므로 [SlackIntegrationTestBootApplication]
 * (test source)을 기동 클래스로 사용한다.
 * (memory: no-cross-bc-deployment-assembly — test-assembled 현 표준.)
 *
 * ## Task 7/8 의존성 배선 ([SlackTestcontainersConfig] @Import)
 * Task 7 의 `@Repository JdbcSlackInstallRepository` 는 [org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate]
 * 을, Task 8 의 `SlackInstallService(@Service)` 는 cross-BC `SystemPermissionResolver` 를 요구한다.
 * test-boot 앱은 DataSource/Flyway autoconfig 을 제외하므로, 이 두 빈이 없으면 컴포넌트 스캔만으로
 * 컨텍스트 로드가 깨진다. [SlackTestcontainersConfig] 가 DataSource/JdbcTemplate/트랜잭션 매니저 +
 * fail-closed 권한 stub 을 제공해 로드를 복구한다(Slack 아웃바운드 fake 는 통합 테스트 전용이라 불필요 —
 * 스캔된 `DefaultSlackOAuthClient` 가 부팅 안전하게 채워진다).
 */
@SpringBootTest(
    classes = [SlackIntegrationTestBootApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
)
@Import(SlackTestcontainersConfig::class)
class SlackContextLoadTest {
    @Autowired
    private lateinit var applicationContext: ApplicationContext

    @Test
    fun `slack-integration test-boot 컨텍스트가 정상 로드된다`() {
        assertThat(applicationContext.getBean(SlackIntegrationTestBootApplication::class.java)).isNotNull()
    }
}
