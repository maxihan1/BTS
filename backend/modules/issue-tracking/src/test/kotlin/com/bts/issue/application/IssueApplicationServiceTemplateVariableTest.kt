// 이슈 생성 시 템플릿 변수(author/date/project) 치환 단위 테스트 — FR-TM-02 Task 2 TDD RED

package com.bts.issue.application

import com.bts.issue.domain.ActorId
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.repository.IssueRepository
import com.bts.issue.template.repository.IssueTemplateRepository
import com.bts.issue.type.domain.IssueType
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.issue.IssueTypeKey
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.user.UserLookupPort
import com.bts.shared.workflow.ProjectKey
import com.bts.shared.workflow.WorkflowKeyResolver
import com.bts.shared.workflow.WorkflowStartState
import com.bts.shared.workflow.WorkflowTransitionPort
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * FR-TM-02 Task 2 — 이슈 생성 시 템플릿 변수 치환 단위 테스트.
 *
 * 검증 케이스 (4개).
 * (1) 3종 변수 모두 포함된 템플릿 → 모두 치환
 * (3) 미정의 토큰 포함 → 정의된 것만 치환, 미정의 토큰은 리터럴 유지
 * (4) userLookupPort.findDisplayNamesByIds 빈 맵 반환 → author 토큰 리터럴 유지, 이슈 생성 성공
 * (최적화) author 토큰 미포함 템플릿 → userLookupPort.findDisplayNamesByIds 미호출
 */
