// IssueApplicationService merge-patch 5필드(description/priority/labels/environment/impact) + IssueResponse 필드 노출 단위 테스트

package com.bts.issue.application

import com.bts.issue.adapter.inbound.rest.IssueResponse
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.event.IssueUpdated
import com.bts.issue.port.outbound.IssuePermission
import com.bts.issue.port.outbound.IssuePermissionResolver
import com.bts.issue.port.outbound.IssueScope
import com.bts.issue.repository.IssueRepository
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.workflow.WorkflowKeyResolver
import com.bts.shared.workflow.WorkflowTransitionPort
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * FR-IS-04 Task 6 — IssueApplicationService merge-patch 5필드 + IssueResponse 신규 필드 단위 테스트.
 *
 * 매핑표 (B1).
 * | 필드        | null(부재) | ""(빈문자열)/[] | 값       |
 * | description | 무변경     | "" → DB NULL    | Markdown |
 * | environment | 무변경     | "" → DB NULL    | 설정     |
 * | labels      | 무변경     | [] → 빈배열     | 교체     |
 * | priority    | 무변경     | —               | 1..5     |
 * | impact      | 무변경     | —               | 1..3     |
 *
 * C3: 목록(listWithType) 경로에서는 descriptionHtml 미포함(null).
 * C5: 기존 updateIssue 확장 — @Transactional 클래스 레벨 상속.
 */
