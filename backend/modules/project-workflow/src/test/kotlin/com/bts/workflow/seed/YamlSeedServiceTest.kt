// YamlSeedService 4 case — 적재 / no-op / 재적재 / FailFast

package com.bts.workflow.seed

import com.bts.workflow.repository.WorkflowRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.mockk
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
import org.testcontainers.utility.DockerImageName
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.sql.DriverManager

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

        // quay.io/tembo/pg16-pgmq:latest — V004 pgmq 확장 요구로 인해 tembo 이미지 사용.
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

        lateinit var service: YamlSeedService
        lateinit var repository: WorkflowRepository

        @BeforeAll
        @JvmStatic
        fun setup() {
            // Flyway — DB 스키마 변경을 버전 관리하는 도구.
            // V201 (workflow_schemes) 가 issue_types FK 를 참조하므로 2단계 실행:
            //   1단계: V200 (project-workflow init) 까지만 — cross-BC dep 으로 issue-tracking V001~V003 도 함께 적용
            //   2단계: issue_types 스텁 IF NOT EXISTS 안전판 → V201 까지
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations(
                    "classpath:db/migration/issue-tracking",
                    "classpath:db/migration/project-workflow",
                )
                .target("200")
                .load()
                .migrate()

            // issue_types 스텁 — V004 의 cross-BC FK 통과용 (프로덕션에서는 issue-tracking V003 이 생성)
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        """
                        CREATE TABLE IF NOT EXISTS issue_types (
                            id          BIGSERIAL    PRIMARY KEY,
                            key         VARCHAR(30)  NOT NULL UNIQUE,
                            name        VARCHAR(255) NOT NULL,
                            is_standard BOOLEAN      NOT NULL DEFAULT FALSE,
                            created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                            updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                            deleted_at  TIMESTAMPTZ
                        )
                        """.trimIndent(),
                    )
                }
            }

            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations(
                    "classpath:db/migration/issue-tracking",
                    "classpath:db/migration/project-workflow",
                )
                .load()
                .migrate()

            val dataSource =
                DriverManagerDataSource(
                    postgres.jdbcUrl,
                    postgres.username,
                    postgres.password,
                )

            // jOOQ DSLContext — SQL을 코드로 안전하게 작성하는 라이브러리의 핵심 진입점
            val dsl = DSL.using(dataSource, SQLDialect.POSTGRES)

            // Jackson YAML 매퍼 — YAML 파일을 Kotlin 데이터 클래스로 역직렬화
            val yamlMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

            repository = WorkflowRepository(dsl)
            // 표준 4 워크플로우는 validator/postAction 이 없어 factory 미호출 → relaxed mock 으로 충분
            service =
                YamlSeedService(
                    repository,
                    dsl,
                    DefaultResourceLoader(),
                    yamlMapper,
                    mockk(relaxed = true),
                    mockk(relaxed = true),
                )
        }
    }

    // ── 시나리오 1. 부팅 시 4 YAML 적재 → workflows 4건 + states/transitions 정합 ──

    @Test
    @Order(1)
    fun `부팅 시 4 YAML 적재되어 4 workflows 와 states transitions 가 정합한다`() {
        service.seedAll()

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
        service.seedAll()

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
        val dataSource =
            DriverManagerDataSource(
                postgres.jdbcUrl,
                postgres.username,
                postgres.password,
            )
        val dsl = DSL.using(dataSource, SQLDialect.POSTGRES)
        val yamlMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

        // "simple" 워크플로우의 name 을 변경한 버전으로 재적재를 검증한다.
        // 표준 4 YAML 중 simple 만 수정된 ResourceLoader 를 주입한다.
        val modifiedResourceLoader = ModifiedSimpleWorkflowResourceLoader()
        val serviceWithModified =
            YamlSeedService(
                WorkflowRepository(dsl),
                dsl,
                modifiedResourceLoader,
                yamlMapper,
                mockk(relaxed = true),
                mockk(relaxed = true),
            )

        serviceWithModified.seedAll()

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
        val dataSource =
            DriverManagerDataSource(
                postgres.jdbcUrl,
                postgres.username,
                postgres.password,
            )
        val dsl = DSL.using(dataSource, SQLDialect.POSTGRES)
        val yamlMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

        // states 가 비어 있는 잘못된 YAML 을 제공하는 ResourceLoader
        val invalidResourceLoader = InvalidWorkflowResourceLoader()
        val serviceWithInvalid =
            YamlSeedService(
                WorkflowRepository(dsl),
                dsl,
                invalidResourceLoader,
                yamlMapper,
                mockk(relaxed = true),
                mockk(relaxed = true),
            )

        assertThatThrownBy { serviceWithInvalid.seedAll() }
            .isInstanceOf(IllegalStateException::class.java)

        log.info("시나리오 4 통과 — FailFast 부팅 차단 확인")
    }

    // ── 시나리오 5. (from, to) 중복 전이 정의 → IllegalStateException fail-fast ─────

    @Test
    @Order(5)
    fun `같은 워크플로우 안에 from-to 가 동일한 전이가 중복 정의되면 IllegalStateException 이 발생한다`() {
        val dataSource =
            DriverManagerDataSource(
                postgres.jdbcUrl,
                postgres.username,
                postgres.password,
            )
        val dsl = DSL.using(dataSource, SQLDialect.POSTGRES)
        val yamlMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

        // (from: open, to: done) 이 name 만 다르게 두 번 정의된 중복 YAML
        val duplicateTransitionResourceLoader = DuplicateTransitionResourceLoader()
        val serviceWithDuplicate =
            YamlSeedService(
                WorkflowRepository(dsl),
                dsl,
                duplicateTransitionResourceLoader,
                yamlMapper,
                mockk(relaxed = true),
                mockk(relaxed = true),
            )

        assertThatThrownBy { serviceWithDuplicate.seedAll() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("duplicate (from, to)=(open,done)")

        log.info("시나리오 5 통과 — (from,to) 중복 fail-fast 확인")
    }

    // ── 시나리오 6. seedAll() 에 @Transactional 어노테이션 존재 검증 ─────────────────

    /**
     * Spring AOP 기반 `@Transactional` 은 Spring ApplicationContext 없이는 동작하지 않으므로
     * 어노테이션 존재 여부를 reflection 으로 검증한다.
     *
     * `seedAll()` 에 `@Transactional` 이 있으면 Spring 이 트랜잭션 경계를 보장하며,
     * DELETE 후 INSERT 실패 시 롤백이 일어남을 컴파일 타임에 보장할 수 있다.
     */
    @Test
    @Order(6)
    fun `seedAll 에 @Transactional 과 @EventListener 어노테이션이 있어야 한다`() {
        val seedAllMethod = YamlSeedService::class.java.getMethod("seedAll")
        assertThat(
            seedAllMethod.isAnnotationPresent(
                org.springframework.transaction.annotation.Transactional::class.java,
            ),
        ).`as`("seedAll() 에 @Transactional 어노테이션 필요").isTrue()

        assertThat(
            seedAllMethod.isAnnotationPresent(
                org.springframework.context.event.EventListener::class.java,
            ),
        ).`as`("seedAll() 에 @EventListener 어노테이션 필요").isTrue()

        log.info("시나리오 5 통과 — @Transactional + @EventListener 어노테이션 존재 확인")
    }

    // ── 시나리오 7. 공존 B — 런타임 post-action 이 재시드 후에도 보존된다 ─────────────

    /**
     * 공존(B) 검증.
     *
     * `isDirty` 에서 `differsInPostActions` 가 제거되면 YAML 이 동일한 한
     * 재시드가 트리거되지 않아 런타임 post-action 이 보존된다.
     *
     * 제거 전(현재 상태): DB post-action 이 있지만 YAML 에 없으면 isDirty=true → 재시드 → 소실 → RED.
     * 제거 후: YAML state/transition 이 동일하면 isDirty=false → skip → 런타임 행 보존 → GREEN.
     *
     * simple 워크플로우를 선택한 이유.
     * - 상태 3개 / 전이 3개의 가장 단순한 구조.
     * - YAML post_action 이 0건이라 런타임 추가 행이 유일한 DB 행.
     * - Order(3) 에서 이름이 변경되어 재적재된 뒤 Order(7) 에서 재시드 = same-YAML no-op.
     *
     * 주의: Order(3) 이 "simple" 워크플로우 이름을 변경해 재적재했으므로,
     * 이 시나리오는 수정된 이름(단순 워크플로우 변경됨) 기준으로 실행된다.
     * 재시드 ResourceLoader 가 동일 내용 → no-op 기대.
     */
    @Test
    @Order(7)
    fun `공존 B - 런타임 post-action 추가 후 재시드 시 보존된다`() {
        val dataSource =
            DriverManagerDataSource(
                postgres.jdbcUrl,
                postgres.username,
                postgres.password,
            )
        val dsl = DSL.using(dataSource, SQLDialect.POSTGRES)

        // simple 워크플로우의 todo→doing 전이 id 를 직접 조회 (jOOQ 없이 SQL)
        val transitionId: java.util.UUID =
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.prepareStatement(
                    """
                    SELECT wt.id
                    FROM workflow_transitions wt
                    JOIN workflow_states fs ON wt.from_state_id = fs.id
                    JOIN workflow_states ts ON wt.to_state_id = ts.id
                    JOIN workflows w ON wt.workflow_id = w.id
                    WHERE w.key = 'simple' AND fs.key = 'todo' AND ts.key = 'doing'
                    """.trimIndent(),
                ).use { ps ->
                    ps.executeQuery().use { rs ->
                        if (rs.next()) java.util.UUID.fromString(rs.getString("id"))
                        else error("simple 워크플로우 todo→doing 전이 없음 — Order(1) 이 먼저 실행되어야 함")
                    }
                }
            }

        // 런타임 post-action 직접 삽입
        dsl.execute(
            "INSERT INTO workflow_post_actions (transition_id, type, config, display_order) VALUES (?::uuid, ?, ?::jsonb, ?)",
            transitionId.toString(),
            "CALL_WEBHOOK",
            """{"url":"https://runtime.example.com","method":"POST"}""",
            0,
        )

        // 삽입 확인
        val countBefore =
            dsl.fetchValue(
                "SELECT COUNT(*) FROM workflow_post_actions WHERE transition_id = ?::uuid",
                transitionId.toString(),
            ) as Long
        assertThat(countBefore).isGreaterThanOrEqualTo(1L)

        // 동일 YAML 로 재시드 — differsInPostActions 제거 전에는 isDirty=true 로 재적재하여 소실
        val yamlMapper =
            com.fasterxml.jackson.databind.ObjectMapper(
                com.fasterxml.jackson.dataformat.yaml.YAMLFactory(),
            ).registerKotlinModule()
        val serviceToReseed =
            YamlSeedService(
                WorkflowRepository(dsl),
                dsl,
                ModifiedSimpleWorkflowResourceLoader(), // Order(3) 와 동일 내용 → no-op 기대
                yamlMapper,
                mockk(relaxed = true),
                mockk(relaxed = true),
            )
        serviceToReseed.seedAll()

        // 런타임 post-action 보존 확인 (differsInPostActions 제거 후 GREEN)
        val countAfter =
            dsl.fetchValue(
                "SELECT COUNT(*) FROM workflow_post_actions WHERE transition_id = ?::uuid",
                transitionId.toString(),
            ) as Long
        assertThat(countAfter)
            .withFailMessage("공존 B 실패 — 재시드로 런타임 post-action 이 소실됨 (countBefore=%d, countAfter=%d)", countBefore, countAfter)
            .isGreaterThanOrEqualTo(countBefore)
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
        if (location.endsWith("simple.yaml")) {
            InMemoryResource(modifiedSimpleYaml, "simple.yaml")
        } else {
            delegate.getResource(location)
        }

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
        if (location.endsWith("software-default.yaml")) {
            InMemoryResource(invalidYaml, "software-default.yaml")
        } else {
            delegate.getResource(location)
        }

    override fun getClassLoader() = delegate.classLoader
}

/**
 * (from: open, to: done) 이 name 만 다르게 두 번 정의된 YAML 을 반환하는 ResourceLoader.
 * 시나리오 5 ((from,to) 중복 fail-fast) 검증에 사용한다.
 */
private class DuplicateTransitionResourceLoader : ResourceLoader {
    private val delegate = DefaultResourceLoader()

    /** (from: open, to: done) 이 A / B 두 개 정의된 중복 YAML. */
    private val duplicateTransitionYaml =
        """
        key: software-default
        name: 중복전이 워크플로우
        states:
          - { key: open,   name: Open,   category: TODO,        displayOrder: 1 }
          - { key: done,   name: Done,   category: DONE,        displayOrder: 2 }
        transitions:
          - { from: open, to: done, name: A }
          - { from: open, to: done, name: B }
        """.trimIndent().toByteArray()

    override fun getResource(location: String): Resource =
        if (location.endsWith("software-default.yaml")) {
            InMemoryResource(duplicateTransitionYaml, "software-default.yaml")
        } else {
            delegate.getResource(location)
        }

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
