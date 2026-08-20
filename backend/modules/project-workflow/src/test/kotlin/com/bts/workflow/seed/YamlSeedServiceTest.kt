// YamlSeedService 통합 테스트 — 적재 / no-op / YAML 변경 무시 / FailFast / 운영자 수정 보존

package com.bts.workflow.seed

import com.bts.workflow.engine.WorkflowPostActionFactory
import com.bts.workflow.engine.WorkflowValidatorFactory
import com.bts.workflow.repository.WorkflowRepository
import com.bts.workflow.scheme.domain.WorkflowSchemeId
import com.bts.workflow.scheme.repository.SchemeIssueTypeMappingRepository
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.configuration.FluentConfiguration
import org.jooq.DSLContext
import org.jooq.Record
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
 * 3. YAML 변경 후 재호출 → 기존 DB 무변경 (ADR db-as-source-of-truth D2)
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

        // 전환 수는 YAML 의 보통 전환 + INITIAL 1건이다. INITIAL 은 aggregate 에도 실린다 —
        // 시작 상태 해석(WorkflowKeyResolverImpl)이 그 행을 읽어야 하기 때문이다.

        // software-default: 5 states, 6 + INITIAL 1 transitions
        val softwareDefault = workflows.first { it.key == "software-default" }
        assertThat(softwareDefault.states).hasSize(5)
        assertThat(softwareDefault.transitions).hasSize(7)

        // bug-tracking: 5 states, 5 + INITIAL 1 transitions
        val bugTracking = workflows.first { it.key == "bug-tracking" }
        assertThat(bugTracking.states).hasSize(5)
        assertThat(bugTracking.transitions).hasSize(6)

        // simple: 3 states, 3 + INITIAL 1 transitions
        val simple = workflows.first { it.key == "simple" }
        assertThat(simple.states).hasSize(3)
        assertThat(simple.transitions).hasSize(4)

        // kanban-basic: 4 states, 3 + INITIAL 1 transitions
        val kanbanBasic = workflows.first { it.key == "kanban-basic" }
        assertThat(kanbanBasic.states).hasSize(4)
        assertThat(kanbanBasic.transitions).hasSize(4)

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

    // ── 시나리오 3. YAML 변경 후 재호출 → **기존 DB 무변경** (ADR db-as-source-of-truth D2) ──
    //
    // 이 테스트는 2026-08-19 이전에 「dirty diff 감지 후 재적재한다」를 단언했다. 그 동작이
    // 운영자의 DB 수정을 재기동마다 되돌리던 원인이라 ADR 2026-08-18-workflow-db-as-source-of-truth D2
    // 가 없앴다. 삭제하지 않고 **뒤집어** 새 계약의 회귀 테스트로 남긴다 — 이 PR 이 바꾼 동작의 증인이다.

    @Test
    @Order(3)
    fun `YAML 을 바꿔도 이미 적재된 워크플로우는 그대로다`() {
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
                dsl,
                modifiedResourceLoader,
                mockk(relaxed = true),
                mockk(relaxed = true),
                SchemeIssueTypeMappingRepository(dsl),
            )

        serviceWithModified.seedAll()

        // YAML 이 바뀌어도 이미 있는 워크플로우는 손대지 않는다 — 시드는 「없을 때만 삽입」이다.
        val workflows = WorkflowRepository(dsl).findAll()
        val simple = workflows.first { it.key == "simple" }
        assertThat(simple.name).isEqualTo("단순 워크플로우 (TODO/DOING/DONE)")

        log.info("시나리오 3 통과 — YAML 변경이 기존 DB 를 덮지 않는다")
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

    // ── 시나리오 5. 같은 (from, to) 에 이름이 다른 전환 2개가 정상 시드된다 (FR-WF-05) ──

    /**
     * 전환 identity 가 `(from, to)` 조합에서 `id` 로 바뀌면서 「같은 상태쌍에 전환 하나」 규칙이 사라졌다.
     * 도메인 `Workflow.of()` 는 이미 이 조합을 받아들이므로 **시드 경로도 같아야 한다** —
     * 한쪽만 바뀌면 YAML 로 부트스트랩되는 표준 워크플로우에서만 다중 전환이 거짓이 된다.
     *
     * 근거. ADR `docs/adr/2026-08-18-workflow-transition-id-identity.md` §D1.
     */
    @Test
    @Order(5)
    fun `같은 워크플로우 안에 from-to 가 같고 이름이 다른 전환 2개가 시드된다`() {
        val dataSource =
            DriverManagerDataSource(
                postgres.jdbcUrl,
                postgres.username,
                postgres.password,
            )
        val dsl = DSL.using(dataSource, SQLDialect.POSTGRES)

        // (from: open, to: done) 이 name 만 다르게 두 번 정의된 YAML
        val multiTransitionResourceLoader = MultiTransitionResourceLoader()
        val serviceWithMulti =
            YamlSeedService(
                dsl,
                multiTransitionResourceLoader,
                mockk(relaxed = true),
                mockk(relaxed = true),
                SchemeIssueTypeMappingRepository(dsl),
            )

        serviceWithMulti.seedAll()

        val names =
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.prepareStatement(
                    """
                    SELECT wt.name
                    FROM workflow_transitions wt
                    JOIN workflow_states fs ON wt.from_state_id = fs.id
                    JOIN workflow_states ts ON wt.to_state_id = ts.id
                    JOIN workflows w ON wt.workflow_id = w.id
                    WHERE w.key = 'multi-transition-wf' AND fs.key = 'open' AND ts.key = 'done'
                    """.trimIndent(),
                ).use { ps -> ps.executeQuery().use { rs -> readFirstColumn(rs) } }
            }

        assertThat(names)
            .withFailMessage("같은 (open,done) 에 이름이 다른 전환 2개가 시드돼야 한다 — 실제=%s", names)
            .containsExactlyInAnyOrder("A", "B")

        log.info("시나리오 5 통과 — 같은 상태쌍 다중 전환 수용 확인")
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

    // ── 시나리오 8·9 제거 (2026-08-19) ──────────────────────────────────────────
    //
    // 두 시나리오는 「YAML structural 변경 → deleteWorkflow → 재삽입」 도중 스킴 매핑이 FK RESTRICT 로
    // 죽지 않고 새 UUID 로 재연결되는지를 지켰다. ADR 2026-08-18-workflow-db-as-source-of-truth D2 가
    // 그 재적재 경로 자체를 없애 **도달 불가**가 됐고, 도달 불가 조합을 지키는 테스트는 가짜 그린이다.
    //
    // 지키던 리포지토리 메서드(detachMappingsByWorkflowId · reinsertMappings)는 남겼다 — 로드맵 PR 3 의
    // 워크플로우 삭제 CRUD 가 같은 FK 를 만난다. 커버리지는 SchemeIssueTypeMappingRepositoryIntegrationTest
    // 의 detach → reinsert 왕복 테스트 2종으로 옮겼다.

    // ── 시나리오 8/9 fixture 헬퍼 ────────────────────────────────────────────────

    /** workflow_schemes.key 로 id 를 조회한다. V201 seed 로 4 표준 스킴은 항상 존재한다. */
    private fun fetchSchemeIdByKey(
        dsl: DSLContext,
        schemeKey: String,
    ): Long = dsl.fetchValue("SELECT id FROM workflow_schemes WHERE key = ?", schemeKey) as Long

    // ── 시나리오 10. 운영자가 DB 에서 고친 값이 재기동을 견딘다 (이 PR 의 핵심 계약) ──────

    @Test
    @Order(10)
    fun `재기동해도 DB 에서 고친 워크플로우 이름이 유지된다`() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.createStatement().use {
                it.execute("UPDATE workflows SET name = '우리 개발 워크플로우' WHERE key = 'software-default'")
            }
        }

        service.seedAll()

        val name = repository.findAll().first { it.key == "software-default" }.name
        assertThat(name).isEqualTo("우리 개발 워크플로우")

        log.info("시나리오 10 통과 — 재기동이 운영자 수정을 덮지 않는다")
    }

    // ── 시나리오 11. 시드가 삽입한 워크플로우는 origin 이 SEED 다 ────────────────────

    @Test
    @Order(11)
    fun `시드가 삽입한 워크플로우의 origin 은 SEED 다`() {
        val origins = mutableMapOf<String, String>()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            val rs = conn.createStatement().executeQuery("SELECT key, origin FROM workflows")
            while (rs.next()) origins[rs.getString(1)] = rs.getString(2)
        }
        assertThat(origins).containsKeys("software-default", "bug-tracking", "simple", "kanban-basic")
        assertThat(origins.values).allMatch { it == "SEED" }

        log.info("시나리오 11 통과 — 시드 적재분 origin=SEED")
    }

    // ── 시나리오 12. 시드가 전환의 신 컬럼을 채운다 (프로덕션 부팅 회귀 가드) ────────

    /**
     * 시드가 심은 전환이 `from_status_id`·`to_status_id`·`kind` 를 채우는가.
     *
     * ### 왜 이 가드가 필요한가 — 빈 DB 부팅이 조용히 반쪽이 된다
     * V207 이 전환의 출발·도착을 `workflow_states` 에서 전역 카탈로그 편성 `workflow_statuses` 로
     * 재지정했다. 재지정 백필은 **마이그레이션 시점에 있던 행**만 손대므로, 그 뒤에 시드가 심는 행은
     * 시드가 직접 신 컬럼을 채워야 한다. 채우지 않으면 표준 4 워크플로우의 전환이 전부 신 컬럼 NULL 로
     * 남아 ① 3단계에서 NOT NULL 을 걸 수 없고 ② 읽기가 구 컬럼 폴백에만 의존하게 된다.
     *
     * ### 왜 `repository.findAll()` 로는 못 잡는가
     * 읽기에 구 컬럼 폴백이 있어 신 컬럼이 비어도 aggregate 복원은 성공한다(시나리오 1 이 그래서 초록이다).
     * 그래서 **컬럼을 직접 본다** — 폴백이 가리는 것을 드러내는 유일한 축이다.
     */
    @Test
    @Order(12)
    fun `시드가 심은 전환은 from_status_id·to_status_id·kind 를 채운다`() {
        service.seedAll()

        val rows = fetchTransitionStateKeys("software-default")
        val normalRows = rows.filter { it[1] == "NORMAL" }

        assertThat(normalRows)
            .describedAs("software-default 의 보통 전환 6건을 읽지 못했다 — 사전조건이 깨졌다 (비-공허 확인)")
            .hasSize(6)
        assertThat(normalRows).allSatisfy { row ->
            // 대조 기준이 NULL 이면 아래 isEqualTo 가 「둘 다 NULL」로 조용히 통과한다.
            // 3단계에서 시드가 구 컬럼을 그만 채울 때 여기가 먼저 red 를 낸다.
            assertThat(row[2]).describedAs("전환 '%s' 의 구 from 컬럼이 대조 기준이다", row[0]).isNotNull()
            assertThat(row[3]).describedAs("전환 '%s' 의 구 to 컬럼이 대조 기준이다", row[0]).isNotNull()
            assertThat(row[4])
                .describedAs("전환 '%s' 의 from_status_id 가 구 컬럼과 같은 상태를 가리켜야 한다", row[0])
                .isEqualTo(row[2])
            assertThat(row[5])
                .describedAs("전환 '%s' 의 to_status_id 가 구 컬럼과 같은 상태를 가리켜야 한다", row[0])
                .isEqualTo(row[3])
        }

        // INITIAL 은 구 컬럼을 비운 채 신 컬럼만 채운다 — V207 ⑨ 가 기존 DB 에 심은 행과 같은 모양이다.
        // 여기서 구 컬럼을 채우면 빈 DB 사이트와 기존 사이트의 같은 전환이 서로 다른 모양이 된다.
        assertThat(rows.filter { it[1] == "INITIAL" })
            .describedAs("INITIAL — (이름, kind, 구 from, 구 to, 신 from, 신 to)")
            .containsExactly(listOf("이슈 생성", "INITIAL", null, null, null, "open"))

        log.info("시나리오 12 통과 — 시드가 전환 신 컬럼(from_status_id·to_status_id·kind)을 채운다")
    }

    // ── 시나리오 13. 빈 스키마에 시드만 돌려도 INITIAL 전환이 생긴다 (P1 #7) ────────

    /**
     * **빈 스키마를 이 테스트가 직접 만든 뒤** 시드만 돌려 INITIAL 전환이 생기는지 본다.
     *
     * ### 왜 컨테이너를 하나 더 띄우나 — 선재 행이 판정을 가짜로 만든다
     * V207 ⑨ 의 INITIAL 백필은 **마이그레이션 시점에 있던 워크플로우**에만 1건씩 넣는다. 그래서
     * 이미 워크플로우가 들어 있는 DB 에서는 시드가 INITIAL 을 안 심어도 INITIAL 행이 보인다 —
     * 저장소가 `shared-dev-db-preexisting-rows-fake-green` 으로 부르는 그 양식이다. 클래스 공용
     * 컨테이너는 앞선 시나리오들이 이미 시드를 돌려 놓았으므로 여기서 쓸 수 없다. 새 컨테이너에
     * 마이그레이션만 적용하면 `workflows` 가 비어 백필이 0행이고, 그 뒤에 나타나는 INITIAL 은
     * **시드가 심은 것뿐**이다. 시드 전 전환 0건을 직접 단언해 그 전제를 못 박는다.
     *
     * ### 무엇을 대조하나
     * 도착지·이름·표시순서·구 컬럼 NULL 여부를 V207 ⑨ 가 기존 DB 에 심은 것과 같은 모양으로 맞춘다.
     * 어긋나면 빈 DB 로 올린 사이트와 기존 사이트의 이슈 생성 진입 상태가 갈린다.
     * 도착지 규칙(`display_order` 최소 편성)은 하드코딩 기대값과 **V207 ⑨ 규칙 재현 조회**를 함께 본다.
     */
    @Test
    @Order(13)
    fun `빈 스키마에 시드만 돌려도 표준 4 워크플로우가 INITIAL 전환을 1건씩 갖는다`() {
        val fresh: PostgreSQLContainer<*> =
            PostgreSQLContainer(temboImage)
                .withDatabaseName("bts_seed_only")
                .withUsername("bts")
                .withPassword("bts_test")

        fresh.use { container ->
            container.start()
            migrateWorkflowSchema(container)
            val dsl = dslFor(container)

            assertThat(dsl.fetchValue("SELECT COUNT(*) FROM workflow_transitions") as Long)
                .withFailMessage("빈 스키마 전제가 깨졌다 — 시드 전에 전환이 이미 있다(%s)", "선재 행")
                .isZero()

            seedServiceFor(dsl).seedAll()

            assertThat(dsl.fetch(initialTransitionSql).map { it.rowText() })
                .describedAs("INITIAL 전환 — 워크플로우|이름|도착상태|표시순서|from신 NULL|from구 NULL|to구 NULL")
                .containsExactly(
                    "bug-tracking|이슈 생성|reported|0|true|true|true",
                    "kanban-basic|이슈 생성|backlog|0|true|true|true",
                    "simple|이슈 생성|todo|0|true|true|true",
                    "software-default|이슈 생성|open|0|true|true|true",
                )

            assertThat(dsl.fetch(initialTargetSql).map { it.rowText() })
                .describedAs("시드가 고른 INITIAL 도착지가 V207 ⑨ 백필 규칙과 어긋나면 기존 DB 와 갈린다")
                .isEqualTo(dsl.fetch(v207InitialTargetRuleSql).map { it.rowText() })
        }

        log.info("시나리오 13 통과 — 빈 스키마 + 시드만으로 INITIAL 전환 4건이 생긴다")
    }

    /**
     * 그 워크플로우의 전환마다 (이름, kind, 구 from key, 구 to key, 신 from key, 신 to key) 를 읽는다.
     *
     * 구 세대(`workflow_states`)와 신 세대(`workflow_statuses ⋈ statuses`) 를 **같은 행에 나란히** 놓아야
     * 「신 컬럼이 구 컬럼과 같은 상태를 가리키는가」를 한 번에 대조할 수 있다. 신 컬럼이 비어 있으면
     * 그 자리가 NULL 로 남아 대조가 어긋난다 — 그것이 이 조회가 잡으려는 것이다.
     *
     * @param workflowKey 대상 `workflows.key`
     */
    private fun fetchTransitionStateKeys(workflowKey: String): List<List<String?>> =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                """
                SELECT wt.name, wt.kind,
                       fs.key  AS legacy_from, ts.key  AS legacy_to,
                       nfs.key AS status_from, nts.key AS status_to
                  FROM workflow_transitions wt
                  JOIN workflows w ON w.id = wt.workflow_id
                  LEFT JOIN workflow_states   fs  ON fs.id  = wt.from_state_id
                  LEFT JOIN workflow_states   ts  ON ts.id  = wt.to_state_id
                  LEFT JOIN workflow_statuses wfs ON wfs.id = wt.from_status_id
                  LEFT JOIN statuses          nfs ON nfs.id = wfs.status_id
                  LEFT JOIN workflow_statuses wts ON wts.id = wt.to_status_id
                  LEFT JOIN statuses          nts ON nts.id = wts.status_id
                 WHERE w.key = ?
                """.trimIndent(),
            ).use { ps ->
                ps.setString(1, workflowKey)
                ps.executeQuery().use { rs -> readSixColumns(rs) }
            }
        }

    /** [java.sql.ResultSet] 의 1열을 문자열 목록으로 옮긴다. while 루프를 밖으로 빼 중첩 깊이를 낮춘다. */
    private fun readFirstColumn(rs: java.sql.ResultSet): List<String> {
        val result = mutableListOf<String>()
        while (rs.next()) result += rs.getString(1)
        return result
    }

    /** [java.sql.ResultSet] 을 6열 문자열 행 목록으로 옮긴다. while 루프를 밖으로 빼 중첩 깊이를 낮춘다. */
    private fun readSixColumns(rs: java.sql.ResultSet): List<List<String?>> {
        val result = mutableListOf<List<String?>>()
        while (rs.next()) {
            result +=
                listOf(
                    rs.getString(1), rs.getString(2), rs.getString(3),
                    rs.getString(4), rs.getString(5), rs.getString(6),
                )
        }
        return result
    }
}

