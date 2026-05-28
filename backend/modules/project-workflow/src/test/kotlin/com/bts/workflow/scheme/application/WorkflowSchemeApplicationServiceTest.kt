// WorkflowSchemeApplicationService 단위 테스트 — MockK mock Repository + AlwaysAllow stub

package com.bts.workflow.scheme.application

import com.bts.shared.issue.IssueTypeId
import com.bts.shared.issue.IssueTypeRef
import com.bts.workflow.domain.Workflow
import com.bts.workflow.port.outbound.ActorId
import com.bts.workflow.repository.WorkflowRepository
import com.bts.workflow.scheme.adapter.outbound.AlwaysAllowWorkflowSchemePermissionResolver
import com.bts.workflow.scheme.adapter.outbound.WorkflowSchemeEventPublisher
import com.bts.workflow.scheme.application.port.IssueTypeLookupPort
import com.bts.workflow.scheme.domain.ProjectWorkflowSchemeAssignment
import com.bts.workflow.scheme.domain.SchemeIssueTypeMapping
import com.bts.workflow.scheme.domain.WorkflowScheme
import com.bts.workflow.scheme.domain.WorkflowSchemeId
import com.bts.workflow.scheme.domain.WorkflowSchemeKey
import com.bts.workflow.scheme.event.WorkflowSchemeAssignedEvent
import com.bts.workflow.scheme.exception.MappingDefaultDuplicateException
import com.bts.workflow.scheme.exception.MappingDuplicateException
import com.bts.workflow.scheme.exception.SchemeInUseException
import com.bts.workflow.scheme.exception.SchemeStandardFieldLockedException
import com.bts.workflow.scheme.exception.SchemeStandardNotDeletableException
import com.bts.workflow.scheme.exception.WorkflowSchemeNotFoundException
import com.bts.workflow.scheme.port.outbound.WorkflowSchemePermission
import com.bts.workflow.scheme.port.outbound.WorkflowSchemePermissionResolver
import com.bts.workflow.scheme.port.outbound.WorkflowSchemeScope
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
 * [WorkflowSchemeApplicationService] 단위 테스트.
 *
 * 트랜잭션 AOP 가 없는 순수 단위 테스트.
 * [WorkflowSchemeRepository] / [ProjectWorkflowSchemeAssignmentRepository] 는 MockK stub 으로 대체한다.
 * 권한 검증은 [AlwaysAllowWorkflowSchemePermissionResolver] stub 을 직접 주입해 항상 통과시킨다.
 */
class WorkflowSchemeApplicationServiceTest {
    private val schemeRepo: WorkflowSchemeRepository = mockk()
    private val assignmentRepo: ProjectWorkflowSchemeAssignmentRepository = mockk()
    private val mappingRepo: SchemeIssueTypeMappingRepository = mockk()
    private val eventPublisher: WorkflowSchemeEventPublisher = mockk()
    private val permissionResolver = AlwaysAllowWorkflowSchemePermissionResolver()
    private val workflowRepo: WorkflowRepository = mockk()
    private val issueTypeLookupPort: IssueTypeLookupPort = mockk()

    private lateinit var service: WorkflowSchemeApplicationService

    private val actor = ActorId("11111111-1111-1111-1111-111111111111")

    @BeforeEach
    fun setUp() {
        service =
            WorkflowSchemeApplicationService(
                schemeRepo,
                assignmentRepo,
                mappingRepo,
                eventPublisher,
                permissionResolver,
                workflowRepo,
                issueTypeLookupPort,
            )
    }

    // ── create ────────────────────────────────────────────────────────────────

    @Test
    fun `create — 정상 입력 시 save 호출 후 저장된 스킴을 반환한다`() {
        val key = WorkflowSchemeKey("software-scheme")
        val expected = buildScheme(key, isDefault = false)
        every { schemeRepo.save(any()) } returns expected

        val result = service.create(actor, key, "Software Scheme", null, isDefault = false)

        assertThat(result.key).isEqualTo(key)
        verify(exactly = 1) { schemeRepo.save(any()) }
    }

    // ── findDetail (task-4 RED) ───────────────────────────────────────────────

