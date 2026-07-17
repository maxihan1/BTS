// SchemeIssueTypeMappingRepository 통합 테스트 — CRUD + repairDefaultMappings(R6/R6-B) + partial UNIQUE INDEX 검증

package com.bts.workflow.scheme.repository

import com.bts.shared.issue.IssueTypeId
import com.bts.workflow.scheme.domain.SchemeIssueTypeMapping
import com.bts.workflow.scheme.domain.WorkflowSchemeId
import com.bts.workflow.scheme.exception.MappingDefaultDuplicateException
import com.bts.workflow.scheme.exception.MappingDuplicateException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

/**
 * SchemeIssueTypeMappingRepository 통합 테스트.
 *
 * 검증 범위.
 * - addMapping: 신규 매핑 INSERT → id 할당된 SchemeIssueTypeMapping 반환
 * - addMapping: UNIQUE 위반 시 (동일 scheme_id + issue_type_id) MappingDuplicateException 발생
 * - addMapping: partial UNIQUE INDEX (ix_scheme_default_mapping) — 같은 scheme_id 로 issue_type_id IS NULL 매핑 2건 INSERT 시 MappingDuplicateException
 * - findBySchemeId: 특정 scheme_id 에 속한 매핑 목록 반환 (deleted_at 없음 — 소프트 삭제 미도입)
 * - findBySchemeId: 해당 scheme_id 매핑 없을 때 빈 목록 반환
 * - findDefaultMapping: issue_type_id IS NULL 매핑 반환 (default workflow)
 * - findDefaultMapping: default mapping 없을 때 null 반환
 * - findByIssueType: 특정 issue_type_id 에 해당하는 매핑 반환
 * - findByIssueType: 해당 issue_type_id 매핑 없을 때 null 반환
 * - deleteMapping: 매핑 삭제 후 findBySchemeId 에서 미포함 확인
 * - deleteMapping: 존재하지 않는 id 삭제 시 no-op
 * - repairDefaultMappings: 매핑 없는 스킴에 default 매핑 백필 (R6)
 * - repairDefaultMappings: dangling(옛 UUID) 매핑을 유효한 workflow 로 수리 (R6-B)
 * - repairDefaultMappings: admin 이 바꾼 유효한 default 매핑은 보존 (무조건 UPSERT 금지 가드)
 *
 * Cross-BC FK 대응 패턴 (T7 WorkflowSchemesMigrationIntegrationTest 동일).
 * - Flyway target="1" → issue_types 스텁 생성 → LATEST migrate.
 * - workflow_scheme_issue_type_mappings 는 workflows(UUID PK) + issue_types(BIGINT PK) FK 보유.
 * - workflows fixture 및 issue_types fixture 사전 INSERT 필요.
 *
 * Testcontainers 이미지: quay.io/tembo/pg16-pgmq:latest (V004 pgmq 확장 요구).
 * Spring 컨텍스트 없이 Flyway + jOOQ DSL 직접 구성.
 */
