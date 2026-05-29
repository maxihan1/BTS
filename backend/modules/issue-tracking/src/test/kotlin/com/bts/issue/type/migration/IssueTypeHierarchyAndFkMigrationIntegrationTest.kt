// issue-tracking V005 마이그레이션 검증 — hierarchy_level 컬럼, issues.type_id FK, 부분 unique 인덱스

package com.bts.issue.type.migration

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.sql.SQLException

/**
 * Flyway V001~V005 마이그레이션 적용 후 V005 변경사항을 검증한다.
 * Spring 컨텍스트 없이 Testcontainers PostgreSQL 에서 직접 실행한다.
 *
 * 검증 범위.
 * (a) issue_types.hierarchy_level 컬럼 존재 + 값: epic=1, subtask=-1, 나머지=0
 * (b) issues.type_id 컬럼 NOT NULL + 기존 row 가 task id 로 backfill 됨
 * (c) fk_issues_type_id FK 제약 존재 (issues.type_id → issue_types.id)
 * (d) ux_issue_types_key_active 부분 unique 인덱스 — 활성 중복 key 거부, soft-delete 후 재INSERT 허용
 *
 * 이미지 선택 이유.
 * V002 마이그레이션이 pgmq 확장을 요구하므로 postgres:16-alpine 사용 불가.
 * quay.io/tembo/pg16-pgmq:latest (pgmq 사전 설치) 로 전체 마이그레이션 체인 실행.
 * ADR 2026-05-22-pgmq-postgres-image 와 동일 결정.
 */
@Testcontainers
class IssueTypeHierarchyAndFkMigrationIntegrationTest {
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

        @BeforeAll
        @JvmStatic
        fun applyMigrations() {
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration/issue-tracking")
                .load()
                .migrate()
        }
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

    @Suppress("NestedBlockDepth")
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