    @Test
    fun `findDetail — mappingRepo findBySchemeId + IssueTypeLookupPort lookup + workflowRepo findByIds 호출 (task-4 RED)`() {
        val key = WorkflowSchemeKey("software-scheme")
        val schemeId = WorkflowSchemeId(1L)
        val scheme = buildScheme(key, id = schemeId)
        val issueTypeId = IssueTypeId(10L)
        val workflowId = java.util.UUID.fromString("cccccccc-0000-0000-0000-000000000001")
        val mapping =
            SchemeIssueTypeMapping(
                id = 1L,
                schemeId = schemeId,
                issueTypeId = issueTypeId,
                workflowId = workflowId,
                createdAt = java.time.Instant.now(),
            )
        val issueTypeRef = IssueTypeRef(key = "bug", name = "버그")
        val workflow =
            com.bts.workflow.domain.Workflow.of(
                key = "software-default",
                name = "소프트웨어 기본",
                states =
                    listOf(
                        com.bts.workflow.domain.WorkflowState(
                            key = "open",
                            name = "열림",
                            category = com.bts.workflow.domain.StateCategory.TODO,
                            displayOrder = 0,
                        ),
                    ),
                transitions = emptyList(),
            )

        every { schemeRepo.findByKey(key) } returns scheme
        every { mappingRepo.findBySchemeId(schemeId) } returns listOf(mapping)
        every { issueTypeLookupPort.lookup(listOf(issueTypeId)) } returns mapOf(issueTypeId to issueTypeRef)
        every { workflowRepo.findByIds(listOf(workflowId)) } returns mapOf(workflowId to workflow)
        every { schemeRepo.countAssignedProjects(schemeId) } returns 2L

        val result = service.findDetail(key)

        assertThat(result.key).isEqualTo(key.value)
        assertThat(result.usedByProjectsCount).isEqualTo(2L)
        assertThat(result.mappingsCount).isEqualTo(1L)
        assertThat(result.mappings).hasSize(1)
        assertThat(result.mappings[0].issueTypeKey).isEqualTo("bug")
        assertThat(result.mappings[0].workflowKey).isEqualTo("software-default")

        verify(exactly = 1) { mappingRepo.findBySchemeId(schemeId) }
        verify(exactly = 1) { issueTypeLookupPort.lookup(listOf(issueTypeId)) }
        verify(exactly = 1) { workflowRepo.findByIds(listOf(workflowId)) }
        verify(exactly = 1) { schemeRepo.countAssignedProjects(schemeId) }
    }

    @Test
    fun `listWithCounts — findAllWithCounts 호출 후 WorkflowSchemeDetailResponse 목록을 반환한다 (task-4 RED)`() {
        val key1 = WorkflowSchemeKey("software-scheme")
        val key2 = WorkflowSchemeKey("service-desk")
        val schemeId1 = WorkflowSchemeId(1L)
        val schemeId2 = WorkflowSchemeId(2L)

        val countRows =
            listOf(
                com.bts.workflow.scheme.repository.SchemeCountRow(buildScheme(key1, id = schemeId1), 3L, 4L),
                com.bts.workflow.scheme.repository.SchemeCountRow(buildScheme(key2, id = schemeId2), 0L, 1L),
            )
        every { schemeRepo.findAllWithCounts() } returns countRows

        val result = service.listWithCounts()

        assertThat(result).hasSize(2)
        assertThat(result[0].key).isEqualTo("software-scheme")
        assertThat(result[0].usedByProjectsCount).isEqualTo(3L)
        assertThat(result[0].mappingsCount).isEqualTo(4L)
        verify(exactly = 1) { schemeRepo.findAllWithCounts() }
    }

    // ── find ──────────────────────────────────────────────────────────────────

    @Test
    fun `find — 존재하는 key 조회 시 스킴을 반환한다`() {
        val key = WorkflowSchemeKey("software-scheme")
        val scheme = buildScheme(key, isDefault = false)
        every { schemeRepo.findByKey(key) } returns scheme

        val result = service.find(key)

        assertThat(result.key).isEqualTo(key)
    }

    @Test
    fun `find — 존재하지 않는 key 조회 시 WorkflowSchemeNotFoundException 을 던진다`() {
        val key = WorkflowSchemeKey("unknown-key")
        every { schemeRepo.findByKey(key) } returns null

        assertThatThrownBy { service.find(key) }
            .isInstanceOf(WorkflowSchemeNotFoundException::class.java)
    }

    // ── update ────────────────────────────────────────────────────────────────

