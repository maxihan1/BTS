// YamlSeedService 4 case — 적재 / no-op / 재적재 / FailFast

package com.bts.workflow.seed

import com.bts.workflow.repository.WorkflowRepository
import com.bts.workflow.scheme.domain.SchemeIssueTypeMapping
import com.bts.workflow.scheme.domain.WorkflowSchemeId
import com.bts.workflow.scheme.repository.SchemeIssueTypeMappingRepository
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
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
import java.time.Instant

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

            repository = WorkflowRepository(dsl)
            // 표준 4 워크플로우는 validator/postAction 이 없어 factory 미호출 → relaxed mock 으로 충분.
            // mappingRepository 는 R6-1/R6-3 시나리오(repairDefaultMappings 실호출)가 실제 DB 상태를
            // 검증하므로 실제 인스턴스가 필요하다.
            service =
                YamlSeedService(
                    repository,
                    dsl,
                    DefaultResourceLoader(),
                    mockk(relaxed = true),
                    mockk(relaxed = true),
                    SchemeIssueTypeMappingRepository(dsl),
                )
        }
    }

    // ── 시나리오 0-A. R6-1 — 빈 DB 최초 부팅이 4 스킴 default 매핑을 백필한다 ─────────

    /**
     * R6-1 (빈 DB 백필) 검증.
     *
     * Flyway 마이그레이션 직후(=이 클래스 안에서 `seedAll()` 을 처음 호출하는 지점) `workflows` 와
     * `workflow_scheme_issue_type_mappings` 는 비어 있다. V201 §6 의 default mapping seed 는
     * `INSERT ... SELECT ... JOIN workflows` 형태라 workflows 가 비어 있으면 0건만 삽입된다
     * (V201:132-134 주석 참조) — 따라서 이 시점의 매핑 0건이 "빈 DB" 전제를 보장한다.
     *
     * `@Order(-1)` 로 기존 `@Order(1)`(첫 YAML 적재 시나리오)보다 앞서 실행되도록 배치해
     * "seedAll() 최초 실행" 조건을 실제로 만족시킨다.
     *
     * ### GREEN — 판별자(C2) mutation 실증 결과
     * `YamlSeedService.seedAll()` 말미의 `mappingRepository.repairDefaultMappings()` 호출을
     * 프로덕션에서 임시 제거하고 재실행한 결과, 이 테스트는 `default 매핑이 4건이 아님(0)` 으로
     * 실제 실패했다 (아래 R6-3 도 동일하게 실패). 호출을 복원하면 다시 통과한다 —
     * 이 테스트가 vacuous(허수) 하지 않고 실제로 그 한 줄에 의존함을 입증한다.
     */
    @Test
    @Order(-1)
    fun `빈 DB 부팅(seedAll 최초 실행)이 4 스킴 기본 매핑을 만든다 (R6-1)`() {
        val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        val dsl = DSL.using(dataSource, SQLDialect.POSTGRES)
        val mappingRepository = SchemeIssueTypeMappingRepository(dsl)

        val workflowCountBefore = dsl.fetchValue("SELECT COUNT(*) FROM workflows") as Long
        val mappingCountBefore = dsl.fetchValue("SELECT COUNT(*) FROM workflow_scheme_issue_type_mappings") as Long
        assertThat(workflowCountBefore)
            .withFailMessage(
                "사전조건 위반 — workflows 가 비어 있지 않음(%d) — Order(-1) 이 최초 호출이 아님",
                workflowCountBefore,
            ).isZero()
        assertThat(mappingCountBefore)
            .withFailMessage("사전조건 위반 — 매핑이 비어 있지 않음(%d)", mappingCountBefore)
            .isZero()

        service.seedAll()

        val defaultMappingCount =
            dsl.fetchValue(
                "SELECT COUNT(*) FROM workflow_scheme_issue_type_mappings WHERE issue_type_id IS NULL",
            ) as Long
        assertThat(defaultMappingCount)
            .withFailMessage("R6-1 실패 — 빈 DB 최초 부팅 후 default 매핑이 4건이 아님(%d)", defaultMappingCount)
            .isEqualTo(4L)

        val schemeToWorkflowKey =
            mapOf(
                "software-scheme" to "software-default",
                "bug-tracking-scheme" to "bug-tracking",
                "simple-scheme" to "simple",
                "kanban-scheme" to "kanban-basic",
            )
        schemeToWorkflowKey.forEach { (schemeKey, workflowKey) ->
            val schemeId = fetchSchemeIdByKey(dsl, schemeKey)
            val mapping = mappingRepository.findDefaultMapping(WorkflowSchemeId(schemeId))
            assertThat(mapping)
                .withFailMessage("R6-1 실패 — 스킴 '%s' default 매핑 없음", schemeKey)
                .isNotNull
            val expectedWorkflowId =
                repository.findIdByKey(workflowKey)
                    ?: error("워크플로우 '$workflowKey' 없음 — seedAll() 이 먼저 4 YAML 을 적재해야 함")
            assertThat(mapping!!.workflowId).isEqualTo(expectedWorkflowId)
        }

        log.info("R6-1 통과 — 빈 DB 최초 부팅이 4 스킴 default 매핑을 백필함")
    }

    // ── 시나리오 0-B. R6-3 — seedAll 재실행은 멱등 (4행 유지, 중복 없음) ────────────

    /**
     * R6-3 (멱등성) 검증.
     *
     * `Order(-1)` 에서 이미 4건 백필이 완료된 상태에서 `seedAll()` 을 두 번 더 호출해도
     * default 매핑이 4건으로 유지되는지 확인한다. 멱등성의 근거는
     * [SchemeIssueTypeMappingRepository.insertMissingDefaultMapping] 의 `WHERE NOT EXISTS` 분기(T1) —
     * 이미 존재하는 스킴에는 재삽입하지 않는다.
     *
     * R6-1 KDoc 의 GREEN — 판별자(C2) mutation 실증 결과 참조 — 같은 mutation 으로 이 테스트도
     * (백필 자체가 안 되어) `default 매핑이 4건이 아님(0)` 으로 실패함을 확인했다.
     */
    @Test
    @Order(0)
    fun `seedAll 재실행은 멱등 — 4행 유지 (R6-3)`() {
        val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        val dsl = DSL.using(dataSource, SQLDialect.POSTGRES)

        service.seedAll()
        service.seedAll()

        val defaultMappingCount =
            dsl.fetchValue(
                "SELECT COUNT(*) FROM workflow_scheme_issue_type_mappings WHERE issue_type_id IS NULL",
            ) as Long
        assertThat(defaultMappingCount)
            .withFailMessage(
                "R6-3 실패 — seedAll 재실행 후 default 매핑이 4건이 아님(%d) — 중복 또는 누락 의심",
                defaultMappingCount,
            ).isEqualTo(4L)

        log.info("R6-3 통과 — seedAll 재실행 후에도 default 매핑 4건 유지 (멱등)")
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

        // "simple" 워크플로우의 name 을 변경한 버전으로 재적재를 검증한다.
        // 표준 4 YAML 중 simple 만 수정된 ResourceLoader 를 주입한다.
        val modifiedResourceLoader = ModifiedSimpleWorkflowResourceLoader()
        val serviceWithModified =
            YamlSeedService(
                WorkflowRepository(dsl),
                dsl,
                modifiedResourceLoader,
                mockk(relaxed = true),
                mockk(relaxed = true),
                SchemeIssueTypeMappingRepository(dsl),
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

        // states 가 비어 있는 잘못된 YAML 을 제공하는 ResourceLoader
        val invalidResourceLoader = InvalidWorkflowResourceLoader()
        val serviceWithInvalid =
            YamlSeedService(
                WorkflowRepository(dsl),
                dsl,
                invalidResourceLoader,
                mockk(relaxed = true),
                mockk(relaxed = true),
                SchemeIssueTypeMappingRepository(dsl),
            )

        assertThatThrownBy { serviceWithInvalid.seedAll() }
            .isInstanceOf(IllegalStateException::class.java)

        log.info("시나리오 4 통과 — FailFast 부팅 차단 확인")
    }

    // ── 시나리오 5. (from, to) 중복 전환 정의 → IllegalStateException fail-fast ─────

    @Test
    @Order(5)
    fun `같은 워크플로우 안에 from-to 가 동일한 전환이 중복 정의되면 IllegalStateException 이 발생한다`() {
        val dataSource =
            DriverManagerDataSource(
                postgres.jdbcUrl,
                postgres.username,
                postgres.password,
            )
        val dsl = DSL.using(dataSource, SQLDialect.POSTGRES)

        // (from: open, to: done) 이 name 만 다르게 두 번 정의된 중복 YAML
        val duplicateTransitionResourceLoader = DuplicateTransitionResourceLoader()
        val serviceWithDuplicate =
            YamlSeedService(
                WorkflowRepository(dsl),
                dsl,
                duplicateTransitionResourceLoader,
                mockk(relaxed = true),
                mockk(relaxed = true),
                SchemeIssueTypeMappingRepository(dsl),
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
     * - 상태 3개 / 전환 3개의 가장 단순한 구조.
     * - YAML post_action 이 0건이라 런타임 추가 행이 유일한 DB 행.
     * - Order(3) 에서 이름이 변경되어 재적재된 뒤 Order(7) 에서 재시드 = same-YAML no-op.
     *
     * 주의: Order(3) 이 "simple" 워크플로우 이름을 변경해 재적재했으므로,
     * 이 시나리오는 수정된 이름(단순 워크플로우 변경됨) 기준으로 실행된다.
     * 재시드 ResourceLoader 가 동일 내용 → no-op 기대.
     */
    @Test
    @Order(7)
    @Suppress("LongMethod", "MaxLineLength", "NestedBlockDepth")
    fun `공존 B - 런타임 post-action 추가 후 재시드 시 보존된다`() {
        val dataSource =
            DriverManagerDataSource(
                postgres.jdbcUrl,
                postgres.username,
                postgres.password,
            )
        val dsl = DSL.using(dataSource, SQLDialect.POSTGRES)

        // simple 워크플로우의 todo→doing 전환 id 를 직접 조회 (jOOQ 없이 SQL)
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
                        if (rs.next()) {
                            java.util.UUID.fromString(rs.getString("id"))
                        } else {
                            error("simple 워크플로우 todo→doing 전환 없음 — Order(1) 이 먼저 실행되어야 함")
                        }
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
        // ModifiedSimpleWorkflowResourceLoader 는 Order(3) 와 동일 내용 → no-op 기대
        val serviceToReseed =
            YamlSeedService(
                WorkflowRepository(dsl),
                dsl,
                ModifiedSimpleWorkflowResourceLoader(),
                mockk(relaxed = true),
                mockk(relaxed = true),
                SchemeIssueTypeMappingRepository(dsl),
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

    // ── 시나리오 8. R6-B — 매핑이 있어도 structural dirty 재적재가 FK RESTRICT 로 죽지 않는다 ────

    /**
     * 매핑 기록→재연결(★★ 수정판) 검증.
     *
     * `workflow_scheme_issue_type_mappings.workflow_id` 는 FK `ON DELETE RESTRICT` (V201:84) 다.
     * 매핑이 이 workflow 를 가리키는 상태에서 `deleteWorkflow`(DELETE FROM workflows) 를 그대로
     * 호출하면 FK 위반으로 예외가 발생한다. `applyIfChanged` 가 delete 직전에 매핑을 기록→삭제하고
     * insert 직후 새 UUID 로 재연결해야 예외 없이 성공하고, default 매핑이 dangling 되지 않는다.
     */
    @Test
    @Order(8)
    fun `매핑이 존재해도 YAML 구조 변경 재적재가 FK RESTRICT 로 죽지 않는다 (R6-B_S11)`() {
        val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        val dsl = DSL.using(dataSource, SQLDialect.POSTGRES)
        val mappingRepository = SchemeIssueTypeMappingRepository(dsl)
        val workflowRepo = WorkflowRepository(dsl)

        // 표준 4 스킴 default mapping 백필 — software-scheme → software-default 매핑을 확보한다.
        // 이미 존재하면 no-op (repairDefaultMappings 는 admin 설정을 덮어쓰지 않는다).
        mappingRepository.repairDefaultMappings()

        val schemeId = fetchSchemeIdByKey(dsl, "software-scheme")
        val mappingBefore =
            mappingRepository.findDefaultMapping(WorkflowSchemeId(schemeId))
                ?: error("software-scheme default mapping 없음 — repairDefaultMappings 선행 실패")

        // software-default YAML 에 state(blocked) 를 추가한 structural dirty 버전으로 재시드.
        val serviceWithStructuralChange =
            YamlSeedService(
                workflowRepo,
                dsl,
                StructurallyChangedSoftwareDefaultResourceLoaderV1(),
                mockk(relaxed = true),
                mockk(relaxed = true),
                mappingRepository = mappingRepository,
            )

        // FK RESTRICT 로 죽지 않고 성공해야 한다 — 실패하면 기록→재연결 로직 누락.
        serviceWithStructuralChange.seedAll()

        val newWorkflowId =
            workflowRepo.findIdByKey("software-default")
                ?: error("재시드 후 software-default 워크플로우 없음")

        val mappingAfter = mappingRepository.findDefaultMapping(WorkflowSchemeId(schemeId))
        assertThat(mappingAfter).isNotNull
        assertThat(mappingAfter!!.workflowId).isEqualTo(newWorkflowId)
        assertThat(mappingAfter.workflowId).isNotEqualTo(mappingBefore.workflowId)

        log.info("시나리오 8 통과 — 매핑 보유 상태 재적재가 FK RESTRICT 없이 새 UUID 로 재연결됨")
    }

    // ── 시나리오 9. admin 특정타입 매핑 보존 (verifier 발견 회귀 가드) ────────────────

    /**
     * "default 만 복구" 구현을 fail 시키는 가드.
     *
     * admin 이 REST 로 건 특정 타입(issue_type_id NOT NULL) 매핑은 재적재 시 default 매핑과
     * 함께 기록→재연결 되어야 한다. default 매핑만 처리하는 구현은 이 매핑을 소실시키거나,
     * 삭제되지 않은 채 남아 FK RESTRICT 로 재적재 자체를 실패시킨다.
     */
    @Test
    @Order(9)
    fun `admin 이 건 특정타입 매핑도 재적재 후 소실 없이 새 workflow UUID 로 재연결된다`() {
        val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        val dsl = DSL.using(dataSource, SQLDialect.POSTGRES)
        val mappingRepository = SchemeIssueTypeMappingRepository(dsl)
        val workflowRepo = WorkflowRepository(dsl)

        val schemeId = fetchSchemeIdByKey(dsl, "software-scheme")
        val bugIssueTypeId =
            mappingRepository.findIssueTypeIdByKey("bug")
                ?: error("issue_types 'bug' 없음 — 테스트 fixture 확인 필요")

        // Order(8) 재시드 이후의 현재 유효한 software-default UUID 를 가리키도록 admin 매핑을 심는다.
        val workflowIdBeforeReseed =
            workflowRepo.findIdByKey("software-default")
                ?: error("Order(8) 이후 software-default 워크플로우 없음")

        mappingRepository.addMapping(
            SchemeIssueTypeMapping(
                id = null,
                schemeId = WorkflowSchemeId(schemeId),
                issueTypeId = bugIssueTypeId,
                workflowId = workflowIdBeforeReseed,
                createdAt = Instant.now(),
            ),
        )

        // Order(8) 과 다른 structural dirty(on_hold state) 로 다시 재시드.
        val serviceWithSecondStructuralChange =
            YamlSeedService(
                workflowRepo,
                dsl,
                StructurallyChangedSoftwareDefaultResourceLoaderV2(),
                mockk(relaxed = true),
                mockk(relaxed = true),
                mappingRepository = mappingRepository,
            )

        serviceWithSecondStructuralChange.seedAll()

        val newWorkflowId =
            workflowRepo.findIdByKey("software-default")
                ?: error("재시드 후 software-default 워크플로우 없음")
        assertThat(newWorkflowId).isNotEqualTo(workflowIdBeforeReseed)

        val adminMapping = mappingRepository.findByIssueType(WorkflowSchemeId(schemeId), bugIssueTypeId)
        assertThat(adminMapping)
            .withFailMessage("admin 특정타입 매핑(bug) 이 재적재 후 소실됨 — 기록→재연결 로직 확인 필요")
            .isNotNull
        assertThat(adminMapping!!.workflowId).isEqualTo(newWorkflowId)

        val defaultMapping = mappingRepository.findDefaultMapping(WorkflowSchemeId(schemeId))
        assertThat(defaultMapping).isNotNull
        assertThat(defaultMapping!!.workflowId).isEqualTo(newWorkflowId)

        log.info("시나리오 9 통과 — admin 특정타입 매핑 보존 확인")
    }

    // ── 시나리오 8/9 fixture 헬퍼 ────────────────────────────────────────────────

    /** workflow_schemes.key 로 id 를 조회한다. V201 seed 로 4 표준 스킴은 항상 존재한다. */
    private fun fetchSchemeIdByKey(
        dsl: DSLContext,
        schemeKey: String,
    ): Long = dsl.fetchValue("SELECT id FROM workflow_schemes WHERE key = ?", schemeKey) as Long
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
        name: 중복전환 워크플로우
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

/**
 * "software-default" 워크플로우에 새 상태(blocked)를 추가한 구조 변경(structural dirty) 버전을 반환하는
 * ResourceLoader. 시나리오 8 (R6-B 매핑 기록→재연결) 검증에 사용한다.
 */
private class StructurallyChangedSoftwareDefaultResourceLoaderV1 : ResourceLoader {
    private val delegate = DefaultResourceLoader()

    /** 원본 5 states + 신규 blocked state 추가 (states 집합 변경 → isDirty=true). transitions 은 원본 유지. */
    private val modifiedYaml =
        """
        key: software-default
        name: 소프트웨어 개발 기본 워크플로우
        description: "Open → In Progress → In Review → Done → Closed 흐름의 표준 소프트웨어 개발 워크플로우"
        states:
          - { key: open, name: Open, category: TODO, displayOrder: 1 }
          - { key: in_progress, name: In Progress, category: IN_PROGRESS, displayOrder: 2 }
          - { key: in_review, name: In Review, category: IN_PROGRESS, displayOrder: 3 }
          - { key: done, name: Done, category: DONE, displayOrder: 4 }
          - { key: closed, name: Closed, category: DONE, displayOrder: 5 }
          - { key: blocked, name: Blocked, category: TODO, displayOrder: 6 }
        transitions:
          - { from: open, to: in_progress, name: Start Work }
          - { from: in_progress, to: in_review, name: Submit for Review }
          - from: in_review
            to: done
            name: Approve
            validators:
              - type: RequiredField
                config:
                  field: resolution
          - { from: in_review, to: in_progress, name: Request Changes }
          - from: done
            to: closed
            name: Close
            validators:
              - type: RequiredField
                config:
                  field: resolution
          - from: open
            to: closed
            name: Cancel
            validators:
              - type: RequiredField
                config:
                  field: resolution
        """.trimIndent().toByteArray()

    override fun getResource(location: String): Resource =
        if (location.endsWith("software-default.yaml")) {
            InMemoryResource(modifiedYaml, "software-default.yaml")
        } else {
            delegate.getResource(location)
        }

    override fun getClassLoader() = delegate.classLoader
}

/**
 * "software-default" 워크플로우에 [StructurallyChangedSoftwareDefaultResourceLoaderV1] 과는 다른
 * 신규 상태(on_hold)를 추가한 두 번째 structural dirty 버전을 반환하는 ResourceLoader.
 * 시나리오 9 (admin 특정타입 매핑 보존) 검증에서, 시나리오 8 재적재 이후 상태(blocked 포함)와
 * 다시 dirty 를 유발해 두 번째 재적재를 트리거한다.
 */
private class StructurallyChangedSoftwareDefaultResourceLoaderV2 : ResourceLoader {
    private val delegate = DefaultResourceLoader()

    /** 원본 5 states + 신규 on_hold state 추가 (blocked 는 미포함 — 시나리오 8 결과와 집합이 달라 dirty 유발). */
    private val modifiedYaml =
        """
        key: software-default
        name: 소프트웨어 개발 기본 워크플로우
        description: "Open → In Progress → In Review → Done → Closed 흐름의 표준 소프트웨어 개발 워크플로우"
        states:
          - { key: open, name: Open, category: TODO, displayOrder: 1 }
          - { key: in_progress, name: In Progress, category: IN_PROGRESS, displayOrder: 2 }
          - { key: in_review, name: In Review, category: IN_PROGRESS, displayOrder: 3 }
          - { key: done, name: Done, category: DONE, displayOrder: 4 }
          - { key: closed, name: Closed, category: DONE, displayOrder: 5 }
          - { key: on_hold, name: On Hold, category: TODO, displayOrder: 6 }
        transitions:
          - { from: open, to: in_progress, name: Start Work }
          - { from: in_progress, to: in_review, name: Submit for Review }
          - from: in_review
            to: done
            name: Approve
            validators:
              - type: RequiredField
                config:
                  field: resolution
          - { from: in_review, to: in_progress, name: Request Changes }
          - from: done
            to: closed
            name: Close
            validators:
              - type: RequiredField
                config:
                  field: resolution
          - from: open
            to: closed
            name: Cancel
            validators:
              - type: RequiredField
                config:
                  field: resolution
        """.trimIndent().toByteArray()

    override fun getResource(location: String): Resource =
        if (location.endsWith("software-default.yaml")) {
            InMemoryResource(modifiedYaml, "software-default.yaml")
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
