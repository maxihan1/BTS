// IssueRepository Epic 통합 테스트 — updateEpic/linkEpic/findByKeyWithType/findEpicChildren + hotfix P1-A/P1-B

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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * IssueRepository epic 관련 메서드 통합 테스트 (FR-EP-01 Task 4 + hotfix P1-A/P1-B).
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
 * - EP-9. linkEpic — 동시 connect 시 정확히 1건만 성공(0행 반환 시 lost-update 없음, P1-B TOCTOU).
 * - EP-10. findByKeyWithType — epic_id 가 다른 프로젝트 epic 을 가리킬 때 IssueResponse.epic 이 null (P1-A cross-project).
 * - EP-11. moveIssue — epic 연결된 이슈를 타 프로젝트로 이동 시 epic_id 가 null 초기화된다 (P1-A move).
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

    // ── EP-9. linkEpic — 동시 connect TOCTOU lost-update 차단 (P1-B) ─────────────

    /**
     * Given  에픽(E)과 자식(C, epic_id=null).
     * When   linkEpic(C.id, E.id) 를 스레드 2개에서 동시 호출.
     * Then   정확히 1건만 1행 반환(성공). 나머지 1건은 0행 반환(원자 실패).
     *        epic_id IS NULL 조건부 UPDATE 로 lost-update 없이 원자 보장.
     *
     * P1-B TOCTOU 핫픽스 RED 테스트.
     * linkEpic 메서드가 없거나 조건부 WHERE 가 없으면 두 건 모두 1 반환 → 테스트 실패(FAIL=RED).
     */
    @Test
    @Order(9)
    fun `EP-9 - linkEpic - 동시 connect 시 정확히 1건만 성공하고 나머지는 0행 반환한다`() {
        val epic = insertIssue(seq = 1L, summary = "에픽")
        val child = insertIssue(seq = 2L, summary = "자식")

        val latch = CountDownLatch(1)
        val successCount = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(2)

        repeat(2) {
            pool.submit {
                latch.await(5, TimeUnit.SECONDS)
                val affected = repository.linkEpic(childId = child.id.value, epicId = epic.id.value)
                if (affected == 1) successCount.incrementAndGet()
            }
        }

        latch.countDown()
        pool.shutdown()
        pool.awaitTermination(10, TimeUnit.SECONDS)

        // 두 쓰레드가 동시에 실행됐을 때 정확히 1건만 성공해야 한다.
        assertThat(successCount.get()).isEqualTo(1)
    }

    // ── EP-10. findByKeyWithType — cross-project epic 은 null 로 차단 (P1-A self-join) ──

    /**
     * Given  프로젝트 P2 의 에픽 E 가 존재한다.
     *        프로젝트 P1(TPRJ) 의 자식 C.epic_id 를 SQL 직접으로 E.id 로 설정(cross-project stale 데이터 시뮬레이션).
     * When   findByKeyWithType(C.key) 호출.
     * Then   IssueResponse.epic == null (cross-project epic 은 self-join 동일프로젝트 필터로 차단).
     *
     * P1-A(a) 핫픽스 RED 테스트.
     * epicAlias.PROJECT_ID.eq(ISSUES.PROJECT_ID) 조건이 없으면 epic 이 채워져 테스트 실패(FAIL=RED).
     */
    @Test
    @Order(10)
    fun `EP-10 - findByKeyWithType - cross-project epic_id 는 IssueResponse-epic 이 null 이다`() {
        // 두 번째 프로젝트 삽입 (TPRJ2)
        val otherProjectId =
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.prepareStatement(
                    "INSERT INTO projects (key, name) VALUES ('TPRJ2', 'Test Project 2')" +
                        " ON CONFLICT (key) DO NOTHING",
                ).use { it.executeUpdate() }
                conn.prepareStatement("SELECT id FROM projects WHERE key = 'TPRJ2'").use { stmt ->
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getObject(1) as UUID
                    }
                }
            }

        // P1(TPRJ) 자식 이슈 삽입
        val child = insertIssue(seq = 1L, summary = "자식 이슈")

        // P2(TPRJ2) 에픽 이슈를 SQL 직접 삽입 (다른 projectId)
        val epicId = UUID.randomUUID()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "INSERT INTO issues (id, key, project_id, summary, reporter_id, current_state_key, version, type_id)" +
                    " VALUES (?::uuid, ?, ?::uuid, ?, ?::uuid, 'open', 1," +
                    " (SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1))",
            ).use { ps ->
                ps.setString(1, epicId.toString())
                ps.setString(2, "TPRJ2-1")
                ps.setString(3, otherProjectId.toString())
                ps.setString(4, "타 프로젝트 에픽")
                ps.setString(5, UUID.randomUUID().toString())
                ps.executeUpdate()
            }
            // 자식의 epic_id 를 cross-project epic 으로 직접 설정 (stale 데이터 시뮬레이션)
            conn.prepareStatement("UPDATE issues SET epic_id = ?::uuid WHERE id = ?::uuid").use { ps ->
                ps.setString(1, epicId.toString())
                ps.setString(2, child.id.value.toString())
                ps.executeUpdate()
            }
        }

        val response = repository.findByKeyWithType(child.key)
        assertThat(response).isNotNull
        // cross-project epic 은 동일 프로젝트 JOIN 필터에 의해 null 이어야 한다.
        assertThat(response!!.epic).isNull()
    }

    // ── EP-11. moveIssue — epic_id 초기화 (P1-A move) ────────────────────────────

    /**
     * Given  에픽(E)과 자식(C, epic_id=E.id) 이 동일 프로젝트(TPRJ).
     *        두 번째 프로젝트(TPRJ3) 가 존재한다.
     * When   repository.moveIssue(C.key → TPRJ3 이동) 호출.
     * Then   issues.epic_id 가 null 이다 (타 프로젝트 이동 시 에픽 연결 자동 해제).
     *
     * P1-A(b) 핫픽스 RED 테스트.
     * moveIssue 에 EPIC_ID null 초기화가 없으면 epic_id 가 남아 테스트 실패(FAIL=RED).
     */
    @Test
    @Order(11)
    fun `EP-11 - moveIssue - 타 프로젝트 이동 시 epic_id 가 null 초기화된다`() {
        // 이동 대상 프로젝트 삽입 (TPRJ3)
        val targetProjectId =
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.prepareStatement(
                    "INSERT INTO projects (key, name) VALUES ('TPRJ3', 'Test Project 3')" +
                        " ON CONFLICT (key) DO NOTHING",
                ).use { it.executeUpdate() }
                conn.prepareStatement("SELECT id FROM projects WHERE key = 'TPRJ3'").use { stmt ->
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getObject(1) as UUID
                    }
                }
            }

        val epic = insertIssue(seq = 1L, summary = "에픽")
        val child = insertIssue(seq = 2L, summary = "자식 이슈")

        // 에픽 연결 설정
        repository.updateEpic(childId = child.id.value, epicId = epic.id.value)

        // 이동 전 epic_id 확인 (vacuous 차단)
        val beforeMove = repository.findByKey(child.key)
        assertThat(beforeMove!!.epicId).isEqualTo(epic.id.value)

        // 타 프로젝트로 이동
        val newKey = IssueKey("TPRJ3-1")
        val affected =
            repository.moveIssue(
                oldKey = child.key,
                newKey = newKey,
                targetProjectId = targetProjectId,
                targetStateKey = "open",
                resolvedResolutionId = null,
                filteredCustomFields = emptyMap(),
                expectedVersion = child.version,
            )
        assertThat(affected).isEqualTo(1)

        // 이동 후 epic_id 가 null 이어야 한다
        val afterMove = repository.findByKey(newKey)
        assertThat(afterMove).isNotNull
        assertThat(afterMove!!.epicId).isNull()
    }
}