    @Test
    fun `update — 일반 스킴의 name 변경 시 update 호출 후 변경된 스킴을 반환한다`() {
        val key = WorkflowSchemeKey("software-scheme")
        val existing = buildScheme(key, isDefault = false)
        val updated = buildScheme(key, name = "Updated Name", isDefault = false)
        every { schemeRepo.findByKey(key) } returns existing
        every { schemeRepo.update(any()) } returns updated

        val result = service.update(actor, key, newName = "Updated Name", newDescription = null, newIsDefault = false)

        assertThat(result.name).isEqualTo("Updated Name")
        verify(exactly = 1) { schemeRepo.update(any()) }
    }

    @Test
    fun `update — 표준 스킴의 name 변경 시 SchemeStandardFieldLockedException 을 던진다`() {
        val key = WorkflowSchemeKey("default-scheme")
        val standardScheme = buildScheme(key, name = "Default Scheme", isDefault = true)
        every { schemeRepo.findByKey(key) } returns standardScheme

        assertThatThrownBy {
            service.update(actor, key, newName = "Hacked Name", newDescription = null, newIsDefault = true)
        }.isInstanceOf(SchemeStandardFieldLockedException::class.java)
    }

    @Test
    fun `update — 표준 스킴의 isDefault 변경 시 SchemeStandardFieldLockedException 을 던진다`() {
        val key = WorkflowSchemeKey("default-scheme")
        val standardScheme = buildScheme(key, name = "Default Scheme", isDefault = true)
        every { schemeRepo.findByKey(key) } returns standardScheme

        assertThatThrownBy {
            service.update(actor, key, newName = "Default Scheme", newDescription = null, newIsDefault = false)
        }.isInstanceOf(SchemeStandardFieldLockedException::class.java)
    }

    // ── softDelete ────────────────────────────────────────────────────────────

    @Test
    fun `softDelete — 사용 중이지 않은 일반 스킴 삭제 시 softDelete 호출된다`() {
        val key = WorkflowSchemeKey("software-scheme")
        val schemeId = WorkflowSchemeId(1L)
        val scheme = buildScheme(key, isDefault = false, id = schemeId)
        every { schemeRepo.findByKey(key) } returns scheme
        every { assignmentRepo.existsBySchemeId(schemeId) } returns false
        every { schemeRepo.softDelete(schemeId) } returns Unit

        service.softDelete(actor, key)

        verify(exactly = 1) { schemeRepo.softDelete(schemeId) }
    }

    @Test
    fun `S6 — softDelete 표준 스킴 시 SchemeStandardNotDeletableException 을 던진다`() {
        val key = WorkflowSchemeKey("default-scheme")
        val standardScheme = buildScheme(key, isDefault = true, id = WorkflowSchemeId(1L))
        every { schemeRepo.findByKey(key) } returns standardScheme

        assertThatThrownBy { service.softDelete(actor, key) }
            .isInstanceOf(SchemeStandardNotDeletableException::class.java)
    }

    @Test
    fun `S7 — softDelete 사용 중인 스킴 시 SchemeInUseException 을 던진다`() {
        val key = WorkflowSchemeKey("software-scheme")
        val schemeId = WorkflowSchemeId(2L)
        val scheme = buildScheme(key, isDefault = false, id = schemeId)
        every { schemeRepo.findByKey(key) } returns scheme
        every { assignmentRepo.existsBySchemeId(schemeId) } returns true

        assertThatThrownBy { service.softDelete(actor, key) }
            .isInstanceOf(SchemeInUseException::class.java)
    }

    // ── list ──────────────────────────────────────────────────────────────────

    @Test
    fun `list — findAll 결과를 그대로 반환한다`() {
        val schemes =
            listOf(
                buildScheme(WorkflowSchemeKey("software-scheme"), isDefault = false),
                buildScheme(WorkflowSchemeKey("service-desk"), isDefault = false),
            )
        every { schemeRepo.findAll() } returns schemes

        val result: List<WorkflowScheme> = service.list()

        assertThat(result).hasSize(2)
        assertThat(result.map { scheme -> scheme.key.value }).containsExactly("software-scheme", "service-desk")
        verify(exactly = 1) { schemeRepo.findAll() }
    }

    // ── addMapping ────────────────────────────────────────────────────────────

