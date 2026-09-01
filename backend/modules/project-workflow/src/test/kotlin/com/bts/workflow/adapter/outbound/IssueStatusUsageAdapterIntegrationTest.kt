// IssueStatusUsageAdapter 통합 테스트 — 프로젝트 스코프 · 빈 스코프 · 소프트 삭제 판정

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
import java.util.UUID

/**
 * [IssueStatusUsageAdapter] Testcontainers 통합 테스트.
 *
 * ### 왜 이 파일이 생겼나
 * 이 어댑터는 **원시 SQL 이 사는 유일한 자리**인데 종전에는 테스트가 0건이었다. 상태 키가 전역이라
 * `current_state_key` 만으로 세면 다른 프로젝트·다른 워크플로우를 쓰는 이슈까지 잡혀 발행이 과하게
 * 막혔고, 그 사실을 잡아 줄 판정이 저장소 어디에도 없었다.
 *
 * ### 세 가지를 잰다
 * 1. **프로젝트 스코프** — 같은 상태 키를 쓰는 남의 프로젝트 이슈는 세지 않는다
 * 2. **빈 스코프는 0** — 빈 `IN` 을 「전체」로 흘리면 fail-open 이다. 스킴이 아직 안 붙은
 *    워크플로우가 **남의 이슈 때문에** 발행을 못 하게 된다
 * 3. **소프트 삭제 제외** — 기존 계약이라 회귀를 막는다
 *
 * ### 마이그레이션은 issue-tracking 만 적용한다
 * 어댑터가 읽는 것은 `issues` 하나다. project-workflow 쪽 스키마는 이 판정에 관여하지 않으므로
 * 함께 올리면 무엇이 판정의 입력인지가 흐려진다. pgmq 확장(V002)이 필요해 tembo 이미지를 쓴다.
 *
 * 참조. FR-WF-07 D4 · `V001__issues_initial.sql`
 */
@Testcontainers
class IssueStatusUsageAdapterIntegrationTest {
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

        /** 활성 이슈가 알파 2건 · 베타 1건 달린 상태 키. */
        private const val SHARED_KEY = "scope-open"

        /** 알파에 **소프트 삭제된** 이슈 1건만 달린 상태 키. */
        private const val DELETED_ONLY_KEY = "scope-gone"

        lateinit var adapter: IssueStatusUsageAdapter

        /** 판정 대상 프로젝트. */
        lateinit var alpha: UUID

        /** 같은 상태 키를 쓰지만 스코프 밖인 프로젝트. 이것이 없으면 스코프 판정이 공허해진다. */
        lateinit var beta: UUID

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
                IssueStatusUsageAdapter(
                    DSL.using(
                        DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password),
                        SQLDialect.POSTGRES,
                    ),
                )

            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                alpha = insertProject(conn, "SCOPEA", "스코프 알파")
                beta = insertProject(conn, "SCOPEB", "스코프 베타")
                val typeId = taskTypeId(conn)

                insertIssue(conn, "SCOPEA-1", alpha, typeId, SHARED_KEY, deleted = false)
                insertIssue(conn, "SCOPEA-2", alpha, typeId, SHARED_KEY, deleted = false)
                insertIssue(conn, "SCOPEB-1", beta, typeId, SHARED_KEY, deleted = false)
                insertIssue(conn, "SCOPEA-3", alpha, typeId, DELETED_ONLY_KEY, deleted = true)
            }
        }

        private fun insertProject(
            conn: Connection,
            key: String,
            name: String,
        ): UUID {
            val id = UUID.randomUUID()
            conn.prepareStatement("INSERT INTO projects (id, key, name) VALUES (?, ?, ?)").use { ps ->
                ps.setObject(1, id)
                ps.setString(2, key)
                ps.setString(3, name)
                ps.executeUpdate()
            }
            return id
        }

        /** V003 이 심는 표준 타입 중 `task` 의 id. `issues.type_id` 가 NOT NULL FK 라 필요하다. */
        private fun taskTypeId(conn: Connection): Long =
            conn.prepareStatement("SELECT id FROM issue_types WHERE key = 'task'").use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }

        @Suppress("LongParameterList")
        private fun insertIssue(
            conn: Connection,
            key: String,
            projectId: UUID,
            typeId: Long,
            stateKey: String,
            deleted: Boolean,
        ) {
            conn.prepareStatement(
                "INSERT INTO issues" +
                    " (key, project_id, summary, reporter_id, current_state_key, type_id, deleted_at)" +
                    " VALUES (?, ?, ?, ?, ?, ?, ?)",
            ).use { ps ->
                ps.setString(1, key)
                ps.setObject(2, projectId)
                ps.setString(3, "사용량 판정용 $key")
                ps.setObject(4, UUID.fromString("00000000-0000-0000-0000-0000000000bb"))
                ps.setString(5, stateKey)
                ps.setLong(6, typeId)
                ps.setTimestamp(7, if (deleted) java.sql.Timestamp.from(java.time.Instant.EPOCH) else null)
                ps.executeUpdate()
            }
        }
    }

    @Test
    fun `같은 상태 키를 쓰는 타 프로젝트 이슈는 세지 않는다`() {
        // 베타에도 같은 상태 키의 활성 이슈가 있다. 스코프를 알파로 좁히면 그것이 빠져야 한다.
        assertThat(adapter.countIssuesInStatus(SHARED_KEY, setOf(alpha)))
            .describedAs("project_id 필터가 없으면 남의 프로젝트 이슈까지 세어 발행을 과하게 막는다")
            .isEqualTo(2L)

        // 베타의 이슈가 실재한다는 증거 — 없으면 위 단언이 「원래 2건뿐」으로 공허해진다.
        assertThat(adapter.countIssuesInStatus(SHARED_KEY, setOf(alpha, beta))).isEqualTo(3L)
    }

    @Test
    fun `projectIds 가 비면 0 을 돌려준다`() {
        // 빈 스코프를 「전체」로 흘리면 스킴 미할당 워크플로우가 남의 이슈 때문에 발행을 못 한다.
        assertThat(adapter.countIssuesInStatus(SHARED_KEY, emptySet()))
            .describedAs("빈 IN 을 전체로 흘리는 것은 fail-open 이다")
            .isZero()
    }

    @Test
    fun `소프트 삭제된 이슈는 세지 않는다`() {
        // 이 키에 달린 유일한 행은 deleted_at 이 채워져 있다 — 기존 계약의 회귀 방지다.
        assertThat(adapter.countIssuesInStatus(DELETED_ONLY_KEY, setOf(alpha))).isZero()
    }
}
