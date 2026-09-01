// MigrationInFlightAdapter Testcontainers 통합 테스트 — bulk_operations 원시 SQL 판정

package com.bts.workflow.adapter.outbound

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp
import java.util.UUID

/**
 * [MigrationInFlightAdapter] Testcontainers 통합 테스트.
 *
 * ### 왜 이 파일이 필요한가
 * 이 어댑터는 **원시 SQL 이 사는 자리**이고 그 SQL 은 형제 어댑터보다 한 겹 더 복잡하다 —
 * 범위가 `payload` JSONB 배열 안에 있어 `jsonb_array_elements_text` 를 거친다. 서비스 쪽 판정은
 * 스텁 포트로 재므로 이 문장이 문법적으로 옳은지, 배열 바인딩이 실제로 먹는지, 결과 컬럼을
 * 제대로 꺼내는지는 **여기서만** 드러난다. 없으면 첫 운영 호출이 첫 실행이 된다.
 *
 * ### 다섯 축을 잰다
 * 1. **PENDING·RUNNING 은 진행 중** — 막아야 할 것
 * 2. **COMPLETED·FAILED 는 끝난 것** — FAILED 까지 막으면 재시도가 영영 불가능해진다
 * 3. **프로젝트 스코프** — 남의 프로젝트 이관이 이 워크플로우의 발행을 막으면 안 된다.
 *    이 축이 없으면 「전역으로 물어도 통과」가 안 잡혀 판정이 공허해진다
 * 4. **operation_type 스코프** — 진행 중인 BULK_EDIT 는 상태 이관과 무관하다
 * 5. **빈 스코프는 false** — 「전체」로 흘리면 fail-closed 가 과해져 스킴이 안 붙은 워크플로우가
 *    남의 이관 때문에 이관을 못 한다
 *
 * ### 마이그레이션은 issue-tracking 만 적용한다
 * 어댑터가 읽는 것은 `bulk_operations` 하나다. 형제 `IssueStatusUsageAdapterIntegrationTest` 와
 * 같은 이유로 project-workflow 스키마는 올리지 않는다 — 무엇이 판정의 입력인지가 흐려진다.
 *
 * 참조. FR-WF-07 D4 · `V008__bulk_operations.sql` · `V038__bulk_operations_status_migration.sql`
 */
@Testcontainers
class MigrationInFlightAdapterIntegrationTest {
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

        lateinit var adapter: MigrationInFlightAdapter

        @BeforeAll
        @JvmStatic
        fun setup() {
            Flyway
                .configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration/issue-tracking")
                .load()
                .migrate()

            adapter =
                MigrationInFlightAdapter(
                    DSL.using(
                        DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password),
                        SQLDialect.POSTGRES,
                    ),
                )

            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                insertOperation(conn, "STATUS_MIGRATION", "PENDING", listOf("PENDA"))
                insertOperation(conn, "STATUS_MIGRATION", "RUNNING", listOf("RUNA"))
                insertOperation(conn, "STATUS_MIGRATION", "COMPLETED", listOf("DONEA"))
                insertOperation(conn, "STATUS_MIGRATION", "FAILED", listOf("FAILA"))
                // 여러 키를 실은 이관 — 그중 하나만 물어도 걸려야 배열 판정이 실재한다.
                insertOperation(conn, "STATUS_MIGRATION", "PENDING", listOf("MULTIA", "MULTIB"))
                // 진행 중이지만 상태 이관이 아니다 — operation_type 축이 살아 있는지 재는 미끼.
                insertOperation(conn, "BULK_EDIT", "RUNNING", listOf("EDITA"))
            }
        }

        /**
         * `bulk_operations` 한 건.
         *
         * `payload` 는 운영과 같은 모양이어야 한다 — `BulkOperationPayload.StatusMigration` 이
         * `mappings` 와 `projectKeys` 를 그대로 직렬화하므로 어댑터는 `payload->'projectKeys'` 를
         * 읽는다. 모양이 다르면 이 테스트만 통과하고 운영에서 아무것도 못 찾는다.
         */
        private fun insertOperation(
            conn: Connection,
            type: String,
            status: String,
            projectKeys: List<String>,
        ) {
            val keys = projectKeys.joinToString(",") { "\"$it\"" }
            val payload = """{"mappings":{"done":"open"},"projectKeys":[$keys]}"""
            conn
                .prepareStatement(
                    "INSERT INTO bulk_operations" +
                        " (operation_type, status, actor_id, payload, total_count, created_at)" +
                        " VALUES (?, ?, ?, ?::jsonb, ?, ?)",
                ).use { ps ->
                    ps.setString(1, type)
                    ps.setString(2, status)
                    ps.setObject(3, UUID.randomUUID())
                    ps.setString(4, payload)
                    ps.setInt(5, projectKeys.size)
                    ps.setTimestamp(6, Timestamp(System.currentTimeMillis()))
                    ps.executeUpdate()
                }
        }
    }

    @Test
    fun `PENDING 이관이 겹치면 진행 중이다`() {
        assertThat(adapter.hasInFlightMigration(setOf("PENDA"))).isTrue()
    }

    @Test
    fun `RUNNING 이관이 겹치면 진행 중이다`() {
        assertThat(adapter.hasInFlightMigration(setOf("RUNA"))).isTrue()
    }

    @Test
    fun `여러 키를 실은 이관은 그중 하나만 물어도 걸린다`() {
        assertThat(adapter.hasInFlightMigration(setOf("MULTIB")))
            .describedAs("payload->'projectKeys' 를 배열로 펼치지 않으면 첫 항목만 걸린다")
            .isTrue()
    }

    @Test
    fun `COMPLETED 만 있으면 진행 중이 아니다`() {
        assertThat(adapter.hasInFlightMigration(setOf("DONEA"))).isFalse()
    }

    /** FAILED 까지 막으면 한 번 실패한 워크플로우가 영영 다시 이관하지 못한다. */
    @Test
    fun `FAILED 만 있으면 진행 중이 아니다`() {
        assertThat(adapter.hasInFlightMigration(setOf("FAILA"))).isFalse()
    }

    /**
     * ★이 판정이 없으면 「범위를 무시하고 전역으로 묻는」 구현이 전부 초록이다.
     * 그러면 남의 프로젝트 이관 하나가 무관한 워크플로우의 이관을 통째로 막는다.
     */
    @Test
    fun `남의 프로젝트에서 도는 이관은 이 범위를 막지 않는다`() {
        assertThat(adapter.hasInFlightMigration(setOf("UNRELATED"))).isFalse()
    }

    /** 진행 중이어도 상태 이관이 아니면 무관하다 — 일괄 편집은 상태를 옮기지 않는다. */
    @Test
    fun `진행 중인 BULK_EDIT 는 이관으로 세지 않는다`() {
        assertThat(adapter.hasInFlightMigration(setOf("EDITA"))).isFalse()
    }

    /** 빈 스코프를 「전체」로 흘리면 fail-closed 가 과해진다. */
    @Test
    fun `빈 범위는 진행 중이 아니다`() {
        assertThat(adapter.hasInFlightMigration(emptySet())).isFalse()
    }
}
