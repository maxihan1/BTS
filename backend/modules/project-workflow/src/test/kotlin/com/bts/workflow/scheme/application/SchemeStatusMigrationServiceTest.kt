// 스킴 게시 · 교체 + 유형별 상태 이관 서비스 단위 테스트 (R11 · FR-WF-02 · FR-WF-07)

package com.bts.workflow.scheme.application

import com.bts.shared.issue.IssueStatusMigrationPort
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.issue.StatusMigrationCommand
import com.bts.shared.issue.StatusMigrationMapping
import com.bts.shared.issue.StatusMigrationTypeFilter
import com.bts.shared.permission.WorkflowScope
import com.bts.workflow.application.port.IssueStatusUsagePort
import com.bts.workflow.application.port.IssueTypeStatusCount
import com.bts.workflow.application.port.MigrationInFlightPort
import com.bts.workflow.domain.StateCategory
import com.bts.workflow.domain.Workflow
import com.bts.workflow.domain.WorkflowState
import com.bts.workflow.port.outbound.ActorId
import com.bts.workflow.port.outbound.toUuid
import com.bts.workflow.repository.WorkflowRepository
import com.bts.workflow.scheme.adapter.outbound.AlwaysAllowWorkflowSchemePermissionResolver
import com.bts.workflow.scheme.adapter.outbound.WorkflowSchemeEventPublisher
import com.bts.workflow.scheme.application.port.IssueTypeLookupPort
import com.bts.workflow.scheme.domain.ProjectKey
import com.bts.workflow.scheme.domain.ProjectWorkflowSchemeAssignment
import com.bts.workflow.scheme.domain.SchemeIssueTypeMapping
import com.bts.workflow.scheme.domain.TypeStatusMapping
import com.bts.workflow.scheme.domain.WorkflowScheme
import com.bts.workflow.scheme.domain.WorkflowSchemeId
import com.bts.workflow.scheme.domain.WorkflowSchemeKey
import com.bts.workflow.scheme.event.WorkflowSchemeAssignedEvent
import com.bts.workflow.scheme.exception.SchemeChangeStrandsIssuesException
import com.bts.workflow.scheme.exception.SchemeMappingsInvalidException
import com.bts.workflow.scheme.exception.SchemeMigrationInFlightException
import com.bts.workflow.scheme.exception.SchemeStatusMappingInvalidException
import com.bts.workflow.scheme.port.outbound.ProjectLookupPort
import com.bts.workflow.scheme.repository.ProjectWorkflowSchemeAssignmentRepository
import com.bts.workflow.scheme.repository.SchemeIssueTypeMappingRepository
import com.bts.workflow.scheme.repository.WorkflowSchemeRepository
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * [SchemeStatusMigrationService] 단위 테스트 — 판정은 진짜 [WorkflowSchemeApplicationService] 로 태운다.
 *
 * 세계: 스킴 `review-scheme` 의 기본 매핑이 `wf-review`(open · review). Bug 를 `wf-plain`(open · done) 으로
 * 옮기면 review 에 있는 Bug 가 남는다. Task 는 계속 `wf-review` 라 남지 않는다.
 */
class SchemeStatusMigrationServiceTest {
    private val schemeRepo: WorkflowSchemeRepository = mockk()
    private val assignmentRepo: ProjectWorkflowSchemeAssignmentRepository = mockk()
    private val mappingRepo: SchemeIssueTypeMappingRepository = mockk()
    private val eventPublisher: WorkflowSchemeEventPublisher = mockk()
    private val workflowRepo: WorkflowRepository = mockk()
    private val scopeResolver: WorkflowOwnershipScopeResolver = mockk()
    private val usagePort: IssueStatusUsagePort = mockk()
    private val projectLookupPort: ProjectLookupPort = mockk()
    private val inFlightPort: MigrationInFlightPort = mockk()
    private val migrationPort: IssueStatusMigrationPort = mockk()

    private lateinit var service: SchemeStatusMigrationService

    private val actor = ActorId("11111111-1111-1111-1111-111111111111")
    private val schemeKey = WorkflowSchemeKey("review-scheme")
    private val schemeId = WorkflowSchemeId(1L)
    private val projectId = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val archivedProjectId = UUID.fromString("00000000-0000-0000-0000-0000000000a2")
    private val wfReview = UUID.fromString("00000000-0000-0000-0000-00000000f001")
    private val wfPlain = UUID.fromString("00000000-0000-0000-0000-00000000f002")
    private val bug = IssueTypeId(10L)
    private val task = IssueTypeId(20L)
    private val bulkId = UUID.fromString("00000000-0000-0000-0000-0000000000b1")

