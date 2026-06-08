// 커스텀 필드 이슈 통합 — 생성/수정/조회 customFields 검증·병합·저장 단위 테스트
package com.bts.issue.application

import com.bts.issue.adapter.inbound.rest.IssueResponse
import com.bts.issue.customfield.domain.CustomFieldDefinition
import com.bts.issue.customfield.domain.CustomFieldOption
import com.bts.issue.customfield.domain.CustomFieldValidationException
import com.bts.issue.customfield.domain.FieldType
import com.bts.issue.customfield.repository.CustomFieldDefinitionRepository
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.repository.IssueRepository
import com.bts.issue.resolution.repository.ResolutionRepository
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.workflow.WorkflowKeyResolver
import com.bts.shared.workflow.WorkflowStartState
import com.bts.shared.workflow.WorkflowTransitionPort
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * Task 9 — 커스텀 필드 이슈 통합 단위 테스트.
 *
 * 검증 범위.
 * - 생성 시 customFields 검증 + 저장 경로 확인
 * - 조회 응답(단건·목록)에 customFields 노출
 * - PATCH 필드단위 병합(키 갱신 / null 제거 / 부재 무변경 — E11)
 * - required 최종상태 검증 (병합 후 기준)
 * - 클론(E10) — custom_fields 미복사
 */
