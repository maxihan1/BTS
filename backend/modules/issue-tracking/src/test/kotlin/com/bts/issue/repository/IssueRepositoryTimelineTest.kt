// IssueRepository 타임라인 조회(listVisibleForTimeline) 통합 테스트 — TL 계열 (FR-TL-01 Task 2)

package com.bts.issue.repository

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.permission.IssueSecurityAccess
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.sql.DriverManager
import java.time.LocalDate
import java.util.UUID

/**
 * IssueRepository.listVisibleForTimeline 통합 테스트 (FR-TL-01 Task 2).
 *
 * [IssueTestcontainersBase] JVM singleton PostgreSQL + Flyway 마이그레이션을 재사용한다.
 *
 * ## 테스트 시나리오
 * - TL-1. 날짜 필터 — startDate/dueDate 중 하나 이상 있는 이슈만 반환, 둘 다 null 인 이슈 제외 (EC1/EC2/EC7).
 * - TL-2. 소프트삭제 이슈는 결과에서 제외된다 (EC2).
 * - TL-3. typeKey 정확 반환 — epic/story/task 혼합 시나리오 (B1).
 * - TL-4. access 집합 밖 보안등급 이슈는 결과에서 제외된다 (S4).
 * - TL-5. cross-project epic_id 는 epicKey 가 null 로 반환된다 (EC3).
 * - TL-6. 501건 시드 시 500건 반환 + truncated=true, 결정적 경계 (EC6/C2).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class IssueRepositoryTimelineTest : IssueTestcontainersBase() {
    /** V003 seed task 타입 id. value class 는 lateinit 불가 → var nullable. */
    private var taskTypeId: IssueTypeId? = null

    /** V003 seed story 타입 id. */
    private var storyTypeId: IssueTypeId? = null

    /** V003 seed epic 타입 id. */
    private var epicTypeId: IssueTypeId? = null

    /**
     * V003 마이그레이션 시드에서 task/story/epic 타입 id 를 조회한다.
     *
     * [IssueTestcontainersBase.bootstrap] 이후 실행되므로 [postgres] 가 보장된다.
     */
    @BeforeAll
    fun resolveTypeIds() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1",
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "V003 마이그레이션에서 task 타입이 없습니다." }
                    taskTypeId = IssueTypeId(rs.getLong(1))
                }
            }
            conn.prepareStatement(
                "SELECT id FROM issue_types WHERE key = 'story' AND deleted_at IS NULL LIMIT 1",
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "V003 마이그레이션에서 story 타입이 없습니다." }
                    storyTypeId = IssueTypeId(rs.getLong(1))
                }
            }
            conn.prepareStatement(
                "SELECT id FROM issue_types WHERE key = 'epic' AND deleted_at IS NULL LIMIT 1",
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "V003 마이그레이션에서 epic 타입이 없습니다." }
                    epicTypeId = IssueTypeId(rs.getLong(1))
                }
            }
        }
    }

    private fun requireTaskTypeId(): IssueTypeId =
        requireNotNull(taskTypeId) { "taskTypeId 미초기화 — resolveTypeIds 실행 확인" }

    private fun requireStoryTypeId(): IssueTypeId =
        requireNotNull(storyTypeId) { "storyTypeId 미초기화 — resolveTypeIds 실행 확인" }

    private fun requireEpicTypeId(): IssueTypeId =
        requireNotNull(epicTypeId) { "epicTypeId 미초기화 — resolveTypeIds 실행 확인" }

    /**
     * 테스트용 이슈를 생성·삽입하고 DB 반환값을 돌려준다.
     *
     * @param seq IssueKey 고유성을 위한 시퀀스 번호.
     * @param typeId 이슈 유형 식별자. 기본값 taskTypeId.
     * @param startDate 시작일. null 이면 미설정.
     * @param dueDate 마감일. null 이면 미설정.
     * @param securityLevelId 보안 등급 UUID. null 이면 공개(등급 없음).
     * @param reporterId 이슈 보고자 UUID.
     */
    private fun insertIssue(
        seq: Long,
        typeId: IssueTypeId = requireTaskTypeId(),
        startDate: LocalDate? = null,
        dueDate: LocalDate? = null,
        securityLevelId: UUID? = null,
        reporterId: UUID = UUID.randomUUID(),
    ): Issue {
        val issue =
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = IssueKey.of("TPRJ", seq),
                projectId = testProjectId,
                typeId = typeId,
                summary = "타임라인 테스트 이슈 $seq",
                reporterId = ActorId(reporterId),
                currentStateKey = "open",
                securityLevelId = securityLevelId,
            ).copy(startDate = startDate, dueDate = dueDate)
        return repository.insert(issue)
    }

    /**
     * issues 행을 소프트삭제(deleted_at = NOW())한다.
     *
     * IssueRepository 에 직접 soft-delete 공개 메서드가 없으므로 테스트용 SQL 직접 실행.
     */
    private fun softDeleteIssue(id: UUID) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("UPDATE issues SET deleted_at = NOW() WHERE id = ?").use { stmt ->
                stmt.setObject(1, id)
                stmt.executeUpdate()
            }
        }
    }

    /** unrestricted=false, 지정 staticLevelIds 만 허용하는 [IssueSecurityAccess] 생성 헬퍼. */
    private fun restrictedAccess(vararg allowedLevelIds: UUID): IssueSecurityAccess =
        IssueSecurityAccess(
            unrestricted = false,
            staticLevelIds = allowedLevelIds.toSet(),
            reporterLevelIds = emptySet(),
            assigneeLevelIds = emptySet(),
        )

    /** unrestricted=true 빠른경로 [IssueSecurityAccess]. */
    private val unrestrictedAccess =
        IssueSecurityAccess(
            unrestricted = true,
            staticLevelIds = emptySet(),
            reporterLevelIds = emptySet(),
            assigneeLevelIds = emptySet(),
        )

    // ── TL-1. 날짜 필터 — (startDate IS NOT NULL OR dueDate IS NOT NULL) ──────────────

    /**
     * Given  이슈 4개: A(startDate+dueDate), B(startDate only), C(dueDate only), D(둘 다 null).
     * When   listVisibleForTimeline 호출.
     * Then   A, B, C 만 반환(D 는 날짜 없으므로 제외). truncated=false. EC1/EC2/EC7.
     */
    @Test
    @Order(1)
    fun `TL-1 - 날짜 필터 - startDate 또는 dueDate 있는 이슈만 반환한다`() {
        val today = LocalDate.of(2026, 7, 1)
        val issueA = insertIssue(seq = 1L, startDate = today, dueDate = today.plusDays(10))
        val issueB = insertIssue(seq = 2L, startDate = today)
        val issueC = insertIssue(seq = 3L, dueDate = today.plusDays(5))
        /* issueD — 날짜 둘 다 null → 제외 대상 */
        insertIssue(seq = 4L)

        val result =
            repository.listVisibleForTimeline(
                projectKey = "TPRJ",
                viewerUserId = UUID.randomUUID(),
                access = unrestrictedAccess,
            )

        val returnedKeys = result.entries.map { it.issue.key.value }
        assertThat(returnedKeys).containsExactlyInAnyOrder(
            issueA.key.value,
            issueB.key.value,
            issueC.key.value,
        )
        assertThat(result.truncated).isFalse()
    }

    // ── TL-2. 소프트삭제 이슈 제외 ──────────────────────────────────────────────

    /**
     * Given  이슈 2개: A(활성, dueDate 있음), B(소프트삭제, dueDate 있음).
     * When   listVisibleForTimeline 호출.
     * Then   A 만 반환(B 는 deleted_at IS NOT NULL 이므로 제외). EC2.
     */
    @Test
    @Order(2)
    fun `TL-2 - 소프트삭제 이슈는 결과에서 제외된다`() {
        val today = LocalDate.of(2026, 7, 1)
        val activeIssue = insertIssue(seq = 1L, dueDate = today)
        val deletedIssue = insertIssue(seq = 2L, dueDate = today)
        softDeleteIssue(deletedIssue.id.value)

        val result =
            repository.listVisibleForTimeline(
                projectKey = "TPRJ",
                viewerUserId = UUID.randomUUID(),
                access = unrestrictedAccess,
            )

        assertThat(result.entries).hasSize(1)
        assertThat(result.entries.single().issue.key.value).isEqualTo(activeIssue.key.value)
    }

    // ── TL-3. typeKey 정확 반환 (B1) ──────────────────────────────────────────

    /**
     * Given  epic/story/task 3종 이슈 각 1개, 모두 startDate 있음.
     * When   listVisibleForTimeline 호출.
     * Then   각 entry.typeKey 가 "epic"/"story"/"task" 와 정확히 일치한다 (B1).
     */
    @Test
    @Order(3)
    fun `TL-3 - typeKey 가 epic story task 를 정확히 반환한다`() {
        val today = LocalDate.of(2026, 7, 1)
        val epicIssue = insertIssue(seq = 1L, typeId = requireEpicTypeId(), startDate = today)
        val storyIssue = insertIssue(seq = 2L, typeId = requireStoryTypeId(), startDate = today)
        val taskIssue = insertIssue(seq = 3L, typeId = requireTaskTypeId(), startDate = today)

        val result =
            repository.listVisibleForTimeline(
                projectKey = "TPRJ",
                viewerUserId = UUID.randomUUID(),
                access = unrestrictedAccess,
            )

        assertThat(result.entries).hasSize(3)
        val keyToTypeKey = result.entries.associate { it.issue.key.value to it.typeKey }
        assertThat(keyToTypeKey[epicIssue.key.value]).isEqualTo("epic")
        assertThat(keyToTypeKey[storyIssue.key.value]).isEqualTo("story")
        assertThat(keyToTypeKey[taskIssue.key.value]).isEqualTo("task")
    }

    // ── TL-4. 보안등급 제외 (S4) ────────────────────────────────────────────────

    /**
     * Given  이슈 2개: A(보안등급 없음, dueDate 있음), B(비허가 보안등급, dueDate 있음).
     *        access.staticLevelIds 에 B 의 등급이 포함되지 않는다.
     * When   listVisibleForTimeline(access = restrictedAccess()) 호출.
     * Then   A 만 반환(B 는 보안등급 필터로 제외). S4.
     */
    @Test
    @Order(4)
    fun `TL-4 - access 밖 보안등급 이슈는 결과에서 제외된다`() {
        val today = LocalDate.of(2026, 7, 1)
        val deniedLevelId = UUID.randomUUID()
        val publicIssue = insertIssue(seq = 1L, dueDate = today)
        val restrictedIssue = insertIssue(seq = 2L, dueDate = today, securityLevelId = deniedLevelId)

        val result =
            repository.listVisibleForTimeline(
                projectKey = "TPRJ",
                viewerUserId = UUID.randomUUID(),
                // staticLevelIds 비어있음 — deniedLevelId 허가 안 됨
                access = restrictedAccess(),
            )

        assertThat(result.entries).hasSize(1)
        assertThat(result.entries.single().issue.key.value).isEqualTo(publicIssue.key.value)
        val returnedKeys = result.entries.map { it.issue.key.value }
        assertThat(returnedKeys).doesNotContain(restrictedIssue.key.value)
    }

    // ── TL-5. cross-project epicKey → null (EC3) ─────────────────────────────

    /**
     * Given  TPRJ 자식 이슈(C, startDate 있음). C.epic_id 를 다른 프로젝트(TPRJX)의 epic 으로 직접 설정.
     * When   listVisibleForTimeline 호출.
     * Then   C 의 entry.epicKey == null (cross-project epic 은 동일 프로젝트 JOIN 필터로 차단). EC3.
     */
    @Test
    @Order(5)
    fun `TL-5 - cross-project epic_id 는 epicKey 가 null 로 반환된다`() {
        val today = LocalDate.of(2026, 7, 1)
        val child = insertIssue(seq = 1L, startDate = today)

        val crossProjectEpicId = UUID.randomUUID()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            // 다른 프로젝트(TPRJX) 삽입 — 이미 존재하면 무시
            conn.prepareStatement(
                "INSERT INTO projects (key, name) VALUES ('TPRJX', 'Cross Project') ON CONFLICT (key) DO NOTHING",
            ).use { it.executeUpdate() }
            val otherProjectId =
                conn.prepareStatement("SELECT id FROM projects WHERE key = 'TPRJX'").use { stmt ->
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getObject(1) as UUID
                    }
                }
            // 다른 프로젝트 에픽을 직접 삽입
            conn.prepareStatement(
                "INSERT INTO issues (id, key, project_id, summary, reporter_id, current_state_key, version, type_id)" +
                    " VALUES (?::uuid, ?, ?::uuid, ?, ?::uuid, 'open', 1," +
                    " (SELECT id FROM issue_types WHERE key = 'epic' AND deleted_at IS NULL LIMIT 1))",
            ).use { ps ->
                ps.setString(1, crossProjectEpicId.toString())
                ps.setString(2, "TPRJX-1")
                ps.setString(3, otherProjectId.toString())
                ps.setString(4, "타 프로젝트 에픽")
                ps.setString(5, UUID.randomUUID().toString())
                ps.executeUpdate()
            }
            // 자식의 epic_id 를 cross-project epic 으로 직접 설정 (stale 데이터 시뮬레이션)
            conn.prepareStatement("UPDATE issues SET epic_id = ?::uuid WHERE id = ?::uuid").use { ps ->
                ps.setString(1, crossProjectEpicId.toString())
                ps.setString(2, child.id.value.toString())
                ps.executeUpdate()
            }
        }

        val result =
            repository.listVisibleForTimeline(
                projectKey = "TPRJ",
                viewerUserId = UUID.randomUUID(),
                access = unrestrictedAccess,
            )

        assertThat(result.entries).hasSize(1)
        val entry = result.entries.single()
        assertThat(entry.issue.key.value).isEqualTo(child.key.value)
        // cross-project epic → epicKey 는 null (동일 프로젝트 JOIN 필터)
        assertThat(entry.epicKey).isNull()
    }

    // ── TL-6. 501건 시드 → 500건 반환 + truncated=true (EC6/C2) ─────────────

    /**
     * Given  TPRJ 에 startDate 있는 이슈 501건 삽입 (TIMELINE_FETCH_LIMIT + 1).
     * When   listVisibleForTimeline 호출.
     * Then   entries 는 정확히 [IssueRepository.TIMELINE_FETCH_LIMIT](500)건, truncated=true. EC6/C2.
     */
    @Test
    @Order(6)
    fun `TL-6 - 501건 시드 시 500건 반환 + truncated true 결정적 경계`() {
        val taskId = requireTaskTypeId().value
        // 501건 batch INSERT — 개별 repository.insert 501회 호출 대비 속도 최적화
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "INSERT INTO issues" +
                    " (id, key, project_id, summary, reporter_id, current_state_key, version, type_id, start_date)" +
                    " VALUES (?::uuid, ?, ?::uuid, ?, ?::uuid, 'open', 1, ?, '2026-07-01')",
            ).use { ps ->
                for (i in 1..501) {
                    ps.setString(1, UUID.randomUUID().toString())
                    ps.setString(2, "TPRJ-$i")
                    ps.setString(3, testProjectId.toString())
                    ps.setString(4, "대량 이슈 $i")
                    ps.setString(5, UUID.randomUUID().toString())
                    ps.setLong(6, taskId)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }

        val result =
            repository.listVisibleForTimeline(
                projectKey = "TPRJ",
                viewerUserId = UUID.randomUUID(),
                access = unrestrictedAccess,
            )

        assertThat(result.entries).hasSize(IssueRepository.TIMELINE_FETCH_LIMIT)
        assertThat(result.truncated).isTrue()
    }
}