class IssueApplicationServiceTest : DescribeSpec({

    val repo = mockk<IssueRepository>()
    val issueTypeRepository = mockk<IssueTypeRepository>(relaxed = true)
    val eventPublisher = mockk<IssueEventPublisher>()
    val permissionResolver = mockk<IssuePermissionResolver>()
    val workflowPort = mockk<WorkflowTransitionPort>()
    val workflowKeyResolver = mockk<WorkflowKeyResolver>()
    val clock = Clock.fixed(Instant.parse("2026-05-30T00:00:00Z"), ZoneOffset.UTC)

    val sut =
        IssueApplicationService(
            repo,
            issueTypeRepository,
            eventPublisher,
            permissionResolver,
            workflowPort,
            workflowKeyResolver,
            clock,
        )

    val actor = ActorId(UUID.randomUUID())
    val issueKey = IssueKey("BTS-1")
    val existingVersion = 1L

    /** 기존 이슈 — 5개 신규 필드를 원하는 값으로 세팅 가능. */
    fun makeIssue(
        summary: String = "기존 제목",
        description: String? = null,
        priority: Int = 3,
        labels: List<String> = emptyList(),
        environment: String? = null,
        impact: Int? = null,
        version: Long = existingVersion,
    ) = Issue(
        id = IssueId(UUID.randomUUID()),
        key = issueKey,
        projectId = UUID.randomUUID(),
        summary = summary,
        reporterId = actor,
        currentStateKey = "open",
        version = version,
        deletedAt = null,
        createdAt = Instant.parse("2026-05-30T00:00:00Z"),
        updatedAt = Instant.parse("2026-05-30T00:00:00Z"),
        typeId = IssueTypeId(3L),
        description = description,
        priority = priority,
        labels = labels,
        environment = environment,
        impact = impact,
    )

    /**
     * IssueResponse 헬퍼 — 신규 필드 포함.
     * descriptionHtml 은 단건 경로에서만 채워진다 (C3).
     */
    fun makeResponse(
        summary: String = "기존 제목",
        version: Long = existingVersion,
        description: String? = null,
        descriptionHtml: String? = null,
        priority: Int = 3,
        priorityName: String = "Medium",
        labels: List<String> = emptyList(),
        environment: String? = null,
        impact: Int? = null,
        impactName: String? = null,
    ) = IssueResponse(
        key = issueKey.value,
        id = UUID.randomUUID(),
        projectKey = issueKey.projectPrefix,
        summary = summary,
        currentStateKey = "open",
        reporterId = actor.value,
        version = version,
        createdAt = Instant.parse("2026-05-30T00:00:00Z"),
        updatedAt = Instant.parse("2026-05-30T00:00:00Z"),
        typeId = 3L,
        typeKey = "task",
        typeName = "Task",
        description = description,
        descriptionHtml = descriptionHtml,
        priority = priority,
        priorityName = priorityName,
        labels = labels,
        environment = environment,
        impact = impact,
        impactName = impactName,
    )

    fun stubPermissionGranted() {
        every {
            permissionResolver.hasPermission(actor, IssuePermission.UPDATE, IssueScope.Issue(issueKey.value))
        } returns true
    }

    beforeEach {
        clearMocks(repo, eventPublisher, permissionResolver, answers = false)
    }

    // ── IssueResponse 신규 필드 노출 ──────────────────────────────────────────

    describe("IssueResponse 신규 필드 노출") {

        context("단건 조회 경로 — description/descriptionHtml/priorityName/impactName/labels/environment/impact") {

            it("description 이 원본 Markdown 그대로 노출된다") {
                val response = makeResponse(description = "**굵게**")
                response.description shouldBe "**굵게**"
            }

            it("descriptionHtml 이 렌더된 HTML 로 노출된다 (단건 경로)") {
                // MarkdownRenderer.renderSafe("**굵게**") → "<p><strong>굵게</strong></p>\n"
                val response = makeResponse(description = "**굵게**", descriptionHtml = "<p><strong>굵게</strong></p>\n")
                response.descriptionHtml.shouldNotBeNull()
            }

            it("priorityName 이 IssuePriority.displayName 으로 노출된다 (priority=1 → Highest)") {
                val response = makeResponse(priority = 1, priorityName = "Highest")
                response.priorityName shouldBe "Highest"
            }

            it("impactName 이 IssueImpact.displayName 으로 노출된다 (impact=1 → High)") {
                val response = makeResponse(impact = 1, impactName = "High")
                response.impactName shouldBe "High"
            }

            it("impact=null 이면 impactName=null") {
                val response = makeResponse(impact = null, impactName = null)
                response.impactName.shouldBeNull()
            }

            it("labels 가 그대로 노출된다") {
                val response = makeResponse(labels = listOf("bug", "frontend"))
                response.labels shouldBe listOf("bug", "frontend")
            }

            it("environment 가 그대로 노출된다") {
                val response = makeResponse(environment = "Chrome 125 / macOS")
                response.environment shouldBe "Chrome 125 / macOS"
            }
        }

        context("C3 — 목록 경로 응답에는 descriptionHtml 미포함(null)") {

            it("목록 IssueResponse 의 descriptionHtml 은 null") {
                // 목록 경로는 renderHtml=false(기본값)로 from() 호출 → descriptionHtml=null
                val listResponse = makeResponse(description = "**굵게**", descriptionHtml = null)
                listResponse.descriptionHtml.shouldBeNull()
            }
        }
    }

    // ── merge-patch 매핑표 B1 ────────────────────────────────────────────────

    describe("updateIssue — merge-patch B1 매핑표") {

        // ── description ──────────────────────────────────────────────────────

        context("description — null(부재) → 무변경") {
            val request = UpdateIssueRequest(
                summary = null,
                expectedVersion = existingVersion,
                description = null,
            )
            val existingIssue = makeIssue(description = "기존 설명")
            val existingResponse = makeResponse(description = "기존 설명")

            beforeEach {
                stubPermissionGranted()
                every { repo.findByKey(issueKey) } returns existingIssue
                every { repo.findByKeyWithType(issueKey) } returns existingResponse
            }

            it("updateFields 가 호출되지 않는다 (no-op)") {
                sut.updateIssue(actor, issueKey, request)
                // repo 가 non-relaxed mock — updateFields stub 없으면 호출 시 에러 → 통과=미호출 증명
            }

            it("eventPublisher 가 호출되지 않는다") {
                sut.updateIssue(actor, issueKey, request)
                verify(exactly = 0) { eventPublisher.publish(any()) }
            }
        }

        context("description — \"\"(빈문자열) → DB NULL 클리어") {
            val request = UpdateIssueRequest(
                summary = null,
                expectedVersion = existingVersion,
                description = "",
            )
            val existingIssue = makeIssue(description = "기존 설명")
            val clearedResponse = makeResponse(description = null)

            beforeEach {
                stubPermissionGranted()
                every { repo.findByKey(issueKey) } returns existingIssue
                every {
                    repo.updateFields(
                        key = issueKey,
                        summary = null,
                        typeId = null,
                        expectedVersion = existingVersion,
                        description = "",
                        priority = null,
                        labels = null,
                        environment = null,
                        impact = null,
                    )
                } returns 1
                every { repo.findByKeyWithType(issueKey) } returns clearedResponse
                every { eventPublisher.publish(any()) } returns Unit
            }

            it("updateFields 가 description=\"\"(클리어 sentinel)로 호출된다") {
                sut.updateIssue(actor, issueKey, request)
                verify(exactly = 1) {
                    repo.updateFields(
                        key = issueKey,
                        summary = null,
                        typeId = null,
                        expectedVersion = existingVersion,
                        description = "",
                        priority = null,
                        labels = null,
                        environment = null,
                        impact = null,
                    )
                }
            }

            it("IssueUpdated(fields={description}) 이벤트가 발행된다") {
                sut.updateIssue(actor, issueKey, request)
                verify(exactly = 1) {
                    eventPublisher.publish(
                        match { it is IssueUpdated && "description" in it.fields },
                    )
                }
            }
        }

        context("description — 값 설정") {
            val newDescription = "## 재현 방법\n1. 로그인"
            val request = UpdateIssueRequest(
                summary = null,
                expectedVersion = existingVersion,
                description = newDescription,
            )
            val existingIssue = makeIssue(description = null)
            val updatedResponse = makeResponse(description = newDescription, descriptionHtml = "<h2>재현 방법</h2>\n<ol>\n<li>로그인</li>\n</ol>\n")

            beforeEach {
                stubPermissionGranted()
                every { repo.findByKey(issueKey) } returns existingIssue
                every {
                    repo.updateFields(
                        key = issueKey,
                        summary = null,
                        typeId = null,
                        expectedVersion = existingVersion,
                        description = newDescription,
                        priority = null,
                        labels = null,
                        environment = null,
                        impact = null,
                    )
                } returns 1
                every { repo.findByKeyWithType(issueKey) } returns updatedResponse
                every { eventPublisher.publish(any()) } returns Unit
            }

            it("updateFields 가 새 description 으로 호출된다") {
                sut.updateIssue(actor, issueKey, request)
                verify(exactly = 1) {
                    repo.updateFields(
                        key = issueKey,
                        summary = null,
                        typeId = null,
                        expectedVersion = existingVersion,
                        description = newDescription,
                        priority = null,
                        labels = null,
                        environment = null,
                        impact = null,
                    )
                }
            }
        }

        // ── environment ───────────────────────────────────────────────────────

        context("environment — null(부재) → 무변경") {
            val request = UpdateIssueRequest(
                summary = null,
                expectedVersion = existingVersion,
                environment = null,
            )
            val existingIssue = makeIssue(environment = "Chrome 125")
            val existingResponse = makeResponse(environment = "Chrome 125")

            beforeEach {
                stubPermissionGranted()
                every { repo.findByKey(issueKey) } returns existingIssue
                every { repo.findByKeyWithType(issueKey) } returns existingResponse
            }

            it("updateFields 가 호출되지 않는다 (no-op)") {
                sut.updateIssue(actor, issueKey, request)
                verify(exactly = 0) { eventPublisher.publish(any()) }
            }
        }

        context("environment — \"\"(빈문자열) → DB NULL 클리어") {
            val request = UpdateIssueRequest(
                summary = null,
                expectedVersion = existingVersion,
                environment = "",
            )
            val existingIssue = makeIssue(environment = "Chrome 125")
            val clearedResponse = makeResponse(environment = null)

            beforeEach {
                stubPermissionGranted()
                every { repo.findByKey(issueKey) } returns existingIssue
                every {
                    repo.updateFields(
                        key = issueKey,
                        summary = null,
                        typeId = null,
                        expectedVersion = existingVersion,
                        description = null,
                        priority = null,
                        labels = null,
                        environment = "",
                        impact = null,
                    )
                } returns 1
                every { repo.findByKeyWithType(issueKey) } returns clearedResponse
                every { eventPublisher.publish(any()) } returns Unit
            }

            it("updateFields 가 environment=\"\"(클리어 sentinel)로 호출된다") {
                sut.updateIssue(actor, issueKey, request)
                verify(exactly = 1) {
                    repo.updateFields(
                        key = issueKey,
                        summary = null,
                        typeId = null,
                        expectedVersion = existingVersion,
                        description = null,
                        priority = null,
                        labels = null,
                        environment = "",
                        impact = null,
                    )
                }
            }
        }

        // ── labels ────────────────────────────────────────────────────────────

        context("labels — null(부재) → 무변경") {
            val request = UpdateIssueRequest(
                summary = null,
                expectedVersion = existingVersion,
                labels = null,
            )
            val existingIssue = makeIssue(labels = listOf("bug"))
            val existingResponse = makeResponse(labels = listOf("bug"))

            beforeEach {
                stubPermissionGranted()
                every { repo.findByKey(issueKey) } returns existingIssue
                every { repo.findByKeyWithType(issueKey) } returns existingResponse
            }

            it("updateFields 가 호출되지 않는다 (no-op)") {
                sut.updateIssue(actor, issueKey, request)
                verify(exactly = 0) { eventPublisher.publish(any()) }
            }
        }

        context("labels — [](빈배열) → 전체 제거") {
            val request = UpdateIssueRequest(
                summary = null,
                expectedVersion = existingVersion,
                labels = emptyList(),
            )
            val existingIssue = makeIssue(labels = listOf("bug", "frontend"))
            val clearedResponse = makeResponse(labels = emptyList())

            beforeEach {
                stubPermissionGranted()
                every { repo.findByKey(issueKey) } returns existingIssue
                every {
                    repo.updateFields(
                        key = issueKey,
                        summary = null,
                        typeId = null,
                        expectedVersion = existingVersion,
                        description = null,
                        priority = null,
                        labels = emptyList(),
                        environment = null,
                        impact = null,
                    )
                } returns 1
                every { repo.findByKeyWithType(issueKey) } returns clearedResponse
                every { eventPublisher.publish(any()) } returns Unit
            }

            it("updateFields 가 labels=emptyList()(전체 제거 sentinel)로 호출된다") {
                sut.updateIssue(actor, issueKey, request)
                verify(exactly = 1) {
                    repo.updateFields(
                        key = issueKey,
                        summary = null,
                        typeId = null,
                        expectedVersion = existingVersion,
                        description = null,
                        priority = null,
                        labels = emptyList(),
                        environment = null,
                        impact = null,
                    )
                }
            }
        }

        context("labels — 값 설정 → 교체") {
            val newLabels = listOf("performance", "backend")
            val request = UpdateIssueRequest(
                summary = null,
                expectedVersion = existingVersion,
                labels = newLabels,
            )
            val existingIssue = makeIssue(labels = listOf("bug"))
            val updatedResponse = makeResponse(labels = newLabels)

            beforeEach {
                stubPermissionGranted()
                every { repo.findByKey(issueKey) } returns existingIssue
                every {
                    repo.updateFields(
                        key = issueKey,
                        summary = null,
                        typeId = null,
                        expectedVersion = existingVersion,
                        description = null,
                        priority = null,
                        labels = newLabels,
                        environment = null,
                        impact = null,
                    )
                } returns 1
                every { repo.findByKeyWithType(issueKey) } returns updatedResponse
                every { eventPublisher.publish(any()) } returns Unit
            }

            it("updateFields 가 새 labels 로 호출된다") {
                sut.updateIssue(actor, issueKey, request)
                verify(exactly = 1) {
                    repo.updateFields(
                        key = issueKey,
                        summary = null,
                        typeId = null,
                        expectedVersion = existingVersion,
                        description = null,
                        priority = null,
                        labels = newLabels,
                        environment = null,
                        impact = null,
                    )
                }
            }
        }

        // ── priority ──────────────────────────────────────────────────────────

        context("priority — null(부재) → 무변경") {
            val request = UpdateIssueRequest(
                summary = null,
                expectedVersion = existingVersion,
                priority = null,
            )
            val existingIssue = makeIssue(priority = 2)
            val existingResponse = makeResponse(priority = 2, priorityName = "High")

            beforeEach {
                stubPermissionGranted()
                every { repo.findByKey(issueKey) } returns existingIssue
                every { repo.findByKeyWithType(issueKey) } returns existingResponse
            }

            it("updateFields 가 호출되지 않는다 (no-op)") {
                sut.updateIssue(actor, issueKey, request)
                verify(exactly = 0) { eventPublisher.publish(any()) }
            }
        }

        context("priority — 1 설정") {
            val request = UpdateIssueRequest(
                summary = null,
                expectedVersion = existingVersion,
                priority = 1,
            )
            val existingIssue = makeIssue(priority = 3)
            val updatedResponse = makeResponse(priority = 1, priorityName = "Highest")

            beforeEach {
                stubPermissionGranted()
                every { repo.findByKey(issueKey) } returns existingIssue
                every {
                    repo.updateFields(
                        key = issueKey,
                        summary = null,
                        typeId = null,
                        expectedVersion = existingVersion,
                        description = null,
                        priority = 1,
                        labels = null,
                        environment = null,
                        impact = null,
                    )
                } returns 1
                every { repo.findByKeyWithType(issueKey) } returns updatedResponse
                every { eventPublisher.publish(any()) } returns Unit
            }

            it("updateFields 가 priority=1 로 호출된다") {
                sut.updateIssue(actor, issueKey, request)
                verify(exactly = 1) {
                    repo.updateFields(
                        key = issueKey,
                        summary = null,
                        typeId = null,
                        expectedVersion = existingVersion,
                        description = null,
                        priority = 1,
                        labels = null,
                        environment = null,
                        impact = null,
                    )
                }
            }

            it("응답 priorityName 이 Highest") {
                val result = sut.updateIssue(actor, issueKey, request)
                result.priorityName shouldBe "Highest"
            }
        }

        context("priority — 범위 초과 (0 → 검증 실패)") {
            val request = UpdateIssueRequest(
                summary = null,
                expectedVersion = existingVersion,
                priority = 0,
            )
            val existingIssue = makeIssue(priority = 3)

            beforeEach {
                stubPermissionGranted()
                every { repo.findByKey(issueKey) } returns existingIssue
            }

            it("IllegalArgumentException 을 던진다") {
                shouldThrow<IllegalArgumentException> {
                    sut.updateIssue(actor, issueKey, request)
                }
            }
        }

        context("priority — 범위 초과 (6 → 검증 실패)") {
            val request = UpdateIssueRequest(
                summary = null,
                expectedVersion = existingVersion,
                priority = 6,
            )
            val existingIssue = makeIssue(priority = 3)

            beforeEach {
                stubPermissionGranted()
                every { repo.findByKey(issueKey) } returns existingIssue
            }

            it("IllegalArgumentException 을 던진다") {
                shouldThrow<IllegalArgumentException> {
                    sut.updateIssue(actor, issueKey, request)
                }
            }
        }

        // ── impact ────────────────────────────────────────────────────────────

        context("impact — null(부재) → 무변경") {
            val request = UpdateIssueRequest(
                summary = null,
                expectedVersion = existingVersion,
                impact = null,
            )
            val existingIssue = makeIssue(impact = 2)
            val existingResponse = makeResponse(impact = 2, impactName = "Medium")

            beforeEach {
                stubPermissionGranted()
                every { repo.findByKey(issueKey) } returns existingIssue
                every { repo.findByKeyWithType(issueKey) } returns existingResponse
            }

            it("updateFields 가 호출되지 않는다 (no-op)") {
                sut.updateIssue(actor, issueKey, request)
                verify(exactly = 0) { eventPublisher.publish(any()) }
            }
        }

        context("impact — 1(High) 설정") {
            val request = UpdateIssueRequest(
                summary = null,
                expectedVersion = existingVersion,
                impact = 1,
            )
            val existingIssue = makeIssue(impact = null)
            val updatedResponse = makeResponse(impact = 1, impactName = "High")

            beforeEach {
                stubPermissionGranted()
                every { repo.findByKey(issueKey) } returns existingIssue
                every {
                    repo.updateFields(
                        key = issueKey,
                        summary = null,
                        typeId = null,
                        expectedVersion = existingVersion,
                        description = null,
                        priority = null,
                        labels = null,
                        environment = null,
                        impact = 1,
                    )
                } returns 1
                every { repo.findByKeyWithType(issueKey) } returns updatedResponse
                every { eventPublisher.publish(any()) } returns Unit
            }

            it("updateFields 가 impact=1 로 호출된다") {
                sut.updateIssue(actor, issueKey, request)
                verify(exactly = 1) {
                    repo.updateFields(
                        key = issueKey,
                        summary = null,
                        typeId = null,
                        expectedVersion = existingVersion,
                        description = null,
                        priority = null,
                        labels = null,
                        environment = null,
                        impact = 1,
                    )
                }
            }

            it("응답 impactName 이 High") {
                val result = sut.updateIssue(actor, issueKey, request)
                result.impactName shouldBe "High"
            }
        }

        context("impact — 범위 초과 (0 → 검증 실패)") {
            val request = UpdateIssueRequest(
                summary = null,
                expectedVersion = existingVersion,
                impact = 0,
            )
            val existingIssue = makeIssue(impact = null)

            beforeEach {
                stubPermissionGranted()
                every { repo.findByKey(issueKey) } returns existingIssue
            }

            it("IllegalArgumentException 을 던진다") {
                shouldThrow<IllegalArgumentException> {
                    sut.updateIssue(actor, issueKey, request)
                }
            }
        }

        context("impact — 범위 초과 (4 → 검증 실패)") {
            val request = UpdateIssueRequest(
                summary = null,
                expectedVersion = existingVersion,
                impact = 4,
            )
            val existingIssue = makeIssue(impact = null)

            beforeEach {
                stubPermissionGranted()
                every { repo.findByKey(issueKey) } returns existingIssue
            }

            it("IllegalArgumentException 을 던진다") {
                shouldThrow<IllegalArgumentException> {
                    sut.updateIssue(actor, issueKey, request)
                }
            }
        }

        // ── 복합 변경 ──────────────────────────────────────────────────────────

        context("복합 — summary + description + priority 동시 변경") {
            val request = UpdateIssueRequest(
                summary = "새 제목",
                expectedVersion = existingVersion,
                description = "## 요약",
                priority = 1,
            )
            val existingIssue = makeIssue(summary = "기존 제목", description = null, priority = 3)
            val updatedResponse = makeResponse(
                summary = "새 제목",
                version = existingVersion + 1,
                description = "## 요약",
                priority = 1,
                priorityName = "Highest",
            )

            beforeEach {
                stubPermissionGranted()
                every { repo.findByKey(issueKey) } returns existingIssue
                every {
                    repo.updateFields(
                        key = issueKey,
                        summary = "새 제목",
                        typeId = null,
                        expectedVersion = existingVersion,
                        description = "## 요약",
                        priority = 1,
                        labels = null,
                        environment = null,
                        impact = null,
                    )
                } returns 1
                every { repo.findByKeyWithType(issueKey) } returns updatedResponse
                every { eventPublisher.publish(any()) } returns Unit
            }

            it("updateFields 가 모든 변경 필드와 함께 호출된다") {
                sut.updateIssue(actor, issueKey, request)
                verify(exactly = 1) {
                    repo.updateFields(
                        key = issueKey,
                        summary = "새 제목",
                        typeId = null,
                        expectedVersion = existingVersion,
                        description = "## 요약",
                        priority = 1,
                        labels = null,
                        environment = null,
                        impact = null,
                    )
                }
            }

            it("IssueUpdated fields 에 summary + description + priority 가 포함된다") {
                sut.updateIssue(actor, issueKey, request)
                verify(exactly = 1) {
                    eventPublisher.publish(
                        match {
                            it is IssueUpdated &&
                                "summary" in it.fields &&
                                "description" in it.fields &&
                                "priority" in it.fields
                        },
                    )
                }
            }
        }
    }
})
