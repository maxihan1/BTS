// V006 마이그레이션 검증 — issues 5컬럼 추가(description/priority/labels/environment/impact) + GIN 인덱스

package com.bts.issue.migration

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager

/**
 * Flyway V001~V006 마이그레이션 적용 후 V006 변경사항을 검증한다.
 * Spring 컨텍스트 없이 Testcontainers PostgreSQL 에서 직접 실행한다.
 *
 * 검증 범위.
 * (a) issues 테이블에 5컬럼 존재: description, priority, labels, environment, impact
 * (b) 기존 row(V005 이전 삽입) backfill — priority=3, labels='{}' (NOT NULL DEFAULT)
 * (c) GIN 인덱스 ix_issues_labels_gin 존재
 * (d) priority CHECK (1~5), impact CHECK (1~3) 제약 동작
 *
 * 이미지 선택 이유.
 * V002 마이그레이션이 pgmq 확장을 요구하므로 postgres:16-alpine 사용 불가.
 * quay.io/tembo/pg16-pgmq:latest (pgmq 사전 설치) 로 전체 마이그레이션 체인 실행.
 * ADR 2026-05-22-pgmq-postgres-image 와 동일 결정.
 */
@Testcontainers
class V006MigrationTest {
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
         * V001~V005 까지 적용 후 기존 row(미래의 backfill 대상)를 삽입하고, V006 을 적용한다.
         * backfill 단언에 사용할 이슈 key 를 V6TEST-1 로 고정.
         */
        @BeforeAll
        @JvmStatic
        fun setup() {
            // 1단계: V005 까지만 적용
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration/issue-tracking")
                .target("5")
                .load()
                .migrate()

            // 2단계: V006 이전 기존 row 삽입 — priority/labels 컬럼이 아직 없으므로 명시 제외
            conn().use { c ->
                c.autoCommit = false

                c.prepareStatement(
                    "INSERT INTO projects (key, name, key_sequence) VALUES ('V6TEST', 'V006 Test Project', 1)" +
                        " ON CONFLICT (key) DO NOTHING",
                ).use { it.executeUpdate() }

                val projectId =
                    c.prepareStatement("SELECT id FROM projects WHERE key = 'V6TEST'")
                        .use { stmt ->
                            stmt.executeQuery().use { rs ->
                                rs.next()
                                rs.getString(1)
                            }
                        }

                val taskTypeId =
                    c.prepareStatement(
                        "SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1",
                    ).use { stmt ->
                        stmt.executeQuery().use { rs ->
                            rs.next()
                            rs.getLong(1)
                        }
                    }

                // V006 이전 row: description/priority/labels/environment/impact 컬럼 없이 삽입
                c.prepareStatement(
                    "INSERT INTO issues (key, project_id, summary, reporter_id, current_state_key, type_id)" +
                        " VALUES ('V6TEST-1', ?::uuid, 'Backfill Target Issue', gen_random_uuid(), 'open', ?)",
                ).use { stmt ->
                    stmt.setString(1, projectId)
                    stmt.setLong(2, taskTypeId)
                    stmt.executeUpdate()
                }

                c.commit()
            }

            // 3단계: V006 적용 (target 제거 → 전체 마이그레이션 실행)
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration/issue-tracking")
                .load()
                .migrate()
        }

