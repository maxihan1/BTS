// IssueRepository.findFixVersionIssuesForReleaseNotes 통합 테스트 — FR-VR-04 Task 1

package com.bts.issue.repository

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.project.repository.ProjectLookupRepository
import com.bts.shared.issue.IssueTypeId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.sql.DriverManager
import java.util.UUID

/**
 * [IssueRepository.findFixVersionIssuesForReleaseNotes] 및
 * [ProjectLookupRepository.findProjectKeyById] 통합 테스트.
 *
 * [IssueTestcontainersBase] JVM singleton PostgreSQL 컨테이너를 재사용한다.
 * Spring ApplicationContext 없이 DSLContext 를 직접 조합한다.
 *
 * 테스트 시나리오 (FR-VR-04 Task 1).
 * - T1-A. findFixVersionIssuesForReleaseNotes — 해당 버전을 fix version 으로 가진 활성 이슈 반환.
 * - T1-B. findFixVersionIssuesForReleaseNotes — soft-delete 이슈는 제외된다.
 * - T1-C. findFixVersionIssuesForReleaseNotes — 다른 버전 이슈는 포함되지 않는다.
 * - T1-D. findFixVersionIssuesForReleaseNotes — 해당 버전의 fix version 이슈가 없으면 빈 리스트 반환.
 * - T1-E. findFixVersionIssuesForReleaseNotes — 이슈 타입 정보(typeId/typeKey/typeName/hierarchyLevel)가 포함된다.
 * - T1-F. findProjectKeyById — 활성 프로젝트 key 반환.
 * - T1-G. findProjectKeyById — 미존재 UUID 는 null 반환.
 * - T1-H. findProjectKeyById — soft-delete 된 프로젝트는 null 반환.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class IssueRepositoryReleaseNotesTest : IssueTestcontainersBase() {
    /** V003 seed task 타입 id. value class 는 lateinit 불가 → nullable var. */
    private var taskTypeId: IssueTypeId? = null
    private lateinit var projectLookupRepository: ProjectLookupRepository

    // ── setup ─────────────────────────────────────────────────────────────────

    /**
     * 각 테스트 전 task 타입 id 조회 + ProjectLookupRepository 초기화.
     * bootstrap() 이 먼저 실행되므로 dsl 이 준비된 상태에서 호출된다.
     */
    @BeforeEach
    fun setupRepositories() {
        projectLookupRepository = ProjectLookupRepository(dsl)

        if (taskTypeId != null) return
        DriverManager.getConnection(
            IssueTestcontainersBase.postgres.jdbcUrl,
            IssueTestcontainersBase.postgres.username,
            IssueTestcontainersBase.postgres.password,
        ).use { conn ->
            conn.prepareStatement("SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1")
                .use { stmt ->
                    stmt.executeQuery().use { rs ->
                        check(rs.next()) { "V003 마이그레이션에서 task 타입이 없습니다." }
                        taskTypeId = IssueTypeId(rs.getLong(1))
                    }
                }
        }
    }

    /** 각 테스트 전 versions / issue_fix_versions 초기화. */
    @BeforeEach
    fun cleanVersionLinks() {
        val pg = IssueTestcontainersBase.postgres
        DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DELETE FROM issue_fix_versions")
                stmt.execute("DELETE FROM issue_affects_versions")
                stmt.execute("DELETE FROM versions WHERE project_id = '$testProjectId'")
            }
        }
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private fun requireTaskTypeId(): IssueTypeId =
        requireNotNull(taskTypeId) { "taskTypeId 가 초기화되지 않았습니다." }

    /** 이슈 1건을 삽입하고 반환한다. version=1 로 시작. */
    private fun insertIssue(
        key: IssueKey,
        summary: String = "릴리즈 노트 테스트 이슈",
    ): Issue =
        repository.insert(
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = key,
                projectId = testProjectId,
                typeId = requireTaskTypeId(),
                summary = summary,
                reporterId = ActorId(UUID.randomUUID()),
                currentStateKey = "open",
            ),
        )

    /**
     * versions 테이블에 버전 1건을 삽입하고 UUID 를 반환한다.
     * [status] 는 UNRELEASED/RELEASED/ARCHIVED 중 하나.
     */
    private fun insertVersion(
        name: String,
        status: String = "UNRELEASED",
    ): UUID {
        val pg = IssueTestcontainersBase.postgres
        val id = UUID.randomUUID()
        DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { conn ->
            conn.prepareStatement(
                "INSERT INTO versions (id, project_id, name, status) VALUES (?, ?, ?, ?)",
            ).use { stmt ->
                stmt.setObject(1, id)
                stmt.setObject(2, testProjectId)
                stmt.setString(3, name)
                stmt.setString(4, status)
                stmt.executeUpdate()
            }
        }
        return id
    }

    /** issue_fix_versions 에 연결 1건을 삽입한다. */
    private fun linkFixVersion(
        issueId: UUID,
        versionId: UUID,
    ) {
        val pg = IssueTestcontainersBase.postgres
        DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { conn ->
            conn.prepareStatement(
                "INSERT INTO issue_fix_versions (issue_id, version_id) VALUES (?, ?)",
            ).use { stmt ->
                stmt.setObject(1, issueId)
                stmt.setObject(2, versionId)
                stmt.executeUpdate()
            }
        }
    }

    /** issues.deleted_at 를 NOW() 로 설정해 소프트 삭제한다. */
    private fun softDeleteIssue(issueId: UUID) {
        val pg = IssueTestcontainersBase.postgres
        DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { conn ->
            conn.prepareStatement("UPDATE issues SET deleted_at = NOW() WHERE id = ?").use { stmt ->
                stmt.setObject(1, issueId)
                stmt.executeUpdate()
            }
        }
    }

    // ── T1-A. 해당 버전 fix version 연결 이슈 반환 ──────────────────────────────

    /**
     * Given  이슈 2건이 같은 버전을 fix version 으로 가짐
     * When   findFixVersionIssuesForReleaseNotes(versionId)
     * Then   2건 반환, key / summary 포함.
     */
    @Test
    @Order(1)
    fun `T1-A - findFixVersionIssuesForReleaseNotes - 해당 버전의 fix version 이슈 2건 반환`() {
        val versionId = insertVersion("v1.0.0")
        val issue1 = insertIssue(IssueKey("TPRJ-1"), "첫 번째 이슈")
        val issue2 = insertIssue(IssueKey("TPRJ-2"), "두 번째 이슈")
        linkFixVersion(issue1.id.value, versionId)
        linkFixVersion(issue2.id.value, versionId)

        val result = repository.findFixVersionIssuesForReleaseNotes(versionId)

        assertThat(result).hasSize(2)
        val keys = result.map { it.key }
        assertThat(keys).containsExactlyInAnyOrder("TPRJ-1", "TPRJ-2")
        val summaries = result.map { it.summary }
        assertThat(summaries).containsExactlyInAnyOrder("첫 번째 이슈", "두 번째 이슈")
    }

    // ── T1-B. soft-delete 이슈 제외 ──────────────────────────────────────────

    /**
     * Given  이슈 2건 중 1건이 소프트 삭제됨
     * When   findFixVersionIssuesForReleaseNotes(versionId)
     * Then   활성 이슈 1건만 반환.
     */
    @Test
    @Order(2)
    fun `T1-B - findFixVersionIssuesForReleaseNotes - soft-delete 이슈 제외`() {
        val versionId = insertVersion("v1.0.0")
        val active = insertIssue(IssueKey("TPRJ-1"), "활성 이슈")
        val deleted = insertIssue(IssueKey("TPRJ-2"), "삭제 이슈")
        linkFixVersion(active.id.value, versionId)
        linkFixVersion(deleted.id.value, versionId)
        softDeleteIssue(deleted.id.value)

        val result = repository.findFixVersionIssuesForReleaseNotes(versionId)

        assertThat(result).hasSize(1)
        assertThat(result.first().key).isEqualTo("TPRJ-1")
    }

    // ── T1-C. 다른 버전 이슈 미포함 ──────────────────────────────────────────

    /**
     * Given  이슈가 다른 버전의 fix version 으로만 연결됨
     * When   findFixVersionIssuesForReleaseNotes(targetVersionId)
     * Then   해당 버전 연결 이슈만 반환(다른 버전 이슈 제외).
     */
    @Test
    @Order(3)
    fun `T1-C - findFixVersionIssuesForReleaseNotes - 다른 버전 이슈 미포함`() {
        val targetVersion = insertVersion("v1.0.0")
        val otherVersion = insertVersion("v2.0.0")
        val targetIssue = insertIssue(IssueKey("TPRJ-1"), "타겟 이슈")
        val otherIssue = insertIssue(IssueKey("TPRJ-2"), "다른 버전 이슈")
        linkFixVersion(targetIssue.id.value, targetVersion)
        linkFixVersion(otherIssue.id.value, otherVersion)

        val result = repository.findFixVersionIssuesForReleaseNotes(targetVersion)

        assertThat(result).hasSize(1)
        assertThat(result.first().key).isEqualTo("TPRJ-1")
    }

    // ── T1-D. 버전에 fix version 이슈 없으면 빈 리스트 ──────────────────────────

    /**
     * Given  버전이 존재하지만 fix version 연결 이슈가 없음
     * When   findFixVersionIssuesForReleaseNotes(versionId)
     * Then   빈 리스트 반환.
     */
    @Test
    @Order(4)
    fun `T1-D - findFixVersionIssuesForReleaseNotes - 연결 이슈 없으면 빈 리스트`() {
        val versionId = insertVersion("v1.0.0")

        val result = repository.findFixVersionIssuesForReleaseNotes(versionId)

        assertThat(result).isEmpty()
    }

    // ── T1-E. 타입 정보 포함 확인 ──────────────────────────────────────────────

    /**
     * Given  이슈 1건이 특정 버전의 fix version 으로 연결됨
     * When   findFixVersionIssuesForReleaseNotes(versionId)
     * Then   반환 row 에 typeId/typeKey/typeName/hierarchyLevel 이 포함된다.
     */
    @Test
    @Order(5)
    fun `T1-E - findFixVersionIssuesForReleaseNotes - 이슈 타입 정보가 포함된다`() {
        val versionId = insertVersion("v1.0.0")
        val issue = insertIssue(IssueKey("TPRJ-1"), "타입 정보 확인 이슈")
        linkFixVersion(issue.id.value, versionId)

        val result = repository.findFixVersionIssuesForReleaseNotes(versionId)

        assertThat(result).hasSize(1)
        val row = result.first()
        assertThat(row.typeId).isNotNull
        assertThat(row.typeKey).isNotBlank
        assertThat(row.typeName).isNotBlank
        // hierarchyLevel 은 0 이 기본값(task)
        assertThat(row.hierarchyLevel).isNotNull
    }

    // ── T1-F. findProjectKeyById — 활성 프로젝트 key 반환 ────────────────────

    /**
     * Given  testProjectId 가 TPRJ 로 삽입되어 있음(IssueTestcontainersBase.bootstrap)
     * When   findProjectKeyById(testProjectId)
     * Then   "TPRJ" 반환.
     */
    @Test
    @Order(6)
    fun `T1-F - findProjectKeyById - 활성 프로젝트 key 반환`() {
        val key = projectLookupRepository.findProjectKeyById(testProjectId)

        assertThat(key).isEqualTo("TPRJ")
    }

    // ── T1-G. findProjectKeyById — 미존재 UUID null 반환 ──────────────────────

    /**
     * Given  존재하지 않는 UUID
     * When   findProjectKeyById(unknownId)
     * Then   null 반환.
     */
    @Test
    @Order(7)
    fun `T1-G - findProjectKeyById - 미존재 UUID 는 null 반환`() {
        val unknownId = UUID.randomUUID()

        val key = projectLookupRepository.findProjectKeyById(unknownId)

        assertThat(key).isNull()
    }

    // ── T1-H. findProjectKeyById — soft-delete 프로젝트 null 반환 ──────────────

    /**
     * Given  soft-delete 된 프로젝트
     * When   findProjectKeyById(softDeletedProjectId)
     * Then   null 반환.
     */
    @Test
    @Order(8)
    fun `T1-H - findProjectKeyById - soft-delete 된 프로젝트 null 반환`() {
        val pg = IssueTestcontainersBase.postgres
        val deletedId = UUID.randomUUID()

        DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { conn ->
            conn.prepareStatement(
                "INSERT INTO projects (id, key, name, deleted_at) VALUES (?, ?, ?, NOW())",
            ).use { stmt ->
                stmt.setObject(1, deletedId)
                stmt.setString(2, "DPRJ")
                stmt.setString(3, "Deleted Project")
                stmt.executeUpdate()
            }
        }

        val key = projectLookupRepository.findProjectKeyById(deletedId)

        assertThat(key).isNull()

        // cleanup
        DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { conn ->
            conn.prepareStatement("DELETE FROM projects WHERE id = ?").use { stmt ->
                stmt.setObject(1, deletedId)
                stmt.executeUpdate()
            }
        }
    }
}
