// IssueRepository Epic 메서드 통합 테스트 — updateEpic / findByKeyWithType epic / findEpicChildren (FR-EP-01 Task 4)

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
import java.util.UUID

/**
 * IssueRepository epic 관련 메서드 통합 테스트 (FR-EP-01 Task 4).
 *
 * [IssueTestcontainersBase] JVM singleton PostgreSQL + Flyway 마이그레이션을 재사용한다.
 *
 * ## 테스트 시나리오
 * - EP-1. updateEpic — epic_id 설정 후 findByKey 재조회 시 Issue.epicId 가 일치한다.
 * - EP-2. updateEpic null — epic_id 해제 후 findByKey 재조회 시 Issue.epicId 가 null 이다.
 * - EP-3. findByKeyWithType — epic 있는 자식 이슈 단건 조회 시 IssueResponse.epic 이 채워진다.
 * - EP-4. findByKeyWithType — epic 없는 이슈 단건 조회 시 IssueResponse.epic 이 null 이다.
 * - EP-5. findByKeyWithType — 소프트삭제된 Epic 에 연결된 자식 조회 시 IssueResponse.epic 이 null 이다.
 * - EP-6. findEpicChildren — 보안 등급 제한으로 접근 불가한 자식은 결과에서 제외된다.
 * - EP-7. findEpicChildren — 소프트삭제된 자식은 결과에서 제외된다.
 * - EP-8. findEpicChildren — 보안 등급 없는 자식은 unrestricted=false 인 경우에도 노출된다.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class IssueRepositoryEpicIntegrationTest : IssueTestcontainersBase() {
    /** V003 seed task 타입 id — value class 는 lateinit 불가 → nullable var. */
    private var taskTypeId: IssueTypeId? = null

    @BeforeAll
    fun resolveTaskTypeId() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1",
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "V003 마이그레이션에서 task 타입이 없습니다." }
                    taskTypeId = IssueTypeId(rs.getLong(1))
                }
            }
        }
    }

    private fun requireTypeId(): IssueTypeId = requireNotNull(taskTypeId) { "taskTypeId 미초기화 — resolveTaskTypeId 확인" }

    /**
     * 테스트용 이슈를 생성·삽입하고 DB 반환값을 돌려준다.
     *
     * @param seq IssueKey 고유성을 위한 시퀀스 번호.
     * @param summary 이슈 제목. 기본값은 "테스트 이슈 $seq".
     * @param securityLevelId 보안 등급 UUID. null 이면 공개(등급 없음).
     * @param reporterId 이슈 보고자 UUID.
     */
    private fun insertIssue(
        seq: Long,
        summary: String = "테스트 이슈 $seq",
        securityLevelId: UUID? = null,
        reporterId: UUID = UUID.randomUUID(),
    ): Issue {
        val issue =
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = IssueKey.of("TPRJ", seq),
                projectId = testProjectId,
                typeId = requireTypeId(),
                summary = summary,
                reporterId = ActorId(reporterId),
                currentStateKey = "open",
                securityLevelId = securityLevelId,
            )
        return repository.insert(issue)
    }

    /**
     * issues 행을 소프트삭제(deleted_at = NOW())한다.
     *
     * IssueRepository 에 직접 soft-delete 메서드가 없으므로 테스트용 SQL 직접 실행.
     */
    private fun softDeleteIssue(id: UUID) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("UPDATE issues SET deleted_at = NOW() WHERE id = ?").use { stmt ->
                stmt.setObject(1, id)
                stmt.executeUpdate()
            }
        }
    }

    /** unrestricted=false, 지정 staticLevelIds 만 허용하는 IssueSecurityAccess 생성 헬퍼. */
    private fun restrictedAccess(vararg allowedLevelIds: UUID): IssueSecurityAccess =
        IssueSecurityAccess(
            unrestricted = false,
            staticLevelIds = allowedLevelIds.toSet(),
            reporterLevelIds = emptySet(),
            assigneeLevelIds = emptySet(),
        )

    /** unrestricted=true 빠른경로 IssueSecurityAccess. */
    private val unrestrictedAccess =
        IssueSecurityAccess(
            unrestricted = true,
            staticLevelIds = emptySet(),
            reporterLevelIds = emptySet(),
            assigneeLevelIds = emptySet(),
        )

    // ── EP-1. updateEpic — epic_id 설정 ─────────────────────────────────────────

    /**
     * Given  에픽 이슈(E)와 자식 이슈(C)가 존재하고, C.epicId 가 null 인 상태.
     * When   updateEpic(childId = C.id, epicId = E.id) 호출.
     * Then   findByKey(C.key).epicId == E.id.
     */
    @Test
    @Order(1)
    fun `EP-1 - updateEpic - epic_id 를 설정하면 findByKey 재조회 시 epicId 가 일치한다`() {
        val epic = insertIssue(seq = 1L, summary = "에픽 이슈")
        val child = insertIssue(seq = 2L, summary = "자식 이슈")

        repository.updateEpic(childId = child.id.value, epicId = epic.id.value)

        val found = repository.findByKey(child.key)
        assertThat(found).isNotNull
        assertThat(found!!.epicId).isEqualTo(epic.id.value)
    }

    // ── EP-2. updateEpic null — epic_id 해제 ────────────────────────────────────

    /**
     * Given  C.epicId == E.id 인 상태.
     * When   updateEpic(childId = C.id, epicId = null) 호출.
     * Then   findByKey(C.key).epicId == null.
     */
    @Test
    @Order(2)
    fun `EP-2 - updateEpic null - epic_id 를 해제하면 findByKey 재조회 시 epicId 가 null 이다`() {
        val epic = insertIssue(seq = 1L, summary = "에픽 이슈")
        val child = insertIssue(seq = 2L, summary = "자식 이슈")

        repository.updateEpic(childId = child.id.value, epicId = epic.id.value)
        repository.updateEpic(childId = child.id.value, epicId = null)

        val found = repository.findByKey(child.key)
        assertThat(found).isNotNull
        assertThat(found!!.epicId).isNull()
    }

    // ── EP-3. findByKeyWithType — epic 있는 자식 조회 ────────────────────────────

    /**
     * Given  에픽(E)과 자식(C)이 존재하고, C.epicId == E.id 인 상태.
     * When   findByKeyWithType(C.key) 호출.
     * Then   IssueResponse.epic.key == E.key, epic.summary == E.summary.
     */
    @Test
    @Order(3)
    fun `EP-3 - findByKeyWithType - epic 있는 자식 조회 시 epic 필드가 채워진다`() {
        val epic = insertIssue(seq = 1L, summary = "에픽 이슈")
        val child = insertIssue(seq = 2L, summary = "자식 이슈")

        repository.updateEpic(childId = child.id.value, epicId = epic.id.value)

        val response = repository.findByKeyWithType(child.key)
        assertThat(response).isNotNull
        assertThat(response!!.epic).isNotNull
        assertThat(response.epic!!.key).isEqualTo(epic.key.value)
        assertThat(response.epic!!.summary).isEqualTo("에픽 이슈")
    }

    // ── EP-4. findByKeyWithType — epic 없는 이슈 ─────────────────────────────────

    /**
     * Given  epic 에 연결되지 않은 이슈.
     * When   findByKeyWithType(issue.key) 호출.
     * Then   IssueResponse.epic == null.
     */
    @Test
    @Order(4)
    fun `EP-4 - findByKeyWithType - epic 없는 이슈 조회 시 epic 이 null 이다`() {
        val issue = insertIssue(seq = 1L)

        val response = repository.findByKeyWithType(issue.key)
        assertThat(response).isNotNull
        assertThat(response!!.epic).isNull()
    }

    // ── EP-5. findByKeyWithType — 소프트삭제된 Epic 연결 자식 ──────────────────────

    /**
     * Given  E 가 소프트삭제된 에픽이고 C.epicId == E.id 인 상태.
     * When   findByKeyWithType(C.key) 호출.
     * Then   IssueResponse.epic == null (소프트삭제 Epic 은 NULL 처리).
     */
    @Test
    @Order(5)
    fun `EP-5 - findByKeyWithType - 소프트삭제된 Epic 에 연결된 자식 조회 시 epic 이 null 이다`() {
        val epic = insertIssue(seq = 1L, summary = "삭제될 에픽")
        val child = insertIssue(seq = 2L, summary = "자식 이슈")

        repository.updateEpic(childId = child.id.value, epicId = epic.id.value)
        softDeleteIssue(epic.id.value)

        val response = repository.findByKeyWithType(child.key)
        assertThat(response).isNotNull
        assertThat(response!!.epic).isNull()
    }

    // ── EP-6. findEpicChildren — 보안 등급 제한으로 접근 불가 자식 제외 ──────────────

    /**
     * Given  에픽(E)에 자식 2개: C1(보안등급 없음), C2(비허가 보안등급 L2).
     *        actor 는 L2 를 허용하는 staticLevelIds 에 포함되지 않는다.
     * When   findEpicChildren(epicId=E.id, actor=..., access=restrictedAccess(허가등급), projectKey="TPRJ").
     * Then   결과에 C1 만 포함된다 (C2 는 보안 등급 필터로 제외).
     */
    @Test
    @Order(6)
    fun `EP-6 - findEpicChildren - 접근 불가 보안등급 자식은 결과에서 제외된다`() {
        val epic = insertIssue(seq = 1L, summary = "에픽")
        val allowedLevelId = UUID.randomUUID()
        val deniedLevelId = UUID.randomUUID()
        val c1 = insertIssue(seq = 2L, summary = "허가 자식")
        val c2 = insertIssue(seq = 3L, summary = "거부 자식", securityLevelId = deniedLevelId)

        repository.updateEpic(childId = c1.id.value, epicId = epic.id.value)
        repository.updateEpic(childId = c2.id.value, epicId = epic.id.value)

        val children =
            repository.findEpicChildren(
                epicId = epic.id.value,
                actor = UUID.randomUUID(),
                access = restrictedAccess(allowedLevelId),
                projectKey = "TPRJ",
            )

        val childKeys = children.map { it.key.value }
        assertThat(childKeys).containsExactly(c1.key.value)
    }

    // ── EP-7. findEpicChildren — 소프트삭제 자식 제외 ────────────────────────────

    /**
     * Given  에픽(E)에 자식 2개: C1(활성), C2(소프트삭제).
     * When   findEpicChildren(epicId=E.id, ...).
     * Then   결과에 C1 만 포함된다 (C2 는 deleted_at IS NOT NULL 이므로 제외).
     */
    @Test
    @Order(7)
    fun `EP-7 - findEpicChildren - 소프트삭제된 자식은 결과에서 제외된다`() {
        val epic = insertIssue(seq = 1L, summary = "에픽")
        val c1 = insertIssue(seq = 2L, summary = "활성 자식")
        val c2 = insertIssue(seq = 3L, summary = "삭제된 자식")

        repository.updateEpic(childId = c1.id.value, epicId = epic.id.value)
        repository.updateEpic(childId = c2.id.value, epicId = epic.id.value)
        softDeleteIssue(c2.id.value)

        val children =
            repository.findEpicChildren(
                epicId = epic.id.value,
                actor = UUID.randomUUID(),
                access = unrestrictedAccess,
                projectKey = "TPRJ",
            )

        val childKeys = children.map { it.key.value }
        assertThat(childKeys).containsExactly(c1.key.value)
    }

    // ── EP-8. findEpicChildren — 보안 등급 없는 자식은 항상 노출 ──────────────────

    /**
     * Given  에픽(E)에 자식 1개: C1(보안 등급 없음).
     *        access 는 unrestricted=false, staticLevelIds 비어있음(허가 등급 없음).
     * When   findEpicChildren(epicId=E.id, actor=..., access=restrictedAccess(), projectKey="TPRJ").
     * Then   결과에 C1 이 포함된다 (security_level_id IS NULL 이면 항상 노출).
     */
    @Test
    @Order(8)
    fun `EP-8 - findEpicChildren - 보안등급 없는 자식은 restricted access 에서도 노출된다`() {
        val epic = insertIssue(seq = 1L, summary = "에픽")
        val c1 = insertIssue(seq = 2L, summary = "공개 자식")

        repository.updateEpic(childId = c1.id.value, epicId = epic.id.value)

        // restrictedAccess() — 허가 등급 없음(staticLevelIds 비어있음)
        val children =
            repository.findEpicChildren(
                epicId = epic.id.value,
                actor = UUID.randomUUID(),
                access = restrictedAccess(),
                projectKey = "TPRJ",
            )

        val childKeys = children.map { it.key.value }
        assertThat(childKeys).containsExactly(c1.key.value)
    }
}
