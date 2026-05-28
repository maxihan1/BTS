// IssueTypeRepository 통합 테스트 — findAll / findByKey / findById + V003 seed idempotent 검증

package com.bts.issue.type.repository

import com.bts.shared.issue.IssueTypeId
import com.bts.shared.issue.IssueTypeKey
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

/**
 * IssueTypeRepository 통합 테스트.
 *
 * 검증 범위.
 * - findAll: V003 seed 5 표준 타입 반환 확인
 * - findByKey: 존재하는 key → IssueType 반환
 * - findByKey: 존재하지 않는 key → null 반환
 * - findById: 존재하는 id → IssueType 반환
 * - findById: 존재하지 않는 id → null 반환
 * - idempotent: migrate() 2회 호출 시 findAll().size == 5 유지
 *
 * Spring 컨텍스트 없이 Testcontainers PostgreSQL + Flyway + jOOQ DSL 직접 구성.
 *
 * 이미지 선택 이유.
 * V002 마이그레이션이 pgmq 확장을 요구하므로 postgres:16-alpine 사용 불가.
 * quay.io/tembo/pg16-pgmq:latest (pgmq 사전 설치) 로 전체 마이그레이션 체인 실행.
 * ADR 2026-05-22-pgmq-postgres-image 와 동일 결정.
 *
 * read-only repository scope.
 * findAll / findByKey / findById 3 메서드만 검증.
 * CRUD (save / update / delete) 는 후속 FR-IS-02 PR scope.
 */
@Testcontainers
class IssueTypeRepositoryIntegrationTest {
    companion object {
        // quay.io/tembo/pg16-pgmq:latest — V002 pgmq 확장 요구로 인해 tembo 이미지 사용.
        // asCompatibleSubstituteFor("postgres"): Testcontainers 이미지 호환성 검증 우회.
        // ADR 2026-05-22-pgmq-postgres-image 와 동일 패턴.
        private val temboImage: DockerImageName =
            DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                .asCompatibleSubstituteFor("postgres")

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(temboImage)
                .withDatabaseName("bts_test")
                .withUsername("bts")
                .withPassword("bts_test")

        lateinit var repository: IssueTypeRepository

        @BeforeAll
        @JvmStatic
        fun setup() {
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
            repository = IssueTypeRepository(dsl)
        }
    }

    // ── findAll ──────────────────────────────────────────────────────────────────

    @Test
    fun `findAll - V003 seed 5 표준 타입 반환`() {
        val types = repository.findAll()

        assertThat(types).hasSize(5)
    }

    @Test
    fun `findAll - 5 표준 key 모두 포함`() {
        val keys = repository.findAll().map { it.key.value }

        assertThat(keys).containsExactlyInAnyOrder("epic", "story", "task", "subtask", "bug")
    }

    @Test
    fun `findAll - 모든 row 의 isStandard 는 true`() {
        val types = repository.findAll()

        assertThat(types).allMatch { it.isStandard }
    }

    @Test
    fun `findAll - 모든 row 의 id 는 non-null`() {
        val types = repository.findAll()

        assertThat(types).allMatch { it.id != null }
    }

    // ── findByKey ────────────────────────────────────────────────────────────────

    @Test
    fun `findByKey - epic key 조회 시 IssueType 반환`() {
        val issueType = repository.findByKey(IssueTypeKey("epic"))

        assertThat(issueType).isNotNull
        assertThat(issueType!!.key.value).isEqualTo("epic")
        assertThat(issueType.isStandard).isTrue()
        assertThat(issueType.id).isNotNull
    }

    @Test
    fun `findByKey - story key 조회`() {
        val issueType = repository.findByKey(IssueTypeKey("story"))

        assertThat(issueType).isNotNull
        assertThat(issueType!!.key.value).isEqualTo("story")
    }

    @Test
    fun `findByKey - task key 조회`() {
        val issueType = repository.findByKey(IssueTypeKey("task"))

        assertThat(issueType).isNotNull
        assertThat(issueType!!.key.value).isEqualTo("task")
    }

    @Test
    fun `findByKey - subtask key 조회`() {
        val issueType = repository.findByKey(IssueTypeKey("subtask"))

        assertThat(issueType).isNotNull
        assertThat(issueType!!.key.value).isEqualTo("subtask")
    }

    @Test
    fun `findByKey - bug key 조회`() {
        val issueType = repository.findByKey(IssueTypeKey("bug"))

        assertThat(issueType).isNotNull
        assertThat(issueType!!.key.value).isEqualTo("bug")
    }

    @Test
    fun `findByKey - 존재하지 않는 key 조회 시 null 반환`() {
        val issueType = repository.findByKey(IssueTypeKey("non-existent-type"))

        assertThat(issueType).isNull()
    }

    // ── findById ─────────────────────────────────────────────────────────────────

    @Test
    fun `findById - epic id 조회 시 IssueType 반환`() {
        val epic = repository.findByKey(IssueTypeKey("epic"))
        assertThat(epic).isNotNull
        val epicId = epic!!.id!!

        val found = repository.findById(epicId)

        assertThat(found).isNotNull
        assertThat(found!!.key.value).isEqualTo("epic")
        assertThat(found.id).isEqualTo(epicId)
    }

    @Test
    fun `findById - 존재하지 않는 id 조회 시 null 반환`() {
        val nonExistentId = IssueTypeId(Long.MAX_VALUE)

        val found = repository.findById(nonExistentId)

        assertThat(found).isNull()
    }

    // ── V003 seed idempotent ──────────────────────────────────────────────────────

    @Test
    fun `V003 seed idempotent - migrate 2회 호출 후에도 findAll size == 5`() {
        // 2번째 migrate() — ON CONFLICT DO NOTHING 이므로 중복 INSERT 없음
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .placeholderReplacement(false)
            .locations("classpath:db/migration/issue-tracking")
            .load()
            .migrate()

        val types = repository.findAll()

        assertThat(types).hasSize(5)
    }
}