    @BeforeEach
    fun setUp() {
        val schemeService =
            WorkflowSchemeApplicationService(
                schemeRepo,
                assignmentRepo,
                mappingRepo,
                eventPublisher,
                AlwaysAllowWorkflowSchemePermissionResolver(),
                workflowRepo,
                mockk<IssueTypeLookupPort>(),
                scopeResolver,
                usagePort,
            )
        service = SchemeStatusMigrationService(schemeService, projectLookupPort, inFlightPort, migrationPort)

        every { scopeResolver.ofScheme(any()) } returns WorkflowScope.Global
        every { schemeRepo.findByKey(schemeKey) } returns scheme(schemeKey, schemeId)
        every { mappingRepo.findBySchemeId(schemeId) } returns listOf(mapping(null, wfReview, id = 1L))
        every { workflowRepo.findByIds(any()) } answers {
            firstArg<List<UUID>>().associateWith { id ->
                if (id == wfReview) workflow("wf-review", "open", "review") else workflow("wf-plain", "open", "done")
            }
        }
        // 게시 범위는 아카이브 포함 조회로만 잡힌다 — 활성 전용 조회는 이 경로가 부르면 안 된다.
        every { assignmentRepo.findLiveProjectIdsBySchemeId(schemeId) } returns setOf(projectId, archivedProjectId)
        every { usagePort.countByTypeAndStatus(setOf(projectId, archivedProjectId)) } returns
            listOf(
                IssueTypeStatusCount(bug.value, "review", 3),
                IssueTypeStatusCount(bug.value, "open", 5),
                IssueTypeStatusCount(task.value, "review", 7),
            )
        every { projectLookupPort.findKeyById(projectId) } returns ProjectKey("ATLAS")
        every { projectLookupPort.findKeyById(archivedProjectId) } returns ProjectKey("OLD")
        every { inFlightPort.hasInFlightMigration(any()) } returns false
        every { migrationPort.enqueueStatusMigration(any()) } returns bulkId
        every { mappingRepo.addMapping(any()) } answers { firstArg() }
        justRun { mappingRepo.deleteMapping(any()) }
    }

    private val bugToPlain: List<Pair<IssueTypeId?, UUID>> = listOf(null to wfReview, bug to wfPlain)

    @Test
    fun `게시 — 상태 매핑 없이 남는 이슈가 있으면 유형별 목록을 담아 409 · 저장도 큐잉도 없다`() {
        assertThatThrownBy { service.publish(actor, schemeKey, bugToPlain, emptyList()) }
            .isInstanceOfSatisfying(SchemeChangeStrandsIssuesException::class.java) {
                assertThat(it.strandedByType).hasSize(1)
                val t = it.strandedByType.single()
                assertThat(t.issueTypeId).isEqualTo(bug.value)
                assertThat(t.workflowKey).isEqualTo("wf-plain")
                assertThat(t.statuses).isEqualTo(mapOf("review" to 3L))
                assertThat(it.strandedByStatus).isEqualTo(mapOf("review" to 3L))
            }
        verify(exactly = 0) { mappingRepo.addMapping(any()) }
        verify(exactly = 0) { migrationPort.enqueueStatusMigration(any()) }
    }

    @Test
    fun `게시 — 매핑이 남는 상태를 덮으면 차집합만 교체하고 그 유형만 아카이브 포함 범위로 옮긴다`() {
        val cmd = slot<StatusMigrationCommand>()
        every { migrationPort.enqueueStatusMigration(capture(cmd)) } returns bulkId

        val ids = service.publish(actor, schemeKey, bugToPlain, listOf(TypeStatusMapping(bug.value, "review", "done")))

        assertThat(ids).containsExactly(bulkId)
        verify(exactly = 0) { mappingRepo.deleteMapping(any()) }
        verify(exactly = 1) { mappingRepo.addMapping(match { it.issueTypeId == bug && it.workflowId == wfPlain }) }
        assertThat(cmd.captured.actorUserId).isEqualTo(actor.toUuid())
        assertThat(cmd.captured.mappings).containsExactly(StatusMigrationMapping("review", "done"))
        val onlyBug = StatusMigrationTypeFilter(setOf(bug.value), exceptListed = false)
        assertThat(cmd.captured.projectScopes).isEqualTo(mapOf("ATLAS" to onlyBug, "OLD" to onlyBug))
    }

    @Test
    fun `게시 — 도착 상태가 그 유형의 새 워크플로우에 없으면 400 · 저장 없음`() {
        assertThatThrownBy {
            service.publish(actor, schemeKey, bugToPlain, listOf(TypeStatusMapping(bug.value, "review", "review")))
        }.isInstanceOf(SchemeStatusMappingInvalidException::class.java)
        verify(exactly = 0) { mappingRepo.addMapping(any()) }
        verify(exactly = 0) { migrationPort.enqueueStatusMigration(any()) }
    }

