// V209(소유 프로젝트 + key 유니크 분리) 마이그레이션 검증 — 인덱스가 실제로 무는지를 INSERT 로 잰다

package com.bts.workflow.db

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
 * Flyway V209(`workflow_project_ownership`) 검증.
 *
 * ### ★「컬럼이 생겼다」는 이 마이그레이션을 검증하지 않는다
 * 이 마이그레이션의 값은 **부분 유니크 인덱스 넷의 조건**에 있다. 컬럼 존재·타입만 재는 테스트는
 * 인덱스 조건을 통째로 지워도 초록이다. 그래서 이 클래스는 같은 key 를
 * **전역 1건 + 서로 다른 프로젝트 2건 + 소프트 삭제 1건**으로 실제로 넣어 보고,
 * 어느 조합이 통과하고 어느 조합이 충돌하는지를 잰다.
 *
 * ### 특히 위험한 자리 — 전역끼리의 유일성
 * `UNIQUE (project_id, key)` 하나만 두면 Postgres 에서 NULL 은 서로 같지 않으므로
 * **전역 행끼리는 key 가 같아도 충돌하지 않는다.** 전역 key 는 `WorkflowKeyResolver`·스킴 매핑·
 * YAML 시드가 단독으로 참조하므로 그 순간 조용히 깨진다. `전역_같은_key_는_충돌한다` 가 그 자리다.
 *
 * ### 적용 순서 (load-bearing)
 * ```
 * ① target 200   issue-tracking V001~ + project-workflow V200
 * ② issue_types 수동 생성          V201 의 cross-BC FK 대상 (V208MigrationTest 와 동일 패턴)
 * ③ target latest V201 ~ V209
 * ```
 * 픽스처 선주입은 없다. V209 는 백필이 0행인 순수 add 마이그레이션이라 원본 데이터가 검증에
 * 기여하지 않는다 — 각 테스트가 필요한 행을 롤백 트랜잭션 안에서 직접 심는다(V208 관례).
 *
 * ### 성공 INSERT 는 반드시 롤백한다
 * 메인 체인 DB 를 오염시키면 뒤따르는 검증이 실행 순서에 좌우된다. JUnit 실행 순서는 보장되지
 * 않으므로 성공 경로도 [inRolledBackTransaction] 으로 격리한다(V207·V208 관례).
 *
 * 이미지는 `quay.io/tembo/pg16-pgmq:latest` — issue-tracking V004 가 pgmq 확장을 요구한다
 * (ADR 2026-05-22-pgmq-postgres-image).
 *
 * 참조. FR-WF-08 D3 · `docs/specs/2026-09-08-project-owned-workflows.md`
 */
@Testcontainers
class V209MigrationTest {
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

        @BeforeAll
        @JvmStatic
        fun applyMigrations() {
            flyway().target("200").load().migrate()
            createIssueTypes()
            flyway().load().migrate()
        }

        @JvmStatic
        private fun flyway() =
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations(
                    "classpath:db/migration/issue-tracking",
                    "classpath:db/migration/project-workflow",
                )

        /** V201 의 cross-BC FK 대상. issue-tracking 마이그레이션이 만들지 않는 경로라 테스트가 직접 만든다. */
        @JvmStatic
        fun createIssueTypes() {
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
        }

