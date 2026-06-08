// 비prod issue-tracking 컨텍스트에 FieldPermissionResolver 빈이 정확히 1개(AlwaysAllow) 주입됨을 고정하는 부팅 회귀 가드 (FR-PM-07 task-6).

package com.bts.issue.fieldpermission

import com.bts.issue.IssueTrackingApplication
import com.bts.issue.fieldpermission.adapter.AlwaysAllowFieldPermissionResolver
import com.bts.shared.permission.FieldKind
import com.bts.shared.permission.FieldPermissionResolver
import com.bts.shared.permission.FieldRef
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
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID

/**
 * 비prod issue-tracking 컨텍스트 [FieldPermissionResolver] 부팅 회귀 가드 (FR-PM-07 task-6).
 *
 * ## 회귀 배경 (FR-PM-02 동형)
 * IssueService 및 향후 T7/T8 소비자가 shared-kernel 포트 [FieldPermissionResolver] 를 주입받는데,
 * 비prod 컨텍스트에 그 포트를 채우는 Bean 이 없으면 컨텍스트 부팅이
 * `UnsatisfiedDependency` / `No qualifying bean` 으로 실패한다.
 * FR-PM-02 에서 동일 유형의 회귀가 66개 통합테스트를 깨뜨린 선례가 있다.
 *
 * ## prod 프로파일은 빈 없음 (의도된 상태)
 * prod 실판정 adapter 는 FR-PM-07 PR-B(identity-access)에서 도입된다. 그 전까지 prod 프로파일에는
 * [FieldPermissionResolver] 구현이 없다. 따라서 이 부팅테스트는 비prod(`test`)로 기동한다.
 *
 * ## 인프라 (ComponentPermissionResolverBootTest 동형)
 * issue-tracking 단독 부팅 시 다른 BC 가 제공하는 outbound port([WorkflowTransitionPort],
 * [WorkflowKeyResolver], [UserLookupPort], [IssueTypeUsagePort])는 존재하지 않으므로
 * MockBean 으로 자리채우기한다. jOOQ/Flyway/DataSource 빈 때문에 Testcontainers PostgreSQL 이 필요하다.
 */
@SpringBootTest(classes = [IssueTrackingApplication::class])
@ActiveProfiles("test")
class FieldPermissionResolverBootTest {
    @MockBean
    lateinit var workflowTransitionPort: WorkflowTransitionPort

    @MockBean
    lateinit var workflowKeyResolver: WorkflowKeyResolver

    @MockBean
    lateinit var userLookupPort: UserLookupPort

    @MockBean
    lateinit var issueTypeUsagePort: IssueTypeUsagePort

    @Autowired
    lateinit var context: ApplicationContext

    @Autowired
    lateinit var fieldPermissionResolver: FieldPermissionResolver

    companion object {
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(
                DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                    .asCompatibleSubstituteFor("postgres"),
            )
                .withDatabaseName("bts_field_perm_test")
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
    fun `비prod 컨텍스트에 FieldPermissionResolver 빈이 정확히 1개 존재한다`() {
        val beans = context.getBeansOfType(FieldPermissionResolver::class.java)

        assertThat(beans).hasSize(1)
        assertThat(beans.values.single()).isInstanceOf(AlwaysAllowFieldPermissionResolver::class.java)
    }

    @Test
    fun `visibleFields는 candidates를 그대로 반환한다`() {
        val actorId = UUID.randomUUID()
        val projectId = UUID.randomUUID()
        val candidates =
            setOf(
                FieldRef(FieldKind.CORE, "summary"),
                FieldRef(FieldKind.CORE, "priority"),
                FieldRef(FieldKind.CUSTOM, "team"),
            )

        val result = fieldPermissionResolver.visibleFields(actorId, projectId, candidates)

        assertThat(result).isEqualTo(candidates)
    }

    @Test
    fun `editableFields는 candidates를 그대로 반환한다`() {
        val actorId = UUID.randomUUID()
        val projectId = UUID.randomUUID()
        val candidates =
            setOf(
                FieldRef(FieldKind.CORE, "summary"),
                FieldRef(FieldKind.CUSTOM, "team"),
            )

        val result = fieldPermissionResolver.editableFields(actorId, projectId, candidates)

        assertThat(result).isEqualTo(candidates)
    }

    @Test
    fun `빈 candidates에 대해 빈 집합을 반환한다`() {
        val actorId = UUID.randomUUID()
        val projectId = UUID.randomUUID()

        assertThat(fieldPermissionResolver.visibleFields(actorId, projectId, emptySet())).isEmpty()
        assertThat(fieldPermissionResolver.editableFields(actorId, projectId, emptySet())).isEmpty()
    }
}
