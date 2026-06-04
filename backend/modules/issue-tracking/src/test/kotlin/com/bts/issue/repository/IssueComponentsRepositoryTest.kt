// IssueComponentsRepositoryTest — replaceComponents / findActiveComponentIdsByIssue Testcontainers 통합 테스트.

package com.bts.issue.repository

import com.bts.issue.component.domain.Component
import com.bts.issue.component.repository.ComponentRepository
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.shared.issue.IssueTypeId
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.sql.DriverManager
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * IssueRepository.replaceComponents / findActiveComponentIdsByIssue 통합 테스트.
 *
 * [IssueTestcontainersBase] 의 JVM singleton PostgreSQL 컨테이너를 재사용한다.
 * Spring ApplicationContext 없이 DSLContext 를 직접 조합한다.
 *
 * 테스트 시나리오 (FR-CM-02 Task 3).
 * - T3-A. replaceComponents — 신규 목록 삽입 + issues.version bump.
 * - T3-B. replaceComponents — 기존 행 전체 삭제 후 교체 (집합 교체).
 * - T3-C. replaceComponents — stale version 시 0 반환, DB 불변.
 * - T3-D. replaceComponents — 빈 목록 전달 시 issue_components 전부 삭제.
 * - T3-E. findActiveComponentIdsByIssue — 소프트삭제 컴포넌트 제외.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class IssueComponentsRepositoryTest {
    private lateinit var repository: IssueRepository
    private lateinit var componentRepository: ComponentRepository
    private lateinit var testProjectId: UUID

    /** V003 seed task 타입 id. value class 는 lateinit 불가 → nullable var. */
    private var taskTypeId: IssueTypeId? = null

    /**
     * JVM 당 1회 실행 — Flyway migrate(멱등) + DSLContext 초기화 + TPRJ upsert + task 타입 id 조회.
     *
     * [IssueTestcontainersBase.postgres] JVM singleton 컨테이너를 재사용한다.
     */
    @BeforeAll
    fun setup() {
        val postgres = IssueTestcontainersBase.postgres

        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .placeholderReplacement(false)
            .locations("classpath:db/migration/issue-tracking")
            .load()
            .migrate()

        val dataSource =
            org.springframework.jdbc.datasource.DriverManagerDataSource(
                postgres.jdbcUrl,
                postgres.username,
                postgres.password,
            )
        val dsl = DSL.using(dataSource, SQLDialect.POSTGRES)
        repository = IssueRepository(dsl)
        componentRepository = ComponentRepository(dsl)

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "INSERT INTO projects (key, name) VALUES ('TPRJ', 'Test Project') ON CONFLICT (key) DO NOTHING",
            ).use { it.executeUpdate() }

            conn.prepareStatement("SELECT id FROM projects WHERE key = 'TPRJ'").use { stmt ->
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "TPRJ 프로젝트를 찾을 수 없습니다." }
                    testProjectId = rs.getObject(1) as UUID
                }
            }
        }

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1")
                .use { stmt ->
                    stmt.executeQuery().use { rs ->
                        check(rs.next()) { "V003 마이그레이션에서 task 타입이 없습니다." }
                        taskTypeId = IssueTypeId(rs.getLong(1))
                    }
                }
        }
    }

    /** 각 테스트 전 issues / issue_components 를 초기화한다. components 는 테스트 내부에서 관리. */
    @BeforeEach
    fun cleanIssues() {
        val postgres = IssueTestcontainersBase.postgres
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DELETE FROM issue_components")
                stmt.execute("DELETE FROM issues")
                stmt.execute("DELETE FROM components WHERE project_id = '$testProjectId'")
                stmt.execute("UPDATE projects SET key_sequence = 0 WHERE key = 'TPRJ'")
            }
        }
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private fun requireTaskTypeId(): IssueTypeId {
        return requireNotNull(taskTypeId) { "taskTypeId 가 초기화되지 않았습니다 — setup 실행 확인" }
    }

    /** 이슈 1건을 삽입하고 반환한다. version=1 로 시작. */
    private fun insertIssue(key: IssueKey): Issue =
        repository.insert(
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = key,
                projectId = testProjectId,
                typeId = requireTaskTypeId(),
                summary = "컴포넌트 교체 테스트 이슈",
                reporterId = ActorId(UUID.randomUUID()),
                currentStateKey = "open",
            ),
        )

    /** 컴포넌트 1건을 삽입하고 id를 반환한다. */
    private fun insertComponent(name: String): UUID {
        val component =
            componentRepository.insert(
                Component(
                    id = null,
                    projectId = testProjectId,
                    name = name,
                    description = null,
                    leadUserId = null,
                    deletedAt = null,
                ),
            )
        return requireNotNull(component.id) { "component.id must not be null after insert" }
    }

    /** issue_components 테이블에서 issueId 로 component_id 목록을 직접 조회한다. */
    @Suppress("NestedBlockDepth") // JDBC use{} 중첩 — 리소스 관리를 위한 불가피한 구조
    private fun rawComponentIds(issueId: UUID): Set<UUID> {
        val postgres = IssueTestcontainersBase.postgres
        return DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("SELECT component_id FROM issue_components WHERE issue_id = ?").use { stmt ->
                stmt.setObject(1, issueId)
                stmt.executeQuery().use { rs ->
                    val result = mutableSetOf<UUID>()
                    while (rs.next()) result.add(rs.getObject(1) as UUID)
                    result
                }
            }
        }
    }

    /** issues.version 을 직접 조회한다. */
    private fun rawVersion(issueId: UUID): Long {
        val postgres = IssueTestcontainersBase.postgres
        return DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("SELECT version FROM issues WHERE id = ?").use { stmt ->
                stmt.setObject(1, issueId)
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "이슈를 찾을 수 없습니다 id=$issueId" }
                    rs.getLong(1)
                }
            }
        }
    }

    // ── T3-A. replaceComponents — 신규 목록 삽입 + version bump ──────────────

    /**
     * Given  이슈 version=1, issue_components 비어 있음
     * When   replaceComponents([C1, C2], expectedVersion=1)
     * Then   반환 1, issue_components 에 C1/C2 행 생성, issues.version=2.
     */
    @Test
    @Order(1)
    fun `T3-A - replaceComponents - 신규 목록 삽입 시 row 1 반환 및 version bump`() {
        val issue = insertIssue(IssueKey.of("TPRJ", 1L))
        val issueId = issue.id.value
        val c1 = insertComponent("Frontend")
        val c2 = insertComponent("Backend")

        val result = repository.replaceComponents(issue.key, issueId, listOf(c1, c2), expectedVersion = 1L)

        assertThat(result).isEqualTo(1)
        assertThat(rawComponentIds(issueId)).containsExactlyInAnyOrder(c1, c2)
        assertThat(rawVersion(issueId)).isEqualTo(2L)
    }

    // ── T3-B. replaceComponents — 기존 행 삭제 후 교체 ───────────────────────

    /**
     * Given  이슈에 [C1, C2] 가 연결된 상태 (version=2)
     * When   replaceComponents([C2], expectedVersion=2)
     * Then   반환 1, issue_components 에 C2 만 남음, C1 행 삭제됨.
     */
    @Test
    @Order(2)
    fun `T3-B - replaceComponents - 기존 행 전체 삭제 후 교체`() {
        val issue = insertIssue(IssueKey.of("TPRJ", 1L))
        val issueId = issue.id.value
        val c1 = insertComponent("Frontend")
        val c2 = insertComponent("Backend")
        // 선행 상태 세팅: [C1, C2] 연결 (version 1→2)
        repository.replaceComponents(issue.key, issueId, listOf(c1, c2), expectedVersion = 1L)

        val result = repository.replaceComponents(issue.key, issueId, listOf(c2), expectedVersion = 2L)

        assertThat(result).isEqualTo(1)
        assertThat(rawComponentIds(issueId)).containsExactly(c2)
        assertThat(rawVersion(issueId)).isEqualTo(3L)
    }

    // ── T3-C. replaceComponents — stale version 시 0 반환 ────────────────────

    /**
     * Given  이슈 version=1
     * When   replaceComponents([C1], expectedVersion=99)
     * Then   반환 0, issue_components 비어 있음, version 변화 없음.
     */
    @Test
    @Order(3)
    fun `T3-C - replaceComponents - stale version 시 0 반환 및 DB 불변`() {
        val issue = insertIssue(IssueKey.of("TPRJ", 1L))
        val issueId = issue.id.value
        val c1 = insertComponent("Frontend")

        val result = repository.replaceComponents(issue.key, issueId, listOf(c1), expectedVersion = 99L)

        assertThat(result).isEqualTo(0)
        assertThat(rawComponentIds(issueId)).isEmpty()
        assertThat(rawVersion(issueId)).isEqualTo(1L)
    }

    // ── T3-D. replaceComponents — 빈 목록 시 전부 삭제 ──────────────────────

    /**
     * Given  이슈에 [C1] 이 연결된 상태 (version=2)
     * When   replaceComponents([], expectedVersion=2)
     * Then   반환 1, issue_components 비어 있음, version=3.
     */
    @Test
    @Order(4)
    fun `T3-D - replaceComponents - 빈 목록 전달 시 전부 삭제`() {
        val issue = insertIssue(IssueKey.of("TPRJ", 1L))
        val issueId = issue.id.value
        val c1 = insertComponent("Frontend")
        repository.replaceComponents(issue.key, issueId, listOf(c1), expectedVersion = 1L)

        val result = repository.replaceComponents(issue.key, issueId, emptyList(), expectedVersion = 2L)

        assertThat(result).isEqualTo(1)
        assertThat(rawComponentIds(issueId)).isEmpty()
        assertThat(rawVersion(issueId)).isEqualTo(3L)
    }

    // ── T3-E. findActiveComponentIdsByIssue — 소프트삭제 컴포넌트 제외 ────────

    /**
     * Given  이슈에 [C1, C2] 가 연결됨. C1 은 소프트삭제 (deleted_at IS NOT NULL).
     * When   findActiveComponentIdsByIssue(issueId)
     * Then   C2 만 반환됨 (C1 제외).
     */
    @Test
    @Order(5)
    fun `T3-E - findActiveComponentIdsByIssue - 소프트삭제 컴포넌트 제외`() {
        val issue = insertIssue(IssueKey.of("TPRJ", 1L))
        val issueId = issue.id.value
        val c1 = insertComponent("Deleted-Component")
        val c2 = insertComponent("Active-Component")
        repository.replaceComponents(issue.key, issueId, listOf(c1, c2), expectedVersion = 1L)

        // C1 소프트삭제
        val postgres = IssueTestcontainersBase.postgres
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("UPDATE components SET deleted_at = ? WHERE id = ?").use { stmt ->
                stmt.setObject(1, OffsetDateTime.now(ZoneOffset.UTC))
                stmt.setObject(2, c1)
                stmt.executeUpdate()
            }
        }

        val activeIds = repository.findActiveComponentIdsByIssue(issueId)

        assertThat(activeIds).containsExactlyInAnyOrder(c2)
    }
}
