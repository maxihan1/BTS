// IssueRepository Testcontainers 통합 테스트 — T1~T9 RED→GREEN 검증 (Task 5 + Task 9, FR-IS-01, FR-IS-02)

package com.bts.issue.repository

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.shared.issue.IssueTypeId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.data.domain.PageRequest
import java.sql.DriverManager
import java.util.UUID

/**
 * IssueRepository 통합 테스트.
 *
 * IssueTestcontainersBase 상속으로 Testcontainers quay.io/tembo/pg16-pgmq:latest + Flyway V001 + V002
 * (`db/migration/issue-tracking/` 하위 namespace 격리 경로) 를 JVM singleton 라이프사이클로 기동하고,
 * IssueRepository 의 메서드를 순서대로 검증한다.
 * Spring ApplicationContext 없이 DSLContext 를 직접 조합한다 (project-workflow 패턴 준용).
 *
 * **`@Testcontainers` annotation 불필요** — IssueTestcontainersBase 가 JVM singleton 패턴 적용 (PR #8 learning #2).
 *
 * 테스트 시나리오.
 * - T1. insert — Issue 를 DB 에 삽입하면 반환된 Issue 의 id/key/version 이 기대 값과 일치한다.
 * - T2. findByKey — 삽입한 Issue 를 key 로 조회하면 동일 데이터가 반환된다.
 * - T2b. findByKey — 존재하지 않는 key 조회 시 null 이 반환된다.
 * - T3. findByKeyForUpdate — 비관락(SELECT FOR UPDATE) 조회 후 동일 key 가 반환된다.
 * - T4. applyTransition — version 일치 시 currentStateKey 가 업데이트되고 1 이 반환된다.
 * - T5. applyTransition stale — version 불일치 시 0 이 반환된다 (낙관락 충돌).
 * - T6. softDelete — 삭제 후 findByKey 가 null 을 반환한다.
 * - T7. list — 활성 이슈 목록을 페이지 단위로 조회한다.
 * - T8. incrementKeySequence — 동일 projectKey 로 두 번 호출 시 연속된 두 숫자를 반환한다.
 * - T9. softDelete 후 같은 key INSERT — PostgreSQL 23505 unique_violation (FR-6 S13).
 *
 * **PR #24** — Flyway V001 namespace 격리 완료로 `@Disabled` 해제. file path = `db/migration/issue-tracking/` 하위.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class IssueRepositoryTest : IssueTestcontainersBase() {
    /**
     * V003 seed 에서 task 타입의 id 를 DB 에서 직접 조회한다.
     * BIGSERIAL 이므로 명시적 id 가 없어 bootstrap 후 조회가 가장 안전하다.
     * IssueTypeId 는 value class 이므로 lateinit 불가 — var + null 허용으로 초기화.
     */
    private var taskTypeId: IssueTypeId? = null

    @BeforeAll
    fun resolveTaskTypeId() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            val sql = "SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1"
            conn.prepareStatement(sql).use { stmt ->
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "V003 마이그레이션에서 task 타입이 없습니다." }
                    taskTypeId = IssueTypeId(rs.getLong(1))
                }
            }
        }
    }

    /** 각 테스트에서 안전하게 taskTypeId 를 꺼내는 helper. resolveTaskTypeId 이후 항상 non-null. */
    @Suppress("MaxLineLength")
    private fun requireTaskTypeId(): IssueTypeId = requireNotNull(taskTypeId) { "taskTypeId 가 초기화되지 않았습니다 — resolveTaskTypeId 실행 확인" }

    // ── T1. insert ───────────────────────────────────────────────────────────────

    /**
     * Given  유효한 Issue 도메인 객체
     * When   insert 호출
     * Then   반환된 Issue 의 id, key, version, projectId 가 입력과 일치한다.
     */
    @Test
    @Order(1)
    fun `T1 - insert - Issue 를 삽입하면 반환된 Issue 가 입력과 일치한다`() {
        val issue =
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = IssueKey.of("TPRJ", 1L),
                projectId = testProjectId,
                typeId = requireTaskTypeId(),
                summary = "Fix login bug",
                reporterId = ActorId(UUID.randomUUID()),
                currentStateKey = "open",
            )

        val inserted = repository.insert(issue)

        assertThat(inserted.id).isEqualTo(issue.id)
        assertThat(inserted.key).isEqualTo(issue.key)
        assertThat(inserted.projectId).isEqualTo(issue.projectId)
        assertThat(inserted.summary).isEqualTo(issue.summary)
        assertThat(inserted.currentStateKey).isEqualTo(issue.currentStateKey)
        assertThat(inserted.version).isEqualTo(1L)
        assertThat(inserted.deletedAt).isNull()
    }

    // ── T2. findByKey ────────────────────────────────────────────────────────────

    /**
     * Given  삽입된 Issue
     * When   findByKey 로 동일 key 조회
     * Then   동일 summary 와 projectId 가 반환된다.
     */
    @Test
    @Order(2)
    fun `T2 - findByKey - 삽입된 Issue 를 key 로 조회하면 동일 데이터가 반환된다`() {
        val issue =
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = IssueKey.of("TPRJ", 1L),
                projectId = testProjectId,
                typeId = requireTaskTypeId(),
                summary = "Find by key test",
                reporterId = ActorId(UUID.randomUUID()),
                currentStateKey = "open",
            )
        repository.insert(issue)

        val found = repository.findByKey(IssueKey.of("TPRJ", 1L))

        assertThat(found).isNotNull
        assertThat(found!!.summary).isEqualTo("Find by key test")
        assertThat(found.projectId).isEqualTo(testProjectId)
    }

    // ── T2-b. findByKey — 없는 키 ────────────────────────────────────────────────

    /**
     * Given  삽입된 이슈가 없는 상태
     * When   존재하지 않는 key 로 findByKey 호출
     * Then   null 이 반환된다.
     */
    @Test
    @Order(3)
    fun `T2b - findByKey - 존재하지 않는 key 조회 시 null 이 반환된다`() {
        val result = repository.findByKey(IssueKey.of("TPRJ", 999L))
        assertThat(result).isNull()
    }

    // ── T3. findByKeyForUpdate ───────────────────────────────────────────────────

    /**
     * Given  삽입된 Issue
     * When   findByKeyForUpdate 호출
     * Then   동일 key 가 반환된다 (비관락 — 락 실제 획득은 트랜잭션 컨텍스트 필요, 여기선 반환값만 검증).
     */
    @Test
    @Order(4)
    fun `T3 - findByKeyForUpdate - 삽입된 Issue 를 비관락으로 조회하면 동일 key 가 반환된다`() {
        val key = IssueKey.of("TPRJ", 1L)
        val issue =
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = key,
                projectId = testProjectId,
                typeId = requireTaskTypeId(),
                summary = "For update test",
                reporterId = ActorId(UUID.randomUUID()),
                currentStateKey = "open",
            )
        repository.insert(issue)

        val found = repository.findByKeyForUpdate(key)

        assertThat(found).isNotNull
        assertThat(found!!.key).isEqualTo(key)
    }

    // ── T4. applyTransition — version 일치 ──────────────────────────────────────

    /**
     * Given  삽입된 Issue (version=1)
     * When   applyTransition 으로 version=1 로 상태 전이 시도
     * Then   반환값 1, findByKey 로 조회 시 currentStateKey 가 "IN_PROGRESS" 로 변경된다.
     */
    @Test
    @Order(5)
    fun `T4 - applyTransition - version 일치 시 상태가 전이되고 1 이 반환된다`() {
        val key = IssueKey.of("TPRJ", 1L)
        val issue =
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = key,
                projectId = testProjectId,
                typeId = requireTaskTypeId(),
                summary = "Transition test",
                reporterId = ActorId(UUID.randomUUID()),
                currentStateKey = "open",
            )
        repository.insert(issue)

        val updated = repository.applyTransition(key, "IN_PROGRESS", expectedVersion = 1L)

        assertThat(updated).isEqualTo(1)
        val found = repository.findByKey(key)
        assertThat(found).isNotNull
        assertThat(found!!.currentStateKey).isEqualTo("IN_PROGRESS")
    }

    // ── T5. applyTransition — version 불일치 (낙관락 충돌) ──────────────────────

    /**
     * Given  삽입된 Issue (version=1)
     * When   applyTransition 으로 version=99 (stale) 로 전이 시도
     * Then   반환값 0 — 업데이트 행 없음, 낙관락 충돌.
     */
    @Test
    @Order(6)
    fun `T5 - applyTransition stale - version 불일치 시 0 이 반환된다`() {
        val key = IssueKey.of("TPRJ", 1L)
        val issue =
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = key,
                projectId = testProjectId,
                typeId = requireTaskTypeId(),
                summary = "Stale version test",
                reporterId = ActorId(UUID.randomUUID()),
                currentStateKey = "open",
            )
        repository.insert(issue)

        val updated = repository.applyTransition(key, "IN_PROGRESS", expectedVersion = 99L)

        assertThat(updated).isEqualTo(0)
        // 상태 변경 없음 확인
        val found = repository.findByKey(key)
        assertThat(found!!.currentStateKey).isEqualTo("open")
    }

    // ── T6. softDelete ───────────────────────────────────────────────────────────

    /**
     * Given  삽입된 Issue
     * When   softDelete 호출
     * Then   반환값 1, findByKey 가 null 을 반환한다.
     */
    @Test
    @Order(7)
    fun `T6 - softDelete - 삭제 후 findByKey 가 null 을 반환한다`() {
        val key = IssueKey.of("TPRJ", 1L)
        val issue =
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = key,
                projectId = testProjectId,
                typeId = requireTaskTypeId(),
                summary = "Soft delete test",
                reporterId = ActorId(UUID.randomUUID()),
                currentStateKey = "open",
            )
        repository.insert(issue)

        val deleted = repository.softDelete(key)

        assertThat(deleted).isEqualTo(1)
        assertThat(repository.findByKey(key)).isNull()
    }

    // ── T7. list ─────────────────────────────────────────────────────────────────

    /**
     * Given  활성 이슈 3건 + 소프트 삭제 1건
     * When   list 로 첫 페이지(size=10) 조회
     * Then   활성 이슈 3건만 반환된다.
     */
    @Test
    @Order(8)
    fun `T7 - list - 활성 이슈 목록을 페이지 단위로 조회한다`() {
        val reporterId = ActorId(UUID.randomUUID())
        for (i in 1..3) {
            repository.insert(
                Issue.create(
                    id = IssueId(UUID.randomUUID()),
                    key = IssueKey.of("TPRJ", i.toLong()),
                    projectId = testProjectId,
                    typeId = requireTaskTypeId(),
                    summary = "Issue $i",
                    reporterId = reporterId,
                    currentStateKey = "open",
                ),
            )
        }
        // 이슈 4번은 소프트 삭제
        repository.insert(
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = IssueKey.of("TPRJ", 4L),
                projectId = testProjectId,
                typeId = requireTaskTypeId(),
                summary = "Deleted issue",
                reporterId = reporterId,
                currentStateKey = "open",
            ),
        )
        repository.softDelete(IssueKey.of("TPRJ", 4L))

        val page = repository.list("TPRJ", PageRequest.of(0, 10))

        assertThat(page.totalElements).isEqualTo(3L)
        assertThat(page.content).hasSize(3)
        assertThat(page.content.map { it.key.value }).containsExactlyInAnyOrder("TPRJ-1", "TPRJ-2", "TPRJ-3")
    }

    // ── T8. incrementKeySequence ─────────────────────────────────────────────────

    /**
     * Given  key_sequence = 0 인 프로젝트 'TPRJ'
     * When   incrementKeySequence 두 번 호출
     * Then   첫 번째 호출은 1, 두 번째 호출은 2 를 반환한다.
     */
    @Test
    @Order(9)
    fun `T8 - incrementKeySequence - 두 번 호출 시 연속된 두 숫자를 반환한다`() {
        val first = repository.incrementKeySequence("TPRJ")
        val second = repository.incrementKeySequence("TPRJ")

        assertThat(first).isEqualTo(1L)
        assertThat(second).isEqualTo(2L)
    }

    // ── T9-regression. currentStateKey 소문자 회귀 가드 ─────────────────────────

    /**
     * Given  currentStateKey = "open" (소문자, V004 마이그레이션 이후 표준) 으로 Issue 를 insert
     * When   findByKey 로 조회
     * Then   currentStateKey 가 "open" (소문자) 로 반환된다.
     *
     * Task 9 회귀 가드: V004 가 기존 OPEN(대문자) → open(소문자) 으로 변환했으므로, 신규 저장 시
     * 소문자 키를 그대로 유지해야 함. 대문자 상태키가 코드에 남아있으면 이 테스트가 RED.
     */
    @Test
    @Order(10)
    fun `T9-regression - currentStateKey 소문자 — insert 후 findByKey 시 소문자로 반환된다`() {
        val key = IssueKey.of("TPRJ", 42L)
        val issue =
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = key,
                projectId = testProjectId,
                typeId = requireTaskTypeId(),
                summary = "소문자 상태키 회귀 가드",
                reporterId = ActorId(UUID.randomUUID()),
                currentStateKey = "open",
            )
        repository.insert(issue)

        val found = repository.findByKey(key)

        assertThat(found).isNotNull
        assertThat(found!!.currentStateKey).isEqualTo("open")
    }

    // ── T9. softDelete 후 같은 key INSERT — unique 위반 ──────────────────────────

    /**
     * Given  활성 이슈 TPRJ-1 이 존재하고 softDelete 완료
     * When   같은 IssueKey("TPRJ", 1L) + 새 id 로 Issue.create 후 repository.insert 호출
     * Then   PostgreSQL 23505 unique_violation — DataIntegrityViolationException 또는
     *        IntegrityConstraintViolationException throw.
     *
     * FR-6 S13 (soft delete 키 보존) 을 DB 통합 영역으로 이동한 시나리오.
     * issues.key UNIQUE 제약이 소프트 삭제 후에도 row 를 보존하므로 동일 key INSERT 는 항상 위반.
     */
    @Test
    @Order(11)
    fun `T9 - softDelete - 삭제 후 같은 key INSERT 시 DB unique constraint 위반`() {
        val key = IssueKey.of("TPRJ", 1L)
        val original =
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = key,
                projectId = testProjectId,
                typeId = requireTaskTypeId(),
                summary = "원래",
                reporterId = ActorId(UUID.randomUUID()),
                currentStateKey = "open",
            )
        repository.insert(original)
        repository.softDelete(key)

        val duplicate =
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = key,
                projectId = testProjectId,
                typeId = requireTaskTypeId(),
                summary = "중복 시도",
                reporterId = ActorId(UUID.randomUUID()),
                currentStateKey = "open",
            )

        assertThatThrownBy { repository.insert(duplicate) }
            .isInstanceOfAny(
                org.springframework.dao.DataIntegrityViolationException::class.java,
                org.jooq.exception.IntegrityConstraintViolationException::class.java,
            )
    }

    // ── T10. 5필드 round-trip (FR-IS-04 Task 5) ──────────────────────────────────

    /**
     * Given  description/priority/labels/environment/impact 5필드가 모두 채워진 Issue
     * When   insert 후 findByKey 로 조회
     * Then   5필드가 모두 정확히 일치한다.
     */
    @Test
    @Order(14)
    fun `T10 - 5필드 round-trip - 다중 라벨, null 없음`() {
        val key = IssueKey.of("TPRJ", 1L)
        val issue =
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = key,
                projectId = testProjectId,
                typeId = requireTaskTypeId(),
                summary = "5필드 round-trip 테스트",
                reporterId = ActorId(UUID.randomUUID()),
                currentStateKey = "open",
                description = "## 재현 방법\n1. 로그인\n2. 클릭",
                priority = 2,
                labels = listOf("backend", "urgent", "special-char:테스트"),
                environment = "production",
                impact = 1,
            )
        repository.insert(issue)

        val found = requireNotNull(repository.findByKey(key)) { "이슈가 없어서 round-trip 검증 불가" }

        assertThat(found.description).isEqualTo("## 재현 방법\n1. 로그인\n2. 클릭")
        assertThat(found.priority).isEqualTo(2)
        assertThat(found.labels).containsExactly("backend", "urgent", "special-char:테스트")
        assertThat(found.environment).isEqualTo("production")
        assertThat(found.impact).isEqualTo(1)
    }

    /**
     * Given  description/environment/impact 를 null 로 지정하고 labels 를 빈 리스트로 지정한 Issue
     * When   insert 후 findByKey 로 조회
     * Then   null 필드는 null, labels 는 빈 리스트로 반환된다.
     */
    @Test
    @Order(15)
    fun `T10b - 5필드 round-trip - null 필드 및 빈 labels`() {
        val key = IssueKey.of("TPRJ", 2L)
        val issue =
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = key,
                projectId = testProjectId,
                typeId = requireTaskTypeId(),
                summary = "null 필드 round-trip 테스트",
                reporterId = ActorId(UUID.randomUUID()),
                currentStateKey = "open",
                description = null,
                priority = 3,
                labels = emptyList(),
                environment = null,
                impact = null,
            )
        repository.insert(issue)

        val found = requireNotNull(repository.findByKey(key)) { "이슈가 없어서 round-trip 검증 불가" }

        assertThat(found.description).isNull()
        assertThat(found.priority).isEqualTo(3)
        assertThat(found.labels).isEmpty()
        assertThat(found.environment).isNull()
        assertThat(found.impact).isNull()
    }

    /**
     * Given  version=1 로 삽입된 이슈
     * When   updateFields 로 description/priority/labels/environment/impact 변경 (expectedVersion=1)
     * Then   반환값 1, findByKey 로 조회 시 5필드 및 version+1 이 반영된다.
     */
    @Test
    @Order(16)
    fun `T10c - updateFields - 5필드 부분 업데이트 + OCC version+1`() {
        val key = IssueKey.of("TPRJ", 1L)
        val issue =
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = key,
                projectId = testProjectId,
                typeId = requireTaskTypeId(),
                summary = "updateFields 5필드 테스트",
                reporterId = ActorId(UUID.randomUUID()),
                currentStateKey = "open",
                description = "초기 설명",
                priority = 3,
                labels = listOf("initial"),
                environment = "staging",
                impact = 2,
            )
        repository.insert(issue)

        val updateCount = repository.updateFields(
            key = key,
            summary = null,
            typeId = null,
            expectedVersion = 1L,
            description = "업데이트된 설명",
            priority = 1,
            labels = listOf("updated", "label2"),
            environment = "production",
            impact = 3,
        )

        assertThat(updateCount).isEqualTo(1)
        val found = requireNotNull(repository.findByKey(key)) { "업데이트 후 이슈 조회 불가" }
        assertThat(found.version).isEqualTo(2L)
        assertThat(found.description).isEqualTo("업데이트된 설명")
        assertThat(found.priority).isEqualTo(1)
        assertThat(found.labels).containsExactly("updated", "label2")
        assertThat(found.environment).isEqualTo("production")
        assertThat(found.impact).isEqualTo(3)
    }

    // ── G4. findByKeyWithType — type 요약 노출 (FR-IS-02 Task 9) ─────────────────

    /**
     * Given  task 타입으로 생성된 이슈
     * When   findByKeyWithType 으로 조회
     * Then   typeId / typeKey = "task" / typeName = "Task" 가 IssueResponse 에 포함된다.
     */
    @Test
    @Order(12)
    fun `G4-findByKey - task 타입 이슈 조회 시 IssueResponse에 typeKey=task, typeName=Task가 포함된다`() {
        val key = IssueKey.of("TPRJ", 1L)
        val issue =
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = key,
                projectId = testProjectId,
                typeId = requireTaskTypeId(),
                summary = "type summary test",
                reporterId = ActorId(UUID.randomUUID()),
                currentStateKey = "open",
            )
        repository.insert(issue)

        val response = repository.findByKeyWithType(key)

        assertThat(response).isNotNull
        assertThat(response!!.typeId).isEqualTo(requireTaskTypeId().value)
        assertThat(response.typeKey).isEqualTo("task")
        assertThat(response.typeName).isEqualTo("Task")
    }

    /**
     * Given  task 타입 활성 이슈 2건
     * When   listWithType 으로 첫 페이지 조회
     * Then   반환된 IssueResponse 모두 typeKey = "task" 를 포함한다.
     */
    @Test
    @Order(13)
    fun `G4-list - 목록 조회 시 IssueResponse 각 항목에 typeKey=task가 포함된다`() {
        val reporterId = ActorId(UUID.randomUUID())
        for (i in 1..2) {
            repository.insert(
                Issue.create(
                    id = IssueId(UUID.randomUUID()),
                    key = IssueKey.of("TPRJ", i.toLong()),
                    projectId = testProjectId,
                    typeId = requireTaskTypeId(),
                    summary = "list type test $i",
                    reporterId = reporterId,
                    currentStateKey = "open",
                ),
            )
        }

        val page = repository.listWithType("TPRJ", PageRequest.of(0, 10))

        assertThat(page.totalElements).isEqualTo(2L)
        assertThat(page.content).allSatisfy { response ->
            assertThat(response.typeKey).isEqualTo("task")
            assertThat(response.typeName).isEqualTo("Task")
            assertThat(response.typeId).isEqualTo(requireTaskTypeId().value)
        }
    }
}
