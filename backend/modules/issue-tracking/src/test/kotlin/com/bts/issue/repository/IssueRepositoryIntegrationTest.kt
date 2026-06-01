// IssueRepository updateAssignee OCC 통합 테스트 — Task 6 (FR-IS-03)

package com.bts.issue.repository

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
import java.util.UUID

/**
 * IssueRepository.updateAssignee OCC UPDATE 통합 테스트.
 *
 * [IssueTestcontainersBase] 의 JVM singleton PostgreSQL 컨테이너를 재사용하되, 이 클래스는
 * 독립적으로 초기화를 수행하여 bootstrap 중복 실행에 따른 TPRJ INSERT 충돌을 방지한다.
 * [IssueTestcontainersBase.postgres] companion object 로 컨테이너에 접근한다.
 *
 * Spring ApplicationContext 없이 DSLContext 를 직접 조합한다.
 *
 * 테스트 시나리오.
 * - T6-A. updateAssignee — assigneeId 설정(UUID) + version+1 갱신 검증.
 * - T6-B. updateAssignee — assigneeId null(unassign) + version+1 갱신 검증.
 * - T6-C. updateAssignee OCC stale — expectedVersion 불일치 시 0 row 반환.
 * - T6-D. toIssue assigneeId 매핑 — insert 시 assigneeId 를 포함하면 findByKey 로 ActorId? 로 반환.
 * - T6-E. toIssue assigneeId null 매핑 — assigneeId 없는 이슈는 findByKey 결과 assigneeId=null.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class IssueRepositoryIntegrationTest {
    private lateinit var repository: IssueRepository
    private lateinit var testProjectId: UUID

    /**
     * V003 seed 에서 task 타입 id 를 DB 에서 직접 조회한다.
     * IssueTypeId 는 value class 이므로 lateinit 불가 — var + null 허용으로 초기화.
     */
    private var taskTypeId: IssueTypeId? = null

    /**
     * JVM 당 1회 실행 — Flyway migrate(멱등) + DSLContext 초기화 + TPRJ 프로젝트 upsert + task 타입 id 조회.
     *
     * [IssueTestcontainersBase.postgres] JVM singleton 컨테이너를 재사용한다.
     * TPRJ INSERT 는 `ON CONFLICT (key) DO NOTHING` 으로 중복 실행에 안전하다.
     */
    @BeforeAll
    fun setup() {
        val postgres = IssueTestcontainersBase.postgres

        // Flyway migrate (멱등 — 이미 최신이면 아무 것도 하지 않음)
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
        repository = IssueRepository(DSL.using(dataSource, SQLDialect.POSTGRES))

        // TPRJ 프로젝트 upsert — 다른 테스트 클래스가 먼저 삽입했어도 충돌 없이 id 조회
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

        // V003 seed 에서 task 타입 id 조회
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

    /** 각 테스트가 독립적으로 실행되도록 테스트마다 issues 행을 삭제하고 key_sequence 를 초기화한다. */
    @BeforeEach
    fun cleanIssues() {
        val postgres = IssueTestcontainersBase.postgres
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DELETE FROM issues")
                stmt.execute("UPDATE projects SET key_sequence = 0 WHERE key = 'TPRJ'")
            }
        }
    }

    /** resolveTaskTypeId 이후 항상 non-null 임을 보장하는 helper. */
    private fun requireTaskTypeId(): IssueTypeId =
        requireNotNull(taskTypeId) { "taskTypeId 가 초기화되지 않았습니다 — setup 실행 확인" }

    // ── T6-A. updateAssignee — assigneeId UUID 설정 ───────────────────────────────

    /**
     * Given  version=1 로 삽입된 이슈 (assigneeId=null)
     * When   updateAssignee(key, assigneeUUID, expectedVersion=1) 호출
     * Then   반환값 1, findByKey 로 조회 시 assigneeId = ActorId(assigneeUUID), version = 2.
     */
    @Test
    @Order(1)
    fun `T6-A - updateAssignee - assigneeId 설정 시 row 1 반환 및 version+1`() {
        val key = IssueKey.of("TPRJ", 1L)
        val assigneeUUID = UUID.randomUUID()
        val issue =
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = key,
                projectId = testProjectId,
                typeId = requireTaskTypeId(),
                summary = "updateAssignee 설정 테스트",
                reporterId = ActorId(UUID.randomUUID()),
                currentStateKey = "open",
            )
        repository.insert(issue)

        val updated = repository.updateAssignee(key, assigneeUUID, expectedVersion = 1L)

        assertThat(updated).isEqualTo(1)
        val found = requireNotNull(repository.findByKey(key)) { "이슈 조회 불가" }
        assertThat(found.version).isEqualTo(2L)
        assertThat(found.assigneeId).isEqualTo(ActorId(assigneeUUID))
    }

    // ── T6-B. updateAssignee — assigneeId null(unassign) ─────────────────────────

    /**
     * Given  assigneeId 가 설정된(version=1) 이슈
     * When   updateAssignee(key, null, expectedVersion=1) 로 담당자 해제
     * Then   반환값 1, findByKey 결과 assigneeId = null, version = 2.
     */
    @Test
    @Order(2)
    fun `T6-B - updateAssignee - assigneeId null unassign 시 row 1 반환 및 version+1`() {
        val key = IssueKey.of("TPRJ", 1L)
        val assigneeUUID = UUID.randomUUID()
        val issue =
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = key,
                projectId = testProjectId,
                typeId = requireTaskTypeId(),
                summary = "unassign 테스트",
                reporterId = ActorId(UUID.randomUUID()),
                currentStateKey = "open",
            )
        repository.insert(issue)
        // 먼저 assignee 설정
        repository.updateAssignee(key, assigneeUUID, expectedVersion = 1L)

        // 담당자 해제 (version 은 이제 2)
        val updated = repository.updateAssignee(key, null, expectedVersion = 2L)

        assertThat(updated).isEqualTo(1)
        val found = requireNotNull(repository.findByKey(key)) { "이슈 조회 불가" }
        assertThat(found.version).isEqualTo(3L)
        assertThat(found.assigneeId).isNull()
    }

    // ── T6-C. updateAssignee OCC stale — version 불일치 ──────────────────────────

    /**
     * Given  version=1 로 삽입된 이슈
     * When   updateAssignee(key, UUID, expectedVersion=99) 로 stale version 전달
     * Then   반환값 0 — 낙관락 충돌, DB 는 변경 없음.
     */
    @Test
    @Order(3)
    fun `T6-C - updateAssignee stale - version 불일치 시 0 반환`() {
        val key = IssueKey.of("TPRJ", 1L)
        val issue =
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = key,
                projectId = testProjectId,
                typeId = requireTaskTypeId(),
                summary = "OCC stale 테스트",
                reporterId = ActorId(UUID.randomUUID()),
                currentStateKey = "open",
            )
        repository.insert(issue)

        val updated = repository.updateAssignee(key, UUID.randomUUID(), expectedVersion = 99L)

        assertThat(updated).isEqualTo(0)
        val found = requireNotNull(repository.findByKey(key)) { "이슈 조회 불가" }
        assertThat(found.version).isEqualTo(1L)
        assertThat(found.assigneeId).isNull()
    }

    // ── T6-D. toIssue — assigneeId UUID → ActorId? 매핑 ─────────────────────────

    /**
     * Given  assigneeId 를 포함하여 이슈 insert (Issue.create 는 assigneeId 지원)
     * When   findByKey 로 조회
     * Then   반환된 Issue.assigneeId 가 ActorId(assigneeUUID) 로 정확히 매핑된다.
     */
    @Test
    @Order(4)
    fun `T6-D - toIssue assigneeId - insert 시 assigneeId 포함하면 findByKey 로 ActorId 반환`() {
        val key = IssueKey.of("TPRJ", 1L)
        val assigneeUUID = UUID.randomUUID()
        val issue =
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = key,
                projectId = testProjectId,
                typeId = requireTaskTypeId(),
                summary = "assigneeId round-trip 테스트",
                reporterId = ActorId(UUID.randomUUID()),
                currentStateKey = "open",
                assigneeId = ActorId(assigneeUUID),
            )
        repository.insert(issue)

        val found = requireNotNull(repository.findByKey(key)) { "이슈 조회 불가" }

        assertThat(found.assigneeId).isEqualTo(ActorId(assigneeUUID))
    }

    // ── T6-E. toIssue — assigneeId null 매핑 ────────────────────────────────────

    /**
     * Given  assigneeId 없이(null) 이슈 insert
     * When   findByKey 로 조회
     * Then   반환된 Issue.assigneeId 가 null.
     */
    @Test
    @Order(5)
    fun `T6-E - toIssue assigneeId null - assigneeId 없는 이슈는 findByKey 결과 null`() {
        val key = IssueKey.of("TPRJ", 1L)
        val issue =
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = key,
                projectId = testProjectId,
                typeId = requireTaskTypeId(),
                summary = "assigneeId null 매핑 테스트",
                reporterId = ActorId(UUID.randomUUID()),
                currentStateKey = "open",
            )
        repository.insert(issue)

        val found = requireNotNull(repository.findByKey(key)) { "이슈 조회 불가" }

        assertThat(found.assigneeId).isNull()
    }
}
