// slack-integration 모듈 test-boot Spring 컨텍스트 로드 검증 (FR-SL-01 Task 1)

package com.bts.slack

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext

/**
 * slack-integration 모듈 골격이 정상 컴파일·부팅되는지 검증하는 최소 통합 테스트.
 *
 * prod 코드에 `@SpringBootApplication`이 없는 라이브러리 모듈이므로 [SlackIntegrationTestBootApplication]
 * (test source)을 기동 클래스로 사용한다.
 * (memory: no-cross-bc-deployment-assembly — test-assembled 현 표준.)
 */
@SpringBootTest(
    classes = [SlackIntegrationTestBootApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
)
class SlackContextLoadTest {
    @Autowired
    private lateinit var applicationContext: ApplicationContext

    @Test
    fun `slack-integration test-boot 컨텍스트가 정상 로드된다`() {
        assertThat(applicationContext.getBean(SlackIntegrationTestBootApplication::class.java)).isNotNull()
    }
}
