// FR-IS-05 IssueTrackingApplication 스프링 컨텍스트 로드 + @EnableScheduling 검증 테스트

package com.bts.issue

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
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
 * ## 왜 [CrossBcPortTestConfig] 가 필요한가
 * [com.bts.issue.application.IssueApplicationService], [com.bts.issue.type.application.IssueTypeApplicationService],
 * [com.bts.issue.project.application.ProjectCreateApplicationService](FR-PJ-01) 가 issue-tracking BC 밖
 * (project-workflow BC / identity-access BC) 에서 구현 빈이 제공되는 outbound port 를 주입받는데,
 * issue-tracking 단독 부팅 시에는 그 빈들이 존재하지 않는다. [CrossBcPortTestConfig] 가 Mockito stub 으로
 * 자리채우기한다.
 *
 * ## ADR 참조
 * ADR 2026-06-02-bulk-operation-async-architecture — FR-IS-05 비동기 워커 토대
 */
@SpringBootTest(classes = [IssueTrackingApplication::class, CrossBcPortTestConfig::class])
@ActiveProfiles("test")
class IssueTrackingApplicationContextTest {
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