// ── 시나리오 13 헬퍼 — 빈 스키마를 새로 만드는 도구 ─────────────────────────────
//
// 클래스 밖 top-level 로 둔 것은 클래스 공용 `setup()` 이 static 필드(service·repository)를 세팅해
// 앞선 시나리오들의 대상 DB 를 바꿔 버리기 때문이다. 여기 함수들은 넘겨받은 컨테이너만 건드린다.

/** V201 의 cross-BC FK 통과용 `issue_types` 스텁. 프로덕션에서는 issue-tracking V003 이 만든다. */
private val issueTypesStubDdl =
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
    """.trimIndent()

/**
 * INITIAL 전환 1행을 (워크플로우|이름|도착상태|표시순서|from신 NULL|from구 NULL|to구 NULL) 로 읽는다.
 *
 * 신·구 컬럼의 NULL 여부를 같은 행에 실어야 「신 컬럼이 정본이고 구 컬럼은 비어 있다」를 한 번에 본다.
 */
private val initialTransitionSql =
    """
    SELECT w.key, t.name, s.key, t.display_order,
           t.from_status_id IS NULL, t.from_state_id IS NULL, t.to_state_id IS NULL
      FROM workflow_transitions t
      JOIN workflows          w  ON w.id  = t.workflow_id
      JOIN workflow_statuses  ws ON ws.id = t.to_status_id
      JOIN statuses           s  ON s.id  = ws.status_id
     WHERE t.kind = 'INITIAL'
     ORDER BY w.key
    """.trimIndent()

/** 시드가 심은 INITIAL 의 (워크플로우|도착상태). 아래 V207 재현 규칙과 대조할 좌변이다. */
private val initialTargetSql =
    """
    SELECT w.key, s.key
      FROM workflow_transitions t
      JOIN workflows          w  ON w.id  = t.workflow_id
      JOIN workflow_statuses  ws ON ws.id = t.to_status_id
      JOIN statuses           s  ON s.id  = ws.status_id
     WHERE t.kind = 'INITIAL'
     ORDER BY w.key
    """.trimIndent()

/**
 * V207 ⑨ 백필이 도착지를 고른 규칙을 **그대로 재현**한다 — 워크플로우별 `display_order` 최소 편성.
 *
 * 기대값을 손으로 적기만 하면 YAML 의 `displayOrder` 가 바뀌었을 때 시드와 마이그레이션의 선택이
 * 갈라져도 아무도 못 잡는다. 마이그레이션은 append-only 라 이 재현이 썩지 않는다.
 */
private val v207InitialTargetRuleSql =
    """
    SELECT w.key, s.key
      FROM (SELECT DISTINCT ON (ws.workflow_id) ws.workflow_id, ws.status_id
              FROM workflow_statuses ws
              JOIN statuses s2 ON s2.id = ws.status_id AND s2.deleted_at IS NULL
             ORDER BY ws.workflow_id, ws.display_order, ws.id) f
      JOIN workflows w ON w.id = f.workflow_id
      JOIN statuses  s ON s.id = f.status_id
     ORDER BY w.key
    """.trimIndent()

/**
 * 새 컨테이너에 project-workflow 스키마를 끝까지 적용한다.
 *
 * 클래스 공용 `setup()` 과 같은 2단계 절차다 — V201(workflow_schemes)이 issue-tracking 의
 * `issue_types` 를 FK 로 참조하므로 V200 까지 올린 뒤 스텁을 만들고 나머지를 올린다.
 *
 * @param container 방금 띄운 빈 PostgreSQL 컨테이너
 */
private fun migrateWorkflowSchema(container: PostgreSQLContainer<*>) {
    flywayFor(container).target("200").load().migrate()
    DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { conn ->
        conn.createStatement().use { it.execute(issueTypesStubDdl) }
    }
    flywayFor(container).load().migrate()
}

/** issue-tracking + project-workflow 두 위치를 함께 보는 Flyway 설정. */
private fun flywayFor(container: PostgreSQLContainer<*>): FluentConfiguration =
    Flyway.configure()
        .dataSource(container.jdbcUrl, container.username, container.password)
        .placeholderReplacement(false)
        .locations(
            "classpath:db/migration/issue-tracking",
            "classpath:db/migration/project-workflow",
        )

/** 컨테이너 접속 정보로 jOOQ DSLContext 를 만든다. */
private fun dslFor(container: PostgreSQLContainer<*>): DSLContext =
    DSL.using(
        DriverManagerDataSource(container.jdbcUrl, container.username, container.password),
        SQLDialect.POSTGRES,
    )

/**
 * 표준 4 YAML 을 classpath 에서 읽는 시드 서비스. validator/postAction 은 factory dry-run 만 하므로
 * relaxed mock 으로 충분하다 (클래스 공용 setup 과 같은 판단).
 */
private fun seedServiceFor(dsl: DSLContext): YamlSeedService =
    YamlSeedService(
        dsl,
        DefaultResourceLoader(),
        mockk<WorkflowValidatorFactory>(relaxed = true),
        mockk<WorkflowPostActionFactory>(relaxed = true),
        SchemeIssueTypeMappingRepository(dsl),
    )

/** 조회 결과 한 행을 `|` 로 이어 붙인다. 실패 메시지가 곧 행 내용이 되어 어디가 어긋났는지 바로 보인다. */
private fun Record.rowText(): String = intoArray().joinToString("|")

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
 * 시나리오 5 (같은 상태쌍 다중 전환 수용) 검증에 사용한다.
 *
 * 워크플로우 key 를 `multi-transition-wf` 로 둔 것이 load-bearing 이다 — 시드는 「행이 없을 때만
 * 삽입」하므로 이미 적재된 `software-default` 키를 쓰면 건너뛰어 삽입 결과를 볼 수 없다.
 */
private class MultiTransitionResourceLoader : ResourceLoader {
    private val delegate = DefaultResourceLoader()

    /** (from: open, to: done) 이 A / B 두 개 정의된 다중 전환 YAML. */
    private val multiTransitionYaml =
        """
        key: multi-transition-wf
        name: 다중전환 워크플로우
        states:
          - { key: open,   name: Open,   category: TODO,        displayOrder: 1 }
          - { key: done,   name: Done,   category: DONE,        displayOrder: 2 }
        transitions:
          - { from: open, to: done, name: A }
          - { from: open, to: done, name: B }
        """.trimIndent().toByteArray()

    override fun getResource(location: String): Resource =
        if (location.endsWith("software-default.yaml")) {
            InMemoryResource(multiTransitionYaml, "software-default.yaml")
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
