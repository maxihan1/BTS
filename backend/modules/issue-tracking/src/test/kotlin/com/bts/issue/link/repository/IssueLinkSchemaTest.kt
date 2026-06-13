// issue-tracking V021 마이그레이션 검증 — issue_links 테이블(자기참조 FK 2개 + UNIQUE + CHECK 2종) + issues.parent_id 컬럼 존재 확인

package com.bts.issue.link.repository

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
import java.util.UUID

/**
 * Flyway V001~V021 마이그레이션 적용 후 이슈 링크 스키마를 검증한다.
 * Testcontainers PostgreSQL 을 직접 사용하며 Spring 컨텍스트 없이 실행한다 (V017 형제 테스트 동형).
 *
 * 검증 범위 (FR-LK-01 Task 1).
 * - issue_links 테이블 존재 + 컬럼(id, source_id, target_id, link_type, created_at)
 * - 자기참조 FK 2개 — source_id / target_id → issues(id) ON DELETE CASCADE
 * - UNIQUE (source_id, target_id, link_type)
 * - CHECK source_id <> target_id (자기 링크 금지)
 * - CHECK link_type IN ('blocks','relates','duplicates','clones')
 * - FK 인덱스 idx_issue_links_source_id / idx_issue_links_target_id (DATA.md §7)
 * - issues.parent_id 컬럼 존재 (UUID, nullable) + 인덱스 idx_issues_parent_id
 *
 * 이미지 선택 이유.
 * V002 마이그레이션이 pgmq 확장을 요구하므로 postgres:16-alpine 사용 불가.
 * quay.io/tembo/pg16-pgmq:latest (pgmq 사전 설치) 로 전체 마이그레이션 체인 실행
 * (IssueVersionLinksSchemaMigrationTest 와 동일 결정).
 *
 * 참조. FR-LK-01 plan Task 1 / DATA.md §7 FK 인덱스 / V017 issue_version_links 동형 선례.
 */
@Testcontainers
class IssueLinkSchemaTest {
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

