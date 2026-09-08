// WorkflowSchemeRepository — Testcontainers + Flyway 통합 테스트 (save/findByKey/softDelete/findAll)

package com.bts.workflow.scheme.repository

import com.bts.workflow.scheme.domain.WorkflowScheme
import com.bts.workflow.scheme.domain.WorkflowSchemeKey
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * WorkflowSchemeRepository 통합 테스트.
 *
 * 검증 범위.
 * - save: 신규 WorkflowScheme INSERT → id 할당된 인스턴스 반환
 * - findByKey: 존재하는 key 조회 → WorkflowScheme 반환
 * - findByKey: 존재하지 않는 key → null 반환
 * - findByKey: soft-delete된 스킴 → null 반환
 * - softDelete: deleted_at 컬럼 SET 확인
 * - findAll: 활성 스킴만 반환 (deleted_at IS NULL)
 * - findAll: 4 표준 seed 스킴 포함 확인
 *
 * Spring 컨텍스트 없이 Testcontainers PostgreSQL + Flyway + jOOQ DSL 직접 구성.
 * cross-BC FK (issue_types) 스텁 처리 — WorkflowSchemesMigrationIntegrationTest 와 동일 패턴.
 *
 * 이미지 선택 이유.
 * V004 마이그레이션이 pgmq 확장 + SELECT pgmq.create() 를 사용하므로 postgres:16-alpine 사용 불가.
 * ADR 2026-05-22-pgmq-postgres-image 참조.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class WorkflowSchemeRepositoryIntegrationTest {
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

        lateinit var repository: WorkflowSchemeRepository

        // 테스트에서 시각 고정 — 결정론적 비교용
        val fixedClock: Clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)

        @BeforeAll
        @JvmStatic
        fun setup() {
            // 2단계 Flyway — cross-BC FK (issue_types) 스텁 패턴
            // 1단계: V200 (project-workflow init) 까지 적용 (cross-BC dep 으로 issue-tracking V001~V003 동시 적용)
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

            // issue_types 스텁 테이블 생성 — V004 FK 통과용
            // 프로덕션에서는 issue-tracking V003 이 먼저 실행하지만,
            // 통합 테스트는 project-workflow Flyway 만 실행하므로 스텁으로 대체.
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

                    // 5 표준 issue_type seed
                    stmt.execute(
                        """
                        INSERT INTO issue_types (key, name, is_standard) VALUES
                            ('story',    '스토리',   TRUE),
                            ('bug',      '버그',     TRUE),
                            ('task',     '태스크',   TRUE),
                            ('epic',     '에픽',     TRUE),
                            ('subtask',  '서브태스크', TRUE)
                        ON CONFLICT DO NOTHING
                        """.trimIndent(),
                    )
                }
            }

            // 2단계: V002~V004 적용
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations(
                    "classpath:db/migration/issue-tracking",
                    "classpath:db/migration/project-workflow",
                )
                .load()
                .migrate()

            // workflows seed — V004 mapping seed 가 workflows JOIN 하므로 필요
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        """
                        INSERT INTO workflows (key, name) VALUES
                            ('software-default', '소프트웨어 개발 기본 워크플로우'),
                            ('bug-tracking',     '버그 추적 워크플로우'),
                            ('simple',           '단순 워크플로우'),
                            ('kanban-basic',     '칸반 기본 워크플로우')
                        ON CONFLICT (key) WHERE project_id IS NULL AND deleted_at IS NULL DO NOTHING
                        """.trimIndent(),
                    )
                }

                // mapping seed (V004 migrate 시점에 workflows 가 비어 있어 0건 — 수동 보완)
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        """
                        INSERT INTO workflow_scheme_issue_type_mappings (scheme_id, issue_type_id, workflow_id)
                        SELECT s.id, NULL, w.id
                        FROM workflow_schemes s
                        JOIN workflows w ON w.key = CASE s.key
                            WHEN 'software-scheme'     THEN 'software-default'
                            WHEN 'bug-tracking-scheme' THEN 'bug-tracking'
                            WHEN 'simple-scheme'       THEN 'simple'
                            WHEN 'kanban-scheme'       THEN 'kanban-basic'
                        END
                        ON CONFLICT ON CONSTRAINT uq_scheme_issue_type DO NOTHING
                        """.trimIndent(),
                    )
                }
            }

            // jOOQ DSLContext 구성
            val dataSource =
                org.springframework.jdbc.datasource.DriverManagerDataSource(
                    postgres.jdbcUrl,
                    postgres.username,
                    postgres.password,
                )
            val dsl = DSL.using(dataSource, SQLDialect.POSTGRES)
            repository = WorkflowSchemeRepository(dsl)
        }
    }

    // ── save ────────────────────────────────────────────────────────────────────

    @Test
    @Order(10)
    fun `save - 신규 WorkflowScheme INSERT 후 id 할당된 인스턴스 반환`() {
        val scheme =
            WorkflowScheme.create(
                key = WorkflowSchemeKey("test-scheme"),
                name = "테스트 스킴",
                description = "통합 테스트용 스킴",
                isDefault = false,
                clock = fixedClock,
            )

        val saved = repository.save(scheme)

        assertThat(saved.id).isNotNull
        assertThat(saved.id!!.value).isGreaterThan(0L)
        assertThat(saved.key.value).isEqualTo("test-scheme")
        assertThat(saved.name).isEqualTo("테스트 스킴")
        assertThat(saved.description).isEqualTo("통합 테스트용 스킴")
        assertThat(saved.isDefault).isFalse()
        assertThat(saved.deletedAt).isNull()
    }

    @Test
    @Order(11)
    fun `save - isDefault true 스킴 저장 가능`() {
        val scheme =
            WorkflowScheme.create(
                key = WorkflowSchemeKey("test-default-scheme"),
                name = "기본 테스트 스킴",
                description = null,
                isDefault = true,
                clock = fixedClock,
            )

        val saved = repository.save(scheme)

        assertThat(saved.id).isNotNull
        assertThat(saved.isDefault).isTrue()
    }

    // ── findByKey ───────────────────────────────────────────────────────────────

    @Test
    @Order(20)
    fun `findByKey - 존재하는 key 조회 시 WorkflowScheme 반환`() {
        // 4 표준 seed 중 software-scheme 조회
        val scheme = repository.findByKey(WorkflowSchemeKey("software-scheme"))

        assertThat(scheme).isNotNull
        assertThat(scheme!!.key.value).isEqualTo("software-scheme")
        assertThat(scheme.name).isEqualTo("Software 표준 스킴")
        assertThat(scheme.isDefault).isTrue()
        assertThat(scheme.id).isNotNull
        assertThat(scheme.deletedAt).isNull()
    }

    @Test
    @Order(21)
    fun `findByKey - bug-tracking-scheme 조회`() {
        val scheme = repository.findByKey(WorkflowSchemeKey("bug-tracking-scheme"))

        assertThat(scheme).isNotNull
        assertThat(scheme!!.key.value).isEqualTo("bug-tracking-scheme")
        assertThat(scheme.isDefault).isTrue()
    }

    @Test
    @Order(22)
    fun `findByKey - simple-scheme 조회`() {
        val scheme = repository.findByKey(WorkflowSchemeKey("simple-scheme"))

        assertThat(scheme).isNotNull
        assertThat(scheme!!.key.value).isEqualTo("simple-scheme")
    }

    @Test
    @Order(23)
    fun `findByKey - kanban-scheme 조회`() {
        val scheme = repository.findByKey(WorkflowSchemeKey("kanban-scheme"))

        assertThat(scheme).isNotNull
        assertThat(scheme!!.key.value).isEqualTo("kanban-scheme")
    }

    @Test
    @Order(24)
    fun `findByKey - 존재하지 않는 key 조회 시 null 반환`() {
        val scheme = repository.findByKey(WorkflowSchemeKey("non-existent-scheme"))

        assertThat(scheme).isNull()
    }

    @Test
    @Order(25)
    fun `findByKey - soft-delete된 스킴은 조회되지 않음`() {
        // soft-delete 할 스킴 저장
        val scheme =
            WorkflowScheme.create(
                key = WorkflowSchemeKey("to-be-deleted-scheme"),
                name = "삭제될 스킴",
                description = null,
                isDefault = false,
                clock = fixedClock,
            )
        val saved = repository.save(scheme)
        val schemeId = saved.id!!

        // soft-delete 적용
        repository.softDelete(schemeId)

        // findByKey 로 조회 시 null 반환 확인 (deleted_at IS NOT NULL → 필터)
        val found = repository.findByKey(WorkflowSchemeKey("to-be-deleted-scheme"))
        assertThat(found).isNull()
    }

    // ── softDelete ──────────────────────────────────────────────────────────────

    @Test
    @Order(30)
    fun `softDelete - deleted_at 컬럼이 SET됨`() {
        // softDelete 대상 스킴 저장
        val scheme =
            WorkflowScheme.create(
                key = WorkflowSchemeKey("soft-delete-target-scheme"),
                name = "소프트 삭제 대상 스킴",
                description = null,
                isDefault = false,
                clock = fixedClock,
            )
        val saved = repository.save(scheme)
        val schemeId = saved.id!!

        // 저장 직후에는 deleted_at IS NULL 확인
        val beforeDelete = repository.findByKey(WorkflowSchemeKey("soft-delete-target-scheme"))
        assertThat(beforeDelete).isNotNull

        // soft-delete 적용
        repository.softDelete(schemeId)

        // DB 직접 쿼리로 deleted_at 컬럼이 NOT NULL 임을 확인
        val deletedAt =
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.prepareStatement(
                    "SELECT deleted_at FROM workflow_schemes WHERE id = ?",
                ).use { stmt ->
                    stmt.setLong(1, schemeId.value)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) rs.getTimestamp(1) else null
                    }
                }
            }

        assertThat(deletedAt).isNotNull
    }

    // ── findAllWithCounts — cartesian product 회귀 가드 ─────────────────────────
    // 결함: LEFT JOIN 2회 (assignments + mappings) → cartesian product.
    // 예. 매핑 3건 + 할당 2건 = 6행 → count(*) = 6 (기댓값. 매핑 3, 할당 2).
    // 권장 옵션 B (서브쿼리) 로 수정 후 이 테스트가 GREEN 이 되어야 한다.

    @Test
    @Order(50)
    fun `findAllWithCounts - cartesian product 없이 정확한 카운트 반환`() {
        // 전용 스킴 생성 — 다른 테스트 데이터와 격리
        val scheme =
            WorkflowScheme.create(
                key = WorkflowSchemeKey("count-guard-scheme"),
                name = "카운트 회귀 가드 스킴",
                description = null,
                isDefault = false,
                clock = fixedClock,
            )
        val saved = repository.save(scheme)
        val schemeId = saved.id!!.value

        // 워크플로우 id 조회 (매핑에 필요)
        val workflowId =
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.prepareStatement("SELECT id FROM workflows LIMIT 1").use { stmt ->
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) rs.getString(1) else error("workflows 테이블이 비어 있음 — setup 확인")
                    }
                }
            }

        // issue_type_id NULL 매핑 3건 삽입 — 단, uq_scheme_issue_type(NULL)은 1건만 허용.
        // partial unique index 우회: issue_type_id 를 다른 row 로 넣으려면 issue_types 행 필요.
        // 여기서는 uq_scheme_issue_type 제약 때문에 NULL 매핑은 1건만 가능하므로
        // 나머지 2건은 실존 issue_type_id 로 삽입한다.
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            // 실존 issue_type id 2개 조회
            val issueTypeIds = mutableListOf<Long>()
            conn.prepareStatement("SELECT id FROM issue_types LIMIT 2").use { stmt ->
                stmt.executeQuery().use { rs ->
                    while (rs.next()) issueTypeIds.add(rs.getLong(1))
                }
            }
            check(issueTypeIds.size >= 2) { "issue_types seed 부족 — setup 확인" }

            // 매핑 3건: (NULL, issueTypeIds[0], issueTypeIds[1])
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    INSERT INTO workflow_scheme_issue_type_mappings (scheme_id, issue_type_id, workflow_id)
                    VALUES
                        ($schemeId, NULL,                  '$workflowId'::uuid),
                        ($schemeId, ${issueTypeIds[0]},    '$workflowId'::uuid),
                        ($schemeId, ${issueTypeIds[1]},    '$workflowId'::uuid)
                    ON CONFLICT ON CONSTRAINT uq_scheme_issue_type DO NOTHING
                    """.trimIndent(),
                )
            }

            // projects 스텁 — V202 FK (project_id → projects.id) 통과용 (V001 있으면 no-op)
            conn.createStatement().use { stmt ->
                stmt.execute(
                    "CREATE TABLE IF NOT EXISTS projects (" +
                        "id UUID PRIMARY KEY DEFAULT gen_random_uuid(), " +
                        "key VARCHAR(10) NOT NULL UNIQUE, name VARCHAR(255) NOT NULL, " +
                        "key_sequence BIGINT NOT NULL DEFAULT 0, " +
                        "created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), " +
                        "updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), deleted_at TIMESTAMPTZ)",
                )
            }

            // 할당 2건용 project fixture UUID (FK 통과용)
            val projectUuid1 = "cccccccc-0000-0000-0000-000000000001"
            val projectUuid2 = "cccccccc-0000-0000-0000-000000000002"
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    INSERT INTO projects (id, key, name)
                    VALUES
                        ('$projectUuid1', 'CGP1', 'Count Guard Project 1'),
                        ('$projectUuid2', 'CGP2', 'Count Guard Project 2')
                    ON CONFLICT DO NOTHING
                    """.trimIndent(),
                )
            }

            // 할당 2건: project_id UUID (V202 FK 준수)
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    INSERT INTO project_workflow_scheme_assignments (project_id, workflow_scheme_id, assigned_by)
                    VALUES
                        ('$projectUuid1'::uuid, $schemeId, '00000000-0000-0000-0000-000000000001'),
                        ('$projectUuid2'::uuid, $schemeId, '00000000-0000-0000-0000-000000000002')
                    ON CONFLICT (project_id) DO NOTHING
                    """.trimIndent(),
                )
            }
        }

        // findAllWithCounts 실행
        val rows = repository.findAllWithCounts()
        val row =
            rows.firstOrNull { it.scheme.key.value == "count-guard-scheme" }
                ?: error("count-guard-scheme 이 findAllWithCounts 결과에 없음")

        // cartesian product 버그 시: mappingsCount = 6, usedByProjectsCount = 6
        // 서브쿼리 수정 후: mappingsCount = 3, usedByProjectsCount = 2
        assertThat(row.mappingsCount)
            .withFailMessage("cartesian product 버그 — mappingsCount 기댓값 3, 실제 %d", row.mappingsCount)
            .isEqualTo(3L)
        assertThat(row.usedByProjectsCount)
            .withFailMessage("cartesian product 버그 — usedByProjectsCount 기댓값 2, 실제 %d", row.usedByProjectsCount)
            .isEqualTo(2L)
    }

    // ── findAll ─────────────────────────────────────────────────────────────────

    @Test
    @Order(40)
    fun `findAll - 활성 스킴만 반환 (deleted_at IS NULL)`() {
        val schemes = repository.findAll()

        // 모든 반환 스킴은 deletedAt IS NULL
        assertThat(schemes).allMatch { it.deletedAt == null }
    }

    @Test
    @Order(41)
    fun `findAll - 4 표준 seed 스킴 포함`() {
        val schemes = repository.findAll()
        val keys = schemes.map { it.key.value }

        assertThat(keys).contains(
            "software-scheme",
            "bug-tracking-scheme",
            "simple-scheme",
            "kanban-scheme",
        )
    }

    @Test
    @Order(42)
    fun `findAll - soft-delete된 스킴은 결과에 포함되지 않음`() {
        val scheme =
            WorkflowScheme.create(
                key = WorkflowSchemeKey("findall-deleted-scheme"),
                name = "findAll 삭제 테스트 스킴",
                description = null,
                isDefault = false,
                clock = fixedClock,
            )
        val saved = repository.save(scheme)

        // soft-delete 후
        repository.softDelete(saved.id!!)

        val schemes = repository.findAll()
        val keys = schemes.map { it.key.value }
        assertThat(keys).doesNotContain("findall-deleted-scheme")
    }
}
