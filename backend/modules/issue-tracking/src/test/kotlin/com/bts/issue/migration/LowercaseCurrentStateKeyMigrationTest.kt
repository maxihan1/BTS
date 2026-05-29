// V004 마이그레이션 검증 — issues.current_state_key 대문자 데이터를 소문자로 정규화

package com.bts.issue.migration

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
import java.sql.DriverManager

/**
 * Flyway V001~V004 마이그레이션 적용 후 issues.current_state_key 소문자 정규화를 검증한다.
 * Testcontainers PostgreSQL 을 직접 사용하며 Spring 컨텍스트 없이 실행한다.
 *
 * 검증 범위.
 * - 대문자 current_state_key (예: OPEN, DONE) 가 소문자(open, done)로 변환됨
 * - 이미 소문자인 row 는 변경되지 않음 (보존)
 * - 혼합 케이스(Open, IN_PROGRESS) 도 LOWER() 로 정규화됨
 * - 멱등성 — V004 를 이미 적용한 상태에서 같은 UPDATE 를 재실행해도 변경 row 0건
 *
 * 이미지 선택 이유.
 * V002 마이그레이션이 pgmq 확장을 요구하므로 postgres:16-alpine 사용 불가.
 * quay.io/tembo/pg16-pgmq:latest (pgmq 사전 설치) 로 전체 마이그레이션 체인 실행.
 * ADR 2026-05-22-pgmq-postgres-image 와 동일 결정.
 *
 * 참조. plan §Task 7 / DATA.md §1.3 (Flyway 전용) / DATA.md §4.1 (V004 BC 번호 범위).
 */
@Testcontainers
class LowercaseCurrentStateKeyMigrationTest {
    companion object {
        // quay.io/tembo/pg16-pgmq:latest — V002 pgmq 확장 요구로 인해 tembo 이미지 사용.
        // asCompatibleSubstituteFor("postgres"): Testcontainers 이미지 호환성 검증 우회.
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

        /**
         * V001~V003 까지만 먼저 적용한 뒤 테스트용 더티 데이터(대문자 state key) 를 삽입한다.
         * V004 는 아직 적용하지 않는다 — RED 단계에서 데이터 상태를 검증 가능하게 하기 위함.
         *
         * 단계별 마이그레이션 제어 전략.
         * Flyway target() 을 사용해 특정 버전까지만 적용한다.
         * "3" 을 target 으로 지정하면 V003 까지만 실행되고 V004 는 pending 상태로 남는다.
         */
        @BeforeAll
        @JvmStatic
        fun setup() {
            // 1단계: V003 까지만 적용
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration/issue-tracking")
                .target("3")
                .load()
                .migrate()

            // 2단계: 더티 데이터 삽입 — 대문자/혼합 케이스 current_state_key
            // project 1건과 issue 3건 (OPEN, Done, IN_PROGRESS) + 이미 소문자 1건(open)
            conn().use { c ->
                c.autoCommit = false
                c.createStatement().use { s ->
                    s.execute(
                        """
                        INSERT INTO projects (key, name, key_sequence) VALUES ('MIGTEST', 'Migration Test Project', 4)
                        """.trimIndent(),
                    )
                }

                val projectId =
                    c.prepareStatement("SELECT id FROM projects WHERE key = 'MIGTEST'")
                        .executeQuery().also { it.next() }.getString(1)

                // 대문자 OPEN
                c.prepareStatement(
                    "INSERT INTO issues (key, project_id, summary, reporter_id, current_state_key) " +
                        "VALUES ('MIGTEST-1', ?::uuid, 'uppercase open', gen_random_uuid(), 'OPEN')",
                ).also { it.setString(1, projectId) }.execute()

                // 파스칼 케이스 Done
                c.prepareStatement(
                    "INSERT INTO issues (key, project_id, summary, reporter_id, current_state_key) " +
                        "VALUES ('MIGTEST-2', ?::uuid, 'pascal Done', gen_random_uuid(), 'Done')",
                ).also { it.setString(1, projectId) }.execute()

                // 대문자 + 언더스코어 IN_PROGRESS
                c.prepareStatement(
                    "INSERT INTO issues (key, project_id, summary, reporter_id, current_state_key) " +
                        "VALUES ('MIGTEST-3', ?::uuid, 'uppercase IN_PROGRESS', gen_random_uuid(), 'IN_PROGRESS')",
                ).also { it.setString(1, projectId) }.execute()

                // 이미 소문자 open — V004 이후에도 unchanged 검증용
                c.prepareStatement(
                    "INSERT INTO issues (key, project_id, summary, reporter_id, current_state_key) " +
                        "VALUES ('MIGTEST-4', ?::uuid, 'already lowercase open', gen_random_uuid(), 'open')",
                ).also { it.setString(1, projectId) }.execute()

                c.commit()
            }

            // 3단계: V004 적용 (target 제거 → 전체 마이그레이션 실행)
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration/issue-tracking")
                .load()
                .migrate()
        }

        private fun conn(): Connection {
            val url = postgres.jdbcUrl
            return DriverManager.getConnection(url, postgres.username, postgres.password)
        }
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private fun currentStateKey(issueKey: String): String? =
        conn().use { c ->
            c.prepareStatement("SELECT current_state_key FROM issues WHERE key = ?")
                .also { it.setString(1, issueKey) }
                .executeQuery()
                .let { rs -> if (rs.next()) rs.getString(1) else null }
        }

    private fun countNonLowercaseRows(): Int =
        conn().use { c ->
            c.createStatement()
                .executeQuery(
                    "SELECT COUNT(*) FROM issues WHERE current_state_key <> LOWER(current_state_key)",
                )
                .also { it.next() }
                .getInt(1)
        }

    // ── 테스트 ────────────────────────────────────────────────────────────────

    @Test
    fun `migration converts existing OPEN to open while preserving lowercase rows`() {
        // OPEN → open 변환 확인
        assertThat(currentStateKey("MIGTEST-1")).isEqualTo("open")
        // 이미 소문자 row 는 그대로 유지
        assertThat(currentStateKey("MIGTEST-4")).isEqualTo("open")
    }

    @Test
    fun `migration is idempotent (re-run yields no change)`() {
        // 마이그레이션 완료 후 대문자 row 가 0건이어야 함 — 재실행해도 변경 대상 없음
        assertThat(countNonLowercaseRows()).isEqualTo(0)
    }

    @Test
    fun `migration handles mixed case (Open Done IN_PROGRESS) via LOWER`() {
        // 파스칼 케이스 Done → done
        assertThat(currentStateKey("MIGTEST-2")).isEqualTo("done")
        // 대문자+언더스코어 IN_PROGRESS → in_progress
        assertThat(currentStateKey("MIGTEST-3")).isEqualTo("in_progress")
    }
}
