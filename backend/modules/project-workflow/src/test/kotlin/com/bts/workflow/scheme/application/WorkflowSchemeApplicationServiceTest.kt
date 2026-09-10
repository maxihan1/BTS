// WorkflowSchemeApplicationService 단위 테스트 — MockK mock Repository + AlwaysAllow stub

package com.bts.workflow.scheme.application

import com.bts.shared.issue.IssueTypeId
import com.bts.shared.issue.IssueTypeRef
import com.bts.shared.permission.WorkflowSchemeAccessDeniedException
import com.bts.shared.permission.WorkflowSchemePermission
import com.bts.shared.permission.WorkflowSchemePermissionResolver
import com.bts.shared.permission.WorkflowScope
import com.bts.workflow.domain.Workflow
import com.bts.workflow.port.outbound.ActorId
import com.bts.workflow.port.outbound.toUuid
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
    private val scopeResolver: WorkflowOwnershipScopeResolver = mockk()

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
                scopeResolver,
            )
        // 이 파일의 픽스처는 전부 전역 스킴이다 — 소유별 스코프 판정은 WorkflowOwnershipScopeResolverTest 가 잰다.
        every { scopeResolver.ofScheme(any()) } returns WorkflowScope.Global
        every { scopeResolver.ofProjectId(any()) } returns WorkflowScope.Global
    }

    // ── create ────────────────────────────────────────────────────────────────

    @Test
    fun `create — 정상 입력 시 save 호출 후 저장된 스킴을 반환한다`() {
        val key = WorkflowSchemeKey("software-scheme")
        val expected = buildScheme(key, isDefault = false)
        every { schemeRepo.save(any()) } returns expected

        val result = service.create(actor, key, "Software Scheme", null, isDefault = false, projectId = null)

        assertThat(result.key).isEqualTo(key)
        verify(exactly = 1) { schemeRepo.save(any()) }
    }

    // ── findDetail (task-4 RED) ───────────────────────────────────────────────

    @Test
    fun `findDetail — mappingRepo findBySchemeId + IssueTypeLookupPort lookup + workflowRepo findByIds 호출`() {
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
        val schemeKey = WorkflowSchemeKey("software-scheme")
        every { schemeRepo.findByKey(schemeKey) } returns buildScheme(schemeKey, isDefault = false)
        every { mappingRepo.findBySchemeId(any()) } returns listOf(buildMapping(99L))
        justRun { mappingRepo.deleteMapping(any()) }

        service.deleteMapping(actor, schemeKey, mappingId = 99L)

        verify(exactly = 1) { mappingRepo.deleteMapping(99L) }
    }

    @Test
    fun `deleteMapping — 다른 스킴의 매핑 id 는 지우지 않는다`() {
        // ★ 소유가 갈리기 전에는 무해했다 — 모두 SYSTEM_ADMIN 이라 어느 스킴이든 지울 수 있었다.
        // 소유가 갈린 뒤로는 A 스킴 권한만 가진 사람이 `/workflow-schemes/A/mappings/{B의 id}` 로
        // B 스킴의 매핑을 지우는 교차 프로젝트 구멍이 된다(FR-WF-08).
        val schemeKey = WorkflowSchemeKey("software-scheme")
        every { schemeRepo.findByKey(schemeKey) } returns buildScheme(schemeKey, isDefault = false)
        every { mappingRepo.findBySchemeId(any()) } returns listOf(buildMapping(11L))
        justRun { mappingRepo.deleteMapping(any()) }

        service.deleteMapping(actor, schemeKey, mappingId = 99L)

        verify(exactly = 0) { mappingRepo.deleteMapping(any()) }
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
    fun `assignToProject — 다른 프로젝트 전용 스킴은 배정되지 않는다`() {
        val schemeKey = WorkflowSchemeKey("other-project-scheme")
        val myProjectId = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
        val otherProjectId = UUID.fromString("00000000-0000-0000-0000-0000000000b2")
        every { schemeRepo.findByKey(schemeKey) } returns buildScheme(schemeKey, projectId = otherProjectId)

        // 403 이 아니라 404 다 — 403 은 「그 키의 스킴은 있다」를 응답으로 흘린다(존재 probe).
        assertThatThrownBy {
            service.assignToProject(actor, myProjectId, "ATLAS", schemeKey)
        }.isInstanceOf(WorkflowSchemeNotFoundException::class.java)

        verify(exactly = 0) { assignmentRepo.saveAssignment(any()) }
    }

    @Test
    fun `assignToProject — 그 프로젝트 소유 스킴은 그대로 배정된다`() {
        val schemeKey = WorkflowSchemeKey("my-project-scheme")
        val projectId = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
        every { schemeRepo.findByKey(schemeKey) } returns buildScheme(schemeKey, projectId = projectId)
        justRun { assignmentRepo.saveAssignment(any()) }
        justRun { eventPublisher.publish(any<WorkflowSchemeAssignedEvent>()) }

        service.assignToProject(actor, projectId, "ATLAS", schemeKey)

        verify(exactly = 1) { assignmentRepo.saveAssignment(any()) }
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
            // 포트가 actorId: UUID 시그니처이므로 actor.toUuid() 구체값으로 매칭한다.
            denyingResolver.requirePermission(
                actor.toUuid(),
                WorkflowSchemePermission.ASSIGN_SCHEME,
                any<WorkflowScope.Project>(),
            )
        } throws RuntimeException("WORKFLOW_PERMISSION_DENIED")

        val svcWithDeny =
            WorkflowSchemeApplicationService(
                schemeRepo,
                assignmentRepo,
                mappingRepo,
                eventPublisher,
                denyingResolver,
                workflowRepo,
                issueTypeLookupPort,
                scopeResolver,
            )

        assertThatThrownBy {
            svcWithDeny.assignToProject(actor, UUID.randomUUID(), "PROJ", WorkflowSchemeKey("software-scheme"))
        }.hasMessageContaining("WORKFLOW_PERMISSION_DENIED")
    }

    // ── task-13 hot-fix — auto-assign SYSTEM_ACTOR 권한 우회 (prod 판정기 하) ─────────

    @Test
    fun `assignment 없는 프로젝트에 auto-assign 시 SYSTEM_ACTOR 가 권한거부 없이 software-scheme 배정`() {
        // prod 판정기(IdentityAccessWorkflowSchemePermissionResolver) 동형 stub —
        // SYSTEM_ACTOR(nil UUID)는 users 에 없는 합성 sentinel 이라 어떤 프로젝트 멤버도 될 수 없어
        // Project 범위 배정 권한을 항상 거부한다(멤버십 게이트 탈락 → WorkflowSchemeAccessDeniedException).
        val prodLikeResolver = mockk<WorkflowSchemePermissionResolver>()
        every {
            prodLikeResolver.requirePermission(
                WorkflowSchemeApplicationService.SYSTEM_ACTOR_UUID,
                WorkflowSchemePermission.ASSIGN_SCHEME,
                any<WorkflowScope.Project>(),
            )
        } throws
            WorkflowSchemeAccessDeniedException(
                WorkflowSchemeApplicationService.SYSTEM_ACTOR_UUID,
                WorkflowSchemePermission.ASSIGN_SCHEME,
                WorkflowScope.Project("NEW"),
            )

        val svcWithProdResolver =
            WorkflowSchemeApplicationService(
                schemeRepo,
                assignmentRepo,
                mappingRepo,
                eventPublisher,
                prodLikeResolver,
                workflowRepo,
                issueTypeLookupPort,
                scopeResolver,
            )

        val softwareSchemeKey = WorkflowSchemeApplicationService.SOFTWARE_SCHEME_KEY
        val schemeId = WorkflowSchemeId(1L)
        val projectId = UUID.fromString("00000000-0000-0000-0000-000000000099")
        val projectKey = "NEW"
        val scheme = buildScheme(softwareSchemeKey, id = schemeId)

        every { assignmentRepo.findByProjectId(projectId) } returns null
        every { schemeRepo.findByKey(softwareSchemeKey) } returns scheme
        justRun { assignmentRepo.saveAssignment(any()) }
        justRun { eventPublisher.publish(any<WorkflowSchemeAssignedEvent>()) }
        every { schemeRepo.findById(schemeId) } returns scheme

        // GREEN: auto-assign 은 SYSTEM_ACTOR 에 대해 권한 검사를 우회하므로 500 없이 배정에 성공한다.
        // 판별자(mutation): 우회를 제거하면 prod 판정기가 WorkflowSchemeAccessDeniedException 을 던져
        // findAssignedScheme 이 실패한다(현재 프로덕션 버그 재현).
        val result = svcWithProdResolver.findAssignedScheme(projectId, projectKey)

        assertThat(result.key).isEqualTo(softwareSchemeKey)
        verify(exactly = 1) { assignmentRepo.saveAssignment(any()) }
        verify(exactly = 1) { eventPublisher.publish(any<WorkflowSchemeAssignedEvent>()) }
        // 우회 검증 — SYSTEM_ACTOR 경로는 requirePermission 을 절대 호출하지 않는다.
        verify(exactly = 0) {
            prodLikeResolver.requirePermission(
                WorkflowSchemeApplicationService.SYSTEM_ACTOR_UUID,
                any(),
                any(),
            )
        }
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

    /** 소속 확인용 매핑 픽스처. `deleteMapping` 이 「그 스킴 것인가」를 묻기 시작해서 필요해졌다. */
    private fun buildMapping(id: Long): SchemeIssueTypeMapping =
        SchemeIssueTypeMapping(
            id = id,
            schemeId = WorkflowSchemeId(1L),
            issueTypeId = null,
            workflowId = UUID.randomUUID(),
            createdAt = Instant.now(),
        )

    private fun buildScheme(
        key: WorkflowSchemeKey,
        name: String = key.value,
        isDefault: Boolean = false,
        id: WorkflowSchemeId = WorkflowSchemeId(1L),
        projectId: UUID? = null,
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
            projectId = projectId,
        )
    }
}
