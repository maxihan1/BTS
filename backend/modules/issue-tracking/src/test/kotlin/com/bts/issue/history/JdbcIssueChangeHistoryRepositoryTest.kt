// JdbcIssueChangeHistoryRepository cursor seek 통합 테스트 — FR-API-01 Task 5

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
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * [JdbcIssueChangeHistoryRepository.findByIssueCursor] keyset seek 통합 테스트 (FR-API-01 Task 5).
 *
 * **Testcontainers 선택 이유.**
 * 실제 PostgreSQL 에서 keyset seek (`(created_at < :c) OR (created_at = :c AND id < :id)`) 동작을
 * 검증해야 한다. H2 등 인메모리 DB 는 PostgreSQL TIMESTAMPTZ 비교·동률 처리를 정확히 재현하지 못한다.
 * 기존 [JdbcIssueChangeHistoryRepositoryIntegrationTest] 와 동일한 quay.io/tembo/pg16-pgmq:latest 사용.
 *
 * **검증 범위.**
 * 1. cursor null(첫 페이지) → 최신순 limit+1 건 반환.
 * 2. cursor 기준 seek → 해당 위치 이후만 반환, 중복·누락 0.
 * 3. created_at 동률 시 id DESC tie-break 보장.
 * 4. limit+1 조회로 hasNext 판정 가능.
 * 5. 다른 issueId 격리 — cursor seek 가 issueId 범위를 넘지 않음.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcIssueChangeHistoryRepositoryTest {
    companion object {
        /**
         * JVM 단위 singleton PostgreSQL container.
         * V002 pgmq 확장 요구로 quay.io/tembo/pg16-pgmq:latest 사용.
         * [JdbcIssueChangeHistoryRepositoryIntegrationTest] 와 동일 선택.
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

    private lateinit var repository: JdbcIssueChangeHistoryRepository
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

        val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        val jdbc = NamedParameterJdbcTemplate(dataSource)
        repository = JdbcIssueChangeHistoryRepository(jdbc)
        bootstrapped = true
    }

    @BeforeEach
    fun cleanTables() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DELETE FROM issue_change_item")
                stmt.execute("DELETE FROM issue_change_group")
            }
        }
    }

    // ── cursor null 첫 페이지 ───────────────────────────────────────────────────

    /**
     * cursor null(첫 페이지) → limit+1 건 반환으로 hasNext 판정 가능.
     *
     * 그룹 3건 삽입 후 limit=2 로 조회하면 3건(limit+1)이 반환되어야 한다.
     * 서비스 계층이 3 > 2 임을 보고 hasNext=true 로 판정한다.
     */
    @Test
    fun `findByIssueCursor cursor null 이면 최신순 limit+1 건 반환`() {
        val issueId = UUID.randomUUID()
        repeat(3) { i ->
            repository.record(
                IssueChangeGroup(issueId = issueId, issueKey = "BTS-FIRST-$i", actorId = null, items = emptyList()),
            )
            Thread.sleep(2)
        }

        // limit=2 → 내부적으로 limit+1=3 조회 → 3건 반환
        val result = repository.findByIssueCursor(issueId, null, null, 2)
        assertThat(result).hasSize(3)
    }

    /**
     * cursor null 이면 최신순(created_at DESC, id DESC) 정렬 보장.
     */
    @Test
    fun `findByIssueCursor cursor null 이면 created_at DESC 정렬 보장`() {
        val issueId = UUID.randomUUID()
        repeat(3) { i ->
            repository.record(
                IssueChangeGroup(issueId = issueId, issueKey = "BTS-ORDER-$i", actorId = null, items = emptyList()),
            )
            Thread.sleep(2)
        }

        val result = repository.findByIssueCursor(issueId, null, null, 10)
        assertThat(result).hasSize(3)
        // 마지막으로 삽입된 그룹(BTS-ORDER-2)이 첫 번째
        assertThat(result[0].second.issueKey).isEqualTo("BTS-ORDER-2")
        assertThat(result[1].second.issueKey).isEqualTo("BTS-ORDER-1")
        assertThat(result[2].second.issueKey).isEqualTo("BTS-ORDER-0")
    }

    // ── cursor seek — 중복·누락 없음 ────────────────────────────────────────────

    /**
     * cursor 기준 seek → 해당 위치 이후만 반환, 중복·누락 0.
     *
     * 그룹 3건 삽입 후 limit=2 첫 페이지를 조회한다.
     * 첫 페이지의 두 번째 행(= 마지막 표시 항목)을 cursor 로 사용해 두 번째 페이지를 조회하면
     * 남은 1건만 반환되어야 하고, 두 페이지 키 집합이 서로소여야 한다(중복 없음).
     */
    @Test
    fun `findByIssueCursor cursor seek 중복 없이 나머지 행 반환`() {
        val issueId = UUID.randomUUID()
        repeat(3) { i ->
            repository.record(
                IssueChangeGroup(issueId = issueId, issueKey = "BTS-SEEK-$i", actorId = null, items = emptyList()),
            )
            Thread.sleep(2)
        }

        // 첫 페이지: limit=2 → limit+1=3 반환 (hasNext 감지)
        val page1 = repository.findByIssueCursor(issueId, null, null, 2)
        assertThat(page1).hasSize(3)

        // limit=2 이므로 앞 2건을 표시, page1[1]이 마지막 표시 항목 → cursor 기준
        val (cursorGroupId, cursorGroup) = page1[1]
        val cursorCreatedAt =
            requireNotNull(cursorGroup.createdAt) { "createdAt must not be null after DB load" }

        // 두 번째 페이지: cursor 기준 seek
        val page2 = repository.findByIssueCursor(issueId, cursorCreatedAt, cursorGroupId, 2)
        assertThat(page2).hasSize(1) // 나머지 1건

        // 중복 없음: 두 페이지 issueKey 집합이 서로소
        val keys1 = page1.take(2).map { it.second.issueKey }.toSet()
        val keys2 = page2.map { it.second.issueKey }.toSet()
        assertThat(keys1.intersect(keys2)).isEmpty()

        // 누락 없음: 3건 모두 등장
        assertThat(keys1 + keys2)
            .containsExactlyInAnyOrder("BTS-SEEK-0", "BTS-SEEK-1", "BTS-SEEK-2")
    }

    // ── created_at 동률 — id DESC tie-break ─────────────────────────────────────

    /**
     * created_at 동률 시 id DESC tie-break 보장.
     *
     * 같은 created_at 을 가진 그룹 2건을 직접 SQL 삽입해 동률 상황을 만든다.
     * cursor seek 가 id DESC 로 정확히 정렬하고, cursor 기준 seek 도 동률 내에서 올바르게 작동해야 한다.
     */
    @Test
    fun `findByIssueCursor created_at 동률 id DESC tie-break 보장`() {
        val issueId = UUID.randomUUID()
        val sameTimestamp = Timestamp.from(Instant.parse("2026-01-01T10:00:00Z"))

        var groupId1 = 0L
        var groupId2 = 0L

        // 동일 created_at 을 강제하기 위해 SQL 직접 사용
        val insertSql =
            "INSERT INTO issue_change_group " +
                "(issue_id, issue_key, actor_id, created_at) VALUES (?, ?, null, ?) RETURNING id"
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(insertSql).use { stmt ->
                stmt.setObject(1, issueId)
                stmt.setString(2, "BTS-TIE-A")
                stmt.setTimestamp(3, sameTimestamp)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    groupId1 = rs.getLong(1)
                }
            }
            conn.prepareStatement(insertSql).use { stmt ->
                stmt.setObject(1, issueId)
                stmt.setString(2, "BTS-TIE-B")
                stmt.setTimestamp(3, sameTimestamp)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    groupId2 = rs.getLong(1)
                }
            }
        }

        // id DESC: groupId2 > groupId1 이므로 groupId2 가 먼저
        val all = repository.findByIssueCursor(issueId, null, null, 10)
        assertThat(all).hasSize(2)
        assertThat(all[0].first).isEqualTo(groupId2)
        assertThat(all[1].first).isEqualTo(groupId1)

        // cursor = (sameTimestamp, groupId2) 기준 seek → groupId1 만 반환
        val afterCursor =
            repository.findByIssueCursor(
                issueId,
                Instant.parse("2026-01-01T10:00:00Z"),
                groupId2,
                10,
            )
        assertThat(afterCursor).hasSize(1)
        assertThat(afterCursor[0].first).isEqualTo(groupId1)
    }

    // ── 다른 issueId 격리 ────────────────────────────────────────────────────────

    /**
     * findByIssueCursor 는 지정된 issueId 그룹만 반환한다.
     * 다른 issueId 그룹은 포함되지 않는다.
     */
    @Test
    fun `findByIssueCursor 다른 issueId 그룹 격리`() {
        val issueId1 = UUID.randomUUID()
        val issueId2 = UUID.randomUUID()

        repository.record(
            IssueChangeGroup(issueId = issueId1, issueKey = "BTS-ISO-1", actorId = null, items = emptyList()),
        )
        repository.record(
            IssueChangeGroup(issueId = issueId2, issueKey = "BTS-ISO-2", actorId = null, items = emptyList()),
        )

        val result = repository.findByIssueCursor(issueId1, null, null, 10)
        assertThat(result).hasSize(1)
        assertThat(result.first().second.issueKey).isEqualTo("BTS-ISO-1")
    }

    // ── cursor seek 시 items 함께 반환 ──────────────────────────────────────────

    /**
     * cursor seek 결과에 items 가 포함된다.
     * 기존 findByIssuePaged 와 동일한 2-step 조회 패턴(group+items 분리).
     */
    @Test
    fun `findByIssueCursor 결과에 items 가 포함된다`() {
        val issueId = UUID.randomUUID()
        val actorId = UUID.randomUUID()
        repository.record(
            IssueChangeGroup(
                issueId = issueId,
                issueKey = "BTS-ITEMS-CURSOR",
                actorId = actorId,
                items =
                    listOf(
                        IssueChangeItem(field = "status", fromValue = "open", toValue = "done"),
                        IssueChangeItem(field = "priority", fromValue = "LOW", toValue = "HIGH"),
                    ),
            ),
        )

        val result = repository.findByIssueCursor(issueId, null, null, 10)
        assertThat(result).hasSize(1)
        val (_, group) = result.first()
        assertThat(group.actorId).isEqualTo(actorId)
        assertThat(group.items).hasSize(2)
        assertThat(group.items.first { it.field == "status" }.toValue).isEqualTo("done")
    }
}