    @Test
    fun `addMapping — 정상 입력 시 mappingRepo addMapping 호출 후 저장된 매핑을 반환한다`() {
        val key = WorkflowSchemeKey("software-scheme")
        val schemeId = WorkflowSchemeId(1L)
        val scheme = buildScheme(key, id = schemeId)
        val issueTypeId = IssueTypeId(10L)
        val workflowId = UUID.randomUUID()
        val expected = buildMapping(schemeId, issueTypeId, workflowId)

        every { schemeRepo.findByKey(key) } returns scheme
        every { mappingRepo.addMapping(any()) } returns expected

        val result = service.addMapping(actor, key, issueTypeId, workflowId)

        assertThat(result.schemeId).isEqualTo(schemeId)
        assertThat(result.issueTypeId).isEqualTo(issueTypeId)
        verify(exactly = 1) { mappingRepo.addMapping(any()) }
    }

    @Test
    fun `addMapping — UNIQUE 위반 시 MappingDuplicateException 을 전파한다`() {
        val key = WorkflowSchemeKey("software-scheme")
        val schemeId = WorkflowSchemeId(1L)
        val scheme = buildScheme(key, id = schemeId)
        val issueTypeId = IssueTypeId(10L)

        every { schemeRepo.findByKey(key) } returns scheme
        every { mappingRepo.addMapping(any()) } throws
            MappingDuplicateException(
                schemeKey = key.value,
                issueTypeKey = issueTypeId.value.toString(),
            )

        assertThatThrownBy { service.addMapping(actor, key, issueTypeId, UUID.randomUUID()) }
            .isInstanceOf(MappingDuplicateException::class.java)
    }

    @Test
    fun `addMapping — default mapping UNIQUE 위반 시 MappingDefaultDuplicateException 을 전파한다`() {
        val key = WorkflowSchemeKey("software-scheme")
        val schemeId = WorkflowSchemeId(1L)
        val scheme = buildScheme(key, id = schemeId)

        every { schemeRepo.findByKey(key) } returns scheme
        every { mappingRepo.addMapping(any()) } throws
            MappingDefaultDuplicateException(
                schemeKey = key.value,
            )

        assertThatThrownBy { service.addMapping(actor, key, issueTypeId = null, UUID.randomUUID()) }
            .isInstanceOf(MappingDefaultDuplicateException::class.java)
    }

    // ── deleteMapping ─────────────────────────────────────────────────────────

    @Test
    fun `deleteMapping — 정상 호출 시 mappingRepo deleteMapping 이 호출된다`() {
        justRun { mappingRepo.deleteMapping(any()) }

        service.deleteMapping(actor, mappingId = 99L)

        verify(exactly = 1) { mappingRepo.deleteMapping(99L) }
    }

    // ── assignToProject ───────────────────────────────────────────────────────

    @Test
    fun `assignToProject — 정상 케이스 — saveAssignment 호출 + WorkflowSchemeAssignedEvent 발행된다`() {
        val schemeKey = WorkflowSchemeKey("software-scheme")
        val schemeId = WorkflowSchemeId(1L)
        val projectId = UUID.fromString("00000000-0000-0000-0000-000000000042")
        val projectKey = "ATLAS"
        val scheme = buildScheme(schemeKey, id = schemeId)

        every { schemeRepo.findByKey(schemeKey) } returns scheme
        justRun { assignmentRepo.saveAssignment(any()) }
        val eventSlot = slot<WorkflowSchemeAssignedEvent>()
        justRun { eventPublisher.publish(capture(eventSlot)) }

        val result = service.assignToProject(actor, projectId, projectKey, schemeKey)

        assertThat(result.projectId).isEqualTo(projectId)
        assertThat(result.workflowSchemeId).isEqualTo(schemeId)
        verify(exactly = 1) { assignmentRepo.saveAssignment(any()) }
        verify(exactly = 1) { eventPublisher.publish(any<WorkflowSchemeAssignedEvent>()) }
        assertThat(eventSlot.captured.schemeId).isEqualTo(schemeId)
        assertThat(eventSlot.captured.projectId).isEqualTo(projectId)
    }

    @Test
    fun `assignToProject — 스킴이 없으면 WorkflowSchemeNotFoundException 을 던진다`() {
        val schemeKey = WorkflowSchemeKey("nonexistent-scheme")
        every { schemeRepo.findByKey(schemeKey) } returns null

        assertThatThrownBy {
            service.assignToProject(actor, UUID.randomUUID(), "PROJ", schemeKey)
        }.isInstanceOf(WorkflowSchemeNotFoundException::class.java)
    }