        private fun conn() = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private fun conn() = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    private fun columnExists(
        tableName: String,
        columnName: String,
    ): Boolean =
        conn().use { c ->
            c.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.columns" +
                    " WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.setString(2, columnName)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1) > 0
                }
            }
        }

    @Suppress("NestedBlockDepth") // JDBC use {} 3중 중첩 — Connection/PreparedStatement/ResultSet 생명주기 관리 패턴
    private fun columnDataType(
        tableName: String,
        columnName: String,
    ): String? =
        conn().use { c ->
            c.prepareStatement(
                "SELECT data_type FROM information_schema.columns" +
                    " WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.setString(2, columnName)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    private fun indexExists(indexName: String): Boolean =
        conn().use { c ->
            c.prepareStatement(
                "SELECT COUNT(*) FROM pg_indexes" +
                    " WHERE schemaname = 'public' AND indexname = ?",
            ).use { stmt ->
                stmt.setString(1, indexName)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1) > 0
                }
            }
        }

    // ── (a) 5컬럼 존재 검증 ───────────────────────────────────────────────────

    @Test
    fun `V006 issues 에 description 컬럼 존재`() {
        assertThat(columnExists("issues", "description")).isTrue()
    }

    @Test
    fun `V006 issues 에 priority 컬럼 존재`() {
        assertThat(columnExists("issues", "priority")).isTrue()
    }

    @Test
    fun `V006 issues 에 labels 컬럼 존재`() {
        assertThat(columnExists("issues", "labels")).isTrue()
    }

    @Test
    fun `V006 issues 에 environment 컬럼 존재`() {
        assertThat(columnExists("issues", "environment")).isTrue()
    }

    @Test
    fun `V006 issues 에 impact 컬럼 존재`() {
        assertThat(columnExists("issues", "impact")).isTrue()
    }

    @Test
    fun `V006 description 컬럼 타입은 text`() {
        assertThat(columnDataType("issues", "description")).isEqualTo("text")
    }

    @Test
    fun `V006 priority 컬럼 타입은 smallint`() {
        assertThat(columnDataType("issues", "priority")).isEqualTo("smallint")
    }

    @Test
    fun `V006 labels 컬럼 타입은 ARRAY`() {
        assertThat(columnDataType("issues", "labels")).isEqualTo("ARRAY")
    }

    @Test
    fun `V006 environment 컬럼 타입은 text`() {
        assertThat(columnDataType("issues", "environment")).isEqualTo("text")
    }

    @Test
    fun `V006 impact 컬럼 타입은 smallint`() {
        assertThat(columnDataType("issues", "impact")).isEqualTo("smallint")
    }

    // ── (b) 기존 row backfill 검증 ────────────────────────────────────────────

    @Test
    fun `V006 기존 row 의 priority 는 3 으로 backfill`() {
        val priority =
            conn().use { c ->
                c.prepareStatement("SELECT priority FROM issues WHERE key = 'V6TEST-1'")
                    .use { stmt ->
                        stmt.executeQuery().use { rs ->
                            check(rs.next()) { "V6TEST-1 row 없음" }
                            rs.getInt(1)
                        }
                    }
            }
        assertThat(priority).isEqualTo(3)
    }

    @Test
    fun `V006 기존 row 의 labels 는 빈 배열로 backfill`() {
        val labels =
            conn().use { c ->
                c.prepareStatement("SELECT labels FROM issues WHERE key = 'V6TEST-1'")
                    .use { stmt ->
                        stmt.executeQuery().use { rs ->
                            check(rs.next()) { "V6TEST-1 row 없음" }
                            (rs.getArray(1).array as Array<*>)
                        }
                    }
            }
        assertThat(labels).isEmpty()
    }

    // ── (c) GIN 인덱스 존재 검증 ─────────────────────────────────────────────

    @Test
    fun `V006 ix_issues_labels_gin GIN 인덱스 존재`() {
        assertThat(indexExists("ix_issues_labels_gin")).isTrue()
    }

    @Test
    @Suppress("NestedBlockDepth") // JDBC use {} 3중 중첩 — Connection/PreparedStatement/ResultSet 생명주기 관리 패턴
    fun `V006 ix_issues_labels_gin 인덱스 타입이 gin`() {
        val indexType =
            conn().use { c ->
                c.prepareStatement(
                    "SELECT am.amname FROM pg_indexes idx" +
                        " JOIN pg_class cls ON cls.relname = idx.indexname" +
                        " JOIN pg_am am ON am.oid = cls.relam" +
                        " WHERE idx.schemaname = 'public' AND idx.indexname = 'ix_issues_labels_gin'",
                ).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) rs.getString(1) else null
                    }
                }
            }
        assertThat(indexType).isEqualTo("gin")
    }

    // ── (d) CHECK 제약 검증 ───────────────────────────────────────────────────

    @Test
    @Suppress("SwallowedException") // DB CHECK constraint 위반 확인 — 예외 발생 여부 자체가 검증 대상
    fun `V006 priority 6 입력 시 CHECK 위반`() {
        conn().use { c ->
            c.prepareStatement(
                "INSERT INTO projects (key, name) VALUES ('CHKTEST', 'Check Test')" +
                    " ON CONFLICT (key) DO NOTHING",
            ).use { it.executeUpdate() }
        }

        val projectId =
            conn().use { c ->
                c.prepareStatement("SELECT id FROM projects WHERE key = 'CHKTEST'")
                    .use { stmt ->
                        stmt.executeQuery().use { rs ->
                            rs.next()
                            rs.getString(1)
                        }
                    }
            }

        val taskTypeId =
            conn().use { c ->
                c.prepareStatement(
                    "SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1",
                ).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }
            }

        var exceptionThrown = false
        try {
            conn().use { c ->
                c.prepareStatement(
                    "INSERT INTO issues (key, project_id, summary, reporter_id, current_state_key, type_id, priority)" +
                        " VALUES ('CHKTEST-1', ?::uuid, 'Bad Priority', gen_random_uuid(), 'open', ?, 6)",
                ).use { stmt ->
                    stmt.setString(1, projectId)
                    stmt.setLong(2, taskTypeId)
                    stmt.executeUpdate()
                }
            }
        } catch (e: java.sql.SQLException) {
            exceptionThrown = true
        }
        assertThat(exceptionThrown).isTrue()
    }

    @Test
    @Suppress("SwallowedException") // DB CHECK constraint 위반 확인 — 예외 발생 여부 자체가 검증 대상
    fun `V006 impact 4 입력 시 CHECK 위반`() {
        conn().use { c ->
            c.prepareStatement(
                "INSERT INTO projects (key, name) VALUES ('CHKTEST2', 'Check Test 2')" +
                    " ON CONFLICT (key) DO NOTHING",
            ).use { it.executeUpdate() }
        }

        val projectId =
            conn().use { c ->
                c.prepareStatement("SELECT id FROM projects WHERE key = 'CHKTEST2'")
                    .use { stmt ->
                        stmt.executeQuery().use { rs ->
                            rs.next()
                            rs.getString(1)
                        }
                    }
            }

        val taskTypeId =
            conn().use { c ->
                c.prepareStatement(
                    "SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1",
                ).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }
            }

        var exceptionThrown = false
        try {
            conn().use { c ->
                c.prepareStatement(
                    "INSERT INTO issues (key, project_id, summary, reporter_id, current_state_key, type_id, impact)" +
                        " VALUES ('CHKTEST2-1', ?::uuid, 'Bad Impact', gen_random_uuid(), 'open', ?, 4)",
                ).use { stmt ->
                    stmt.setString(1, projectId)
                    stmt.setLong(2, taskTypeId)
                    stmt.executeUpdate()
                }
            }
        } catch (e: java.sql.SQLException) {
            exceptionThrown = true
        }
        assertThat(exceptionThrown).isTrue()
    }
}
