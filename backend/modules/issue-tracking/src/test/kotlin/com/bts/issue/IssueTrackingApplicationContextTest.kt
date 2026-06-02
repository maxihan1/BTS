// FR-IS-05 IssueTrackingApplication 스프링 컨텍스트 로드 + @EnableScheduling 검증 테스트

package com.bts.issue

import com.bts.shared.user.UserLookupPort
import com.bts.shared.workflow.WorkflowKeyResolver
import com.bts.shared.workflow.WorkflowTransitionPort
import com.bts.workflow.scheme.application.port.IssueTypeUsagePort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.ApplicationContext
import org.springframework.scheduling.TaskScheduler
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * IssueTracking 모듈 부팅 컨텍스트 로드 테스트.
 *
 * ## 목적
 * - [IssueTrackingApplication] `@SpringBootApplication` 컨텍스트가 정상 로드되는지 검증.
 * - `@EnableScheduling` 이 활성화되어 [TaskScheduler] 빈이 등록됨을 검증.
 *
 * ## 왜 Testcontainers 가 필요한가
 * issue-tracking 모듈은 jOOQ/Flyway/DataSource 빈이 전체 컨텍스트 로드에 포함되므로
 * 실제 PostgreSQL 인스턴스 없이는 ApplicationContext 기동 자체가 실패한다.
 * [com.bts.issue.repository.IssueTestcontainersBase] 와 동일한 quay.io/tembo/pg16-pgmq:latest 이미지로
 * 격리된 테스트 컨테이너를 기동한다.
 *
 * ## 왜 MockBean 이 네 개 필요한가
 * [com.bts.issue.application.IssueApplicationService] 가 [WorkflowTransitionPort], [WorkflowKeyResolver],
 * [UserLookupPort] 를 주입받고, [com.bts.issue.type.application.IssueTypeApplicationService] 가
 * [IssueTypeUsagePort] 를 주입받는다. 이 빈들은 모두 issue-tracking BC 밖
 * (project-workflow BC / identity-access BC) 에서 구현 빈이 제공되는 outbound port 이므로
 * issue-tracking 단독 부팅 시에는 존재하지 않는다. MockBean 으로 자리채우기(stub)를 제공한다.
 *
 * ## ADR 참조
 * ADR 2026-06-02-bulk-operation-async-architecture — FR-IS-05 비동기 워커 토대
 */
@SpringBootTest(classes = [IssueTrackingApplication::class])
@ActiveProfiles("test")
class IssueTrackingApplicationContextTest {

    // 다른 BC 가 구현 빈을 제공하는 outbound port — issue-tracking 단독 부팅 시 Mockito stub 으로 채움
    @MockBean
    lateinit var workflowTransitionPort: WorkflowTransitionPort

    @MockBean
    lateinit var workflowKeyResolver: WorkflowKeyResolver

    @MockBean
    lateinit var userLookupPort: UserLookupPort

    @MockBean
    lateinit var issueTypeUsagePort: IssueTypeUsagePort

    @Autowired
    lateinit var applicationContext: ApplicationContext

    @Autowired
    lateinit var taskScheduler: TaskScheduler

    companion object {
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(
                DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                    .asCompatibleSubstituteFor("postgres"),
            )
                .withDatabaseName("bts_context_test")
                .withUsername("bts")
                .withPassword("bts_test")
                .apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }

    @Test
    fun `컨텍스트가 정상 로드된다`() {
        assertThat(applicationContext).isNotNull()
    }

    @Test
    fun `EnableScheduling 으로 인해 TaskScheduler 빈이 등록된다`() {
        assertThat(taskScheduler).isNotNull()
    }
}