    @Test
    fun `findAssignedScheme — assignment 존재 시 해당 scheme 을 반환한다`() {
        val schemeKey = WorkflowSchemeKey("software-scheme")
        val schemeId = WorkflowSchemeId(1L)
        val projectId = UUID.fromString("00000000-0000-0000-0000-000000000010")
        val scheme = buildScheme(schemeKey, id = schemeId)
        val assignment =
            ProjectWorkflowSchemeAssignment(
                projectId = projectId,
                workflowSchemeId = schemeId,
                assignedAt = Instant.now(),
                assignedBy = UUID.randomUUID(),
            )

        every { assignmentRepo.findByProjectId(projectId) } returns assignment
        every { schemeRepo.findById(schemeId) } returns scheme

        val result = service.findAssignedScheme(projectId, "ATLAS")

        assertThat(result.key).isEqualTo(schemeKey)
    }

    @Test
    fun `EC-1 D10 — findAssignedScheme assignment 없으면 software-scheme 으로 auto-assign 후 scheme 반환한다`() {
        val softwareSchemeKey = WorkflowSchemeKey("software-scheme")
        val schemeId = WorkflowSchemeId(1L)
        val projectId = UUID.fromString("00000000-0000-0000-0000-000000000099")
        val projectKey = "NEW"
        val scheme = buildScheme(softwareSchemeKey, id = schemeId)

        // 첫 호출: assignment 없음
        every { assignmentRepo.findByProjectId(projectId) } returns null
        // auto-assign 흐름: software-scheme 조회
        every { schemeRepo.findByKey(softwareSchemeKey) } returns scheme
        justRun { assignmentRepo.saveAssignment(any()) }
        justRun { eventPublisher.publish(any<WorkflowSchemeAssignedEvent>()) }
        // auto-assign 후 scheme 재조회
        every { schemeRepo.findById(schemeId) } returns scheme

        val result = service.findAssignedScheme(projectId, projectKey)

        assertThat(result.key).isEqualTo(softwareSchemeKey)
        verify(exactly = 1) { assignmentRepo.saveAssignment(any()) }
        verify(exactly = 1) { eventPublisher.publish(any<WorkflowSchemeAssignedEvent>()) }
    }

    @Test
    fun `assignToProject — ASSIGN_SCHEME 권한 거부 시 예외를 던진다`() {
        val denyingResolver = mockk<WorkflowSchemePermissionResolver>()
        every {
            // ActorId 는 UUID 강제 value class — any() 매칭 시 MockK 가 임의값으로 생성하다 검증 실패한다. 구체 actor 로 매칭.
            denyingResolver.requirePermission(actor, WorkflowSchemePermission.ASSIGN_SCHEME, any<WorkflowSchemeScope.Project>())
        } throws RuntimeException("WORKFLOW_PERMISSION_DENIED")

        val svcWithDeny =
            WorkflowSchemeApplicationService(schemeRepo, assignmentRepo, mappingRepo, eventPublisher, denyingResolver, workflowRepo, issueTypeLookupPort)

        assertThatThrownBy {
            svcWithDeny.assignToProject(actor, UUID.randomUUID(), "PROJ", WorkflowSchemeKey("software-scheme"))
        }.hasMessageContaining("WORKFLOW_PERMISSION_DENIED")
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private fun buildMapping(
        schemeId: WorkflowSchemeId,
        issueTypeId: IssueTypeId?,
        workflowId: UUID,
        id: Long = 1L,
    ): SchemeIssueTypeMapping =
        SchemeIssueTypeMapping(
            id = id,
            schemeId = schemeId,
            issueTypeId = issueTypeId,
            workflowId = workflowId,
            createdAt = Instant.now(),
        )

    private fun buildScheme(
        key: WorkflowSchemeKey,
        name: String = key.value,
        isDefault: Boolean = false,
        id: WorkflowSchemeId = WorkflowSchemeId(1L),
    ): WorkflowScheme {
        val now = Instant.now()
        return WorkflowScheme.reconstruct(
            id = id,
            key = key,
            name = name,
            description = null,
            isDefault = isDefault,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
        )
    }
}