class IssueApplicationServiceTemplateVariableTest : DescribeSpec({

    val repo = mockk<IssueRepository>()
    val issueTypeRepository = mockk<IssueTypeRepository>()
    val resolutionRepository = mockk<com.bts.issue.resolution.repository.ResolutionRepository>(relaxed = true)
    val eventPublisher = mockk<IssueEventPublisher>()
    val permissionResolver = mockk<IssuePermissionResolver>()
    val workflowPort = mockk<WorkflowTransitionPort>()
    val workflowKeyResolver = mockk<WorkflowKeyResolver>()
    val userLookupPort = mockk<UserLookupPort>()
    val templateRepo = mockk<IssueTemplateRepository>()
    // 2026-06-12T00:00:00Z 고정 → LocalDate.now(clock) = 2026-06-12
    val clock = Clock.fixed(Instant.parse("2026-06-12T00:00:00Z"), ZoneOffset.UTC)

    val sut =
        IssueApplicationService(
            repo = repo,
            issueTypeRepository = issueTypeRepository,
            resolutionRepository = resolutionRepository,
            eventPublisher = eventPublisher,
            permissionResolver = permissionResolver,
            workflowPort = workflowPort,
            workflowKeyResolver = workflowKeyResolver,
            userLookupPort = userLookupPort,
            componentRepository = mockk(relaxed = true),
            projectLeadRepository = mockk(relaxed = true),
            versionRepository = mockk(relaxed = true),
            clock = clock,
            historyRecorder = mockk(relaxed = true),
            issueTemplateRepository = templateRepo,
        )

    val actor = ActorId(UUID.randomUUID())
    val projectKey = "BTS"
    val projectId = UUID.randomUUID()
    val typeId = IssueTypeId(3L)

    val taskIssueType =
        IssueType(
            id = typeId,
            key = IssueTypeKey("task"),
            name = "Task",
            description = null,
            iconName = null,
            isStandard = true,
            hierarchyLevel = 0,
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
            deletedAt = null,
        )

    /** 공통 인프라 stub — 케이스 간 templateRepo/userLookupPort call-count 누적 방지를 위해 clearMocks 선행. */
    fun stubCommonInfra() {
        clearMocks(templateRepo, userLookupPort)
        every {
            permissionResolver.hasPermission(actor.value, IssuePermission.CREATE, IssueScope.Project(projectKey))
        } returns true
        every { repo.incrementKeySequence(projectKey) } returns 1L
        every { repo.findProjectIdByKey(projectKey) } returns projectId
        every { issueTypeRepository.findByKey(IssueTypeKey("task")) } returns taskIssueType
        every {
            workflowKeyResolver.resolveStart(ProjectKey.of(projectKey), null)
        } returns WorkflowStartState(workflowKey = "default", startStateKey = "open")
        every { eventPublisher.publish(any()) } returns Unit
        every { repo.insertComponents(any(), any()) } returns Unit
    }

    /** repo.insert 가 전달받은 Issue 를 그대로 반환하도록 stub. */
    fun stubInsert() {
        val slot = slot<com.bts.issue.domain.Issue>()
        every { repo.insert(capture(slot)) } answers { slot.captured }
    }

    describe("(1) 3종 변수 모두 포함 → 모두 치환") {
        val templateContent = "보고자: {{author}}\n생성일: {{date}}\n프로젝트: {{project}}"

        beforeEach {
            stubCommonInfra()
            stubInsert()
            every {
                templateRepo.findActiveContentByProjectAndType(projectId, typeId.value)
            } returns templateContent
            every {
                userLookupPort.findDisplayNamesByIds(setOf(actor.value))
            } returns mapOf(actor.value to "김앨리스")
        }

        it("description null → 치환된 description 이 주입되어야 한다") {
            val request =
                CreateIssueRequest(
                    projectKey = projectKey,
                    summary = "변수 치환 이슈",
                    reporterId = actor,
                    description = null,
                )
            val result = sut.createIssue(actor, request)
            result.description shouldBe "보고자: 김앨리스\n생성일: 2026-06-12\n프로젝트: BTS"
        }
    }

    describe("(3) 미정의 토큰 포함 → 정의된 것만 치환, 미정의 토큰 리터럴 유지") {
        val templateContent = "{{author}} {{foo}}"

        beforeEach {
            stubCommonInfra()
            stubInsert()
            every {
                templateRepo.findActiveContentByProjectAndType(projectId, typeId.value)
            } returns templateContent
            every {
                userLookupPort.findDisplayNamesByIds(setOf(actor.value))
            } returns mapOf(actor.value to "김앨리스")
        }

        it("author 는 치환되고 foo 는 리터럴로 남아야 한다") {
            val request =
                CreateIssueRequest(
                    projectKey = projectKey,
                    summary = "미정의 토큰 이슈",
                    reporterId = actor,
                    description = null,
                )
            val result = sut.createIssue(actor, request)
            result.description shouldBe "김앨리스 {{foo}}"
        }
    }

    describe("(4) userLookupPort 빈 맵 반환 → author 리터럴 유지, 이슈 생성 성공") {
        val templateContent = "{{author}} 님이 작성"

        beforeEach {
            stubCommonInfra()
            stubInsert()
            every {
                templateRepo.findActiveContentByProjectAndType(projectId, typeId.value)
            } returns templateContent
            every {
                userLookupPort.findDisplayNamesByIds(setOf(actor.value))
            } returns emptyMap()
        }

        it("author 를 조회했으나 결과 없음 → author 토큰은 리터럴로 남아야 한다") {
            val request =
                CreateIssueRequest(
                    projectKey = projectKey,
                    summary = "display_name 미조회 이슈",
                    reporterId = actor,
                    description = null,
                )
            val result = sut.createIssue(actor, request)
            result.description shouldBe "{{author}} 님이 작성"
        }
    }

    describe("(최적화) author 토큰 미포함 → userLookupPort.findDisplayNamesByIds 미호출") {
        val templateContent = "## 재현"

        beforeEach {
            stubCommonInfra()
            stubInsert()
            every {
                templateRepo.findActiveContentByProjectAndType(projectId, typeId.value)
            } returns templateContent
        }

        it("author 토큰 없는 템플릿에서는 userLookupPort 를 조회하지 않아야 한다") {
            val request =
                CreateIssueRequest(
                    projectKey = projectKey,
                    summary = "author 없는 이슈",
                    reporterId = actor,
                    description = null,
                )
            val result = sut.createIssue(actor, request)
            result.description shouldBe templateContent
            verify(exactly = 0) {
                userLookupPort.findDisplayNamesByIds(any())
            }
        }
    }
})