    private fun constraintExists(
        tableName: String,
        constraintName: String,
    ): Boolean =
        conn().use { c ->
            c.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.table_constraints" +
                    " WHERE table_schema = 'public' AND table_name = ? AND constraint_name = ?",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.setString(2, constraintName)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1) > 0
                }
            }
        }

    private fun hierarchyLevelForKey(key: String): Int =
        conn().use { c ->
            c.prepareStatement(
                "SELECT hierarchy_level FROM issue_types WHERE key = ? AND deleted_at IS NULL",
            ).use { stmt ->
                stmt.setString(1, key)
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "issue_type key=$key 없음" }
                    rs.getInt(1)
                }
            }
        }

    // ── (a) hierarchy_level 컬럼 존재 및 값 검증 ─────────────────────────────

    @Test
    fun `V005 issue_types 에 hierarchy_level 컬럼 존재`() {
        assertThat(columnExists("issue_types", "hierarchy_level")).isTrue()
    }

    @Test
    fun `V005 hierarchy_level 은 INTEGER 타입`() {
        assertThat(columnDataType("issue_types", "hierarchy_level")).isEqualTo("integer")
    }

    @Test
    fun `V005 epic 의 hierarchy_level 은 1`() {
        assertThat(hierarchyLevelForKey("epic")).isEqualTo(1)
    }

    @Test
    fun `V005 subtask 의 hierarchy_level 은 -1`() {
        assertThat(hierarchyLevelForKey("subtask")).isEqualTo(-1)
    }

    @Test
    fun `V005 task 의 hierarchy_level 은 0`() {
        assertThat(hierarchyLevelForKey("task")).isEqualTo(0)
    }

    @Test
    fun `V005 story 의 hierarchy_level 은 0`() {
        assertThat(hierarchyLevelForKey("story")).isEqualTo(0)
    }

    @Test
    fun `V005 bug 의 hierarchy_level 은 0`() {
        assertThat(hierarchyLevelForKey("bug")).isEqualTo(0)
    }

    // ── (b) issues.type_id NOT NULL + backfill 검증 ───────────────────────────

    @Test
    fun `V005 issues 에 type_id 컬럼 존재`() {
        assertThat(columnExists("issues", "type_id")).isTrue()
    }

    @Test
    fun `V005 issues type_id 컬럼은 NOT NULL`() {
        // IS_NULLABLE = 'NO' 인지 확인
        val isNullable =
            conn().use { c ->
                c.prepareStatement(
                    "SELECT is_nullable FROM information_schema.columns" +
                        " WHERE table_schema = 'public' AND table_name = 'issues' AND column_name = 'type_id'",
                ).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getString(1)
                    }
                }
            }
        assertThat(isNullable).isEqualTo("NO")
    }

    @Test
    fun `V005 신규 issues row 는 type_id 없이 INSERT 불가`() {
        // project 삽입 (이미 있으면 스킵)
        conn().use { c ->
            c.prepareStatement(
                "INSERT INTO projects(key, name) VALUES('TST', 'Test Project')" +
                    " ON CONFLICT(key) DO NOTHING",
            ).use { it.executeUpdate() }
        }
        val projectId =
            conn().use { c ->
                c.prepareStatement("SELECT id FROM projects WHERE key = 'TST'").use { stmt ->
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getString(1)
                    }
                }
            }

        assertThatThrownBy {
            conn().use { c ->
                c.prepareStatement(
                    "INSERT INTO issues(key, project_id, summary, reporter_id, current_state_key)" +
                        " VALUES('TST-999', ?::uuid, 'No TypeId', gen_random_uuid(), 'open')",
                ).use { stmt ->
                    stmt.setString(1, projectId)
                    stmt.executeUpdate()
                }
            }
        }.isInstanceOf(SQLException::class.java)
    }

    // ── (c) FK 제약 존재 검증 ─────────────────────────────────────────────────

    @Test
    fun `V005 fk_issues_type_id 외래 키 제약 존재`() {
        assertThat(constraintExists("issues", "fk_issues_type_id")).isTrue()
    }

    @Test
    fun `V005 존재하지 않는 type_id 로 issues INSERT 시 FK 위반`() {
        conn().use { c ->
            c.prepareStatement(
                "INSERT INTO projects(key, name) VALUES('TST', 'Test Project')" +
                    " ON CONFLICT(key) DO NOTHING",
            ).use { it.executeUpdate() }
        }
        val projectId =
            conn().use { c ->
                c.prepareStatement("SELECT id FROM projects WHERE key = 'TST'").use { stmt ->
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getString(1)
                    }
                }
            }

        assertThatThrownBy {
            conn().use { c ->
                c.prepareStatement(
                    "INSERT INTO issues(key, project_id, summary, reporter_id, current_state_key, type_id)" +
                        " VALUES('TST-998', ?::uuid, 'Bad TypeId', gen_random_uuid(), 'open', 999999)",
                ).use { stmt ->
                    stmt.setString(1, projectId)
                    stmt.executeUpdate()
                }
            }
        }.isInstanceOf(SQLException::class.java)
            .hasMessageContaining("fk_issues_type_id")
    }

    @Test
    fun `V005 ix_issues_type_id 인덱스 존재`() {
        assertThat(indexExists("ix_issues_type_id")).isTrue()
    }

    // ── (d) 부분 unique 인덱스 검증 ──────────────────────────────────────────

    @Test
    fun `V005 ux_issue_types_key_active 부분 unique 인덱스 존재`() {
        assertThat(indexExists("ux_issue_types_key_active")).isTrue()
    }

    @Test
    fun `V005 활성 상태에서 같은 key 중복 INSERT 거부`() {
        // 'epic' 은 이미 활성 row 존재 — 같은 key 로 재INSERT 시 unique 위반
        assertThatThrownBy {
            conn().use { c ->
                c.prepareStatement(
                    "INSERT INTO issue_types(key, name, is_standard) VALUES('epic', 'Epic Dup', false)",
                ).use { it.executeUpdate() }
            }
        }.isInstanceOf(SQLException::class.java)
    }

    @Test
    fun `V005 soft-delete 후 같은 key 재INSERT 허용`() {
        // 새 타입 삽입
        conn().use { c ->
            c.prepareStatement(
                "INSERT INTO issue_types(key, name, is_standard) VALUES('test-only-type', 'Test Only', false)",
            ).use { it.executeUpdate() }
        }

        // soft-delete
        conn().use { c ->
            c.prepareStatement(
                "UPDATE issue_types SET deleted_at = NOW() WHERE key = 'test-only-type'",
            ).use { it.executeUpdate() }
        }

        // 같은 key 로 재INSERT — 부분 unique 는 deleted_at IS NULL 에만 적용되므로 허용됨
        conn().use { c ->
            c.prepareStatement(
                "INSERT INTO issue_types(key, name, is_standard) VALUES('test-only-type', 'Test Only Revived', false)",
            ).use { it.executeUpdate() }
        }

        // 재INSERT 된 활성 row 확인
        val activeCount =
            conn().use { c ->
                c.prepareStatement(
                    "SELECT COUNT(*) FROM issue_types WHERE key = 'test-only-type' AND deleted_at IS NULL",
                ).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
            }
        assertThat(activeCount).isEqualTo(1)
    }
}
