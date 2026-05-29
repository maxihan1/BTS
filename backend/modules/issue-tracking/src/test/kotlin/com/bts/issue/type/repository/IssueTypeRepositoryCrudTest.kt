// IssueTypeRepository CRUD 통합 테스트 — insert / update / softDelete / countIssuesByTypeId / reassignIssues

package com.bts.issue.type.repository

import com.bts.issue.type.domain.IssueType
import com.bts.shared.issue.IssueTypeKey
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.util.UUID

/**
 * IssueTypeRepository CRUD 통합 테스트.
 *
 * 검증 범위.
 * - insert: 커스텀 IssueType 삽입 → findById 조회 일치 (hierarchyLevel 포함)
 * - update: name/description/iconName/hierarchyLevel 변경 → DB 반영 확인
 * - softDelete: deleted_at 설정 → findById null (활성 필터)
 * - countIssuesByTypeId: 해당 type_id 활성 이슈 수 스칼라 반환
 * - reassignIssues: issues.type_id 일괄 변경 + version+1 → 변경 건수 반환
 * - (B1) soft-delete된 key 재INSERT 허용 (부분 unique index ux_issue_types_key_active)
 *
 * Spring 컨텍스트 없이 Testcontainers + Flyway + jOOQ DSL 직접 구성.
 * JVM singleton container — .apply{start()} 패턴 (IssueTestcontainersBase 선례).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IssueTypeRepositoryCrudTest {
    companion object {
        /**
         * JVM 단위 singleton PostgreSQL container.
         * quay.io/tembo/pg16-pgmq:latest — V002 pgmq 확장 요구로 인해 tembo 이미지 사용.
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

    lateinit var dsl: DSLContext
    lateinit var repository: IssueTypeRepository

    private var bootstrapped = false

    @BeforeAll
    fun bootstrap() {
        if (bootstrapped) return

        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .placeholderReplacement(false)
            .locations("classpath:db/migration/issue-tracking")
            .load()
            .migrate()

        val dataSource =
            DriverManagerDataSource(
                postgres.jdbcUrl,
                postgres.username,
                postgres.password,
            )
        dsl = DSL.using(dataSource, SQLDialect.POSTGRES)
        repository = IssueTypeRepository(dsl)

        // 프로젝트 삽입 — issues 테이블 FK 충족용
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "INSERT INTO projects (key, name) VALUES ('CRUDPRJ', 'CRUD Test Project') ON CONFLICT DO NOTHING",
            ).execute()
        }

        bootstrapped = true
    }

    @BeforeEach
    fun cleanCustomTypes() {
        // 각 테스트 독립성 — issues + 커스텀 issue_types 제거 (표준 5종은 is_standard=true 로 구분)
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DELETE FROM issues")
                stmt.execute("DELETE FROM issue_types WHERE is_standard = false")
            }
        }
    }

    // ── insert ───────────────────────────────────────────────────────────────────

    @Test
    fun `insert - 커스텀 IssueType 삽입 후 findById 조회 일치`() {
        val newType =
            IssueType.create(
                key = IssueTypeKey("feature"),
                name = "Feature",
                description = "신규 기능 요청",
                iconName = "feature-icon",
                isStandard = false,
                hierarchyLevel = 0,
            )

        val inserted = repository.insert(newType)

        assertThat(inserted.id).isNotNull
        val found = repository.findById(inserted.id!!)
        assertThat(found).isNotNull
        assertThat(found!!.key.value).isEqualTo("feature")
        assertThat(found.name).isEqualTo("Feature")
        assertThat(found.description).isEqualTo("신규 기능 요청")
        assertThat(found.iconName).isEqualTo("feature-icon")
        assertThat(found.isStandard).isFalse()
        assertThat(found.hierarchyLevel).isEqualTo(0)
        assertThat(found.deletedAt).isNull()
    }

    @Test
    fun `insert - hierarchyLevel = 1 (epic 계층) 저장 확인`() {
        val newType =
            IssueType.create(
                key = IssueTypeKey("initiative"),
                name = "Initiative",
                hierarchyLevel = 1,
            )

        val inserted = repository.insert(newType)

        val found = repository.findById(inserted.id!!)
        assertThat(found!!.hierarchyLevel).isEqualTo(1)
    }

    @Test
    fun `insert - hierarchyLevel = -1 (subtask 계층) 저장 확인`() {
        val newType =
            IssueType.create(
                key = IssueTypeKey("chore"),
                name = "Chore",
                hierarchyLevel = -1,
            )

        val inserted = repository.insert(newType)

        val found = repository.findById(inserted.id!!)
        assertThat(found!!.hierarchyLevel).isEqualTo(-1)
    }

    // ── update ───────────────────────────────────────────────────────────────────

    @Test
    fun `update - name description iconName hierarchyLevel 변경 반영 확인`() {
        val original =
            repository.insert(
                IssueType.create(
                    key = IssueTypeKey("improve"),
                    name = "Improvement",
                    description = "원래 설명",
                    iconName = "orig-icon",
                    hierarchyLevel = 0,
                ),
            )

        repository.update(
            original.copy(
                name = "개선사항",
                description = "변경된 설명",
                iconName = "new-icon",
                hierarchyLevel = 1,
            ),
        )

        val found = repository.findById(original.id!!)
        assertThat(found!!.name).isEqualTo("개선사항")
        assertThat(found.description).isEqualTo("변경된 설명")
        assertThat(found.iconName).isEqualTo("new-icon")
        assertThat(found.hierarchyLevel).isEqualTo(1)
    }

    // ── softDelete ───────────────────────────────────────────────────────────────

    @Test
    fun `softDelete - deleted_at 설정 후 findById null 반환`() {
        val inserted =
            repository.insert(
                IssueType.create(key = IssueTypeKey("remove-me"), name = "Remove Me"),
            )

        repository.softDelete(inserted.id!!)

        val found = repository.findById(inserted.id!!)
        assertThat(found).isNull()
    }

    // ── countIssuesByTypeId ───────────────────────────────────────────────────────

    @Test
    fun `countIssuesByTypeId - 해당 type_id 활성 이슈 수 반환`() {
        val typeA = repository.insert(IssueType.create(key = IssueTypeKey("type-a"), name = "Type A"))
        val typeB = repository.insert(IssueType.create(key = IssueTypeKey("type-b"), name = "Type B"))

        val projectId = fetchProjectId("CRUDPRJ")
        insertIssue(projectId = projectId, typeId = typeA.id!!.value, key = "CRUDPRJ-1")
        insertIssue(projectId = projectId, typeId = typeA.id!!.value, key = "CRUDPRJ-2")
        insertIssue(projectId = projectId, typeId = typeB.id!!.value, key = "CRUDPRJ-3")

        val countA = repository.countIssuesByTypeId(typeA.id!!.value)
        val countB = repository.countIssuesByTypeId(typeB.id!!.value)

        assertThat(countA).isEqualTo(2L)
        assertThat(countB).isEqualTo(1L)
    }

    @Test
    fun `countIssuesByTypeId - 소프트 삭제된 이슈는 카운트 제외`() {
        val typeC = repository.insert(IssueType.create(key = IssueTypeKey("type-c"), name = "Type C"))
        val projectId = fetchProjectId("CRUDPRJ")
        insertIssue(projectId = projectId, typeId = typeC.id!!.value, key = "CRUDPRJ-10")
        softDeleteIssue("CRUDPRJ-10")

        val count = repository.countIssuesByTypeId(typeC.id!!.value)

        assertThat(count).isEqualTo(0L)
    }

    // ── reassignIssues ────────────────────────────────────────────────────────────

    @Test
    fun `reassignIssues - fromType 이슈를 toType 으로 일괄 변경 + 변경 건수 반환`() {
        val fromType = repository.insert(IssueType.create(key = IssueTypeKey("from-type"), name = "From Type"))
        val toType = repository.insert(IssueType.create(key = IssueTypeKey("to-type"), name = "To Type"))
        val projectId = fetchProjectId("CRUDPRJ")
        insertIssue(projectId = projectId, typeId = fromType.id!!.value, key = "CRUDPRJ-20")
        insertIssue(projectId = projectId, typeId = fromType.id!!.value, key = "CRUDPRJ-21")
        insertIssue(projectId = projectId, typeId = toType.id!!.value, key = "CRUDPRJ-22")

        val changed = repository.reassignIssues(fromType.id!!.value, toType.id!!.value)

        assertThat(changed).isEqualTo(2L)
        assertThat(repository.countIssuesByTypeId(fromType.id!!.value)).isEqualTo(0L)
        assertThat(repository.countIssuesByTypeId(toType.id!!.value)).isEqualTo(3L)
    }

    @Test
    fun `reassignIssues - version 이 1 증가한다`() {
        val fromType = repository.insert(IssueType.create(key = IssueTypeKey("from-v"), name = "From V"))
        val toType = repository.insert(IssueType.create(key = IssueTypeKey("to-v"), name = "To V"))
        val projectId = fetchProjectId("CRUDPRJ")
        insertIssue(projectId = projectId, typeId = fromType.id!!.value, key = "CRUDPRJ-30")

        val versionBefore = fetchIssueVersion("CRUDPRJ-30")
        repository.reassignIssues(fromType.id!!.value, toType.id!!.value)
        val versionAfter = fetchIssueVersion("CRUDPRJ-30")

        assertThat(versionAfter).isEqualTo(versionBefore + 1)
    }

    // ── B1: 부분 unique index 재INSERT 허용 ───────────────────────────────────────

    @Test
    fun `B1 - soft-delete된 key 재INSERT 허용 (부분 unique index ux_issue_types_key_active)`() {
        val first =
            repository.insert(
                IssueType.create(key = IssueTypeKey("reusable-key"), name = "First"),
            )
        repository.softDelete(first.id!!)

        // 동일 key 재 INSERT — deleted_at IS NULL 조건 부분 unique 이므로 허용
        val second =
            repository.insert(
                IssueType.create(key = IssueTypeKey("reusable-key"), name = "Second"),
            )

        assertThat(second.id).isNotNull
        assertThat(second.id!!.value).isNotEqualTo(first.id!!.value)
        val found = repository.findByKey(IssueTypeKey("reusable-key"))
        assertThat(found).isNotNull
        assertThat(found!!.name).isEqualTo("Second")
    }

    // ── private helpers ───────────────────────────────────────────────────────────

    private fun fetchProjectId(projectKey: String): UUID =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("SELECT id FROM projects WHERE key = ?").use { stmt ->
                stmt.setString(1, projectKey)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getObject(1) as UUID
                }
            }
        }

    private fun insertIssue(
        projectId: UUID,
        typeId: Long,
        key: String,
    ) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO issues (key, project_id, type_id, summary, reporter_id, current_state_key)
                VALUES (?, ?, ?, 'test summary', gen_random_uuid(), 'open')
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, key)
                stmt.setObject(2, projectId)
                stmt.setLong(3, typeId)
                stmt.execute()
            }
        }
    }

    private fun softDeleteIssue(issueKey: String) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                "UPDATE issues SET deleted_at = NOW() WHERE key = ?",
            ).use { stmt ->
                stmt.setString(1, issueKey)
                stmt.execute()
            }
        }
    }

    private fun fetchIssueVersion(issueKey: String): Long =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("SELECT version FROM issues WHERE key = ?").use { stmt ->
                stmt.setString(1, issueKey)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        }
}