    @Test
    fun `게시 — 기본 매핑이 정확히 하나가 아니면 400`() {
        assertThatThrownBy { service.publish(actor, schemeKey, listOf(bug to wfPlain), emptyList()) }
            .isInstanceOf(SchemeMappingsInvalidException::class.java)
        assertThatThrownBy {
            service.publish(actor, schemeKey, listOf(null to wfPlain, null to wfReview), emptyList())
        }.isInstanceOf(SchemeMappingsInvalidException::class.java)
    }

    @Test
    fun `게시 — 끝나지 않은 이관이 있으면 409 · 저장 없음`() {
        every { inFlightPort.hasInFlightMigration(setOf("ATLAS", "OLD")) } returns true

        assertThatThrownBy {
            service.publish(actor, schemeKey, bugToPlain, listOf(TypeStatusMapping(bug.value, "review", "done")))
        }.isInstanceOf(SchemeMigrationInFlightException::class.java)
        verify(exactly = 0) { mappingRepo.addMapping(any()) }
    }

    @Test
    fun `게시 — 기본 워크플로우를 바꾸면 지운 뒤 더한다 · 남는 이슈가 없으면 큐잉 0`() {
        every { usagePort.countByTypeAndStatus(any()) } returns listOf(IssueTypeStatusCount(bug.value, "open", 5))

        val ids = service.publish(actor, schemeKey, listOf<Pair<IssueTypeId?, UUID>>(null to wfPlain), emptyList())

        assertThat(ids).isEmpty()
        verify(exactly = 1) { mappingRepo.deleteMapping(1L) }
        verify(exactly = 1) { mappingRepo.addMapping(match { it.issueTypeId == null && it.workflowId == wfPlain }) }
        verify(exactly = 0) { migrationPort.enqueueStatusMigration(any()) }
    }

    @Test
    fun `교체 — 매핑이 덮으면 배정하고 그 프로젝트만 옮긴다`() {
        val newKey = WorkflowSchemeKey("plain-scheme")
        val newSchemeId = WorkflowSchemeId(2L)
        every { schemeRepo.findByKey(newKey) } returns scheme(newKey, newSchemeId)
        every { assignmentRepo.findByProjectId(projectId) } returns
            ProjectWorkflowSchemeAssignment(projectId, schemeId, Instant.now(), actor.toUuid())
        every { mappingRepo.findBySchemeId(newSchemeId) } returns listOf(mapping(null, wfPlain, id = 9L))
        every { usagePort.countByTypeAndStatus(setOf(projectId)) } returns listOf(IssueTypeStatusCount(task.value, "review", 2))
        justRun { assignmentRepo.saveAssignment(any()) }
        justRun { eventPublisher.publish(any<WorkflowSchemeAssignedEvent>()) }
        val cmd = slot<StatusMigrationCommand>()
        every { migrationPort.enqueueStatusMigration(capture(cmd)) } returns bulkId

        val ids =
            service.switchScheme(
                actor,
                projectId,
                "ATLAS",
                newKey,
                listOf(TypeStatusMapping(task.value, "review", "open")),
            )

        assertThat(ids).containsExactly(bulkId)
        verify(exactly = 1) { assignmentRepo.saveAssignment(any()) }
        assertThat(cmd.captured.projectScopes.keys).containsExactly("ATLAS")
        assertThat(cmd.captured.mappings).containsExactly(StatusMigrationMapping("review", "open"))
    }

    private fun workflow(
        key: String,
        vararg stateKeys: String,
    ): Workflow =
        Workflow.of(
            key = key,
            name = key,
            states = stateKeys.mapIndexed { i, k -> WorkflowState(key = k, name = k, category = StateCategory.TODO, displayOrder = i) },
            transitions = emptyList(),
        )

    private fun mapping(
        issueTypeId: IssueTypeId?,
        workflowId: UUID,
        id: Long,
    ) = SchemeIssueTypeMapping(id = id, schemeId = schemeId, issueTypeId = issueTypeId, workflowId = workflowId, createdAt = Instant.now())

    private fun scheme(
        key: WorkflowSchemeKey,
        id: WorkflowSchemeId,
    ): WorkflowScheme {
        val now = Instant.now()
        return WorkflowScheme.reconstruct(
            id = id,
            key = key,
            name = key.value,
            description = null,
            isDefault = false,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
            projectId = null,
        )
    }
}
