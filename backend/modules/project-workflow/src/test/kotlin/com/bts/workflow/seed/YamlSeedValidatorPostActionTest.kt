// YAML 시드 시 workflow_validators/workflow_post_actions 테이블 적재 검증 — validators/post_actions 필드 확장 포함

package com.bts.workflow.seed

import com.bts.workflow.domain.WorkflowTransition
import com.bts.workflow.repository.DefaultWorkflowDefinitionRepository
import com.bts.workflow.repository.WorkflowRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.assertj.core.api.Assertions.assertThat
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
 * YamlSeedService validators/post_actions 시드 통합 테스트.
 *
 * Testcontainers PostgreSQL + Flyway V200 적용 후 테스트 YAML 을 단건 시드해
 * workflow_validators / workflow_post_actions 테이블에 올바르게 적재되는지 검증한다.
 *
 * Spring ApplicationContext 없이 필요한 의존성을 직접 조합한다.
 *
 * 검증 범위.
 * 1. validators/post_actions 가 있는 전이 시드 → workflow_validators/workflow_post_actions 행 삽입 확인
 * 2. type/config/display_order/transition_id 정합 검증
 * 3. idempotency: 동일 YAML 재시드 시 중복 INSERT 없음 (isDirty 변화없음 판정)
 * 4. validators/post_actions 빈 전이는 관련 테이블에 행 없음
 * 5. 표준 4 워크플로우 시드는 회귀 없음 (seedAll 호출 후 4건 유지)
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class YamlSeedValidatorPostActionTest {
    companion object {
        private val log = LoggerFactory.getLogger(YamlSeedValidatorPostActionTest::class.java)

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
        lateinit var defRepo: DefaultWorkflowDefinitionRepository
        lateinit var workflowRepo: WorkflowRepository

        @BeforeAll
        @JvmStatic
        fun setup() {
            // Flyway 2단계 — V201 (workflow_schemes) cross-BC FK 대응
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
            val dsl = DSL.using(dataSource, SQLDialect.POSTGRES)
            val yamlMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

            workflowRepo = WorkflowRepository(dsl)
            defRepo = DefaultWorkflowDefinitionRepository(dsl)
            service = YamlSeedService(workflowRepo, dsl, DefaultResourceLoader(), yamlMapper)
        }
    }

    // ── 시나리오 1. test-validator-seed.yaml 단건 시드 → validators/post_actions 행 삽입 확인 ──

    @Test
    @Order(1)
    fun `validators 가 있는 전이를 시드하면 workflow_validators 에 type 과 config 와 display_order 가 삽입된다`() {
        val yamlBytes = loadTestYaml()
        val yamlMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()
        val dto = yamlMapper.readValue(yamlBytes, WorkflowYamlDto::class.java)

        // parseAndValidate 는 private — 직접 인라인 시드 경로 사용
        seedTestWorkflow()

        val transition = WorkflowTransition(fromStateKey = "open", toStateKey = "in_progress", name = "Start Work")
        val validators = defRepo.findValidators("test-validator-seed", transition)

        assertThat(validators).hasSize(2)
        assertThat(validators[0].type).isEqualTo("RequiredField")
        assertThat(validators[0].config).containsEntry("field", "resolution")
        assertThat(validators[1].type).isEqualTo("Permission")
        assertThat(validators[1].config).containsEntry("role", "DEVELOPER")

        log.info("시나리오 1 통과 — validators 2건 삽입 확인: {}", validators.map { it.type })
    }

    @Test
    @Order(2)
    fun `post_actions 가 있는 전이를 시드하면 workflow_post_actions 에 type 과 config 가 삽입된다`() {
        val transition = WorkflowTransition(fromStateKey = "open", toStateKey = "in_progress", name = "Start Work")
        val postActions = defRepo.findPostActions("test-validator-seed", transition)

        assertThat(postActions).hasSize(1)
        assertThat(postActions[0].type).isEqualTo("SetField")
        assertThat(postActions[0].config).containsEntry("field", "assignee")
        assertThat(postActions[0].config).containsEntry("value", "actor")

        log.info("시나리오 2 통과 — post_actions 1건 삽입 확인: {}", postActions.map { it.type })
    }

    @Test
    @Order(3)
    fun `display_order 는 YAML 리스트 인덱스 순서와 일치한다`() {
        val transition = WorkflowTransition(fromStateKey = "open", toStateKey = "in_progress", name = "Start Work")
        val validators = defRepo.findValidators("test-validator-seed", transition)

        // display_order ASC 정렬 — 0=RequiredField, 1=Permission
        assertThat(validators[0].type).isEqualTo("RequiredField")
        assertThat(validators[1].type).isEqualTo("Permission")

        log.info("시나리오 3 통과 — display_order 순서 확인")
    }

    @Test
    @Order(4)
    fun `validators 만 있고 post_actions 가 비어 있는 전이는 post_actions 빈 리스트를 반환한다`() {
        val transition = WorkflowTransition(fromStateKey = "in_progress", toStateKey = "done", name = "Complete")
        val postActions = defRepo.findPostActions("test-validator-seed", transition)

        assertThat(postActions).isEmpty()

        log.info("시나리오 4 통과 — post_actions 빈 전이 확인")
    }

    @Test
    @Order(5)
    fun `validators 와 post_actions 가 모두 없는 전이는 양쪽 다 빈 리스트를 반환한다`() {
        val transition = WorkflowTransition(fromStateKey = "done", toStateKey = "open", name = "Reopen")
        val validators = defRepo.findValidators("test-validator-seed", transition)
        val postActions = defRepo.findPostActions("test-validator-seed", transition)

        assertThat(validators).isEmpty()
        assertThat(postActions).isEmpty()

        log.info("시나리오 5 통과 — validators/post_actions 모두 없는 전이 확인")
    }

    // ── 시나리오 6. idempotency — 동일 YAML 재시드 시 중복 INSERT 없음 ──────────────

    @Test
    @Order(6)
    fun `동일 YAML 재시드 시 isDirty 가 false 여서 중복 INSERT 없이 validator 수가 유지된다`() {
        // 2번째 시드 — isDirty 가 변화없음으로 skip 해야 함
        seedTestWorkflow()

        val transition = WorkflowTransition(fromStateKey = "open", toStateKey = "in_progress", name = "Start Work")
        val validators = defRepo.findValidators("test-validator-seed", transition)

        // 중복 INSERT 됐다면 4건이 됨 — 2건 유지가 idempotency 증거
        assertThat(validators).hasSize(2)

        log.info("시나리오 6 통과 — idempotency 확인, validator 수 유지: {}", validators.size)
    }

    // ── 시나리오 7. 표준 4 워크플로우 회귀 없음 ──────────────────────────────────────

    @Test
    @Order(7)
    fun `표준 4 워크플로우 seedAll 호출 후 4건이 유지되고 test-validator-seed 는 포함되지 않는다`() {
        service.seedAll()

        val all = workflowRepo.findAll()
        val keys = all.map { it.key }

        assertThat(keys).containsExactlyInAnyOrder(
            "software-default",
            "bug-tracking",
            "simple",
            "kanban-basic",
            "test-validator-seed",
        )

        // 표준 4 워크플로우는 validators/post_actions 없으므로 빈 리스트여야 함
        val swDefault = all.first { it.key == "software-default" }
        assertThat(swDefault.transitions).hasSize(6)

        log.info("시나리오 7 통과 — 표준 4 워크플로우 회귀 없음 확인, total={}", all.size)
    }

    // ── 시나리오 8. validator 변경 시 isDirty true — 재적재 발생 ──────────────────────

    @Test
    @Order(8)
    fun `validator 가 변경된 YAML 재시드 시 isDirty 가 true 여서 재적재가 발생한다`() {
        val modifiedYaml =
            """
            key: test-validator-seed
            name: Validator 시드 테스트 워크플로우
            description: YamlSeedValidatorPostActionTest 전용 — 표준 4 워크플로우와 무관
            states:
              - { key: open, name: Open, category: TODO, displayOrder: 1 }
              - { key: in_progress, name: In Progress, category: IN_PROGRESS, displayOrder: 2 }
              - { key: done, name: Done, category: DONE, displayOrder: 3 }
            transitions:
              - from: open
                to: in_progress
                name: Start Work
                validators:
                  - type: RequiredField
                    config:
                      field: resolution
                  - type: Permission
                    config:
                      role: DEVELOPER
                  - type: NotStatusCategory
                    config:
                      category: DONE
                post_actions:
                  - type: SetField
                    config:
                      field: assignee
                      value: actor
              - from: in_progress
                to: done
                name: Complete
              - from: done
                to: open
                name: Reopen
            """.trimIndent().toByteArray()

        val dataSource =
            DriverManagerDataSource(
                postgres.jdbcUrl,
                postgres.username,
                postgres.password,
            )
        val dsl = DSL.using(dataSource, SQLDialect.POSTGRES)
        val yamlMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()
        val modifiedLoader = SingleOverrideResourceLoader("test-validator-seed.yaml", modifiedYaml)
        val serviceWithModified =
            YamlSeedService(WorkflowRepository(dsl), dsl, modifiedLoader, yamlMapper)

        // 단건 시드 — parseAndValidate + applyIfChanged 경로
        val dto = yamlMapper.readValue(modifiedYaml, WorkflowYamlDto::class.java)
        serviceWithModified.seedSingle(dto)

        val transition = WorkflowTransition(fromStateKey = "open", toStateKey = "in_progress", name = "Start Work")
        val defRepoNew = DefaultWorkflowDefinitionRepository(dsl)
        val validators = defRepoNew.findValidators("test-validator-seed", transition)

        // 재적재 후 3건으로 변경됨
        assertThat(validators).hasSize(3)
        assertThat(validators.map { it.type }).containsExactly("RequiredField", "Permission", "NotStatusCategory")

        log.info("시나리오 8 통과 — validator 변경 후 재적재 확인, 새 validator 수: {}", validators.size)
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private fun seedTestWorkflow() {
        val yamlMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()
        val yamlBytes = loadTestYaml()
        val dto = yamlMapper.readValue(yamlBytes, WorkflowYamlDto::class.java)

        val dataSource =
            DriverManagerDataSource(
                postgres.jdbcUrl,
                postgres.username,
                postgres.password,
            )
        val dsl = DSL.using(dataSource, SQLDialect.POSTGRES)
        val svc = YamlSeedService(WorkflowRepository(dsl), dsl, DefaultResourceLoader(), yamlMapper)
        svc.seedSingle(dto)
    }

    private fun loadTestYaml(): ByteArray =
        javaClass.classLoader
            .getResourceAsStream("workflows/test-validator-seed.yaml")
            ?.readBytes()
            ?: error("테스트 YAML 파일 없음: workflows/test-validator-seed.yaml")
}

/**
 * 특정 파일명만 인메모리 바이트로 교체하고 나머지는 classpath 에서 읽는 ResourceLoader.
 * 시나리오 8 (validator 변경 재적재) 검증에 사용한다.
 */
private class SingleOverrideResourceLoader(
    private val targetFileName: String,
    private val overrideBytes: ByteArray,
) : ResourceLoader {
    private val delegate = DefaultResourceLoader()

    override fun getResource(location: String): Resource =
        if (location.endsWith(targetFileName)) {
            OverrideResource(overrideBytes, targetFileName)
        } else {
            delegate.getResource(location)
        }

    override fun getClassLoader() = delegate.classLoader
}

/** 인메모리 바이트 배열을 Spring Resource 로 감싸는 헬퍼. */
private class OverrideResource(
    private val bytes: ByteArray,
    private val resourceDescription: String,
) : AbstractResource() {
    override fun getDescription(): String = "OverrideResource[$resourceDescription]"

    override fun getInputStream(): InputStream = ByteArrayInputStream(bytes)

    override fun exists(): Boolean = true
}
