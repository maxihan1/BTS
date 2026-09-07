// 이슈 생성 시 템플릿 description 안전망(옵션 C) 단위 테스트 — FR-TM-01 Task 7 TDD RED

package com.bts.issue.application

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
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
import io.kotest.matchers.nulls.shouldBeNull
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
 * FR-TM-01 Task 7 — 이슈 생성 시 템플릿 description 안전망(옵션 C) 단위 테스트.
 *
 * 옵션 C 로직: `resolvedDescription = request.description?.takeIf { it.isNotBlank() }
 *   ?: issueTemplateRepository?.findActiveContentByProjectAndType(projectId, resolvedTypeId.value)`
 *
 * 검증 케이스 (5개).
 * (a) description blank + 활성 템플릿 존재 → 템플릿 content 주입
 * (b) description non-blank → 요청 값 우선(템플릿 무시)
 * (c) description blank + 활성 템플릿 없음 → null
 * (d) description "" (빈 문자열) + 활성 템플릿 존재 → 템플릿 content 주입
 * (e) cloneIssue 는 issueTemplateRepository 를 참조하지 않음(원본 description 복사 유지)
 */
class IssueApplicationServiceTemplateApplyTest : DescribeSpec({

    val repo = mockk<IssueRepository>()
    val issueTypeRepository = mockk<IssueTypeRepository>()
    val resolutionRepository = mockk<com.bts.issue.resolution.repository.ResolutionRepository>(relaxed = true)
    val eventPublisher = mockk<IssueEventPublisher>()
    val permissionResolver = mockk<IssuePermissionResolver>()
    val workflowPort = mockk<WorkflowTransitionPort>()
    val workflowKeyResolver = mockk<WorkflowKeyResolver>()
    val userLookupPort = mockk<UserLookupPort>(relaxed = true)
    val templateRepo = mockk<IssueTemplateRepository>()
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
    val templateContent = "## 재현 방법\n\n## 기대 동작\n\n## 실제 동작"

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

    /** 공통 인프라 stub 설정 — 각 케이스에서 재사용. 케이스 간 templateRepo stub/call-count 누적을 방지하기 위해 clearMocks 선행. */
    fun stubCommonInfra() {
        clearMocks(templateRepo)
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
        every { repo.insert(capture(slot), any()) } answers { slot.captured }
    }

    describe("(a) description null + 활성 템플릿 존재 → 템플릿 content 주입") {
        beforeEach {
            stubCommonInfra()
            stubInsert()
            every {
                templateRepo.findActiveContentByProjectAndType(projectId, typeId.value)
            } returns templateContent
        }

        it("생성된 이슈의 description 이 템플릿 content 와 동일해야 한다") {
            val request =
                CreateIssueRequest(
                    projectKey = projectKey,
                    summary = "새 이슈",
                    reporterId = actor,
                    description = null,
                )
            val result = sut.createIssue(actor, request)
            result.description shouldBe templateContent
        }
    }

    describe("(b) description non-blank → 요청 값 우선, 템플릿 무시") {
        val userDescription = "직접 작성한 설명"

        beforeEach {
            stubCommonInfra()
            stubInsert()
        }

        it("생성된 이슈의 description 이 요청 값이어야 하고 templateRepo 는 호출되지 않아야 한다") {
            val request =
                CreateIssueRequest(
                    projectKey = projectKey,
                    summary = "새 이슈",
                    reporterId = actor,
                    description = userDescription,
                )
            val result = sut.createIssue(actor, request)
            result.description shouldBe userDescription
            verify(exactly = 0) {
                templateRepo.findActiveContentByProjectAndType(any(), any())
            }
        }
    }

    describe("(c) description null + 활성 템플릿 없음 → null") {
        beforeEach {
            stubCommonInfra()
            stubInsert()
            every {
                templateRepo.findActiveContentByProjectAndType(projectId, typeId.value)
            } returns null
        }

        it("생성된 이슈의 description 이 null 이어야 한다") {
            val request =
                CreateIssueRequest(
                    projectKey = projectKey,
                    summary = "새 이슈",
                    reporterId = actor,
                    description = null,
                )
            val result = sut.createIssue(actor, request)
            result.description.shouldBeNull()
        }
    }

    describe("(d) description \"\" (빈 문자열) + 활성 템플릿 존재 → 템플릿 content 주입") {
        beforeEach {
            stubCommonInfra()
            stubInsert()
            every {
                templateRepo.findActiveContentByProjectAndType(projectId, typeId.value)
            } returns templateContent
        }

        it("빈 문자열은 blank 처리되어 템플릿 content 가 주입되어야 한다") {
            val request =
                CreateIssueRequest(
                    projectKey = projectKey,
                    summary = "새 이슈",
                    reporterId = actor,
                    description = "",
                )
            val result = sut.createIssue(actor, request)
            result.description shouldBe templateContent
        }
    }

    describe("(e) cloneIssue 는 issueTemplateRepository 를 참조하지 않음") {
        val sourceKey = IssueKey("BTS-1")
        val sourceDescription = "원본 이슈 설명"
        val sourceIssue =
            Issue(
                id = IssueId(UUID.randomUUID()),
                key = sourceKey,
                projectId = projectId,
                summary = "원본 이슈",
                reporterId = actor,
                currentStateKey = "open",
                version = 1L,
                deletedAt = null,
                createdAt = Instant.parse("2026-06-12T00:00:00Z"),
                updatedAt = Instant.parse("2026-06-12T00:00:00Z"),
                typeId = typeId,
                description = sourceDescription,
            )

        beforeEach {
            stubCommonInfra()
            stubInsert()
            every {
                permissionResolver.hasPermission(actor.value, IssuePermission.VIEW, IssueScope.Issue(sourceKey.value))
            } returns true
            every { repo.findByKey(sourceKey) } returns sourceIssue
            // 본문은 두 컬럼이다(V039) — 복제 경로가 원본 HTML 을 따로 읽는다.
            every { repo.findDescriptionHtml(sourceKey) } returns null
            every { repo.incrementKeySequence(projectKey) } returns 2L
        }

        it("cloneIssue 결과의 description 은 원본과 동일하고 templateRepo 는 호출되지 않아야 한다") {
            val request = CloneIssueRequest()
            val result = sut.cloneIssue(actor, sourceKey, request)
            result.description shouldBe sourceDescription
            verify(exactly = 0) {
                templateRepo.findActiveContentByProjectAndType(any(), any())
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 리치 에디터 생성 경로 (V039 · J23) — 본문이 두 컬럼이 된 뒤의 템플릿 상호작용
    //
    // ★(f)(g) 가 **가장 위험한 회귀**를 지킨다. TipTap 은 빈 문서를 빈 문자열이 아니라
    //   `<p></p>` 로 직렬화한다. 종전 판정(`isNotBlank()`)을 그대로 두면 「빈 본문」이 영영
    //   도착하지 않아 FR-TM-01 템플릿이 **조용히 죽는다** — 이슈는 만들어지고 본문만 비어
    //   있을 뿐이라 아무 오류도 안 난다.
    // ─────────────────────────────────────────────────────────────────────────

    describe("(f) descriptionHtml 내용 있음 → HTML 컬럼에 정화되어 저장, 템플릿 무시") {
        val htmlSlot = slot<String>()

        beforeEach {
            stubCommonInfra()
            val issueSlot = slot<Issue>()
            every { repo.insert(capture(issueSlot), capture(htmlSlot)) } answers { issueSlot.captured }
            every {
                templateRepo.findActiveContentByProjectAndType(projectId, typeId.value)
            } returns templateContent
        }

        it("HTML 이 두 번째 인자로 흘러가고 마크다운 컬럼은 비며 템플릿은 안 탄다") {
            val request =
                CreateIssueRequest(
                    projectKey = projectKey,
                    summary = "새 이슈",
                    reporterId = actor,
                    descriptionHtml = "<p>리치 <strong>본문</strong></p>",
                )
            val result = sut.createIssue(actor, request)

            // 에디터 경로는 마크다운 컬럼을 비운다 — 남기면 옛 내용을 담은 채 굳는다.
            result.description shouldBe null
            htmlSlot.captured shouldBe "<p>리치 <strong>본문</strong></p>"
            verify(exactly = 0) {
                templateRepo.findActiveContentByProjectAndType(any(), any())
            }
        }
    }

    describe("(g) descriptionHtml 이 빈 문서 → 템플릿이 살아 있다 (FR-TM-01 회귀 차단)") {
        beforeEach {
            stubCommonInfra()
            stubInsert()
            every {
                templateRepo.findActiveContentByProjectAndType(projectId, typeId.value)
            } returns templateContent
        }

        // TipTap 이 실제로 보내는 빈 문서 형태들. 하나라도 「내용 있음」으로 읽히면 템플릿이 죽는다.
        listOf("<p></p>", "<p><br></p>", "<p>&nbsp;</p>", "").forEach { emptyHtml ->
            it("빈 문서 ${'"'}$emptyHtml${'"'} 는 템플릿 content 로 대체된다") {
                val request =
                    CreateIssueRequest(
                        projectKey = projectKey,
                        summary = "새 이슈",
                        reporterId = actor,
                        descriptionHtml = emptyHtml,
                    )
                val result = sut.createIssue(actor, request)
                result.description shouldBe templateContent
            }
        }
    }

    describe("(h) 이미지만 든 본문은 빈 문서가 아니다 — 템플릿이 덮지 않는다") {
        val htmlSlot = slot<String>()

        beforeEach {
            stubCommonInfra()
            val issueSlot = slot<Issue>()
            every { repo.insert(capture(issueSlot), capture(htmlSlot)) } answers { issueSlot.captured }
            every {
                templateRepo.findActiveContentByProjectAndType(projectId, typeId.value)
            } returns templateContent
        }

        it("텍스트가 0자여도 이미지가 있으면 본문으로 저장된다") {
            // ★이 예외가 없으면 「이미지만 넣고 저장했더니 프로젝트 템플릿이 덮어썼다」가 된다.
            val imageOnly = "<p><img src=\"attachment:11111111-1111-1111-1111-111111111111\" alt=\"\" /></p>"
            val request =
                CreateIssueRequest(
                    projectKey = projectKey,
                    summary = "새 이슈",
                    reporterId = actor,
                    descriptionHtml = imageOnly,
                )
            val result = sut.createIssue(actor, request)

            result.description shouldBe null
            htmlSlot.captured.contains("<img") shouldBe true
        }
    }
})
