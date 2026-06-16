// MovePreviewService 단위 테스트 — 이슈 이동 preview 비호환 항목 계산 검증 (FR-MV-01)

package com.bts.issue.application

import com.bts.issue.component.domain.Component
import com.bts.issue.component.repository.ComponentRepository
import com.bts.issue.customfield.domain.CustomFieldDefinition
import com.bts.issue.customfield.domain.FieldType
import com.bts.issue.customfield.repository.CustomFieldDefinitionRepository
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueNotFoundException
import com.bts.issue.domain.IssueProjectNotFoundException
import com.bts.issue.repository.IssueRepository
import com.bts.issue.version.domain.Version
import com.bts.issue.version.domain.VersionStatus
import com.bts.issue.version.repository.VersionRepository
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.issue.IssueTypeKey
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.workflow.ProjectKey
import com.bts.shared.workflow.WorkflowStateCatalog
import com.bts.shared.workflow.WorkflowStateView
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.util.UUID

/**
 * MovePreviewService 단위 테스트 (FR-MV-01).
 *
 * 검증 대상.
 * - 워크플로우 상태 호환/비호환 계산
 * - 컴포넌트 이름 일치 자동매핑
 * - 버전 이름 일치 자동매핑
 * - 커스텀필드 removed / requiredMissing 계산
 * - 원본 프로젝트 UPDATE 권한 게이트 (403)
 * - 대상 프로젝트 CREATE 권한 게이트 (403)
 */
