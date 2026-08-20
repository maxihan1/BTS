// 시드가 workflow_states 와 전역 상태 카탈로그(statuses · workflow_statuses)를 함께 기록하는지 검증한다

package com.bts.workflow.seed

import com.bts.workflow.scheme.repository.SchemeIssueTypeMappingRepository
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager

/**
 * 시드가 상태를 **두 곳에 함께** 기록하는지 본다.
 *
 * ### 왜 이중 기록인가
 * 배포된 DB 는 V204 백필이 `statuses`/`workflow_statuses` 를 채운다. 그런데 빈 DB 는 백필할
 * `workflow_states` 가 0행이라 백필이 아무것도 만들지 않고, 그 뒤 시드가 워크플로우를 넣는다.
 * 시드가 카탈로그를 함께 기록하지 않으면 **빈 DB 와 기존 DB 의 카탈로그 상태가 갈라진다**(스펙 E1).
 *
 * `workflow_states` 는 계속 기록한다 — `workflow_transitions` 의 FK 대상이라 로드맵 마지막 PR 의
 * DROP 전까지 살아 있어야 한다.
 *
 * 기대값 12/17 은 시드 YAML 4개 전수 실측이다 (상태 키 유일 12 · 워크플로우별 행 합 17).
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SeedStatusCatalogIntegrationTest {
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
            // V201 이 issue_types 를 참조하므로 2단계 — V200 까지 적용 → 스텁 생성 → 나머지 전부.
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
            val rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM $table")
            rs.next()
            rs.getInt(1)
        }

    private fun execute(sql: String) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.createStatement().use { it.execute(sql) }
        }
    }

    private fun statusName(key: String): String? =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            val rs = conn.createStatement().executeQuery("SELECT name FROM statuses WHERE key = '$key'")
            if (rs.next()) rs.getString(1) else null
        }

    @Test
    @Order(1)
    fun `빈 DB 최초 부팅이 statuses 12행과 workflow_statuses 17행을 만든다`() {
        // 백필(V204)은 빈 DB 에서 0행이다 — 여기 채우는 주체는 시드다.
        assertThat(count("statuses")).isZero()

        service.seedAll()

        assertThat(count("statuses")).isEqualTo(12)
        assertThat(count("workflow_statuses")).isEqualTo(17)
        // workflow_states 도 그대로 기록한다 — workflow_transitions 의 FK 대상이다.
        assertThat(count("workflow_states")).isEqualTo(17)
    }

    @Test
    @Order(2)
    fun `시드 재호출은 카탈로그 행을 늘리지 않는다`() {
        service.seedAll()

        assertThat(count("statuses")).isEqualTo(12)
        assertThat(count("workflow_statuses")).isEqualTo(17)
    }

    @Test
    @Order(3)
    fun `이미 있는 전역 상태의 이름을 시드가 덮지 않는다`() {
        // 운영자가 'doing' 상태 이름을 바꾼 상황을 만든다.
        execute("UPDATE statuses SET name = '진행 중' WHERE key = 'doing'")
        // 그 상태를 쓰던 워크플로우를 지운다 — CASCADE 로 workflow_statuses 도 사라지고,
        // 다음 seedAll() 이 'simple' 을 다시 넣는 경로를 탄다(「없을 때만 삽입」).
        execute(
            "DELETE FROM workflow_scheme_issue_type_mappings " +
                "WHERE workflow_id IN (SELECT id FROM workflows WHERE key = 'simple')",
        )
        execute("DELETE FROM workflows WHERE key = 'simple'")

        service.seedAll()

        // 전역 상태는 재사용된다 — 이름은 운영자가 바꾼 값 그대로다 (ADR D3 「이름은 자유, 키는 불변」).
        assertThat(statusName("doing")).isEqualTo("진행 중")
        assertThat(count("statuses")).isEqualTo(12)
        assertThat(count("workflow_statuses")).isEqualTo(17)
    }
}
