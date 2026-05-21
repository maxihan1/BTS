// YamlSeedService 4 case — 적재 / no-op / 재적재 / FailFast

package com.bts.workflow.seed

import com.bts.workflow.repository.WorkflowRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.slf4j.LoggerFactory
import org.springframework.core.io.AbstractResource
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.core.io.Resource
import org.springframework.core.io.ResourceLoader
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * YamlSeedService 통합 테스트.
 *
 * Testcontainers PostgreSQL + Flyway V001 적용 후 YamlSeedService를 직접 호출한다.
 * Spring ApplicationContext 없이 필요한 의존성을 직접 조합한다.
 *
 * 검증 범위.
 * 1. 부팅 시 4 YAML 적재 → workflows 4건 + states/transitions 정합
 * 2. 동일 YAML 재호출 → no-op (skip)
 * 3. YAML 변경 후 재호출 → 재적재 (dirty diff)
 * 4. 잘못된 YAML → IllegalStateException (fail-fast 부팅 차단)
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class YamlSeedServiceTest {

    companion object {
        private val log = LoggerFactory.getLogger(YamlSeedServiceTest::class.java)

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("bts_test")
                .withUsername("bts")
                .withPassword("bts_test")

        lateinit var service: YamlSeedService
        lateinit var repository: WorkflowRepository

        @BeforeAll
        @JvmStatic
        fun setup() {
            // Flyway — DB 스키마 변경을 버전 관리하는 도구. V001 마이그레이션으로 5 테이블 생성
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration")
                .load()
                .migrate()

            val dataSource = DriverManagerDataSource(
                postgres.jdbcUrl,
                postgres.username,
                postgres.password,
            )

            // jOOQ DSLContext — SQL을 코드로 안전하게 작성하는 라이브러리의 핵심 진입점
            val dsl = DSL.using(dataSource, SQLDialect.POSTGRES)

            // Jackson YAML 매퍼 — YAML 파일을 Kotlin 데이터 클래스로 역직렬화
            val yamlMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

            repository = WorkflowRepository(dsl)
            service = YamlSeedService(repository, dsl, DefaultResourceLoader(), yamlMapper)
        }
    }

    // ── 시나리오 1. 부팅 시 4 YAML 적재 → workflows 4건 + states/transitions 정합 ──

    @Test
    @Order(1)
    fun `부팅 시 4 YAML 적재되어 4 workflows 와 states transitions 가 정합한다`() {
        service.seed()

        val workflows = repository.findAll()
        assertThat(workflows).hasSize(4)

        val keys = workflows.map { it.key }
        assertThat(keys).containsExactlyInAnyOrder(
            "software-default",
            "bug-tracking",
            "simple",
            "kanban-basic",
        )

        // software-default: 5 states, 6 transitions
        val softwareDefault = workflows.first { it.key == "software-default" }
        assertThat(softwareDefault.states).hasSize(5)
        assertThat(softwareDefault.transitions).hasSize(6)

        // bug-tracking: 5 states, 5 transitions
        val bugTracking = workflows.first { it.key == "bug-tracking" }
        assertThat(bugTracking.states).hasSize(5)
        assertThat(bugTracking.transitions).hasSize(5)

        // simple: 3 states, 3 transitions
        val simple = workflows.first { it.key == "simple" }
        assertThat(simple.states).hasSize(3)
        assertThat(simple.transitions).hasSize(3)

        // kanban-basic: 4 states, 3 transitions
        val kanbanBasic = workflows.first { it.key == "kanban-basic" }
        assertThat(kanbanBasic.states).hasSize(4)
        assertThat(kanbanBasic.transitions).hasSize(3)

        log.info("시나리오 1 통과 — 4 workflows 적재 완료")
    }

    // ── 시나리오 2. 동일 YAML 재호출 → no-op (skip) ──────────────────────────────

    @Test
    @Order(2)
    fun `동일 YAML 재호출 시 skip 하고 row 수가 변하지 않는다`() {
        // Order(1) 에서 이미 적재됨. 동일 seed() 재호출 — dirty diff 비교로 skip
        service.seed()

        val workflows = repository.findAll()
        // 재적재 없이 동일 4개 유지
        assertThat(workflows).hasSize(4)

        // 재적재 발생 시 states 수가 중복될 수 있음 — skip이면 그대로 5개
        val softwareDefault = workflows.first { it.key == "software-default" }
        assertThat(softwareDefault.states).hasSize(5)

        log.info("시나리오 2 통과 — no-op skip 확인")
    }

    // ── 시나리오 3. YAML 변경 후 재호출 → 재적재 (dirty diff) ────────────────────

    @Test
    @Order(3)
    fun `YAML 변경 시 dirty diff 감지 후 재적재한다`() {
        val dataSource = DriverManagerDataSource(
            postgres.jdbcUrl,
            postgres.username,
            postgres.password,
        )
        val dsl = DSL.using(dataSource, SQLDialect.POSTGRES)
        val yamlMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

        // "simple" 워크플로우의 name 을 변경한 버전으로 재적재를 검증한다.
        // 표준 4 YAML 중 simple 만 수정된 ResourceLoader 를 주입한다.
        val modifiedResourceLoader = ModifiedSimpleWorkflowResourceLoader()
        val serviceWithModified = YamlSeedService(WorkflowRepository(dsl), dsl, modifiedResourceLoader, yamlMapper)

        serviceWithModified.seed()

        // 재적재 후 simple 워크플로우 이름 변경 확인
        val workflows = WorkflowRepository(dsl).findAll()
        val simple = workflows.first { it.key == "simple" }
        assertThat(simple.name).isEqualTo("단순 워크플로우 변경됨")

        log.info("시나리오 3 통과 — dirty diff 재적재 확인")
    }

    // ── 시나리오 4. 잘못된 YAML → IllegalStateException (FailFast) ────────────────

    @Test
    @Order(4)
    fun `잘못된 YAML 은 IllegalStateException 으로 부팅을 차단한다`() {
        val dataSource = DriverManagerDataSource(
            postgres.jdbcUrl,
            postgres.username,
            postgres.password,
        )
        val dsl = DSL.using(dataSource, SQLDialect.POSTGRES)
        val yamlMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

        // states 가 비어 있는 잘못된 YAML 을 제공하는 ResourceLoader
        val invalidResourceLoader = InvalidWorkflowResourceLoader()
        val serviceWithInvalid = YamlSeedService(WorkflowRepository(dsl), dsl, invalidResourceLoader, yamlMapper)

        assertThatThrownBy { serviceWithInvalid.seed() }
            .isInstanceOf(IllegalStateException::class.java)

        log.info("시나리오 4 통과 — FailFast 부팅 차단 확인")
    }
}

