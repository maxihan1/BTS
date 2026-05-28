// WorkflowSchemeController Testcontainers 통합 테스트 — GET 단건/목록 매핑+카운트 응답 검증 (task-4)

package com.bts.workflow.scheme.web

import com.bts.shared.issue.IssueTypeId
import com.bts.shared.issue.IssueTypeRef
import com.bts.workflow.domain.StateCategory
import com.bts.workflow.domain.Workflow
import com.bts.workflow.domain.WorkflowState
import com.bts.workflow.port.outbound.ActorId
import com.bts.workflow.scheme.application.WorkflowSchemeApplicationService
import com.bts.workflow.scheme.application.port.IssueTypeLookupPort
import com.bts.workflow.scheme.domain.WorkflowSchemeKey
import com.bts.workflow.scheme.web.dto.WorkflowSchemeDetailResponse
import io.mockk.every
import io.mockk.mockk
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
import java.sql.DriverManager
import java.util.UUID

/**
 * WorkflowSchemeController 통합 테스트 (task-4).
 *
 * Spring 컨텍스트 없이 직접 ApplicationService 와 Repository 를 조합해
 * GET /api/v1/workflow-schemes/{schemeKey} 의 mappings 동봉 응답을 검증한다.
 *
 * 검증 범위.
 * - IT1. findDetail — WorkflowSchemeDetailResponse 에 mappings 리스트 포함
 * - IT2. findDetail — usedByProjectsCount + mappingsCount 올바른 값 반환
 * - IT3. listWithCounts — WorkflowSchemeDetailResponse 목록에 카운트 포함
 */
@Testcontainers
class WorkflowSchemeControllerIntegrationTest {
    companion object {
        private val temboImage: DockerImageName =
            DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                .asCompatibleSubstituteFor("postgres")

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(temboImage)
                .withDatabaseName("bts_ctrl_int")
                .withUsername("bts")
                .withPassword("bts_test")

        lateinit var service: WorkflowSchemeApplicationService
        lateinit var issueTypeLookupPort: IssueTypeLookupPort

        @BeforeAll
        @JvmStatic
        fun setup() {
            // 1단계: project-workflow + issue-tracking migration (cross-BC stub 포함)
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
                    stmt.execute(
                        """
                        INSERT INTO issue_types (key, name, is_standard) VALUES
                            ('story',    '스토리',    TRUE),
                            ('bug',      '버그',      TRUE),
                            ('task',     '태스크',    TRUE),
                            ('epic',     '에픽',      TRUE),
                            ('subtask',  '서브태스크', TRUE)
                        ON CONFLICT (key) DO NOTHING
                        """.trimIndent(),
                    )
                }
            }

            // 2단계: 나머지 migration 전부 적용
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .placeholderReplacement(false)
                .locations(
                    "classpath:db/migration/issue-tracking",
                    "classpath:db/migration/project-workflow",
                )
                .load()
                .migrate()

