// 이슈 변경 이력 Repository 통합 테스트 — Testcontainers PostgreSQL + Flyway V018

package com.bts.issue.history

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.util.UUID

/**
 * [JdbcIssueChangeHistoryRepository] 통합 테스트 (FR-HS-01 Task 4).
 *
 * **Testcontainers 선택 이유.**
 * V002 마이그레이션이 pgmq 확장을 요구하므로 postgres:16-alpine 은 사용 불가.
 * quay.io/tembo/pg16-pgmq:latest 이미지를 사용하며, [IssueTestcontainersBase] 의
 * JVM singleton 패턴을 이 테스트에서도 적용한다. [IssueChangeHistorySchemaTest] 와 동일 결정.
 *
 * **검증 범위 (Task 4 명세).**
 * 1. record(group with N items) → findByIssue로 group 1행 + item N행 검증.
 * 2. from_value/to_value/from_label/to_label nullable 필드 정확히 저장·조회.
 * 3. 여러 그룹 record 후 findByIssue는 해당 issueId 그룹만 반환.
 * 4. 인터페이스에 update/delete 메서드가 없음 — append-only 컴파일 레벨 보장.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcIssueChangeHistoryRepositoryIntegrationTest {
    companion object {
        /**
         * JVM 단위 singleton PostgreSQL container.
         * quay.io/tembo/pg16-pgmq:latest — V002 pgmq 확장 요구.
         * [com.bts.issue.repository.IssueTestcontainersBase] 와 동일 이미지 선택 이유.
         */
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(
                DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                    .asCompatibleSubstituteFor("postgres"),
            )
                .withDatabaseName("bts_test")
                .withUsername("bts")
                .withPassword("bts_test")
                .apply { start() }
    }

    private lateinit var repository: IssueChangeHistoryRepository
    private var bootstrapped = false

    @BeforeAll
    fun bootstrap() {
        if (bootstrapped) return

        // Flyway — DB 스키마 버전 관리 도구. V001~V018 마이그레이션 전체 적용.
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .placeholderReplacement(false)
            .locations("classpath:db/migration/issue-tracking")
            .load()
            .migrate()

        val dataSource =
            DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)

        // NamedParameterJdbcTemplate — named parameter(:name)로 SQL 인젝션 방어하는 Spring JDBC 래퍼.
        val jdbc = NamedParameterJdbcTemplate(dataSource)
        repository = JdbcIssueChangeHistoryRepository(jdbc)

        bootstrapped = true
    }

    @BeforeEach
    fun cleanTables() {
        // 각 테스트 독립 실행을 위해 이력 테이블 정리. CASCADE로 item도 같이 삭제됨.
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DELETE FROM issue_change_item")
                stmt.execute("DELETE FROM issue_change_group")
            }
        }
    }

    // ── 기본 기록·조회 ─────────────────────────────────────────────────────────

    @Test
    fun `record 후 findByIssue로 그룹 1건과 items를 정확히 조회한다`() {
        val issueId = UUID.randomUUID()
        val actorId = UUID.randomUUID()
        val items =
            listOf(
                IssueChangeItem(
                    field = "status",
                    fromValue = "open",
                    toValue = "in_progress",
                    fromLabel = "열림",
                    toLabel = "진행 중",
                ),
                IssueChangeItem(
                    field = "priority",
                    fromValue = "LOW",
                    toValue = "HIGH",
                    fromLabel = "낮음",
                    toLabel = "높음",
                ),
            )
        val group =
            IssueChangeGroup(
                issueId = issueId,
                issueKey = "BTS-1",
                actorId = actorId,
                items = items,
            )

        repository.record(group)

        val found = repository.findByIssue(issueId)
        assertThat(found).hasSize(1)

        val foundGroup = found.first()
        assertThat(foundGroup.issueId).isEqualTo(issueId)
        assertThat(foundGroup.issueKey).isEqualTo("BTS-1")
        assertThat(foundGroup.actorId).isEqualTo(actorId)
        assertThat(foundGroup.createdAt).isNotNull()
        assertThat(foundGroup.items).hasSize(2)

        val statusItem = foundGroup.items.first { it.field == "status" }
        assertThat(statusItem.fromValue).isEqualTo("open")
        assertThat(statusItem.toValue).isEqualTo("in_progress")
        assertThat(statusItem.fromLabel).isEqualTo("열림")
        assertThat(statusItem.toLabel).isEqualTo("진행 중")

        val priorityItem = foundGroup.items.first { it.field == "priority" }
        assertThat(priorityItem.fromValue).isEqualTo("LOW")
        assertThat(priorityItem.toValue).isEqualTo("HIGH")
        assertThat(priorityItem.fromLabel).isEqualTo("낮음")
        assertThat(priorityItem.toLabel).isEqualTo("높음")
    }

    // ── nullable 값 검증 ────────────────────────────────────────────────────────

    @Test
    fun `nullable fromValue toValue fromLabel toLabel을 정확히 저장하고 조회한다`() {
        val issueId = UUID.randomUUID()
        val items =
            listOf(
                // assignee 필드: 값은 있지만 라벨 없음
                IssueChangeItem(
                    field = "assignee",
                    fromValue = null,
                    toValue = UUID.randomUUID().toString(),
                    fromLabel = null,
                    toLabel = null,
                ),
                // summary 필드: 값과 라벨 모두 null
                IssueChangeItem(
                    field = "summary",
                    fromValue = "이전 요약",
                    toValue = null,
                    fromLabel = null,
                    toLabel = null,
                ),
            )
        val group =
            IssueChangeGroup(
                issueId = issueId,
                issueKey = "BTS-2",
                // 시스템 자동 변경 — actorId null
                actorId = null,
                items = items,
            )

        repository.record(group)

        val found = repository.findByIssue(issueId)
        assertThat(found).hasSize(1)

        val foundGroup = found.first()
        assertThat(foundGroup.actorId).isNull()

        val assigneeItem = foundGroup.items.first { it.field == "assignee" }
        assertThat(assigneeItem.fromValue).isNull()
        assertThat(assigneeItem.toValue).isNotNull()
        assertThat(assigneeItem.fromLabel).isNull()
        assertThat(assigneeItem.toLabel).isNull()

        val summaryItem = foundGroup.items.first { it.field == "summary" }
        assertThat(summaryItem.fromValue).isEqualTo("이전 요약")
        assertThat(summaryItem.toValue).isNull()
        assertThat(summaryItem.fromLabel).isNull()
        assertThat(summaryItem.toLabel).isNull()
    }

    // ── 다건 기록 + 격리 검증 ──────────────────────────────────────────────────

    @Test
    fun `다른 issueId의 그룹은 findByIssue 결과에 포함되지 않는다`() {
        val issueId1 = UUID.randomUUID()
        val issueId2 = UUID.randomUUID()

        repository.record(
            IssueChangeGroup(
                issueId = issueId1,
                issueKey = "BTS-10",
                actorId = UUID.randomUUID(),
                items = listOf(IssueChangeItem("status", "open", "done", null, null)),
            ),
        )
        repository.record(
            IssueChangeGroup(
                issueId = issueId2,
                issueKey = "BTS-11",
                actorId = UUID.randomUUID(),
                items = listOf(IssueChangeItem("priority", "LOW", "HIGH", null, null)),
            ),
        )

        val result1 = repository.findByIssue(issueId1)
        assertThat(result1).hasSize(1)
        assertThat(result1.first().issueKey).isEqualTo("BTS-10")

        val result2 = repository.findByIssue(issueId2)
        assertThat(result2).hasSize(1)
        assertThat(result2.first().issueKey).isEqualTo("BTS-11")
    }

    // ── 빈 items 그룹 ─────────────────────────────────────────────────────────

    @Test
    fun `items가 빈 그룹을 record하면 findByIssue로 items 없는 그룹이 반환된다`() {
        val issueId = UUID.randomUUID()
        val group =
            IssueChangeGroup(
                issueId = issueId,
                issueKey = "BTS-99",
                actorId = null,
                items = emptyList(),
            )

        repository.record(group)

        val found = repository.findByIssue(issueId)
        assertThat(found).hasSize(1)
        assertThat(found.first().items).hasSize(0)
    }

    // ── 존재하지 않는 issueId ─────────────────────────────────────────────────

    @Test
    fun `존재하지 않는 issueId로 조회하면 빈 리스트를 반환한다`() {
        val result = repository.findByIssue(UUID.randomUUID())
        assertThat(result).hasSize(0)
    }

    // ── findByIssuePaged + countByIssue (FR-HS-02 Task B1) ───────────────────

    /**
     * 3개 그룹 시드 후 페이지 경계 검증.
     * limit=2 offset=0 → 2건 (가장 최신 2개).
     * limit=2 offset=2 → 1건 (나머지 1개).
     */
    @Test
    fun `findByIssuePaged는 limit과 offset을 정확히 적용하여 그룹을 반환한다`() {
        val issueId = UUID.randomUUID()
        val actorId = UUID.randomUUID()

        // 3개 그룹을 순서대로 기록 (created_at 순서를 보장하기 위해 1ms 간격)
        repeat(3) { index ->
            repository.record(
                IssueChangeGroup(
                    issueId = issueId,
                    issueKey = "BTS-PAGED-$index",
                    actorId = actorId,
                    items = listOf(
                        IssueChangeItem(
                            field = "status",
                            fromValue = "open",
                            toValue = "group$index",
                            fromLabel = null,
                            toLabel = null,
                        ),
                    ),
                ),
            )
            Thread.sleep(2)
        }

        // 첫 번째 페이지: limit=2 offset=0 → 2건 반환
        val page1 = repository.findByIssuePaged(issueId, limit = 2, offset = 0)
        assertThat(page1).hasSize(2)

        // 두 번째 페이지: limit=2 offset=2 → 1건 반환
        val page2 = repository.findByIssuePaged(issueId, limit = 2, offset = 2)
        assertThat(page2).hasSize(1)

        // 전체 3건이 분리되어 조회되어야 한다 (중복 없음)
        val allKeys = (page1 + page2).map { it.issueKey }.toSet()
        assertThat(allKeys).hasSize(3)
    }

    @Test
    fun `findByIssuePaged는 created_at DESC id DESC 순으로 반환한다`() {
        val issueId = UUID.randomUUID()

        repeat(3) { index ->
            repository.record(
                IssueChangeGroup(
                    issueId = issueId,
                    issueKey = "BTS-ORDER-$index",
                    actorId = null,
                    items = emptyList(),
                ),
            )
            Thread.sleep(2)
        }

        val page = repository.findByIssuePaged(issueId, limit = 3, offset = 0)
        assertThat(page).hasSize(3)

        // created_at DESC 순 → 마지막으로 기록된 그룹이 첫 번째
        assertThat(page[0].issueKey).isEqualTo("BTS-ORDER-2")
        assertThat(page[1].issueKey).isEqualTo("BTS-ORDER-1")
        assertThat(page[2].issueKey).isEqualTo("BTS-ORDER-0")
    }

    @Test
    fun `findByIssuePaged는 그룹에 속한 items를 정확히 매핑한다`() {
        val issueId = UUID.randomUUID()
        val actorId = UUID.randomUUID()

        val items = listOf(
            IssueChangeItem(field = "status", fromValue = "open", toValue = "done", fromLabel = "열림", toLabel = "완료"),
            IssueChangeItem(field = "priority", fromValue = "LOW", toValue = "HIGH", fromLabel = null, toLabel = null),
        )
        repository.record(
            IssueChangeGroup(
                issueId = issueId,
                issueKey = "BTS-ITEMS",
                actorId = actorId,
                items = items,
            ),
        )

        val page = repository.findByIssuePaged(issueId, limit = 1, offset = 0)
        assertThat(page).hasSize(1)

        val group = page.first()
        assertThat(group.issueId).isEqualTo(issueId)
        assertThat(group.actorId).isEqualTo(actorId)
        assertThat(group.items).hasSize(2)

        val statusItem = group.items.first { it.field == "status" }
        assertThat(statusItem.fromValue).isEqualTo("open")
        assertThat(statusItem.toValue).isEqualTo("done")
        assertThat(statusItem.fromLabel).isEqualTo("열림")
        assertThat(statusItem.toLabel).isEqualTo("완료")
    }

    @Test
    fun `findByIssuePaged offset이 총 그룹 수를 초과하면 빈 리스트를 반환한다`() {
        val issueId = UUID.randomUUID()
        repository.record(
            IssueChangeGroup(
                issueId = issueId,
                issueKey = "BTS-OFFSET-OVER",
                actorId = null,
                items = emptyList(),
            ),
        )

        val result = repository.findByIssuePaged(issueId, limit = 10, offset = 100)
        assertThat(result).isEmpty()
    }

    @Test
    fun `countByIssue는 해당 이슈의 변경 그룹 총 수를 반환한다`() {
        val issueId = UUID.randomUUID()
        val otherIssueId = UUID.randomUUID()

        repeat(3) { index ->
            repository.record(
                IssueChangeGroup(
                    issueId = issueId,
                    issueKey = "BTS-COUNT-$index",
                    actorId = null,
                    items = emptyList(),
                ),
            )
        }
        // 다른 이슈 그룹 — count에 포함되지 않아야 함
        repository.record(
            IssueChangeGroup(
                issueId = otherIssueId,
                issueKey = "BTS-OTHER",
                actorId = null,
                items = emptyList(),
            ),
        )

        assertThat(repository.countByIssue(issueId)).isEqualTo(3L)
        assertThat(repository.countByIssue(otherIssueId)).isEqualTo(1L)
    }

    @Test
    fun `countByIssue는 이력이 없으면 0을 반환한다`() {
        assertThat(repository.countByIssue(UUID.randomUUID())).isEqualTo(0L)
    }
}
