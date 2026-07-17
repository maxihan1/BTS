// ProjectCreateRepository 통합 테스트 — INSERT + UNIQUE(EC-1/EC-6)/CHECK(PJ1-6) 제약 검증 (FR-PJ-01)

package com.bts.issue.project.repository

import com.bts.issue.project.domain.Project
import com.bts.issue.project.domain.ProjectKeyAlreadyExistsException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.exception.IntegrityConstraintViolationException
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.time.OffsetDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * [ProjectCreateRepository] 통합 테스트.
 *
 * `IssueTypeRepositoryIntegrationTest` 와 동형 패턴 — 클래스 전용 `@Testcontainers` +
 * `@Container` 라이프사이클로 독립 PostgreSQL 컨테이너를 기동한다. Spring 컨텍스트 없이
 * Flyway 로 `db/migration/issue-tracking` 을 적용하고 raw jOOQ `DSLContext` 로 검증한다.
 *
 * 검증 범위.
 * - insert 성공 — id/key/name 반환.
 * - key 중복(PJ1-7) — [ProjectKeyAlreadyExistsException] 변환.
 * - key 정규식 위반(PJ1-6) — DB CHECK 제약이 원 예외를 그대로 전파(도메인 예외로 변환하지 않음).
 * - 소프트 삭제된 프로젝트의 key 재사용(EC-1) — DB UNIQUE 는 deleted_at 무관하게 점유하므로 409.
 * - 동시 동일 key 2건 insert(EC-6) — DB UNIQUE 가 진실원천, 1건만 성공.
 */
@Testcontainers
class ProjectCreateRepositoryIntegrationTest {
    companion object {
        // quay.io/tembo/pg16-pgmq:latest — V002 마이그레이션의 pgmq 확장 요구로 tembo 이미지 사용.
        // asCompatibleSubstituteFor("postgres"): Testcontainers 이미지 호환성 검증 우회.
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

        lateinit var repository: ProjectCreateRepository

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
                DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            val dsl = DSL.using(dataSource, SQLDialect.POSTGRES)
            repository = ProjectCreateRepository(dsl)
        }
    }

    /** 각 테스트가 독립적으로 실행되도록 테스트마다 projects 행을 전부 삭제한다. */
    @BeforeEach
    fun cleanProjects() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.createStatement().use { it.execute("DELETE FROM projects") }
        }
    }

    @Test
    fun `insert 가 projects 1행을 만들고 생성된 id-key-name 을 반환한다`() {
        val created = repository.insert(key = "PJ1", name = "Project One")

        assertThat(created.id).isNotNull
        assertThat(created.key).isEqualTo("PJ1")
        assertThat(created.name).isEqualTo("Project One")
    }

    @Test
    fun `key 중복 시 도메인 예외(409 매핑) 를 던진다 (PJ1-7)`() {
        repository.insert(key = "DUP1", name = "First Project")

        assertThatThrownBy { repository.insert(key = "DUP1", name = "Second Project") }
            .isInstanceOf(ProjectKeyAlreadyExistsException::class.java)
    }

    @Test
    fun `key 정규식 위반은 DB CHECK 로 거부한다 (PJ1-6)`() {
        assertThatThrownBy { repository.insert(key = "invalid-key", name = "Bad Key Project") }
            .isInstanceOf(IntegrityConstraintViolationException::class.java)
            .isNotInstanceOf(ProjectKeyAlreadyExistsException::class.java)
    }

    @Test
    fun `소프트 삭제된 프로젝트의 key 로 생성 시 409 (EC-1)`() {
        val softDeletedKey = "SDEL1"
        val original = repository.insert(key = softDeletedKey, name = "Original Project")
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("UPDATE projects SET deleted_at = ? WHERE id = ?").use { stmt ->
                stmt.setObject(1, OffsetDateTime.now())
                stmt.setObject(2, original.id)
                stmt.executeUpdate()
            }
        }

        assertThatThrownBy { repository.insert(key = softDeletedKey, name = "Reuse Attempt") }
            .isInstanceOf(ProjectKeyAlreadyExistsException::class.java)
    }

    @Test
    fun `동시에 같은 key 로 2건 insert 시 1건은 201 1건은 409 (EC-6)`() {
        val key = "CONC1"
        val executor = Executors.newFixedThreadPool(2)
        val startLatch = CountDownLatch(1)
        val results = java.util.Collections.synchronizedList(mutableListOf<Result<Project>>())

        val tasks =
            (1..2).map { idx ->
                executor.submit {
                    startLatch.await()
                    results.add(runCatching { repository.insert(key = key, name = "Concurrent $idx") })
                }
            }
        startLatch.countDown()
        tasks.forEach { it.get(10, TimeUnit.SECONDS) }
        executor.shutdown()

        assertThat(results).hasSize(2)
        assertThat(results.count { it.isSuccess }).isEqualTo(1)
        assertThat(results.count { it.isFailure }).isEqualTo(1)
        assertThat(results.single { it.isFailure }.exceptionOrNull())
            .isInstanceOf(ProjectKeyAlreadyExistsException::class.java)
    }
}