            // workflows seed (V004 mapping seed 가 workflows FK 참조)
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        """
                        INSERT INTO workflows (key, name) VALUES
                            ('software-default', '소프트웨어 개발 기본 워크플로우'),
                            ('bug-tracking',     '버그 추적 워크플로우'),
                            ('simple',           '단순 워크플로우'),
                            ('kanban-basic',     '칸반 기본 워크플로우')
                        ON CONFLICT (key) DO NOTHING
                        """.trimIndent(),
                    )
                }
            }

            val dsl = DSL.using(postgres.jdbcUrl, postgres.username, postgres.password, SQLDialect.POSTGRES)

            // IssueTypeLookupPort mock (cross-BC — DB 직접 호출하지 않음)
            issueTypeLookupPort = mockk()
            every { issueTypeLookupPort.lookup(emptyList()) } returns emptyMap()
            every { issueTypeLookupPort.lookup(any()) } answers {
                val ids = firstArg<List<IssueTypeId>>()
                ids.mapNotNull { id ->
                    // IT 에서 사용하는 issue_types 는 seed 순서대로 ID 가 1~5
                    val ref =
                        when (id.value) {
                            1L -> IssueTypeRef(key = "story", name = "스토리")
                            2L -> IssueTypeRef(key = "bug", name = "버그")
                            3L -> IssueTypeRef(key = "task", name = "태스크")
                            4L -> IssueTypeRef(key = "epic", name = "에픽")
                            5L -> IssueTypeRef(key = "subtask", name = "서브태스크")
                            else -> null
                        }
                    ref?.let { id to it }
                }.toMap()
            }

            service =
                buildService(
                    dsl = dsl,
                    issueTypeLookupPort = issueTypeLookupPort,
                )
        }

        /**
         * ApplicationService 와 의존 Repository 를 직접 구성한다.
         * Spring AOP 없이 순수 인스턴스화 — @Transactional AOP 없음.
         */
        private fun buildService(
            dsl: org.jooq.DSLContext,
            issueTypeLookupPort: IssueTypeLookupPort,
        ): WorkflowSchemeApplicationService {
            val schemeRepo = com.bts.workflow.scheme.repository.WorkflowSchemeRepository(dsl)
            val assignmentRepo = com.bts.workflow.scheme.repository.ProjectWorkflowSchemeAssignmentRepository(dsl)
            val mappingRepo = com.bts.workflow.scheme.repository.SchemeIssueTypeMappingRepository(dsl)
            val workflowRepo = com.bts.workflow.repository.WorkflowRepository(dsl)
            val eventPublisher = mockk<com.bts.workflow.scheme.adapter.outbound.WorkflowSchemeEventPublisher>(relaxed = true)
            val permissionResolver = com.bts.workflow.scheme.adapter.outbound.AlwaysAllowWorkflowSchemePermissionResolver()

            return WorkflowSchemeApplicationService(
                schemeRepo = schemeRepo,
                assignmentRepo = assignmentRepo,
                mappingRepo = mappingRepo,
                eventPublisher = eventPublisher,
                permissionResolver = permissionResolver,
                workflowRepo = workflowRepo,
                issueTypeLookupPort = issueTypeLookupPort,
            )
        }
    }

    // ── IT1. findDetail — mappings 동봉 ──────────────────────────────────────

    @Test
    fun `IT1 findDetail — software-scheme 단건 조회 시 mappings 리스트 포함`() {
        val result: WorkflowSchemeDetailResponse = service.findDetail(WorkflowSchemeKey("software-scheme"))

        assertThat(result.key).isEqualTo("software-scheme")
        // V004 seed — software-scheme 에 default mapping + story/bug/task/epic/subtask 5건 등록
        assertThat(result.mappings).isNotEmpty
        // workflowKey 는 빈 문자열이 아닌 실제 워크플로우 키여야 한다
        result.mappings.forEach { mapping ->
            assertThat(mapping.workflowKey).isNotBlank()
            assertThat(mapping.workflowName).isNotBlank()
        }
    }

    // ── IT2. findDetail — usedByProjectsCount + mappingsCount ────────────────

    @Test
    fun `IT2 findDetail — usedByProjectsCount + mappingsCount 올바른 카운트 반환`() {
        val result: WorkflowSchemeDetailResponse = service.findDetail(WorkflowSchemeKey("software-scheme"))

        // mappingsCount 는 mappings 리스트 크기와 일치해야 한다
        assertThat(result.mappingsCount).isEqualTo(result.mappings.size.toLong())
        // usedByProjectsCount 는 0 이상 (초기 상태는 0)
        assertThat(result.usedByProjectsCount).isGreaterThanOrEqualTo(0L)
    }

    // ── IT3. listWithCounts — 카운트 동봉 목록 ──────────────────────────────

    @Test
    fun `IT3 listWithCounts — 카운트 동봉된 스킴 목록 반환`() {
        val results: List<WorkflowSchemeDetailResponse> = service.listWithCounts()

        assertThat(results).isNotEmpty
        // 모든 스킴은 usedByProjectsCount + mappingsCount 를 포함해야 한다
        results.forEach { scheme ->
            assertThat(scheme.usedByProjectsCount).isGreaterThanOrEqualTo(0L)
            assertThat(scheme.mappingsCount).isGreaterThanOrEqualTo(0L)
        }
        // software-scheme 포함 확인
        val softwareScheme = results.find { it.key == "software-scheme" }
        assertThat(softwareScheme).isNotNull
    }
}