@Testcontainers
class SchemeIssueTypeMappingRepositoryIntegrationTest {
    companion object {
        // quay.io/tembo/pg16-pgmq:latest — V004 pgmq 확장 + pgmq.create() 요구로 인해 tembo 이미지 사용.
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

        lateinit var repository: SchemeIssueTypeMappingRepository

        // 테스트 공유 fixture
        var schemeId1: Long = 0L
        var schemeId2: Long = 0L
        lateinit var workflowId1: UUID
        lateinit var workflowId2: UUID
        var issueTypeId1: Long = 0L
        var issueTypeId2: Long = 0L

        @BeforeAll
        @JvmStatic
        fun setup() {
            // 2단계 Flyway — V200 까지 적용 → issue_types 스텁 IF NOT EXISTS → V201 실행.
            // cross-BC dep 으로 issue-tracking V001~V003 도 함께 적용됨 (V003 가 issue_types 진짜 테이블 생성).
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

            // issue_types 스텁 — workflow_scheme_issue_type_mappings.issue_type_id cross-BC FK 통과용.
            // 프로덕션: issue-tracking V003 이 먼저 실행. 테스트: project-workflow 모듈 Flyway 만 실행.
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        "CREATE TABLE IF NOT EXISTS issue_types (" +
                            "id BIGSERIAL PRIMARY KEY, key VARCHAR(30) NOT NULL UNIQUE, " +
                            "name VARCHAR(255) NOT NULL, is_standard BOOLEAN NOT NULL DEFAULT FALSE, " +
                            "created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), " +
                            "updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), deleted_at TIMESTAMPTZ)",
                    )
                }
            }

            // V002~V004 실행 (LATEST)
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations(
                    "classpath:db/migration/issue-tracking",
                    "classpath:db/migration/project-workflow",
                )
                .load()
                .migrate()

            // workflows fixture — V004 default mapping seed 가 ON CONFLICT 를 쓰므로,
            // test 전용 워크플로우를 추가 삽입한다.
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                workflowId1 =
                    conn.prepareStatement(
                        "INSERT INTO workflows (key, name) VALUES (?, ?) RETURNING id",
                    ).use { stmt ->
                        stmt.setString(1, "test-workflow-1")
                        stmt.setString(2, "테스트 워크플로우 1")
                        stmt.executeQuery().use { rs ->
                            rs.next()
                            rs.getObject(1) as UUID
                        }
                    }

                workflowId2 =
                    conn.prepareStatement(
                        "INSERT INTO workflows (key, name) VALUES (?, ?) RETURNING id",
                    ).use { stmt ->
                        stmt.setString(1, "test-workflow-2")
                        stmt.setString(2, "테스트 워크플로우 2")
                        stmt.executeQuery().use { rs ->
                            rs.next()
                            rs.getObject(1) as UUID
                        }
                    }

                // workflow_schemes fixture — V004 seed 로 4 row 이미 존재. test 전용 2건 추가.
                schemeId1 =
                    conn.prepareStatement(
                        "INSERT INTO workflow_schemes (key, name) VALUES (?, ?) RETURNING id",
                    ).use { stmt ->
                        stmt.setString(1, "test-scheme-a")
                        stmt.setString(2, "테스트 스킴 A")
                        stmt.executeQuery().use { rs ->
                            rs.next()
                            rs.getLong(1)
                        }
                    }

                schemeId2 =
                    conn.prepareStatement(
                        "INSERT INTO workflow_schemes (key, name) VALUES (?, ?) RETURNING id",
                    ).use { stmt ->
                        stmt.setString(1, "test-scheme-b")
                        stmt.setString(2, "테스트 스킴 B")
                        stmt.executeQuery().use { rs ->
                            rs.next()
                            rs.getLong(1)
                        }
                    }

                // repairDefaultMappings 검증용 — 표준 4 워크플로우 키로 fixture 심음.
                // YamlSeedService.standardWorkflowKeys / V201:126-130 CASE 매핑과 동일한 키.
                // (schemeId 는 별도 INSERT 불필요 — V201 seed 가 4 표준 스킴을 이미 생성했다.)
                listOf("software-default", "bug-tracking", "simple", "kanban-basic").forEach { key ->
                    conn.prepareStatement(
                        "INSERT INTO workflows (key, name) VALUES (?, ?)",
                    ).use { stmt ->
                        stmt.setString(1, key)
                        stmt.setString(2, "표준 워크플로우 - $key")
                        stmt.execute()
                    }
                }

                // issue_types fixture — issue-tracking V003 이 5 표준 seed (story/bug/task/epic/subtask) 를
                // 이미 INSERT 했으므로, 신규 INSERT 가 아니라 SELECT 로 id 를 조회해 FK 참조용으로 사용한다.
                issueTypeId1 =
                    conn.prepareStatement(
                        "SELECT id FROM issue_types WHERE key = ?",
                    ).use { stmt ->
                        stmt.setString(1, "story")
                        stmt.executeQuery().use { rs ->
                            rs.next()
                            rs.getLong(1)
                        }
                    }

                issueTypeId2 =
                    conn.prepareStatement(
                        "SELECT id FROM issue_types WHERE key = ?",
                    ).use { stmt ->
                        stmt.setString(1, "task")
                        stmt.executeQuery().use { rs ->
                            rs.next()
                            rs.getLong(1)
                        }
                    }
            }

            val dataSource =
                org.springframework.jdbc.datasource.DriverManagerDataSource(
                    postgres.jdbcUrl,
                    postgres.username,
                    postgres.password,
                )
            repository = SchemeIssueTypeMappingRepository(DSL.using(dataSource, SQLDialect.POSTGRES))
        }
    }

    // ── addMapping: 신규 INSERT ────────────────────────────────────────────────

    @Test
    fun `addMapping - 신규 매핑 저장 시 id 가 할당된 SchemeIssueTypeMapping 반환`() {
        val mapping =
            SchemeIssueTypeMapping(
                id = null,
                schemeId = WorkflowSchemeId(schemeId1),
                issueTypeId = IssueTypeId(issueTypeId1),
                workflowId = workflowId1,
                createdAt = Instant.now(),
            )

        val saved = repository.addMapping(mapping)

        assertThat(saved.id).isNotNull
        assertThat(saved.schemeId).isEqualTo(WorkflowSchemeId(schemeId1))
        assertThat(saved.issueTypeId).isEqualTo(IssueTypeId(issueTypeId1))
        assertThat(saved.workflowId).isEqualTo(workflowId1)
    }

    // ── addMapping: UNIQUE 위반 → MappingDuplicateException ──────────────────

    @Test
    fun `addMapping - 동일 scheme_id + issue_type_id 중복 시 MappingDuplicateException 발생`() {
        val schemeId = WorkflowSchemeId(schemeId2)
        val issueTypeId = IssueTypeId(issueTypeId2)

        // 1차 삽입 — 성공
        repository.addMapping(
            SchemeIssueTypeMapping(
                id = null,
                schemeId = schemeId,
                issueTypeId = issueTypeId,
                workflowId = workflowId1,
                createdAt = Instant.now(),
            ),
        )

        // 2차 삽입 — UNIQUE 위반 (scheme_id + issue_type_id UNIQUE constraint)
        assertThatThrownBy {
            repository.addMapping(
                SchemeIssueTypeMapping(
                    id = null,
                    schemeId = schemeId,
                    issueTypeId = issueTypeId,
                    workflowId = workflowId2,
                    createdAt = Instant.now(),
                ),
            )
        }.isInstanceOf(MappingDuplicateException::class.java)
    }

    // ── addMapping: partial UNIQUE INDEX ix_scheme_default_mapping 검증 ────────

    @Test
    fun `addMapping - 같은 scheme_id 로 default mapping(issue_type_id IS NULL) 2건 시 MappingDefaultDuplicateException`() {
        // 별도 scheme — schemeId1/schemeId2 는 다른 테스트에서 조작하므로 신규 스킴 생성
        val newSchemeId =
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.prepareStatement(
                    "INSERT INTO workflow_schemes (key, name) VALUES (?, ?) RETURNING id",
                ).use { stmt ->
                    stmt.setString(1, "test-scheme-partial-idx")
                    stmt.setString(2, "partial idx 검증용 스킴")
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }
            }

        val schemeId = WorkflowSchemeId(newSchemeId)

        // 1차 default mapping (issue_type_id = null) 삽입 — 성공
        repository.addMapping(
            SchemeIssueTypeMapping(
                id = null,
                schemeId = schemeId,
                issueTypeId = null,
                workflowId = workflowId1,
                createdAt = Instant.now(),
            ),
        )

        // 2차 default mapping (issue_type_id = null) 삽입 — ix_scheme_default_mapping partial UNIQUE INDEX 위반
        // constraint name "ix_scheme_default_mapping" 분기 → MappingDefaultDuplicateException (CONCERN-5 옵션 a)
        assertThatThrownBy {
            repository.addMapping(
                SchemeIssueTypeMapping(
                    id = null,
                    schemeId = schemeId,
                    issueTypeId = null,
                    workflowId = workflowId2,
                    createdAt = Instant.now(),
                ),
            )
        }.isInstanceOf(MappingDefaultDuplicateException::class.java)
    }

    // ── findBySchemeId: 목록 반환 ─────────────────────────────────────────────

    @Test
    fun `findBySchemeId - 매핑이 있는 scheme_id 조회 시 목록 반환`() {
        val newSchemeId =
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.prepareStatement(
                    "INSERT INTO workflow_schemes (key, name) VALUES (?, ?) RETURNING id",
                ).use { stmt ->
                    stmt.setString(1, "test-scheme-find")
                    stmt.setString(2, "findBySchemeId 검증용 스킴")
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }
            }

        val schemeId = WorkflowSchemeId(newSchemeId)

        repository.addMapping(
            SchemeIssueTypeMapping(
                id = null,
                schemeId = schemeId,
                issueTypeId = null,
                workflowId = workflowId1,
                createdAt = Instant.now(),
            ),
        )
        repository.addMapping(
            SchemeIssueTypeMapping(
                id = null,
                schemeId = schemeId,
                issueTypeId = IssueTypeId(issueTypeId1),
                workflowId = workflowId2,
                createdAt = Instant.now(),
            ),
        )

        val result = repository.findBySchemeId(schemeId)

        assertThat(result).hasSize(2)
        assertThat(result.map { it.schemeId }).allMatch { it == schemeId }
    }

    // ── findBySchemeId: 빈 목록 ───────────────────────────────────────────────

    @Test
    fun `findBySchemeId - 매핑이 없는 scheme_id 조회 시 빈 목록 반환`() {
        val newSchemeId =
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.prepareStatement(
                    "INSERT INTO workflow_schemes (key, name) VALUES (?, ?) RETURNING id",
                ).use { stmt ->
                    stmt.setString(1, "test-scheme-empty")
                    stmt.setString(2, "빈 매핑 스킴")
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }
            }

        val result = repository.findBySchemeId(WorkflowSchemeId(newSchemeId))

        assertThat(result).isEmpty()
    }

    // ── findDefaultMapping: default mapping 반환 ──────────────────────────────

    @Test
    fun `findDefaultMapping - issue_type_id IS NULL 매핑 반환`() {
        val newSchemeId =
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.prepareStatement(
                    "INSERT INTO workflow_schemes (key, name) VALUES (?, ?) RETURNING id",
                ).use { stmt ->
                    stmt.setString(1, "test-scheme-default")
                    stmt.setString(2, "default mapping 검증용 스킴")
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }
            }

        val schemeId = WorkflowSchemeId(newSchemeId)

        repository.addMapping(
            SchemeIssueTypeMapping(
                id = null,
                schemeId = schemeId,
                issueTypeId = null,
                workflowId = workflowId1,
                createdAt = Instant.now(),
            ),
        )

        val result = repository.findDefaultMapping(schemeId)

        assertThat(result).isNotNull
        assertThat(result!!.issueTypeId).isNull()
        assertThat(result.workflowId).isEqualTo(workflowId1)
    }

    // ── findDefaultMapping: default mapping 없을 때 null ────────────────────

    @Test
    fun `findDefaultMapping - default mapping 없으면 null 반환`() {
        val newSchemeId =
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.prepareStatement(
                    "INSERT INTO workflow_schemes (key, name) VALUES (?, ?) RETURNING id",
                ).use { stmt ->
                    stmt.setString(1, "test-scheme-no-default")
                    stmt.setString(2, "default 없는 스킴")
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }
            }

        val result = repository.findDefaultMapping(WorkflowSchemeId(newSchemeId))

        assertThat(result).isNull()
    }

    // ── findByIssueType: 매핑 반환 ────────────────────────────────────────────

    @Test
    fun `findByIssueType - 특정 issue_type_id 매핑 반환`() {
        val newSchemeId =
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.prepareStatement(
                    "INSERT INTO workflow_schemes (key, name) VALUES (?, ?) RETURNING id",
                ).use { stmt ->
                    stmt.setString(1, "test-scheme-by-type")
                    stmt.setString(2, "이슈타입별 매핑 검증용 스킴")
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }
            }

        val schemeId = WorkflowSchemeId(newSchemeId)
        val issueTypeId = IssueTypeId(issueTypeId1)

        repository.addMapping(
            SchemeIssueTypeMapping(
                id = null,
                schemeId = schemeId,
                issueTypeId = issueTypeId,
                workflowId = workflowId1,
                createdAt = Instant.now(),
            ),
        )

        val result = repository.findByIssueType(schemeId, issueTypeId)

        assertThat(result).isNotNull
        assertThat(result!!.issueTypeId).isEqualTo(issueTypeId)
        assertThat(result.workflowId).isEqualTo(workflowId1)
    }

    // ── findByIssueType: 부재 시 null ──────────────────────────────────────────

    @Test
    fun `findByIssueType - 해당 issue_type_id 매핑 없으면 null 반환`() {
        val newSchemeId =
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.prepareStatement(
                    "INSERT INTO workflow_schemes (key, name) VALUES (?, ?) RETURNING id",
                ).use { stmt ->
                    stmt.setString(1, "test-scheme-no-type")
                    stmt.setString(2, "이슈타입 없는 스킴")
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }
            }

        val result = repository.findByIssueType(WorkflowSchemeId(newSchemeId), IssueTypeId(issueTypeId2))

        assertThat(result).isNull()
    }

    // ── deleteMapping: 삭제 후 미포함 ─────────────────────────────────────────

    @Test
    fun `deleteMapping - 삭제 후 findBySchemeId 에 미포함`() {
        val newSchemeId =
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.prepareStatement(
                    "INSERT INTO workflow_schemes (key, name) VALUES (?, ?) RETURNING id",
                ).use { stmt ->
                    stmt.setString(1, "test-scheme-delete")
                    stmt.setString(2, "삭제 검증용 스킴")
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }
            }

        val schemeId = WorkflowSchemeId(newSchemeId)

        val saved =
            repository.addMapping(
                SchemeIssueTypeMapping(
                    id = null,
                    schemeId = schemeId,
                    issueTypeId = null,
                    workflowId = workflowId1,
                    createdAt = Instant.now(),
                ),
            )

        repository.deleteMapping(saved.id!!)

        val remaining = repository.findBySchemeId(schemeId)
        assertThat(remaining).isEmpty()
    }

    // ── deleteMapping: 존재하지 않는 id → no-op ─────────────────────────────

    @Test
    fun `deleteMapping - 존재하지 않는 id 삭제 시 예외 없이 no-op`() {
        // 예외 없이 정상 완료되어야 한다
        repository.deleteMapping(99999L)
    }

    // ── repairDefaultMappings ────────────────────────────────────────────────
    //
    // 3 테스트 모두 표준 4 스킴("software-scheme" 등)을 공유 상태로 사용한다. JUnit 5 는
    // 메서드 실행 순서를 보장하지 않으므로, 각 테스트는 시작 전 clearDefaultMapping 으로
    // 대상 스킴의 default mapping 을 지워 순서 독립적인 상태에서 시작한다. workflow id 는
    // 캐시하지 않고 매번 fetchWorkflowIdByKey 로 최신 상태를 조회한다 (테스트 2 가 workflow 를
    // delete/reinsert 해 UUID 를 바꾸므로, 캐시하면 다른 테스트가 stale UUID 를 참조하게 된다).

    @Test
    fun `repairDefaultMappings - 매핑 없는 스킴에 default 매핑을 만든다 (R6 백필)`() {
        val expectedWorkflowKeyByScheme =
            mapOf(
                "software-scheme" to "software-default",
                "bug-tracking-scheme" to "bug-tracking",
                "simple-scheme" to "simple",
                "kanban-scheme" to "kanban-basic",
            )
        val schemeIds = expectedWorkflowKeyByScheme.keys.associateWith { fetchSchemeIdByKey(it) }
        schemeIds.values.forEach { clearDefaultMapping(it) }

        repository.repairDefaultMappings()

        expectedWorkflowKeyByScheme.forEach { (schemeKey, workflowKey) ->
            val schemeId = schemeIds.getValue(schemeKey)
            val expectedWorkflowId =
                fetchWorkflowIdByKey(workflowKey) ?: error("fixture 워크플로우 '$workflowKey' 없음")
            val mapping = repository.findDefaultMapping(WorkflowSchemeId(schemeId))

            assertThat(mapping).isNotNull
            assertThat(mapping!!.workflowId).isEqualTo(expectedWorkflowId)
        }
    }

    @Test
    fun `repairDefaultMappings - dangling(옛 UUID) 매핑을 유효한 workflow 로 수리한다 (R6-B)`() {
        val schemeKey = "software-scheme"
        val workflowKey = "software-default"
        val schemeId = fetchSchemeIdByKey(schemeKey)
        clearDefaultMapping(schemeId)

        // workflow 를 delete/reinsert 해 UUID 를 변경한다 (YamlSeedService.deleteWorkflow + insertWorkflow 재현).
        val oldWorkflowId =
            fetchWorkflowIdByKey(workflowKey) ?: error("fixture 워크플로우 '$workflowKey' 없음 — setup() 확인")
        reinsertWorkflow(workflowKey)
        val newWorkflowId =
            fetchWorkflowIdByKey(workflowKey) ?: error("재시드 후 워크플로우 '$workflowKey' 조회 실패")
        assertThat(newWorkflowId).isNotEqualTo(oldWorkflowId)

        // dangling 매핑 인위 구성 — FK RESTRICT(V201:84) 때문에 정상 INSERT 로는 만들 수 없어
        // 트리거를 일시 해제해 옛(더 이상 존재하지 않는) workflow_id 를 가리키는 행을 직접 심는다.
        // 실 프로덕션에서는 Task 2(매핑 정리→재연결)가 이 상태를 사전에 막는다 —
        // 이 테스트는 repairDefaultMappings 단독의 방어선(defense-in-depth)을 검증한다.
        insertDanglingDefaultMapping(schemeId, oldWorkflowId)

        repository.repairDefaultMappings()

        val repaired = repository.findDefaultMapping(WorkflowSchemeId(schemeId))
        assertThat(repaired).isNotNull
        assertThat(repaired!!.workflowId).isEqualTo(newWorkflowId)
        assertThat(repaired.workflowId).isNotEqualTo(oldWorkflowId)
    }

    @Test
    fun `repairDefaultMappings - admin 이 바꾼 유효한 default 매핑은 건드리지 않는다`() {
        val schemeId = fetchSchemeIdByKey("software-scheme")
        clearDefaultMapping(schemeId)

        // admin 이 REST 로 default 매핑을 시스템 기본값이 아닌 다른 유효한 workflow(bug-tracking) 로 바꿔 심었다고 가정.
        val adminChosenWorkflowId =
            fetchWorkflowIdByKey("bug-tracking") ?: error("fixture 워크플로우 'bug-tracking' 없음 — setup() 확인")
        repository.addMapping(
            SchemeIssueTypeMapping(
                id = null,
                schemeId = WorkflowSchemeId(schemeId),
                issueTypeId = null,
                workflowId = adminChosenWorkflowId,
                createdAt = Instant.now(),
            ),
        )

        repository.repairDefaultMappings()

        val mapping = repository.findDefaultMapping(WorkflowSchemeId(schemeId))
        assertThat(mapping).isNotNull
        // 무조건 UPSERT 라면 시스템 기본값(software-default) 로 되돌아갔을 것 — admin 설정(bug-tracking) 이 유지돼야 한다.
        assertThat(mapping!!.workflowId).isEqualTo(adminChosenWorkflowId)
    }

    // ── repairDefaultMappings 검증용 fixture 헬퍼 ──────────────────────────────
    //
    // 이미 존재하는 컨트롤/DB 상태를 조작해야 하므로 jOOQ DSL 이 아니라 raw JDBC 를 쓴다
    // (기존 파일 컨벤션 — 이 파일의 다른 fixture 헬퍼도 전부 DriverManager 직접 사용).

    /** workflow_schemes.key 로 id 를 조회한다. 없으면 예외 (V201 seed 로 4 표준 스킴은 항상 존재). */
    private fun fetchSchemeIdByKey(schemeKey: String): Long =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("SELECT id FROM workflow_schemes WHERE key = ?").use { stmt ->
                stmt.setString(1, schemeKey)
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "workflow_schemes.key='$schemeKey' 없음" }
                    rs.getLong(1)
                }
            }
        }

    /** workflows.key 로 id(UUID) 를 조회한다. 없으면 null. */
    private fun fetchWorkflowIdByKey(workflowKey: String): UUID? =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("SELECT id FROM workflows WHERE key = ?").use { stmt ->
                stmt.setString(1, workflowKey)
                stmt.executeQuery().use { rs -> extractOptionalUuid(rs) }
            }
        }

    /** ResultSet 의 첫 컬럼을 UUID 로 읽는다. 행이 없으면 null. */
    private fun extractOptionalUuid(rs: java.sql.ResultSet): UUID? {
        if (!rs.next()) return null
        return rs.getObject(1) as UUID
    }

    /** 스킴의 default mapping(issue_type_id IS NULL) 이 존재하면 삭제해 테스트 시작 상태를 초기화한다. */
    private fun clearDefaultMapping(schemeId: Long) {
        repository.findDefaultMapping(WorkflowSchemeId(schemeId))?.let { mapping ->
            repository.deleteMapping(mapping.id ?: error("SchemeIssueTypeMapping.id 가 null — 저장된 매핑이어야 함"))
        }
    }

    /** workflows 에서 key 로 행을 DELETE 후 동일 key 로 재INSERT 해 UUID 를 바꾼다 (YAML 재시드 재현). */
    private fun reinsertWorkflow(workflowKey: String) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("DELETE FROM workflows WHERE key = ?").use { stmt ->
                stmt.setString(1, workflowKey)
                stmt.execute()
            }
            conn.prepareStatement("INSERT INTO workflows (key, name) VALUES (?, ?)").use { stmt ->
                stmt.setString(1, workflowKey)
                stmt.setString(2, "표준 워크플로우 - $workflowKey (재시드)")
                stmt.execute()
            }
        }
    }

    /**
     * scheme_id/workflow_id 로 default mapping 행을 직접 INSERT 한다 — [workflowId] 가 workflows 에
     * 존재하지 않아도(dangling) 삽입되도록 FK 트리거를 일시 해제한다.
     *
     * 정상 경로([SchemeIssueTypeMappingRepository.addMapping])는 FK 제약상 존재하지 않는 workflow_id 를
     * 절대 저장할 수 없으므로, dangling 상태(R6-B) 를 재현하려면 이 방식이 유일하다.
     */
    private fun insertDanglingDefaultMapping(
        schemeId: Long,
        workflowId: UUID,
    ) {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("ALTER TABLE workflow_scheme_issue_type_mappings DISABLE TRIGGER ALL")
            }
            try {
                conn.prepareStatement(
                    "INSERT INTO workflow_scheme_issue_type_mappings (scheme_id, issue_type_id, workflow_id) " +
                        "VALUES (?, NULL, ?)",
                ).use { stmt ->
                    stmt.setLong(1, schemeId)
                    stmt.setObject(2, workflowId)
                    stmt.execute()
                }
            } finally {
                conn.createStatement().use { stmt ->
                    stmt.execute("ALTER TABLE workflow_scheme_issue_type_mappings ENABLE TRIGGER ALL")
                }
            }
        }
    }
}
