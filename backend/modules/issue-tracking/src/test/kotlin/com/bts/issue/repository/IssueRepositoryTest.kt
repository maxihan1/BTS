// IssueRepository Testcontainers 통합 테스트 — 7 메서드 RED→GREEN 검증 (Task 5, FR-IS-01)

package com.bts.issue.repository

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.data.domain.PageRequest
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.util.UUID

/**
 * IssueRepository 통합 테스트.
 *
 * Testcontainers quay.io/tembo/pg16-pgmq:latest 위에서 Flyway V001 + V002 를 적용하고
 * IssueRepository 의 7 메서드를 순서대로 검증한다.
 * Spring ApplicationContext 없이 DSLContext 를 직접 조합한다 (project-workflow 패턴 준용).
 *
 * 테스트 시나리오.
 * - T1. insert — Issue 를 DB 에 삽입하면 반환된 Issue 의 id/key/version 이 기대 값과 일치한다.
 * - T2. findByKey — 삽입한 Issue 를 key 로 조회하면 동일 데이터가 반환된다.
 * - T3. findByKeyForUpdate — 비관락(SELECT FOR UPDATE) 조회 후 동일 key 가 반환된다.
 * - T4. applyTransition — version 일치 시 currentStateKey 가 업데이트되고 1 이 반환된다.
 * - T5. applyTransition stale — version 불일치 시 0 이 반환된다 (낙관락 충돌).
 * - T6. softDelete — 삭제 후 findByKey 가 null 을 반환한다.
 * - T7. list — 활성 이슈 목록을 페이지 단위로 조회한다.
 * - T8. incrementKeySequence — 동일 projectKey 로 두 번 호출 시 연속된 두 숫자를 반환한다.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class IssueRepositoryTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(
                DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                    .asCompatibleSubstituteFor("postgres"),
            )
                .withDatabaseName("bts_test")
                .withUsername("bts")
                .withPassword("bts_test")

        private lateinit var repository: IssueRepository

        // 테스트 전체에서 공유하는 프로젝트 ID — beforeAll 에서 projects 테이블에 삽입
        private lateinit var testProjectId: UUID

        @BeforeAll
        @JvmStatic
        fun setup() {
            // Flyway — DB 스키마 변경을 버전 관리하는 도구
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration")
                .load()
                .migrate()

            val dataSource =
                org.springframework.jdbc.datasource.DriverManagerDataSource(
                    postgres.jdbcUrl,
                    postgres.username,
                    postgres.password,
                )

            // jOOQ DSLContext — SQL을 코드로 안전하게 작성하는 라이브러리의 핵심 진입점
            val dsl = DSL.using(dataSource, SQLDialect.POSTGRES)
            repository = IssueRepository(dsl)

            // 테스트용 프로젝트 1건 삽입 — key_sequence = 0 으로 시작
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.prepareStatement(
                    "INSERT INTO projects (key, name) VALUES ('TPRJ', 'Test Project') RETURNING id",
                ).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        testProjectId = rs.getObject(1) as UUID
                    }
                }
            }
        }
    }

    // 각 테스트가 독립적으로 실행되도록 테스트마다 issues 를 초기화
    @BeforeEach
    fun cleanIssues() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DELETE FROM issues")
                stmt.execute("UPDATE projects SET key_sequence = 0 WHERE key = 'TPRJ'")
            }
        }
    }

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
                summary = "Fix login bug",
                reporterId = ActorId(UUID.randomUUID()),
                currentStateKey = "OPEN",
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
                summary = "Find by key test",
                reporterId = ActorId(UUID.randomUUID()),
                currentStateKey = "OPEN",
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
                summary = "For update test",
                reporterId = ActorId(UUID.randomUUID()),
                currentStateKey = "OPEN",
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
                summary = "Transition test",
                reporterId = ActorId(UUID.randomUUID()),
                currentStateKey = "OPEN",
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
                summary = "Stale version test",
                reporterId = ActorId(UUID.randomUUID()),
                currentStateKey = "OPEN",
            )
        repository.insert(issue)

        val updated = repository.applyTransition(key, "IN_PROGRESS", expectedVersion = 99L)

        assertThat(updated).isEqualTo(0)
        // 상태 변경 없음 확인
        val found = repository.findByKey(key)
        assertThat(found!!.currentStateKey).isEqualTo("OPEN")
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
                summary = "Soft delete test",
                reporterId = ActorId(UUID.randomUUID()),
                currentStateKey = "OPEN",
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
                    summary = "Issue $i",
                    reporterId = reporterId,
                    currentStateKey = "OPEN",
                ),
            )
        }
        // 이슈 4번은 소프트 삭제
        repository.insert(
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = IssueKey.of("TPRJ", 4L),
                projectId = testProjectId,
                summary = "Deleted issue",
                reporterId = reporterId,
                currentStateKey = "OPEN",
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
    @Order(10)
    fun `T9 - softDelete - 삭제 후 같은 key INSERT 시 DB unique constraint 위반`() {
        val key = IssueKey.of("TPRJ", 1L)
        val original =
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = key,
                projectId = testProjectId,
                summary = "원래",
                reporterId = ActorId(UUID.randomUUID()),
                currentStateKey = "OPEN",
            )
        repository.insert(original)
        repository.softDelete(key)

        val duplicate =
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = key,
                projectId = testProjectId,
                summary = "중복 시도",
                reporterId = ActorId(UUID.randomUUID()),
                currentStateKey = "OPEN",
            )

        assertThatThrownBy { repository.insert(duplicate) }
            .isInstanceOfAny(
                org.springframework.dao.DataIntegrityViolationException::class.java,
                org.jooq.exception.IntegrityConstraintViolationException::class.java,
            )
    }
}
