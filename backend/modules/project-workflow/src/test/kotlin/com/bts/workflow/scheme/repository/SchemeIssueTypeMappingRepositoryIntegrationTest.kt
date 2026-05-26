// SchemeIssueTypeMappingRepository 통합 테스트 — addMapping / findBySchemeId / findDefaultMapping / findByIssueType / deleteMapping + partial UNIQUE INDEX 검증

package com.bts.workflow.scheme.repository

import com.bts.issue.type.domain.IssueTypeId
import com.bts.workflow.scheme.domain.SchemeIssueTypeMapping
import com.bts.workflow.scheme.domain.WorkflowSchemeId
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
            // 2단계 Flyway — V001 먼저 → issue_types 스텁 → V002~V004
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations("classpath:db/migration")
                .target("1")
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
                .locations("classpath:db/migration")
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

                // issue_types fixture — 테스트에서 issue_type_id FK 참조용
                issueTypeId1 =
                    conn.prepareStatement(
                        "INSERT INTO issue_types (key, name) VALUES (?, ?) RETURNING id",
                    ).use { stmt ->
                        stmt.setString(1, "story")
                        stmt.setString(2, "스토리")
                        stmt.executeQuery().use { rs ->
                            rs.next()
                            rs.getLong(1)
                        }
                    }

                issueTypeId2 =
                    conn.prepareStatement(
                        "INSERT INTO issue_types (key, name) VALUES (?, ?) RETURNING id",
                    ).use { stmt ->
                        stmt.setString(1, "task")
                        stmt.setString(2, "태스크")
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
    fun `addMapping - 같은 scheme_id 로 default mapping(issue_type_id IS NULL) 2건 시 MappingDuplicateException`() {
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

        // 2차 default mapping (issue_type_id = null) 삽입 — partial UNIQUE INDEX 위반
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
        }.isInstanceOf(MappingDuplicateException::class.java)
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
}