        private const val PROJECT_A = "11111111-1111-1111-1111-111111111111"
        private const val PROJECT_B = "22222222-2222-2222-2222-222222222222"
    }

    // ── 컬럼 ─────────────────────────────────────────────────────────────────

    @Test
    fun `두 테이블에 nullable UUID project_id 가 생긴다`() {
        listOf("workflows", "workflow_schemes").forEach { table ->
            assertThat(columnType(table, "project_id"))
                .describedAs("$table.project_id 타입")
                .isEqualTo("uuid")
            assertThat(isNullable(table, "project_id"))
                .describedAs("$table.project_id 는 nullable 이어야 한다 — NULL 이 전역을 뜻한다")
                .isTrue()
        }
    }

    @Test
    fun `기존 행은 전부 전역으로 남는다 — 마이그레이션이 데이터를 옮기지 않는다`() {
        // V201 이 시드한 표준 스킴 4종은 전역이어야 한다. 하나라도 프로젝트 소유가 되면
        // 그 스킴은 다른 프로젝트에서 보이지 않게 되어 기존 배정이 끊긴다.
        assertThat(count("SELECT COUNT(*) FROM workflow_schemes WHERE project_id IS NOT NULL"))
            .describedAs("마이그레이션 직후 프로젝트 소유 스킴은 0건이어야 한다")
            .isZero()
        assertThat(count("SELECT COUNT(*) FROM workflows WHERE project_id IS NOT NULL"))
            .describedAs("마이그레이션 직후 프로젝트 소유 워크플로우는 0건이어야 한다")
            .isZero()
        assertThat(count("SELECT COUNT(*) FROM workflow_schemes WHERE is_default = TRUE AND project_id IS NULL"))
            .describedAs("표준 스킴 4종이 전역으로 남아 있어야 한다")
            .isEqualTo(4)
    }

    // ── workflows — 인덱스가 실제로 무는지 ────────────────────────────────────

    @Test
    fun `workflows - 전역 같은 key 는 충돌한다`() {
        // ★NULL 은 서로 같지 않으므로 (project_id, key) 유니크 하나만으로는 이 충돌이 나지 않는다.
        // 전역 key 는 WorkflowKeyResolver·스킴 매핑·YAML 시드가 단독 참조하므로 유일해야 한다.
        val message =
            statementFailsWith(
                "INSERT INTO workflows (key, name, project_id) VALUES " +
                    "('dup-global', '전역 1', NULL), ('dup-global', '전역 2', NULL)",
            )
        assertThat(message).contains("uq_workflows_key_global")
    }

    @Test
    fun `workflows - 서로 다른 프로젝트는 같은 key 를 쓸 수 있다`() {
        inRolledBackTransaction { conn ->
            conn.createStatement().use {
                it.execute(
                    "INSERT INTO workflows (key, name, project_id) VALUES " +
                        "('my-flow', 'A 팀', '$PROJECT_A'), ('my-flow', 'B 팀', '$PROJECT_B')",
                )
            }
            assertThat(countIn(conn, "SELECT COUNT(*) FROM workflows WHERE key = 'my-flow'"))
                .describedAs("팀마다 같은 이름의 워크플로우를 가질 수 있어야 한다")
                .isEqualTo(2)
        }
    }

    @Test
    fun `workflows - 같은 프로젝트 안의 같은 key 는 충돌한다`() {
        val message =
            statementFailsWith(
                "INSERT INTO workflows (key, name, project_id) VALUES " +
                    "('same-proj', '첫째', '$PROJECT_A'), ('same-proj', '둘째', '$PROJECT_A')",
            )
        assertThat(message).contains("uq_workflows_key_project")
    }

    @Test
    fun `workflows - 전역과 프로젝트는 같은 key 를 동시에 가질 수 있다`() {
        // 전역 템플릿을 복제해 프로젝트 소유 사본을 만드는 것이 주 사용 경로다
        // (Atlassian 권장 우회). 키를 바꾸도록 강제하면 그 경로가 어색해진다.
        inRolledBackTransaction { conn ->
            conn.createStatement().use {
                it.execute(
                    "INSERT INTO workflows (key, name, project_id) VALUES " +
                        "('shared-key', '전역 템플릿', NULL), ('shared-key', 'A 팀 사본', '$PROJECT_A')",
                )
            }
            assertThat(countIn(conn, "SELECT COUNT(*) FROM workflows WHERE key = 'shared-key'"))
                .isEqualTo(2)
        }
    }

    @Test
    fun `workflows - 소프트 삭제된 행은 key 를 점유하지 않는다`() {
        // V206 이 연 성질이다. V209 가 인덱스를 다시 쓰면서 이 조건을 떨어뜨리면
        // 「지웠다 다시 만들기」가 다시 막힌다 — 편집기에서 일상 조작이다.
        inRolledBackTransaction { conn ->
            conn.createStatement().use {
                it.execute(
                    "INSERT INTO workflows (key, name, project_id, deleted_at) VALUES " +
                        "('revived', '삭제된 것', '$PROJECT_A', NOW())",
                )
                it.execute(
                    "INSERT INTO workflows (key, name, project_id) VALUES " +
                        "('revived', '되살린 것', '$PROJECT_A')",
                )
            }
            assertThat(countIn(conn, "SELECT COUNT(*) FROM workflows WHERE key = 'revived'"))
                .describedAs("소프트 삭제된 행과 같은 key 로 재생성이 가능해야 한다")
                .isEqualTo(2)
        }
    }

    // ── workflow_schemes — 동형 + V206 에서 빠졌던 소프트 삭제 정합 ─────────────

    @Test
    fun `workflow_schemes - 전역 같은 key 는 충돌한다`() {
        val message =
            statementFailsWith(
                "INSERT INTO workflow_schemes (key, name, project_id) VALUES " +
                    "('dup-scheme', '전역 1', NULL), ('dup-scheme', '전역 2', NULL)",
            )
        assertThat(message).contains("uq_workflow_schemes_key_global")
    }

    @Test
    fun `workflow_schemes - 서로 다른 프로젝트는 같은 key 를 쓸 수 있다`() {
        inRolledBackTransaction { conn ->
            conn.createStatement().use {
                it.execute(
                    "INSERT INTO workflow_schemes (key, name, project_id) VALUES " +
                        "('team-scheme', 'A 팀', '$PROJECT_A'), ('team-scheme', 'B 팀', '$PROJECT_B')",
                )
            }
            assertThat(countIn(conn, "SELECT COUNT(*) FROM workflow_schemes WHERE key = 'team-scheme'"))
                .isEqualTo(2)
        }
    }

    @Test
    fun `workflow_schemes - 소프트 삭제된 스킴은 key 를 점유하지 않는다`() {
        // ★선재 결함을 닫은 자리다. V206 이 statuses·workflows 만 부분 유니크로 바꾸고
        // workflow_schemes 는 컬럼 UNIQUE(V201:25) 그대로 두어, 스킴을 지우면 같은 key 로
        // 재생성이 영영 불가능했다. 세 테이블 중 둘만 고쳐진 비대칭이었다.
        inRolledBackTransaction { conn ->
            conn.createStatement().use {
                it.execute(
                    "INSERT INTO workflow_schemes (key, name, deleted_at) VALUES " +
                        "('gone-scheme', '지운 스킴', NOW())",
                )
                it.execute(
                    "INSERT INTO workflow_schemes (key, name) VALUES ('gone-scheme', '되살린 스킴')",
                )
            }
            assertThat(countIn(conn, "SELECT COUNT(*) FROM workflow_schemes WHERE key = 'gone-scheme'"))
                .describedAs("V206 이 workflows 에 연 성질이 스킴에도 있어야 한다")
                .isEqualTo(2)
        }
    }

    @Test
    fun `workflow_schemes - 컬럼 UNIQUE 제약이 부분 인덱스로 교체됐다`() {
        assertThat(
            count(
                "SELECT COUNT(*) FROM information_schema.table_constraints" +
                    " WHERE table_name = 'workflow_schemes' AND constraint_name = 'workflow_schemes_key_key'",
            ),
        ).describedAs("무조건 UNIQUE 가 남아 있으면 프로젝트별 같은 key 가 막힌다")
            .isZero()
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private fun <T> query(
        sql: String,
        read: (java.sql.ResultSet) -> T,
    ): T =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(sql).use { rs ->
                    rs.next()
                    read(rs)
                }
            }
        }

    private fun count(sql: String): Int = query(sql) { it.getInt(1) }

    private fun countIn(
        conn: Connection,
        sql: String,
    ): Int =
        conn.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }

    private fun columnType(
        tableName: String,
        columnName: String,
    ): String =
        query(
            "SELECT data_type FROM information_schema.columns" +
                " WHERE table_schema = 'public' AND table_name = '$tableName'" +
                " AND column_name = '$columnName'",
        ) { it.getString(1) ?: "" }

    private fun isNullable(
        tableName: String,
        columnName: String,
    ): Boolean =
        query(
            "SELECT is_nullable FROM information_schema.columns" +
                " WHERE table_schema = 'public' AND table_name = '$tableName'" +
                " AND column_name = '$columnName'",
        ) { it.getString(1) == "YES" }

    /**
     * SQL 을 트랜잭션 안에서 실행한 뒤 **반드시 롤백**한다.
     *
     * 성공 경로 검증이 메인 체인 DB 를 오염시키면 뒤따르는 검증이 실행 순서에 따라 무너진다.
     */
    private fun <T> inRolledBackTransaction(work: (Connection) -> T): T =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.autoCommit = false
            try {
                work(conn)
            } finally {
                conn.rollback()
            }
        }

    /** 문장이 실패하기를 기대하고 그 메시지를 돌려준다. 성공하면 테스트를 실패시킨다. */
    private fun statementFailsWith(sql: String): String {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.autoCommit = false
            try {
                conn.createStatement().use { it.execute(sql) }
            } catch (ex: java.sql.SQLException) {
                return ex.message ?: ""
            } finally {
                conn.rollback()
            }
        }
        error("문장이 실패해야 하는데 성공했다: $sql")
    }
}
