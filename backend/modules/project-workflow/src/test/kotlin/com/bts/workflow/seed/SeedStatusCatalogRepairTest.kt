// 롤백이 비운 상태 카탈로그를 시드가 되채우는지 검증한다 (게이트 1 리뷰 R6)

package com.bts.workflow.seed

import com.bts.workflow.repository.WorkflowRepository
import com.bts.workflow.scheme.repository.SchemeIssueTypeMappingRepository
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager

/**
 * 앱만 롤백했다가 다시 롤포워드한 상태를 재현하고, 시드가 카탈로그를 되채우는지 본다.
 *
 * ### 왜 필요한가 (CEO 렌즈 C1)
 * 이 PR 이후 운영자가 DB 에서 워크플로우를 고치는 것은 정상 동작이다. 그 상태에서 앱을 이전
 * 버전으로 롤백하면 구 `YamlSeedService` 가 되살아나 `deleteWorkflow` → 재삽입을 하고,
 * `workflow_statuses` 는 `workflows` FK CASCADE 로 함께 사라진다. 다시 롤포워드해도 새 시드는
 * 「workflow 행이 있으면 삽입 안 함」이라 **영영 채우지 않는다.** CI 판별식은 운영 DB 를 보지 않아
 * 조용하다.
 *
 * 그래서 `seedAll()` 말미에 보정 경로를 둔다. 선례는 같은 자리에서 같은 이유로 도는
 * `SchemeIssueTypeMappingRepository.repairDefaultMappings()` 다 — 새 패턴이 아니다.
 */
@Testcontainers
class SeedStatusCatalogRepairTest {
    companion object {
        private val temboImage: DockerImageName =
            DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                .asCompatibleSubstituteFor("postgres")

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(temboImage)
                .withDatabaseName("bts_test")
                .withUsername("bts")
                .withPassword("bts_test")

        lateinit var service: YamlSeedService

        @BeforeAll
        @JvmStatic
        fun setup() {
            migrate("200")
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        "CREATE TABLE IF NOT EXISTS issue_types (" +
                            "id BIGSERIAL PRIMARY KEY, key VARCHAR(30) NOT NULL UNIQUE, " +
                            "name VARCHAR(255) NOT NULL, is_standard BOOLEAN NOT NULL DEFAULT FALSE, " +
                            "created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), " +
                            "updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), deleted_at TIMESTAMPTZ)",
                    )
                }
            }
            migrate(null)

            val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            val dsl = DSL.using(dataSource, SQLDialect.POSTGRES)
            service =
                YamlSeedService(
                    WorkflowRepository(dsl),
                    dsl,
                    DefaultResourceLoader(),
                    mockk(relaxed = true),
                    mockk(relaxed = true),
                    SchemeIssueTypeMappingRepository(dsl),
                )
        }

        private fun migrate(target: String?) {
            val configure =
                Flyway.configure()
                    .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                    .placeholderReplacement(false)
                    .locations(
                        "classpath:db/migration/issue-tracking",
                        "classpath:db/migration/project-workflow",
                    )
            if (target != null) configure.target(target)
            configure.load().migrate()
        }
    }

    private fun count(table: String): Int =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM $table").use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    @Test
    fun `롤백으로 비워진 workflow_statuses 를 재기동이 되채운다`() {
        service.seedAll()
        assertThat(count("workflow_statuses")).isEqualTo(17)

        // 구 코드로 롤백했다가 롤포워드한 상태 재현 — workflows 행은 있는데 매핑만 사라졌다.
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.createStatement().use { it.execute("DELETE FROM workflow_statuses") }
        }
        assertThat(count("workflow_statuses")).isZero()

        service.seedAll()

        // 「없을 때만 삽입」이라 workflow 행이 있는 이상 insertWorkflow 는 돌지 않는다.
        // 되채우는 주체는 seedAll 말미의 보정 경로다.
        assertThat(count("workflow_statuses")).isEqualTo(17)
        assertThat(count("statuses")).isEqualTo(12)
    }

    @Test
    fun `카탈로그가 멀쩡하면 보정이 행을 늘리지 않는다`() {
        service.seedAll()
        service.seedAll()

        assertThat(count("workflow_statuses")).isEqualTo(17)
        assertThat(count("statuses")).isEqualTo(12)
    }
}