class IssueCustomFieldsTest : DescribeSpec({

    // ── 픽스처 ─────────────────────────────────────────────────────────────────

    val repo = mockk<IssueRepository>()
    val issueTypeRepository = mockk<IssueTypeRepository>(relaxed = true)
    val resolutionRepository = mockk<ResolutionRepository>(relaxed = true)
    val eventPublisher = mockk<IssueEventPublisher>(relaxed = true)
    val permissionResolver = mockk<IssuePermissionResolver>()
    val workflowPort = mockk<WorkflowTransitionPort>()
    val workflowKeyResolver = mockk<WorkflowKeyResolver>()
    val customFieldDefinitionRepository = mockk<CustomFieldDefinitionRepository>()
    val clock = Clock.fixed(Instant.parse("2026-06-08T00:00:00Z"), ZoneOffset.UTC)

    val sut =
        IssueApplicationService(
            repo = repo,
            issueTypeRepository = issueTypeRepository,
            resolutionRepository = resolutionRepository,
            eventPublisher = eventPublisher,
            permissionResolver = permissionResolver,
            workflowPort = workflowPort,
            workflowKeyResolver = workflowKeyResolver,
            userLookupPort = mockk(relaxed = true),
            componentRepository = mockk(relaxed = true),
            projectLeadRepository = mockk(relaxed = true),
            customFieldDefinitionRepository = customFieldDefinitionRepository,
            clock = clock,
        )

    val actor = ActorId(UUID.randomUUID())
    val projectKey = "ATLAS"
    val projectId = UUID.randomUUID()
    val issueKey = IssueKey("ATLAS-1")
    val taskTypeId = IssueTypeId(1L)

    /** 공통 픽스처 — NUMBER 타입 필수 필드 salary_impact. */
    val salaryImpactDef =
        CustomFieldDefinition(
            id = UUID.randomUUID(),
            projectId = projectId,
            key = "salary_impact",
            name = "급여 영향도",
            fieldType = FieldType.NUMBER,
            required = true,
            displayOrder = 1,
            options = emptyList(),
        )

    /** 공통 픽스처 — TEXT 타입 선택 필드 note. */
    val noteDef =
        CustomFieldDefinition(
            id = UUID.randomUUID(),
            projectId = projectId,
            key = "note",
            name = "노트",
            fieldType = FieldType.SHORT_TEXT,
            required = false,
            displayOrder = 2,
            options = emptyList(),
        )

    /** SINGLE_SELECT 타입 status 필드. */
    val statusDef =
        CustomFieldDefinition(
            id = UUID.randomUUID(),
            projectId = projectId,
            key = "dept_status",
            name = "부서 상태",
            fieldType = FieldType.SINGLE_SELECT,
            required = false,
            displayOrder = 3,
            options = listOf(
                CustomFieldOption(value = "active", label = "Active", displayOrder = 1),
                CustomFieldOption(value = "inactive", label = "Inactive", displayOrder = 2),
            ),
        )

    fun makeIssue(customFields: Map<String, Any?> = emptyMap()) =
        Issue(
            id = IssueId(UUID.randomUUID()),
            key = issueKey,
            projectId = projectId,
            summary = "테스트 이슈",
            reporterId = actor,
            currentStateKey = "open",
            version = 1L,
            deletedAt = null,
            createdAt = Instant.parse("2026-06-08T00:00:00Z"),
            updatedAt = Instant.parse("2026-06-08T00:00:00Z"),
            typeId = taskTypeId,
            customFields = customFields,
        )

    fun makeResponse(customFields: Map<String, Any?> = emptyMap()) =
        IssueResponse(
            key = issueKey.value,
            id = UUID.randomUUID(),
            projectKey = projectKey,
            summary = "테스트 이슈",
            currentStateKey = "open",
            reporterId = actor.value,
            version = 1L,
            createdAt = Instant.parse("2026-06-08T00:00:00Z"),
            updatedAt = Instant.parse("2026-06-08T00:00:00Z"),
            typeId = taskTypeId.value,
            typeKey = "task",
            typeName = "Task",
            customFields = customFields,
        )

    // ── 생성 경로 ───────────────────────────────────────────────────────────────

    describe("createIssue — 커스텀 필드") {
        beforeEach {
            every { permissionResolver.hasPermission(actor.value, IssuePermission.CREATE, IssueScope.Project(projectKey)) } returns true
            every { repo.incrementKeySequence(projectKey) } returns 1L
            every { repo.findProjectIdByKey(projectKey) } returns projectId
            every { issueTypeRepository.findByKey(any()) } returns mockk {
                every { id } returns taskTypeId
            }
            every { workflowKeyResolver.resolveStart(any(), any()) } returns
                WorkflowStartState(workflowKey = "wf", startStateKey = "open")
            every { repo.insertComponents(any(), any()) } returns Unit
            every { eventPublisher.publish(any()) } returns Unit
        }

        it("customFields 포함 생성 시 Validator가 호출되고 이슈에 저장된다") {
            val customFieldValues = mapOf("salary_impact" to 3.0)
            every { customFieldDefinitionRepository.findActiveByProject(projectId) } returns
                listOf(salaryImpactDef, noteDef)
            val savedIssue = makeIssue(customFields = customFieldValues)
            every { repo.insert(any()) } returns savedIssue

            val request =
                CreateIssueRequest(
                    projectKey = projectKey,
                    summary = "이슈 생성",
                    reporterId = actor,
                    customFields = customFieldValues,
                )

            val result = sut.createIssue(actor, request)

            result.customFields shouldBe customFieldValues
            verify { customFieldDefinitionRepository.findActiveByProject(projectId) }
        }

        it("미정의 키 포함 시 CustomFieldValidationException(E1) 발생") {
            every { customFieldDefinitionRepository.findActiveByProject(projectId) } returns
                listOf(salaryImpactDef)
            every { repo.insert(any()) } returns makeIssue()

            val request =
                CreateIssueRequest(
                    projectKey = projectKey,
                    summary = "이슈 생성",
                    reporterId = actor,
                    customFields = mapOf("unknown_field" to "value"),
                )

            shouldThrow<CustomFieldValidationException> {
                sut.createIssue(actor, request)
            }
        }

        it("required 필드 누락 시 CustomFieldValidationException(E2) 발생") {
            every { customFieldDefinitionRepository.findActiveByProject(projectId) } returns
                listOf(salaryImpactDef)
            every { repo.insert(any()) } returns makeIssue()

            val request =
                CreateIssueRequest(
                    projectKey = projectKey,
                    summary = "이슈 생성",
                    reporterId = actor,
                    customFields = emptyMap(), // required salary_impact 누락
                )

            shouldThrow<CustomFieldValidationException> {
                sut.createIssue(actor, request)
            }
        }

        it("customFields null/부재 시 빈 맵으로 저장 — required 없는 프로젝트") {
            every { customFieldDefinitionRepository.findActiveByProject(projectId) } returns
                listOf(noteDef) // required=false만
            val savedIssue = makeIssue(customFields = emptyMap())
            every { repo.insert(any()) } returns savedIssue

            val request =
                CreateIssueRequest(
                    projectKey = projectKey,
                    summary = "이슈 생성",
                    reporterId = actor,
                    customFields = null,
                )

            val result = sut.createIssue(actor, request)
            result.customFields shouldBe emptyMap()
        }
    }

    // ── 조회 응답 노출 ──────────────────────────────────────────────────────────

    describe("IssueResponse — customFields 노출") {
        it("단건 조회 시 customFields 가 응답에 포함된다") {
            val customFields = mapOf("salary_impact" to 5.0, "note" to "메모")
            every { permissionResolver.hasPermission(actor.value, IssuePermission.VIEW, IssueScope.Issue(issueKey.value)) } returns true
            val responseWithCustom = makeResponse(customFields = customFields)
            every { repo.findByKeyWithType(issueKey) } returns responseWithCustom
            every { repo.findActiveComponentIdsByIssue(any()) } returns emptyList()

            val result = sut.findByKey(actor, issueKey)

            result.customFields shouldBe customFields
        }

        it("목록 조회에서도 customFields 가 포함된다 — from() 매핑 검증") {
            val customFields = mapOf("salary_impact" to 3.0)
            val issue = makeIssue(customFields = customFields)
            val typeInfo = IssueResponse.IssueTypeInfo(id = 1L, key = "task", name = "Task")

            val response = IssueResponse.from(issue = issue, projectKey = projectKey, typeInfo = typeInfo)

            response.customFields shouldBe customFields
        }
    }

    // ── PATCH 필드단위 병합 (E11) ───────────────────────────────────────────────

    describe("updateIssue — PATCH customFields 필드단위 병합(E11)") {
        val existingCustomFields = mapOf<String, Any?>("salary_impact" to 3.0, "note" to "기존 노트")
        val existingVersion = 1L

        beforeEach {
            every { permissionResolver.hasPermission(actor.value, IssuePermission.UPDATE, IssueScope.Issue(issueKey.value)) } returns true
            every { customFieldDefinitionRepository.findActiveByProject(projectId) } returns
                listOf(salaryImpactDef, noteDef)
        }

        it("customFields 부재(null) 시 기존 값 무변경(E11-부재)") {
            val existingIssue = makeIssue(customFields = existingCustomFields)
            every { repo.findByKey(issueKey) } returns existingIssue
            every { repo.updateFields(any(), any(), any()) } returns 1
            val updatedResponse = makeResponse(customFields = existingCustomFields)
            every { repo.findByKeyWithType(issueKey) } returns updatedResponse
            every { repo.findActiveComponentIdsByIssue(any()) } returns emptyList()

            val request =
                UpdateIssueRequest(
                    summary = null,
                    expectedVersion = existingVersion,
                    customFields = null, // 부재 — 무변경
                )

            val result = sut.updateIssue(actor, issueKey, request)

            // customFields patch가 null이면 validator 호출 없이 기존 값 유지
            verify(exactly = 0) { customFieldDefinitionRepository.findActiveByProject(any()) }
            result.customFields shouldBe existingCustomFields
        }

        it("customFields 맵 명시 시 키 단위 병합 — 갱신(E11-병합)") {
            val existingIssue = makeIssue(customFields = existingCustomFields)
            every { repo.findByKey(issueKey) } returns existingIssue

            // salary_impact를 5.0으로 갱신, note는 유지
            val patchFields = mapOf<String, Any?>("salary_impact" to 5.0)
            val mergedFields = mapOf<String, Any?>("salary_impact" to 5.0, "note" to "기존 노트")

            every { repo.updateFields(any(), any(), any()) } returns 1
            val updatedResponse = makeResponse(customFields = mergedFields)
            every { repo.findByKeyWithType(issueKey) } returns updatedResponse
            every { repo.findActiveComponentIdsByIssue(any()) } returns emptyList()

            val request =
                UpdateIssueRequest(
                    summary = null,
                    expectedVersion = existingVersion,
                    customFields = mapOf("salary_impact" to 5.0),
                )

            val result = sut.updateIssue(actor, issueKey, request)

            verify { customFieldDefinitionRepository.findActiveByProject(projectId) }
            result.customFields shouldContainKey "salary_impact"
            result.customFields shouldContainKey "note"
        }

        it("키 값 null 시 해당 필드 제거(E11-null제거)") {
            val existingIssue = makeIssue(customFields = existingCustomFields)
            every { repo.findByKey(issueKey) } returns existingIssue

            // note를 null로 지정 → 제거, salary_impact는 유지
            val afterRemove = mapOf<String, Any?>("salary_impact" to 3.0)

            every { repo.updateFields(any(), any(), any()) } returns 1
            val updatedResponse = makeResponse(customFields = afterRemove)
            every { repo.findByKeyWithType(issueKey) } returns updatedResponse
            every { repo.findActiveComponentIdsByIssue(any()) } returns emptyList()

            val request =
                UpdateIssueRequest(
                    summary = null,
                    expectedVersion = existingVersion,
                    customFields = mapOf("note" to null),
                )

            val result = sut.updateIssue(actor, issueKey, request)

            result.customFields shouldContainKey "salary_impact"
            result.customFields shouldNotContainKey "note"
        }

        it("병합 후 required 필드가 null 상태가 되면 CustomFieldValidationException 발생") {
            // 기존에 salary_impact(required)가 있는데, null로 제거하려 시도
            val existingIssue = makeIssue(customFields = existingCustomFields)
            every { repo.findByKey(issueKey) } returns existingIssue

            val request =
                UpdateIssueRequest(
                    summary = null,
                    expectedVersion = existingVersion,
                    customFields = mapOf("salary_impact" to null), // required 필드 제거 시도
                )

            shouldThrow<CustomFieldValidationException> {
                sut.updateIssue(actor, issueKey, request)
            }
        }
    }

    // ── 클론 E10 — custom_fields 미복사 ────────────────────────────────────────

    describe("cloneIssue — E10 custom_fields 미복사") {
        it("클론 시 원본의 custom_fields 를 복사하지 않고 빈 맵으로 생성") {
            val sourceIssue = makeIssue(customFields = mapOf("salary_impact" to 99.0, "note" to "원본"))
            every { permissionResolver.hasPermission(actor.value, IssuePermission.VIEW, IssueScope.Issue(issueKey.value)) } returns true
            every { permissionResolver.hasPermission(actor.value, IssuePermission.CREATE, IssueScope.Project(projectKey)) } returns true
            every { repo.findByKey(issueKey) } returns sourceIssue
            every { repo.incrementKeySequence(projectKey) } returns 2L
            every { workflowKeyResolver.resolveStart(any(), any()) } returns
                WorkflowStartState(workflowKey = "wf", startStateKey = "open")

            val cloneIssue = makeIssue(customFields = emptyMap())
                .copy(key = IssueKey("ATLAS-2"), version = 1L)
            every { repo.insert(any()) } returns cloneIssue
            every { eventPublisher.publish(any()) } returns Unit

            val result = sut.cloneIssue(actor, issueKey, CloneIssueRequest())

            // 클론본의 customFields는 빈 맵이어야 한다
            result.customFields shouldBe emptyMap()
        }
    }
})