@Suppress("LongMethod")
class MovePreviewServiceTest : DescribeSpec({

    val permissionResolver = mockk<IssuePermissionResolver>(relaxed = true)
    val issueRepository = mockk<IssueRepository>(relaxed = true)
    val componentRepository = mockk<ComponentRepository>(relaxed = true)
    val versionRepository = mockk<VersionRepository>(relaxed = true)
    val customFieldDefinitionRepository = mockk<CustomFieldDefinitionRepository>(relaxed = true)
    val workflowStateCatalog = mockk<WorkflowStateCatalog>(relaxed = true)

    val actor = ActorId(UUID.randomUUID())
    val sourceProjectKey = "SRC"
    val targetProjectKey = "TGT"
    val sourceProjectId = UUID.randomUUID()
    val targetProjectId = UUID.randomUUID()
    val issueKey = IssueKey.of(sourceProjectKey, 1)

    /** IssueTypeId를 실제 값으로 생성한다 (value class — Long 기반). */
    val issueTypeId = IssueTypeId(1L)

    /** 최소 유효 Issue 픽스처를 생성한다. */
    fun makeIssue(
        currentStateKey: String,
        componentIds: List<UUID> = emptyList(),
        affectsVersionIds: List<UUID> = emptyList(),
        fixVersionIds: List<UUID> = emptyList(),
        customFields: Map<String, Any?> = emptyMap(),
    ) = com.bts.issue.domain.Issue(
        id = IssueId(UUID.randomUUID()),
        key = issueKey,
        projectId = sourceProjectId,
        summary = "Test Issue",
        reporterId = actor,
        currentStateKey = currentStateKey,
        version = 1L,
        deletedAt = null,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
        typeId = issueTypeId,
        componentIds = componentIds,
        affectsVersionIds = affectsVersionIds,
        fixVersionIds = fixVersionIds,
        customFields = customFields,
    )

    fun makeComponent(
        id: UUID = UUID.randomUUID(),
        projectId: UUID,
        name: String,
    ) = Component(
        id = id,
        projectId = projectId,
        name = name,
        description = null,
        leadUserId = null,
        deletedAt = null,
    )

    fun makeVersion(
        id: UUID = UUID.randomUUID(),
        projectId: UUID,
        name: String,
    ) = Version(
        id = id,
        projectId = projectId,
        name = name,
        description = null,
        startDate = null,
        releaseDate = null,
        status = VersionStatus.UNRELEASED,
        releasedAt = null,
        deletedAt = null,
    )

    fun makeFieldDef(
        id: UUID = UUID.randomUUID(),
        projectId: UUID,
        key: String,
        name: String,
        required: Boolean,
    ) = CustomFieldDefinition(
        id = id,
        projectId = projectId,
        key = key,
        name = name,
        description = null,
        fieldType = FieldType.SHORT_TEXT,
        required = required,
        displayOrder = 0,
        options = emptyList(),
    )

    val sut =
        MovePreviewService(
            permissionResolver = permissionResolver,
            issueRepository = issueRepository,
            componentRepository = componentRepository,
            versionRepository = versionRepository,
            customFieldDefinitionRepository = customFieldDefinitionRepository,
            workflowStateCatalog = workflowStateCatalog,
        )

    // ── 공통 setup (각 describe 블록에서 필요에 따라 override) ─────────────────

    beforeEach {
        // 기본 권한: 모두 허용
        every {
            permissionResolver.hasPermission(actor.value, IssuePermission.UPDATE, IssueScope.Project(sourceProjectKey))
        } returns true
        every {
            permissionResolver.hasPermission(actor.value, IssuePermission.CREATE, IssueScope.Project(targetProjectKey))
        } returns true

        // 기본 프로젝트 ID 조회
        every { issueRepository.findProjectIdByKey(sourceProjectKey) } returns sourceProjectId
        every { issueRepository.findProjectIdByKey(targetProjectKey) } returns targetProjectId

        // 기본 이슈 조회
        every { issueRepository.findByKey(issueKey) } returns makeIssue(currentStateKey = "open")

        // 기본 워크플로우 상태 목록
        every {
            workflowStateCatalog.listStates(ProjectKey.of(targetProjectKey), null)
        } returns
            listOf(
                WorkflowStateView("open", "열림"),
                WorkflowStateView("in-progress", "진행 중"),
                WorkflowStateView("closed", "완료"),
            )

        // 기본: 빈 컴포넌트/버전/커스텀필드
        every { componentRepository.findByProject(sourceProjectId) } returns emptyList()
        every { componentRepository.findByProject(targetProjectId) } returns emptyList()
        every { versionRepository.findByProject(sourceProjectId) } returns emptyList()
        every { versionRepository.findByProject(targetProjectId) } returns emptyList()
        every { customFieldDefinitionRepository.findActiveByProject(sourceProjectId) } returns emptyList()
        every { customFieldDefinitionRepository.findActiveByProject(targetProjectId) } returns emptyList()
    }

    // ── 권한 게이트 ──────────────────────────────────────────────────────────────

    describe("권한 게이트") {

        it("원본 프로젝트 UPDATE 권한 없으면 IssueAccessDeniedException") {
            every {
                permissionResolver.hasPermission(
                    actor.value,
                    IssuePermission.UPDATE,
                    IssueScope.Project(sourceProjectKey),
                )
            } returns false

            shouldThrow<IssueAccessDeniedException> {
                sut.preview(actor, issueKey, targetProjectKey)
            }
        }

        it("대상 프로젝트 CREATE 권한 없으면 IssueAccessDeniedException") {
            every {
                permissionResolver.hasPermission(
                    actor.value,
                    IssuePermission.CREATE,
                    IssueScope.Project(targetProjectKey),
                )
            } returns false

            shouldThrow<IssueAccessDeniedException> {
                sut.preview(actor, issueKey, targetProjectKey)
            }
        }

        it("이슈가 없으면 IssueNotFoundException") {
            every { issueRepository.findByKey(issueKey) } returns null

            shouldThrow<IssueNotFoundException> {
                sut.preview(actor, issueKey, targetProjectKey)
            }
        }

        it("대상 프로젝트가 없으면 IssueProjectNotFoundException") {
            every { issueRepository.findProjectIdByKey(targetProjectKey) } returns null

            shouldThrow<IssueProjectNotFoundException> {
                sut.preview(actor, issueKey, targetProjectKey)
            }
        }
    }

    // ── 워크플로우 상태 계산 ──────────────────────────────────────────────────

    describe("워크플로우 상태 계산") {

        it("현재 상태가 대상 워크플로우에 있으면 compatible=true, suggestedStateKey=현재 상태") {
            every { issueRepository.findByKey(issueKey) } returns makeIssue(currentStateKey = "open")

            val result = sut.preview(actor, issueKey, targetProjectKey)

            result.workflow.compatible shouldBe true
            result.workflow.suggestedStateKey shouldBe "open"
            result.workflow.targetStates.map { state -> state.key } shouldBe listOf("open", "in-progress", "closed")
        }

        it("현재 상태가 대상 워크플로우에 없으면 compatible=false, suggestedStateKey=첫 번째 상태") {
            every { issueRepository.findByKey(issueKey) } returns makeIssue(currentStateKey = "resolved")

            val result = sut.preview(actor, issueKey, targetProjectKey)

            result.workflow.compatible shouldBe false
            result.workflow.suggestedStateKey shouldBe "open"
        }

        it("대상 워크플로우 상태 목록이 비어있으면 compatible=false, suggestedStateKey=null") {
            every { issueRepository.findByKey(issueKey) } returns makeIssue(currentStateKey = "open")
            every {
                workflowStateCatalog.listStates(ProjectKey.of(targetProjectKey), null)
            } returns emptyList()

            val result = sut.preview(actor, issueKey, targetProjectKey)

            result.workflow.compatible shouldBe false
            result.workflow.suggestedStateKey shouldBe null
        }
    }

    // ── 컴포넌트 자동매핑 ──────────────────────────────────────────────────────

    describe("컴포넌트 자동매핑") {

        it("이름이 일치하는 대상 컴포넌트가 있으면 autoMapping에 대상 id 채움") {
            val srcCompId = UUID.randomUUID()
            val tgtCompId = UUID.randomUUID()
            val srcComp = makeComponent(id = srcCompId, projectId = sourceProjectId, name = "Backend")
            val tgtComp = makeComponent(id = tgtCompId, projectId = targetProjectId, name = "Backend")

            every { issueRepository.findByKey(issueKey) } returns
                makeIssue(currentStateKey = "open", componentIds = listOf(srcCompId))
            every { componentRepository.findByProject(sourceProjectId) } returns listOf(srcComp)
            every { componentRepository.findByProject(targetProjectId) } returns listOf(tgtComp)

            val result = sut.preview(actor, issueKey, targetProjectKey)

            result.components.current shouldBe listOf(srcComp)
            result.components.target shouldBe listOf(tgtComp)
            result.components.autoMapping[srcCompId] shouldBe tgtCompId
        }

        it("이름이 일치하는 대상 컴포넌트가 없으면 autoMapping 값이 null") {
            val srcCompId = UUID.randomUUID()
            val srcComp = makeComponent(id = srcCompId, projectId = sourceProjectId, name = "Frontend")
            val tgtComp = makeComponent(id = UUID.randomUUID(), projectId = targetProjectId, name = "Backend")

            every { issueRepository.findByKey(issueKey) } returns
                makeIssue(currentStateKey = "open", componentIds = listOf(srcCompId))
            every { componentRepository.findByProject(sourceProjectId) } returns listOf(srcComp)
            every { componentRepository.findByProject(targetProjectId) } returns listOf(tgtComp)

            val result = sut.preview(actor, issueKey, targetProjectKey)

            result.components.autoMapping[srcCompId] shouldBe null
        }

        it("이슈에 컴포넌트가 없으면 current 빈 목록, autoMapping 빈 맵") {
            val result = sut.preview(actor, issueKey, targetProjectKey)

            result.components.current shouldBe emptyList()
            result.components.autoMapping shouldBe emptyMap()
        }
    }

    // ── 버전 자동매핑 ─────────────────────────────────────────────────────────

    describe("버전 자동매핑") {

        it("affectsVersions — 이름 일치 시 autoMapping에 대상 id 채움") {
            val srcVerId = UUID.randomUUID()
            val tgtVerId = UUID.randomUUID()
            val srcVer = makeVersion(id = srcVerId, projectId = sourceProjectId, name = "v1.0")
            val tgtVer = makeVersion(id = tgtVerId, projectId = targetProjectId, name = "v1.0")

            every { issueRepository.findByKey(issueKey) } returns
                makeIssue(currentStateKey = "open", affectsVersionIds = listOf(srcVerId))
            every { versionRepository.findByProject(sourceProjectId) } returns listOf(srcVer)
            every { versionRepository.findByProject(targetProjectId) } returns listOf(tgtVer)

            val result = sut.preview(actor, issueKey, targetProjectKey)

            result.affectsVersions.autoMapping[srcVerId] shouldBe tgtVerId
        }

        it("fixVersions — 이름 불일치 시 autoMapping 값이 null") {
            val srcVerId = UUID.randomUUID()
            val srcVer = makeVersion(id = srcVerId, projectId = sourceProjectId, name = "v1.0")
            val tgtVer = makeVersion(id = UUID.randomUUID(), projectId = targetProjectId, name = "v2.0")

            every { issueRepository.findByKey(issueKey) } returns
                makeIssue(currentStateKey = "open", fixVersionIds = listOf(srcVerId))
            every { versionRepository.findByProject(sourceProjectId) } returns listOf(srcVer)
            every { versionRepository.findByProject(targetProjectId) } returns listOf(tgtVer)

            val result = sut.preview(actor, issueKey, targetProjectKey)

            result.fixVersions.autoMapping[srcVerId] shouldBe null
        }
    }

    // ── 커스텀필드 removed / requiredMissing ─────────────────────────────────

    describe("커스텀필드 계산") {

        it("대상 프로젝트에 정의되지 않은 필드는 removed에 포함") {
            val srcFieldId = UUID.randomUUID()
            val srcField =
                makeFieldDef(
                    id = srcFieldId,
                    projectId = sourceProjectId,
                    key = "src-only",
                    name = "Source Only Field",
                    required = false,
                )

            every { issueRepository.findByKey(issueKey) } returns
                makeIssue(currentStateKey = "open", customFields = mapOf("src-only" to "value"))
            every { customFieldDefinitionRepository.findActiveByProject(sourceProjectId) } returns listOf(srcField)
            // 대상 프로젝트에는 해당 필드 없음

            val result = sut.preview(actor, issueKey, targetProjectKey)

            result.customFields.removed.map { def -> def.key } shouldBe listOf("src-only")
        }

        it("대상 프로젝트 필수 필드에 현재 값이 없으면 requiredMissing에 포함") {
            val tgtFieldId = UUID.randomUUID()
            val tgtField =
                makeFieldDef(
                    id = tgtFieldId,
                    projectId = targetProjectId,
                    key = "required-field",
                    name = "필수 필드",
                    required = true,
                )

            // 현재 이슈에 해당 필드 값 없음
            every { issueRepository.findByKey(issueKey) } returns makeIssue(currentStateKey = "open")
            every { customFieldDefinitionRepository.findActiveByProject(targetProjectId) } returns listOf(tgtField)

            val result = sut.preview(actor, issueKey, targetProjectKey)

            result.customFields.requiredMissing.map { def -> def.key } shouldBe listOf("required-field")
        }

        it("대상 프로젝트 필수 필드에 현재 값이 있으면 requiredMissing에 포함 안 됨") {
            val tgtFieldId = UUID.randomUUID()
            val tgtField =
                makeFieldDef(
                    id = tgtFieldId,
                    projectId = targetProjectId,
                    key = "required-field",
                    name = "필수 필드",
                    required = true,
                )

            every { issueRepository.findByKey(issueKey) } returns
                makeIssue(currentStateKey = "open", customFields = mapOf("required-field" to "some-value"))
            every { customFieldDefinitionRepository.findActiveByProject(targetProjectId) } returns listOf(tgtField)

            val result = sut.preview(actor, issueKey, targetProjectKey)

            result.customFields.requiredMissing shouldBe emptyList()
        }

        it("비필수 필드가 양쪽에 모두 있으면 removed/requiredMissing 모두 비어있음") {
            val srcField =
                makeFieldDef(
                    projectId = sourceProjectId,
                    key = "shared-field",
                    name = "공유 필드",
                    required = false,
                )
            val tgtField =
                makeFieldDef(
                    projectId = targetProjectId,
                    key = "shared-field",
                    name = "공유 필드",
                    required = false,
                )

            every { issueRepository.findByKey(issueKey) } returns
                makeIssue(currentStateKey = "open", customFields = mapOf("shared-field" to "val"))
            every { customFieldDefinitionRepository.findActiveByProject(sourceProjectId) } returns listOf(srcField)
            every { customFieldDefinitionRepository.findActiveByProject(targetProjectId) } returns listOf(tgtField)

            val result = sut.preview(actor, issueKey, targetProjectKey)

            result.customFields.removed shouldBe emptyList()
            result.customFields.requiredMissing shouldBe emptyList()
        }
    }

    // ── 복합 시나리오 ─────────────────────────────────────────────────────────

    describe("복합 시나리오") {

        it("호환 상태 + 컴포넌트 이름 일치 + 필수 필드 충족 시 모두 호환") {
            val srcCompId = UUID.randomUUID()
            val tgtCompId = UUID.randomUUID()
            val srcComp = makeComponent(id = srcCompId, projectId = sourceProjectId, name = "Core")
            val tgtComp = makeComponent(id = tgtCompId, projectId = targetProjectId, name = "Core")
            val tgtField =
                makeFieldDef(
                    projectId = targetProjectId,
                    key = "priority-field",
                    name = "우선순위",
                    required = true,
                )

            every { issueRepository.findByKey(issueKey) } returns
                makeIssue(
                    currentStateKey = "open",
                    componentIds = listOf(srcCompId),
                    customFields = mapOf("priority-field" to "HIGH"),
                )
            every { componentRepository.findByProject(sourceProjectId) } returns listOf(srcComp)
            every { componentRepository.findByProject(targetProjectId) } returns listOf(tgtComp)
            every { customFieldDefinitionRepository.findActiveByProject(targetProjectId) } returns listOf(tgtField)

            val result = sut.preview(actor, issueKey, targetProjectKey)

            result.workflow.compatible shouldBe true
            result.components.autoMapping[srcCompId] shouldBe tgtCompId
            result.customFields.requiredMissing shouldBe emptyList()
        }

        it("preview 반환값 자체가 null이 아님") {
            val result = sut.preview(actor, issueKey, targetProjectKey)
            result shouldNotBe null
        }
    }

    // ── 서브태스크 동반 preview — subtasks 섹션 ─────────────────────────────────

    describe("서브태스크 동반 preview") {

        val childKey = IssueKey.of(sourceProjectKey, 2)
        val subtaskTypeId = IssueTypeId(2L)

        it("자식 없으면 subtasks 빈 배열") {
            every { issueRepository.findDirectChildren(any()) } returns emptyList()

            val result = sut.preview(actor, issueKey, targetProjectKey)

            result.subtasks shouldBe emptyList()
        }

        it("자식 있는 이슈 preview는 노드별 subtasks 섹션 반환") {
            val childIssueId = IssueId(UUID.randomUUID())
            val childIssue =
                makeIssue(currentStateKey = "open").copy(
                    id = childIssueId,
                    key = childKey,
                    typeId = subtaskTypeId,
                )
            // findDirectChildren 가 자식 반환
            every { issueRepository.findDirectChildren(any()) } returns listOf(childIssue)

            // C2: 자식 issueTypeKey 조회용 findByKeyWithType stub
            val childIssueResponse =
                com.bts.issue.adapter.inbound.rest.IssueResponse.from(
                    issue = childIssue,
                    projectKey = sourceProjectKey,
                    typeInfo =
                        com.bts.issue.adapter.inbound.rest.IssueResponse.IssueTypeInfo(
                            id = subtaskTypeId.value,
                            key = "subtask",
                            name = "Subtask",
                        ),
                    parent = null,
                )
            every { issueRepository.findByKeyWithType(childKey) } returns childIssueResponse

            // 자식 issueTypeKey="subtask" 로 listStates 호출
            every {
                workflowStateCatalog.listStates(ProjectKey.of(targetProjectKey), IssueTypeKey("subtask"))
            } returns listOf(WorkflowStateView("open", "열림"))

            val result = sut.preview(actor, issueKey, targetProjectKey)

            result.subtasks.size shouldBe 1
            result.subtasks[0].issueKey shouldBe childKey.value
            result.subtasks[0].issueTypeKey shouldBe "subtask"
        }

        it("자식 워크플로우는 자식 issueTypeKey로 조회") {
            val childIssueId = IssueId(UUID.randomUUID())
            val childIssue =
                makeIssue(currentStateKey = "open").copy(
                    id = childIssueId,
                    key = childKey,
                    typeId = subtaskTypeId,
                )
            every { issueRepository.findDirectChildren(any()) } returns listOf(childIssue)

            val childIssueResponse =
                com.bts.issue.adapter.inbound.rest.IssueResponse.from(
                    issue = childIssue,
                    projectKey = sourceProjectKey,
                    typeInfo =
                        com.bts.issue.adapter.inbound.rest.IssueResponse.IssueTypeInfo(
                            id = subtaskTypeId.value,
                            key = "subtask",
                            name = "Subtask",
                        ),
                    parent = null,
                )
            every { issueRepository.findByKeyWithType(childKey) } returns childIssueResponse

            // 자식 타입으로 목록 조회 호출 확인
            every {
                workflowStateCatalog.listStates(ProjectKey.of(targetProjectKey), IssueTypeKey("subtask"))
            } returns listOf(WorkflowStateView("subtask-open", "서브태스크 열림"))

            val result = sut.preview(actor, issueKey, targetProjectKey)

            result.subtasks[0].workflow.targetStates.map { it.key } shouldBe listOf("subtask-open")
        }
    }
})
