// IssueVersionLinksRepositoryTest — replaceAffectsVersions / replaceFixVersions / 독립 읽기쿼리 Testcontainers 통합 테스트 (FR-VR-03 Task 3).

package com.bts.issue.repository

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
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
 * IssueRepository.replaceAffectsVersions / replaceFixVersions /
 * findAffectsVersionIdsByIssue / findFixVersionIdsByIssue 통합 테스트.
 *
 * [IssueTestcontainersBase] JVM singleton PostgreSQL 컨테이너를 재사용한다.
 * Spring ApplicationContext 없이 DSLContext 를 직접 조합한다.
 *
 * 테스트 시나리오 (FR-VR-03 Task 3).
 * - T3-A. replaceAffectsVersions — 신규 목록 삽입 + issues.version bump.
 * - T3-B. replaceAffectsVersions — 기존 행 전체 삭제 후 교체 (집합 교체).
 * - T3-C. replaceAffectsVersions — stale version 시 rows=0, DB 불변.
 * - T3-D. replaceAffectsVersions — 빈 목록 전달 시 issue_affects_versions 전부 삭제.
 * - T3-E. findAffectsVersionIdsByIssue — ARCHIVED 버전 링크도 반환(status 필터 없음).
 * - T3-F. replaceFixVersions — 신규 목록 삽입 + issues.version bump (fix 변형).
 * - T3-G. replaceFixVersions — stale version 시 rows=0 (fix 변형).
 * - T3-H. findFixVersionIdsByIssue — ARCHIVED 버전 링크도 반환(status 필터 없음).
 * - T3-I. affects / fix 독립 쿼리 — 같은 이슈에 affects / fix 각각 연결 시 교차 없음 (CONCERN-3 검증).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class IssueVersionLinksRepositoryTest : IssueTestcontainersBase() {

    /** V003 seed task 타입 id. value class 는 lateinit 불가 → nullable var. */
    private var taskTypeId: IssueTypeId? = null

    override fun configureFlyway(builder: org.flywaydb.core.api.configuration.FluentConfiguration) =
        super.configureFlyway(builder)

    // ── setup ─────────────────────────────────────────────────────────────────

    /**
     * 부모 [IssueTestcontainersBase.bootstrap] 가 JVM 당 1회 실행된다.
     * task 타입 id 는 여기서 조회한다.
     */
    @BeforeEach
    fun setupTaskType() {
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

    /** 각 테스트 전 issue_affects_versions / issue_fix_versions / versions 초기화. */
    @BeforeEach
    fun cleanVersionLinks() {
        val pg = IssueTestcontainersBase.postgres
        DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DELETE FROM issue_affects_versions")
                stmt.execute("DELETE FROM issue_fix_versions")
                stmt.execute("DELETE FROM versions WHERE project_id = '$testProjectId'")
            }
        }
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private fun requireTaskTypeId(): IssueTypeId =
        requireNotNull(taskTypeId) { "taskTypeId 가 초기화되지 않았습니다." }

    /** 이슈 1건을 삽입하고 반환한다. version=1 로 시작. */
    private fun insertIssue(key: IssueKey): Issue =
        repository.insert(
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = key,
                projectId = testProjectId,
                typeId = requireTaskTypeId(),
                summary = "버전 연결 테스트 이슈",
                reporterId = ActorId(UUID.randomUUID()),
                currentStateKey = "open",
            ),
        )

    /**
     * versions 테이블에 버전 1건을 삽입하고 UUID 를 반환한다.
     * [status] 를 ARCHIVED 로 지정하면 ARCHIVED 버전 링크 테스트에 활용한다.
     */
    private fun insertVersion(name: String, status: String = "UNRELEASED"): UUID {
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

    /** issue_affects_versions 테이블에서 issueId 로 version_id 목록을 직접 조회한다. */
    @Suppress("NestedBlockDepth")
    private fun rawAffectsVersionIds(issueId: UUID): Set<UUID> {
        val pg = IssueTestcontainersBase.postgres
        return DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { conn ->
            conn.prepareStatement("SELECT version_id FROM issue_affects_versions WHERE issue_id = ?").use { stmt ->
                stmt.setObject(1, issueId)
                stmt.executeQuery().use { rs ->
                    val result = mutableSetOf<UUID>()
                    while (rs.next()) result.add(rs.getObject(1) as UUID)
                    result
                }
            }
        }
    }

    /** issue_fix_versions 테이블에서 issueId 로 version_id 목록을 직접 조회한다. */
    @Suppress("NestedBlockDepth")
    private fun rawFixVersionIds(issueId: UUID): Set<UUID> {
        val pg = IssueTestcontainersBase.postgres
        return DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { conn ->
            conn.prepareStatement("SELECT version_id FROM issue_fix_versions WHERE issue_id = ?").use { stmt ->
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
    @Suppress("NestedBlockDepth")
    private fun rawVersion(issueId: UUID): Long {
        val pg = IssueTestcontainersBase.postgres
        return DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { conn ->
            conn.prepareStatement("SELECT version FROM issues WHERE id = ?").use { stmt ->
                stmt.setObject(1, issueId)
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "이슈를 찾을 수 없습니다 id=$issueId" }
                    rs.getLong(1)
                }
            }
        }
    }

    // ── T3-A. replaceAffectsVersions — 신규 목록 삽입 + version bump ──────────

    /**
     * Given  이슈 version=1, issue_affects_versions 비어 있음
     * When   replaceAffectsVersions([V1, V2], expectedVersion=1)
     * Then   반환 1, issue_affects_versions 에 V1/V2 행 생성, issues.version=2.
     */
    @Test
    @Order(1)
    fun `T3-A - replaceAffectsVersions - 신규 목록 삽입 시 rows=1 반환 및 version bump`() {
        val issue = insertIssue(IssueKey.of("TPRJ", 1L))
        val issueId = issue.id.value
        val v1 = insertVersion("v1.0.0")
        val v2 = insertVersion("v1.1.0")

        val result = repository.replaceAffectsVersions(issue.key, issueId, listOf(v1, v2), expectedVersion = 1L)

        assertThat(result).isEqualTo(1)
        assertThat(rawAffectsVersionIds(issueId)).containsExactlyInAnyOrder(v1, v2)
        assertThat(rawVersion(issueId)).isEqualTo(2L)
    }

    // ── T3-B. replaceAffectsVersions — 기존 행 삭제 후 교체 ──────────────────

    /**
     * Given  이슈에 [V1, V2] 가 연결된 상태 (version=2)
     * When   replaceAffectsVersions([V2], expectedVersion=2)
     * Then   반환 1, issue_affects_versions 에 V2 만 남음, V1 행 삭제됨.
     */
    @Test
    @Order(2)
    fun `T3-B - replaceAffectsVersions - 기존 행 전체 삭제 후 교체`() {
        val issue = insertIssue(IssueKey.of("TPRJ", 1L))
        val issueId = issue.id.value
        val v1 = insertVersion("v1.0.0")
        val v2 = insertVersion("v1.1.0")
        repository.replaceAffectsVersions(issue.key, issueId, listOf(v1, v2), expectedVersion = 1L)

        val result = repository.replaceAffectsVersions(issue.key, issueId, listOf(v2), expectedVersion = 2L)

        assertThat(result).isEqualTo(1)
        assertThat(rawAffectsVersionIds(issueId)).containsExactly(v2)
        assertThat(rawVersion(issueId)).isEqualTo(3L)
    }

    // ── T3-C. replaceAffectsVersions — stale version 시 rows=0 ───────────────

    /**
     * Given  이슈 version=1
     * When   replaceAffectsVersions([V1], expectedVersion=99)
     * Then   반환 0, issue_affects_versions 비어 있음, version 변화 없음.
     */
    @Test
    @Order(3)
    fun `T3-C - replaceAffectsVersions - stale version 시 rows=0 및 DB 불변`() {
        val issue = insertIssue(IssueKey.of("TPRJ", 1L))
        val issueId = issue.id.value
        val v1 = insertVersion("v1.0.0")

        val result = repository.replaceAffectsVersions(issue.key, issueId, listOf(v1), expectedVersion = 99L)

        assertThat(result).isEqualTo(0)
        assertThat(rawAffectsVersionIds(issueId)).isEmpty()
        assertThat(rawVersion(issueId)).isEqualTo(1L)
    }

    // ── T3-D. replaceAffectsVersions — 빈 목록 시 전부 삭제 ─────────────────

    /**
     * Given  이슈에 [V1] 이 연결된 상태 (version=2)
     * When   replaceAffectsVersions([], expectedVersion=2)
     * Then   반환 1, issue_affects_versions 비어 있음, version=3.
     */
    @Test
    @Order(4)
    fun `T3-D - replaceAffectsVersions - 빈 목록 전달 시 전부 삭제`() {
        val issue = insertIssue(IssueKey.of("TPRJ", 1L))
        val issueId = issue.id.value
        val v1 = insertVersion("v1.0.0")
        repository.replaceAffectsVersions(issue.key, issueId, listOf(v1), expectedVersion = 1L)

        val result = repository.replaceAffectsVersions(issue.key, issueId, emptyList(), expectedVersion = 2L)

        assertThat(result).isEqualTo(1)
        assertThat(rawAffectsVersionIds(issueId)).isEmpty()
        assertThat(rawVersion(issueId)).isEqualTo(3L)
    }

    // ── T3-E. findAffectsVersionIdsByIssue — ARCHIVED 버전도 반환 ─────────────

    /**
     * CONCERN-4 검증: status 필터 없음 — ARCHIVED 버전 링크도 그대로 반환해야 한다.
     *
     * Given  이슈에 [V_ARCHIVED, V_UNRELEASED] 가 연결됨
     * When   findAffectsVersionIdsByIssue(issueId)
     * Then   둘 다 반환됨 (ARCHIVED 제외 없음).
     */
    @Test
    @Order(5)
    fun `T3-E - findAffectsVersionIdsByIssue - ARCHIVED 버전 링크도 반환`() {
        val issue = insertIssue(IssueKey.of("TPRJ", 1L))
        val issueId = issue.id.value
        val vArchived = insertVersion("v0.9.0-archived", status = "ARCHIVED")
        val vActive = insertVersion("v1.0.0")
        repository.replaceAffectsVersions(issue.key, issueId, listOf(vArchived, vActive), expectedVersion = 1L)

        val result = repository.findAffectsVersionIdsByIssue(issueId)

        assertThat(result).containsExactlyInAnyOrder(vArchived, vActive)
    }

    // ── T3-F. replaceFixVersions — 신규 목록 삽입 + version bump ─────────────

    /**
     * Given  이슈 version=1, issue_fix_versions 비어 있음
     * When   replaceFixVersions([V1, V2], expectedVersion=1)
     * Then   반환 1, issue_fix_versions 에 V1/V2 행 생성, issues.version=2.
     */
    @Test
    @Order(6)
    fun `T3-F - replaceFixVersions - 신규 목록 삽입 시 rows=1 반환 및 version bump`() {
        val issue = insertIssue(IssueKey.of("TPRJ", 1L))
        val issueId = issue.id.value
        val v1 = insertVersion("v2.0.0")
        val v2 = insertVersion("v2.1.0")

        val result = repository.replaceFixVersions(issue.key, issueId, listOf(v1, v2), expectedVersion = 1L)

        assertThat(result).isEqualTo(1)
        assertThat(rawFixVersionIds(issueId)).containsExactlyInAnyOrder(v1, v2)
        assertThat(rawVersion(issueId)).isEqualTo(2L)
    }

    // ── T3-G. replaceFixVersions — stale version 시 rows=0 ───────────────────

    /**
     * Given  이슈 version=1
     * When   replaceFixVersions([V1], expectedVersion=99)
     * Then   반환 0, issue_fix_versions 비어 있음, version 변화 없음.
     */
    @Test
    @Order(7)
    fun `T3-G - replaceFixVersions - stale version 시 rows=0 및 DB 불변`() {
        val issue = insertIssue(IssueKey.of("TPRJ", 1L))
        val issueId = issue.id.value
        val v1 = insertVersion("v2.0.0")

        val result = repository.replaceFixVersions(issue.key, issueId, listOf(v1), expectedVersion = 99L)

        assertThat(result).isEqualTo(0)
        assertThat(rawFixVersionIds(issueId)).isEmpty()
        assertThat(rawVersion(issueId)).isEqualTo(1L)
    }

    // ── T3-H. findFixVersionIdsByIssue — ARCHIVED 버전도 반환 ────────────────

    /**
     * CONCERN-4 검증(fix 변형): status 필터 없음.
     *
     * Given  이슈에 [V_ARCHIVED, V_RELEASED] 가 fix 버전으로 연결됨
     * When   findFixVersionIdsByIssue(issueId)
     * Then   둘 다 반환됨 (ARCHIVED/RELEASED 제외 없음).
     */
    @Test
    @Order(8)
    fun `T3-H - findFixVersionIdsByIssue - ARCHIVED 버전 링크도 반환`() {
        val issue = insertIssue(IssueKey.of("TPRJ", 1L))
        val issueId = issue.id.value
        val vArchived = insertVersion("v0.9.0-fix-archived", status = "ARCHIVED")
        val vReleased = insertVersion("v1.0.0-released", status = "RELEASED")
        repository.replaceFixVersions(issue.key, issueId, listOf(vArchived, vReleased), expectedVersion = 1L)

        val result = repository.findFixVersionIdsByIssue(issueId)

        assertThat(result).containsExactlyInAnyOrder(vArchived, vReleased)
    }

    // ── T3-I. affects / fix 독립 쿼리 — 교차 없음 (CONCERN-3 검증) ─────────────

    /**
     * CONCERN-3 검증: affects / fix 를 동시에 LEFT JOIN 하면 곱집합(cartesian product)이 발생한다.
     * 독립 쿼리 2개로 분리하면 교차 없음을 확인한다.
     *
     * Given  이슈에 affects=[VA1, VA2], fix=[VF1] 이 각각 연결됨
     * When   findAffectsVersionIdsByIssue / findFixVersionIdsByIssue 독립 호출
     * Then   affects 는 [VA1, VA2], fix 는 [VF1] 만 반환 (교차 없음, 중복 없음).
     */
    @Test
    @Order(9)
    fun `T3-I - affects fix 독립 쿼리 - 교차 없음`() {
        val issue = insertIssue(IssueKey.of("TPRJ", 1L))
        val issueId = issue.id.value
        val va1 = insertVersion("vA1.0.0")
        val va2 = insertVersion("vA2.0.0")
        val vf1 = insertVersion("vF1.0.0")
        repository.replaceAffectsVersions(issue.key, issueId, listOf(va1, va2), expectedVersion = 1L)
        repository.replaceFixVersions(issue.key, issueId, listOf(vf1), expectedVersion = 2L)

        val affects = repository.findAffectsVersionIdsByIssue(issueId)
        val fix = repository.findFixVersionIdsByIssue(issueId)

        assertThat(affects).containsExactlyInAnyOrder(va1, va2)
        assertThat(fix).containsExactly(vf1)
        // 교차 없음: fix ID 가 affects 결과에 포함되지 않는다
        assertThat(affects).doesNotContain(vf1)
        // affects ID 가 fix 결과에 포함되지 않는다
        assertThat(fix.toSet()).doesNotContain(va1, va2)
    }
}
