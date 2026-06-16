// 비prod issue-tracking 컨텍스트에 ComponentPermissionResolver 빈이 정확히 1개(AlwaysAllow) 주입됨을 고정하는 부팅 회귀 가드 (FR-CM-01 task-5).

package com.bts.issue.component

import com.bts.issue.IssueTrackingApplication
import com.bts.issue.component.adapter.AlwaysAllowComponentPermissionResolver
import com.bts.shared.permission.ComponentPermissionResolver
import com.bts.shared.user.UserLookupPort
import com.bts.shared.workflow.WorkflowKeyResolver
import com.bts.shared.workflow.WorkflowStateCatalog
import com.bts.shared.workflow.WorkflowTransitionPort
import com.bts.workflow.scheme.application.port.IssueTypeUsagePort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * 비prod issue-tracking 컨텍스트 [ComponentPermissionResolver] 부팅 회귀 가드 (FR-CM-01 task-5).
 *
 * ## 회귀 배경 (FR-PM-02 IssuePermissionResolverBootTest 동형)
 * ComponentController 가 shared-kernel 포트 [ComponentPermissionResolver] 를 주입받는데,
 * 비prod 컨텍스트에 그 포트를 채우는 Bean 이 사라지면 컨텍스트 부팅 자체가
 * `UnsatisfiedDependency` / `No qualifying bean` 으로 실패한다.
 * FR-PM-02 에서 동일 유형의 회귀가 66개 통합테스트를 깨뜨린 선례가 있다.
 *
 * ## FR-PM-02 와의 구조적 차이
 * IssuePermissionResolver 는 소비자(issue-tracking)와 구현(identity-access prod adapter)이
 * 서로 다른 BC 라 cross-BC component-scan 누락이 회귀의 핵심이었다.
 * 반면 ComponentPermissionResolver 는 소비자(ComponentController)와 비prod 구현
 * ([AlwaysAllowComponentPermissionResolver]) 모두 issue-tracking 同모듈이라
 * cross-BC 스캔 누락은 구조적으로 발생하지 않는다.
 * 따라서 이 가드의 의도는 "비prod 컨텍스트에서 resolver 빈이 정확히 1개 주입됨"을
 * 회귀 방지로 고정하는 데 있다.
 *
 * ## prod 프로파일은 빈 없음 (의도된 상태)
 * prod 실판정 adapter 는 FR-PM-03 으로 이연한다. 그 전까지 prod 프로파일에는
 * [ComponentPermissionResolver] 구현이 없다. 따라서 이 부팅테스트는 비prod(`test`)로 기동한다.
 *
 * ## 인프라 (IssueTrackingApplicationContextTest 동형)
 * issue-tracking 단독 부팅 시 다른 BC 가 제공하는 outbound port([WorkflowTransitionPort],
 * [WorkflowKeyResolver], [UserLookupPort], [IssueTypeUsagePort])는 존재하지 않으므로
 * MockBean 으로 자리채우기한다. jOOQ/Flyway/DataSource 빈 때문에 Testcontainers PostgreSQL 이 필요하다.
 */
@SpringBootTest(classes = [IssueTrackingApplication::class])
@ActiveProfiles("test")
class ComponentPermissionResolverBootTest {
    @MockBean
    lateinit var workflowTransitionPort: WorkflowTransitionPort

    @MockBean
    lateinit var workflowKeyResolver: WorkflowKeyResolver

    @MockBean
    lateinit var workflowStateCatalog: WorkflowStateCatalog

    @MockBean
    lateinit var userLookupPort: UserLookupPort

    @MockBean
    lateinit var issueTypeUsagePort: IssueTypeUsagePort

    @Autowired
    lateinit var context: ApplicationContext

    companion object {
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(
                DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                    .asCompatibleSubstituteFor("postgres"),
            )
                .withDatabaseName("bts_component_perm_test")
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
    fun `비prod 컨텍스트에 ComponentPermissionResolver 빈이 정확히 1개 존재한다`() {
        val beans = context.getBeansOfType(ComponentPermissionResolver::class.java)

        assertThat(beans).hasSize(1)
        assertThat(beans.values.single()).isInstanceOf(AlwaysAllowComponentPermissionResolver::class.java)
    }
}