// ── 테스트 헬퍼 ResourceLoader ──────────────────────────────────────────────────

/**
 * "simple" 워크플로우만 수정된 버전으로 교체하고 나머지는 classpath 에서 읽는 ResourceLoader.
 * 시나리오 3 (dirty diff 재적재) 검증에 사용한다.
 */
private class ModifiedSimpleWorkflowResourceLoader : ResourceLoader {
    private val delegate = DefaultResourceLoader()

    /** "simple" YAML 이름이 변경된 버전 (dirty diff 유발). */
    private val modifiedSimpleYaml =
        """
        # 단순 워크플로우 (변경됨)
        key: simple
        name: 단순 워크플로우 변경됨
        states:
          - { key: todo, name: To Do, category: TODO, displayOrder: 1 }
          - { key: doing, name: Doing, category: IN_PROGRESS, displayOrder: 2 }
          - { key: done, name: Done, category: DONE, displayOrder: 3 }
        transitions:
          - { from: todo, to: doing, name: Start }
          - { from: doing, to: done, name: Complete }
          - { from: done, to: doing, name: Reopen }
        """.trimIndent().toByteArray()

    override fun getResource(location: String): Resource =
        if (location.endsWith("simple.yaml")) InMemoryResource(modifiedSimpleYaml, "simple.yaml")
        else delegate.getResource(location)

    override fun getClassLoader() = delegate.classLoader
}

/**
 * states 가 비어 있는 잘못된 YAML 을 반환하는 ResourceLoader.
 * 시나리오 4 (FailFast) 검증에 사용한다.
 */
private class InvalidWorkflowResourceLoader : ResourceLoader {
    private val delegate = DefaultResourceLoader()

    /** states 가 비어 있어 Konform 검증에서 실패해야 하는 YAML. */
    private val invalidYaml =
        """
        # 잘못된 워크플로우 (states 없음)
        key: software-default
        name: 잘못된 워크플로우
        states: []
        transitions: []
        """.trimIndent().toByteArray()

    override fun getResource(location: String): Resource =
        if (location.endsWith("software-default.yaml")) InMemoryResource(invalidYaml, "software-default.yaml")
        else delegate.getResource(location)

    override fun getClassLoader() = delegate.classLoader
}

/** 인메모리 바이트 배열을 Spring Resource 로 감싸는 헬퍼. */
private class InMemoryResource(
    private val bytes: ByteArray,
    private val resourceDescription: String,
) : AbstractResource() {
    override fun getDescription(): String = "InMemoryResource[$resourceDescription]"
    override fun getInputStream(): InputStream = ByteArrayInputStream(bytes)
    override fun exists(): Boolean = true
}