    private fun tableExists(tableName: String): Boolean =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.tables" +
                    " WHERE table_schema = 'public' AND table_name = ?",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1) > 0
                }
            }
        }

    private fun columnDataType(
        tableName: String,
        columnName: String,
    ): String? =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT data_type FROM information_schema.columns" +
                    " WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.setString(2, columnName)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getString(1) else null
                }
            }
        }

    private fun columnIsNullable(
        tableName: String,
        columnName: String,
    ): Boolean? =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT is_nullable FROM information_schema.columns" +
                    " WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.setString(2, columnName)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getString(1) == "YES" else null
                }
            }
        }

    private fun indexExists(indexName: String): Boolean =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?",
            ).use { stmt ->
                stmt.setString(1, indexName)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1) > 0
                }
            }
        }

    // 주어진 테이블의 FK 가 (로컬 컬럼 → 참조 테이블 + 삭제 규칙) 형태로 존재하는지 확인.
    @Suppress("NestedBlockDepth")
    private fun foreignKeyDeleteRule(
        tableName: String,
        column: String,
        referencedTable: String,
    ): String? =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                """
                SELECT rc.delete_rule
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON tc.constraint_name = kcu.constraint_name
                 AND tc.table_schema = kcu.table_schema
                JOIN information_schema.constraint_column_usage ccu
                  ON tc.constraint_name = ccu.constraint_name
                 AND tc.table_schema = ccu.table_schema
                JOIN information_schema.referential_constraints rc
                  ON tc.constraint_name = rc.constraint_name
                 AND tc.table_schema = rc.constraint_schema
                WHERE tc.table_schema = 'public'
                  AND tc.table_name = ?
                  AND tc.constraint_type = 'FOREIGN KEY'
                  AND kcu.column_name = ?
                  AND ccu.table_name = ?
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.setString(2, column)
                stmt.setString(3, referencedTable)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getString(1) else null
                }
            }
        }

    // 주어진 테이블의 UNIQUE 제약이 정확히 지정한 컬럼 집합으로 존재하는지 확인.
    @Suppress("NestedBlockDepth")
    private fun hasUniqueConstraintOn(
        tableName: String,
        columns: Set<String>,
    ): Boolean =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                """
                SELECT tc.constraint_name, kcu.column_name
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON tc.constraint_name = kcu.constraint_name
                 AND tc.table_schema = kcu.table_schema
                WHERE tc.table_schema = 'public'
                  AND tc.table_name = ?
                  AND tc.constraint_type = 'UNIQUE'
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.executeQuery().use { rs ->
                    val byConstraint = mutableMapOf<String, MutableSet<String>>()
                    while (rs.next()) {
                        byConstraint
                            .getOrPut(rs.getString(1)) { mutableSetOf() }
                            .add(rs.getString(2))
                    }
                    byConstraint.values.any { it == columns }
                }
            }
        }

    // 주어진 테이블의 CHECK 제약 정의 문자열들을 반환 (pg_get_constraintdef 로 원본 표현 조회).
    @Suppress("NestedBlockDepth")
    private fun checkConstraintDefs(tableName: String): List<String> =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                """
                SELECT pg_get_constraintdef(c.oid)
                FROM pg_constraint c
                JOIN pg_class t ON c.conrelid = t.oid
                JOIN pg_namespace n ON t.relnamespace = n.oid
                WHERE n.nspname = 'public'
                  AND t.relname = ?
                  AND c.contype = 'c'
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, tableName)
                stmt.executeQuery().use { rs ->
                    val defs = mutableListOf<String>()
                    while (rs.next()) defs.add(rs.getString(1))
                    defs
                }
            }
        }

    // 테스트용 이슈 1건을 삽입하고 그 id 를 반환 (CHECK 제약 위반 INSERT 검증용 픽스처).
    // type_id 는 V005 에서 NOT NULL FK 라 'task' 표준 시드(V003)를 조회해 채운다.
    @Suppress("NestedBlockDepth")
    private fun insertIssueFixture(key: String): UUID =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "INSERT INTO projects (key, name) VALUES ('LKT', 'Link Test')" +
                    " ON CONFLICT (key) DO NOTHING",
            ).use { it.executeUpdate() }

            val projectId =
                conn.prepareStatement("SELECT id FROM projects WHERE key = 'LKT'").use { stmt ->
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getObject(1) as UUID
                    }
                }

            val taskTypeId =
                conn.prepareStatement("SELECT id FROM issue_types WHERE key = 'task'").use { stmt ->
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }

            conn.prepareStatement(
                "INSERT INTO issues (key, project_id, summary, reporter_id, current_state_key, type_id)" +
                    " VALUES (?, ?, 'fixture', ?, 'open', ?) RETURNING id",
            ).use { stmt ->
                stmt.setString(1, key)
                stmt.setObject(2, projectId)
                stmt.setObject(3, UUID.randomUUID())
                stmt.setLong(4, taskTypeId)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getObject(1) as UUID
                }
            }
        }

    // ── issue_links 테이블 / 컬럼 ───────────────────────────────────────────────

    @Test
    fun `V021 issue_links 테이블 존재`() {
        assertThat(tableExists("issue_links")).isTrue()
    }

    @Test
    fun `V021 issue_links 컬럼 id_source_id_target_id_link_type_created_at 존재`() {
        assertThat(columnDataType("issue_links", "id")).isNotNull()
        assertThat(columnDataType("issue_links", "source_id")).isEqualTo("uuid")
        assertThat(columnDataType("issue_links", "target_id")).isEqualTo("uuid")
        assertThat(columnDataType("issue_links", "link_type")).isEqualTo("character varying")
        assertThat(columnDataType("issue_links", "created_at")).isEqualTo("timestamp with time zone")
    }

    @Test
    fun `V021 issue_links source_id FK 는 issues 를 CASCADE 참조`() {
        assertThat(foreignKeyDeleteRule("issue_links", "source_id", "issues"))
            .isEqualTo("CASCADE")
    }

    @Test
    fun `V021 issue_links target_id FK 는 issues 를 CASCADE 참조`() {
        assertThat(foreignKeyDeleteRule("issue_links", "target_id", "issues"))
            .isEqualTo("CASCADE")
    }

    @Test
    fun `V021 issue_links UNIQUE (source_id, target_id, link_type) 존재`() {
        assertThat(hasUniqueConstraintOn("issue_links", setOf("source_id", "target_id", "link_type")))
            .isTrue()
    }

    @Test
    fun `V021 issue_links CHECK source_id 와 target_id 가 다름`() {
        val defs = checkConstraintDefs("issue_links")
        assertThat(defs).anyMatch { it.contains("source_id <> target_id") }
    }

    @Test
    fun `V021 issue_links CHECK link_type 는 4종으로 제한`() {
        val defs = checkConstraintDefs("issue_links")
        // pg_get_constraintdef 는 IN 절을 ANY (ARRAY[...]) 로 풀어서 표현하므로 4종 리터럴 포함 여부로 검증.
        assertThat(defs).anyMatch { def ->
            def.contains("link_type") &&
                def.contains("blocks") &&
                def.contains("relates") &&
                def.contains("duplicates") &&
                def.contains("clones")
        }
    }

    @Test
    fun `V021 issue_links FK 인덱스 idx_issue_links_source_id 존재`() {
        assertThat(indexExists("idx_issue_links_source_id")).isTrue()
    }

    @Test
    fun `V021 issue_links FK 인덱스 idx_issue_links_target_id 존재`() {
        assertThat(indexExists("idx_issue_links_target_id")).isTrue()
    }

    @Test
    fun `V021 issue_links 자기 링크 INSERT 는 CHECK 제약으로 거부`() {
        val issueId = insertIssueFixture("LKT-1")
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            assertThatThrownBy {
                conn.prepareStatement(
                    "INSERT INTO issue_links (source_id, target_id, link_type) VALUES (?, ?, 'blocks')",
                ).use { stmt ->
                    stmt.setObject(1, issueId)
                    stmt.setObject(2, issueId)
                    stmt.executeUpdate()
                }
            }.isInstanceOf(SQLException::class.java)
        }
    }

    @Test
    fun `V021 issue_links 미정의 link_type INSERT 는 CHECK 제약으로 거부`() {
        val source = insertIssueFixture("LKT-2")
        val target = insertIssueFixture("LKT-3")
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            assertThatThrownBy {
                conn.prepareStatement(
                    "INSERT INTO issue_links (source_id, target_id, link_type) VALUES (?, ?, 'depends')",
                ).use { stmt ->
                    stmt.setObject(1, source)
                    stmt.setObject(2, target)
                    stmt.executeUpdate()
                }
            }.isInstanceOf(SQLException::class.java)
        }
    }

    // ── issues.parent_id 컬럼 ───────────────────────────────────────────────────

    @Test
    fun `V021 issues_parent_id 컬럼은 UUID 이고 nullable`() {
        assertThat(columnDataType("issues", "parent_id")).isEqualTo("uuid")
        assertThat(columnIsNullable("issues", "parent_id")).isTrue()
    }

    @Test
    fun `V021 issues_parent_id FK 는 issues 를 참조`() {
        // parent-child 는 구조적 계층 — 부모 삭제 시 자동 cascade 하지 않음(NO ACTION 기본).
        assertThat(foreignKeyDeleteRule("issues", "parent_id", "issues"))
            .isEqualTo("NO ACTION")
    }

    @Test
    fun `V021 issues_parent_id FK 인덱스 idx_issues_parent_id 존재`() {
        assertThat(indexExists("idx_issues_parent_id")).isTrue()
    }
}
