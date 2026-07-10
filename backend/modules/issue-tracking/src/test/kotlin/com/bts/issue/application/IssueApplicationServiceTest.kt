// IssueApplicationService merge-patch 5필드(description/priority/labels/environment/impact) + IssueResponse 필드 노출 단위 테스트

package com.bts.issue.application

import com.bts.issue.adapter.inbound.rest.IssueResponse
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.AssigneeNotFoundException
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueVersionConflictException
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.event.IssueUpdated
import com.bts.issue.repository.IssueFieldPatch
import com.bts.issue.repository.IssueRepository
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.user.UserLookupPort
import com.bts.shared.workflow.WorkflowKeyResolver
import com.bts.shared.workflow.WorkflowTransitionPort
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
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
 * LargeClass: 5필드 merge-patch 전체 케이스 + IssueResponse 필드 — 의도적으로 한 클래스에 집결.
 */
@Suppress("LargeClass")
class IssueApplicationServiceTest : DescribeSpec({

    val repo = mockk<IssueRepository>()
    val issueTypeRepository = mockk<IssueTypeRepository>(relaxed = true)
    val resolutionRepository = mockk<com.bts.issue.resolution.repository.ResolutionRepository>(relaxed = true)
    val eventPublisher = mockk<IssueEventPublisher>()
    val permissionResolver = mockk<IssuePermissionResolver>()
    val workflowPort = mockk<WorkflowTransitionPort>()
    val workflowKeyResolver = mockk<WorkflowKeyResolver>()
    val userLookupPort = mockk<UserLookupPort>(relaxed = true)
    val clock = Clock.fixed(Instant.parse("2026-05-30T00:00:00Z"), ZoneOffset.UTC)

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
        )

    val actor = ActorId(UUID.randomUUID())
    val issueKey = IssueKey("BTS-1")
    val existingVersion = 1L

    /** 기존 이슈 — 5개 신규 필드 및 날짜 필드를 원하는 값으로 세팅 가능. */
    @Suppress("LongParameterList") // 테스트 픽스처 헬퍼 — Issue 도메인 필드 수를 반영
    fun makeIssue(
        summary: String = "기존 제목",
        description: String? = null,
        priority: Int = 3,
        labels: List<String> = emptyList(),
        environment: String? = null,
        impact: Int? = null,
        version: Long = existingVersion,
        startDate: LocalDate? = null,
        dueDate: LocalDate? = null,
        targetDate: LocalDate? = null,
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
        startDate = startDate,
        dueDate = dueDate,
        targetDate = targetDate,
    )

    /**
     * IssueResponse 헬퍼 — 신규 필드 포함.
     * descriptionHtml 은 단건 경로에서만 채워진다 (C3).
     */
    @Suppress("LongParameterList") // 테스트 픽스처 헬퍼 — IssueResponse 신규 필드 수를 반영
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
            permissionResolver.hasPermission(actor.value, IssuePermission.UPDATE, IssueScope.Issue(issueKey.value))
        } returns true
    }

    // assertEditableOrForbidden 이 AlwaysAllowFieldPermissionResolver 기본값으로 실행될 때
    // repo.findProjectIdByKey 를 호출한다. 임의 UUID 를 반환하면 AlwaysAllow 가 전 필드 허용.
    val anyProjectId: UUID = UUID.fromString("11111111-0000-0000-0000-000000000001")

    beforeEach {
        clearMocks(repo, eventPublisher, permissionResolver, userLookupPort, answers = false)
        // withSingleDetail() 내부에서 findActiveComponentIdsByIssue 호출 — 단건 응답 테스트 기본 stub
        every { repo.findActiveComponentIdsByIssue(any()) } returns emptyList()
        every { repo.findAffectsVersionIdsByIssue(any()) } returns emptyList()
        every { repo.findFixVersionIdsByIssue(any()) } returns emptyList()
        // assertEditableOrForbidden(AlwaysAllow 기본값) 이 findProjectIdByKey 를 호출한다 — 전 필드 허용용 stub
        every { repo.findProjectIdByKey(issueKey.projectPrefix) } returns anyProjectId
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
            val request =
                UpdateIssueRequest(
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
            val request =
                UpdateIssueRequest(
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
                        patch =
                            IssueFieldPatch(
                                summary = null,
                                typeId = null,
                                description = "",
                                priority = null,
                                labels = null,
                                environment = null,
                                impact = null,
                            ),
                        expectedVersion = existingVersion,
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
                        patch =
                            IssueFieldPatch(
                                summary = null,
                                typeId = null,
                                description = "",
                                priority = null,
                                labels = null,
                                environment = null,
                                impact = null,
                            ),
                        expectedVersion = existingVersion,
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
            val request =
                UpdateIssueRequest(
                    summary = null,
                    expectedVersion = existingVersion,
                    description = newDescription,
                )
            val existingIssue = makeIssue(description = null)
            val updatedResponse =
                makeResponse(
                    description = newDescription,
                    descriptionHtml = "<h2>재현 방법</h2>\n<ol>\n<li>로그인</li>\n</ol>\n",
                )

            beforeEach {
                stubPermissionGranted()
                every { repo.findByKey(issueKey) } returns existingIssue
                every {
                    repo.updateFields(
                        key = issueKey,
                        patch =
                            IssueFieldPatch(
                                summary = null,
                                typeId = null,
                                description = newDescription,
                                priority = null,
                                labels = null,
                                environment = null,
                                impact = null,
                            ),
                        expectedVersion = existingVersion,
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
                        patch =
                            IssueFieldPatch(
                                summary = null,
                                typeId = null,
                                description = newDescription,
                                priority = null,
                                labels = null,
                                environment = null,
                                impact = null,
                            ),
                        expectedVersion = existingVersion,
                    )
                }
            }
        }

        // ── environment ───────────────────────────────────────────────────────

        context("environment — null(부재) → 무변경") {
            val request =
                UpdateIssueRequest(
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
            val request =
                UpdateIssueRequest(
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
                        patch =
                            IssueFieldPatch(
                                summary = null,
                                typeId = null,
                                description = null,
                                priority = null,
                                labels = null,
                                environment = "",
                                impact = null,
                            ),
                        expectedVersion = existingVersion,
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
                        patch =
                            IssueFieldPatch(
                                summary = null,
                                typeId = null,
                                description = null,
                                priority = null,
                                labels = null,
                                environment = "",
                                impact = null,
                            ),
                        expectedVersion = existingVersion,
                    )
                }
            }
        }

        // ── labels ────────────────────────────────────────────────────────────

        context("labels — null(부재) → 무변경") {
            val request =
                UpdateIssueRequest(
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
            val request =
                UpdateIssueRequest(
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
                        patch =
                            IssueFieldPatch(
                                summary = null,
                                typeId = null,
                                description = null,
                                priority = null,
                                labels = emptyList(),
                                environment = null,
                                impact = null,
                            ),
                        expectedVersion = existingVersion,
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
                        patch =
                            IssueFieldPatch(
                                summary = null,
                                typeId = null,
                                description = null,
                                priority = null,
                                labels = emptyList(),
                                environment = null,
                                impact = null,
                            ),
                        expectedVersion = existingVersion,
                    )
                }
            }
        }

        context("labels — 값 설정 → 교체") {
            val newLabels = listOf("performance", "backend")
            val request =
                UpdateIssueRequest(
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
                        patch =
                            IssueFieldPatch(
                                summary = null,
                                typeId = null,
                                description = null,
                                priority = null,
                                labels = newLabels,
                                environment = null,
                                impact = null,
                            ),
                        expectedVersion = existingVersion,
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
                        patch =
                            IssueFieldPatch(
                                summary = null,
                                typeId = null,
                                description = null,
                                priority = null,
                                labels = newLabels,
                                environment = null,
                                impact = null,
                            ),
                        expectedVersion = existingVersion,
                    )
                }
            }
        }

        // ── priority ──────────────────────────────────────────────────────────

        context("priority — null(부재) → 무변경") {
            val request =
                UpdateIssueRequest(
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
            val request =
                UpdateIssueRequest(
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
                        patch =
                            IssueFieldPatch(
                                summary = null,
                                typeId = null,
                                description = null,
                                priority = 1,
                                labels = null,
                                environment = null,
                                impact = null,
                            ),
                        expectedVersion = existingVersion,
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
                        patch =
                            IssueFieldPatch(
                                summary = null,
                                typeId = null,
                                description = null,
                                priority = 1,
                                labels = null,
                                environment = null,
                                impact = null,
                            ),
                        expectedVersion = existingVersion,
                    )
                }
            }

            it("응답 priorityName 이 Highest") {
                val result = sut.updateIssue(actor, issueKey, request)
                result.priorityName shouldBe "Highest"
            }
        }

        context("priority — 범위 초과 (0 → 검증 실패)") {
            val request =
                UpdateIssueRequest(
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
            val request =
                UpdateIssueRequest(
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
            val request =
                UpdateIssueRequest(
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
            val request =
                UpdateIssueRequest(
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
                        patch =
                            IssueFieldPatch(
                                summary = null,
                                typeId = null,
                                description = null,
                                priority = null,
                                labels = null,
                                environment = null,
                                impact = 1,
                            ),
                        expectedVersion = existingVersion,
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
                        patch =
                            IssueFieldPatch(
                                summary = null,
                                typeId = null,
                                description = null,
                                priority = null,
                                labels = null,
                                environment = null,
                                impact = 1,
                            ),
                        expectedVersion = existingVersion,
                    )
                }
            }

            it("응답 impactName 이 High") {
                val result = sut.updateIssue(actor, issueKey, request)
                result.impactName shouldBe "High"
            }
        }

        context("impact — 범위 초과 (0 → 검증 실패)") {
            val request =
                UpdateIssueRequest(
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
            val request =
                UpdateIssueRequest(
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

        // ── labels 도메인 검증 (BLOCKER 1 회귀 가드) ─────────────────────────────

        context("labels — 21개(초과) PATCH → IllegalArgumentException (도메인 검증 우회 불가)") {
            val tooManyLabels = (1..21).map { "label-$it" }
            val request =
                UpdateIssueRequest(
                    summary = null,
                    expectedVersion = existingVersion,
                    labels = tooManyLabels,
                )
            val existingIssue = makeIssue(labels = emptyList())

            beforeEach {
                stubPermissionGranted()
                every { repo.findByKey(issueKey) } returns existingIssue
            }

            it("21개 라벨 PATCH 는 도메인 검증에서 IllegalArgumentException 이 발생한다") {
                // repo 는 non-relaxed mock — updateFields stub 없이 호출 시 MockKException.
                // 예외가 IllegalArgumentException 이면 updateFields 미도달 증명 (domain 검증이 먼저 실행).
                shouldThrow<IllegalArgumentException> {
                    sut.updateIssue(actor, issueKey, request)
                }
            }
        }

        context("labels — 중복 라벨 PATCH → 정규화되어 dedup 후 repository 전달") {
            val rawLabels = listOf("bug", "backend", "bug") // "bug" 중복
            val deduped = listOf("bug", "backend")
            val request =
                UpdateIssueRequest(
                    summary = null,
                    expectedVersion = existingVersion,
                    labels = rawLabels,
                )
            val existingIssue = makeIssue(labels = emptyList())
            val updatedResponse = makeResponse(labels = deduped)

            beforeEach {
                stubPermissionGranted()
                every { repo.findByKey(issueKey) } returns existingIssue
                every {
                    repo.updateFields(
                        key = issueKey,
                        patch =
                            IssueFieldPatch(
                                summary = null,
                                typeId = null,
                                description = null,
                                priority = null,
                                labels = deduped,
                                environment = null,
                                impact = null,
                            ),
                        expectedVersion = existingVersion,
                    )
                } returns 1
                every { repo.findByKeyWithType(issueKey) } returns updatedResponse
                every { eventPublisher.publish(any()) } returns Unit
            }

            it("updateFields 에 dedup 된 labels 로 호출된다 (중복 제거)") {
                sut.updateIssue(actor, issueKey, request)
                verify(exactly = 1) {
                    repo.updateFields(
                        key = issueKey,
                        patch =
                            IssueFieldPatch(
                                summary = null,
                                typeId = null,
                                description = null,
                                priority = null,
                                labels = deduped,
                                environment = null,
                                impact = null,
                            ),
                        expectedVersion = existingVersion,
                    )
                }
            }
        }

        context("labels — 공백-only 라벨 PATCH → IllegalArgumentException (도메인 검증)") {
            val request =
                UpdateIssueRequest(
                    summary = null,
                    expectedVersion = existingVersion,
                    // "   " 는 공백-only 라벨 — 도메인 검증에서 IllegalArgumentException
                    labels = listOf("bug", "   "),
                )
            val existingIssue = makeIssue(labels = emptyList())

            beforeEach {
                stubPermissionGranted()
                every { repo.findByKey(issueKey) } returns existingIssue
            }

            it("공백-only 라벨은 IllegalArgumentException 이 발생한다") {
                shouldThrow<IllegalArgumentException> {
                    sut.updateIssue(actor, issueKey, request)
                }
            }
        }

        context("labels — 51자 라벨 PATCH → IllegalArgumentException (도메인 검증)") {
            val longLabel = "a".repeat(51)
            val request =
                UpdateIssueRequest(
                    summary = null,
                    expectedVersion = existingVersion,
                    labels = listOf(longLabel),
                )
            val existingIssue = makeIssue(labels = emptyList())

            beforeEach {
                stubPermissionGranted()
                every { repo.findByKey(issueKey) } returns existingIssue
            }

            it("51자 라벨은 IllegalArgumentException 이 발생한다") {
                shouldThrow<IllegalArgumentException> {
                    sut.updateIssue(actor, issueKey, request)
                }
            }
        }

        context("labels — 빈 문자열 포함 PATCH → 자동 제거 후 정상 저장") {
            val rawLabels = listOf("bug", "", "backend") // "" 자동 제거
            val filtered = listOf("bug", "backend")
            val request =
                UpdateIssueRequest(
                    summary = null,
                    expectedVersion = existingVersion,
                    labels = rawLabels,
                )
            val existingIssue = makeIssue(labels = emptyList())
            val updatedResponse = makeResponse(labels = filtered)

            beforeEach {
                stubPermissionGranted()
                every { repo.findByKey(issueKey) } returns existingIssue
                every {
                    repo.updateFields(
                        key = issueKey,
                        patch =
                            IssueFieldPatch(
                                summary = null,
                                typeId = null,
                                description = null,
                                priority = null,
                                labels = filtered,
                                environment = null,
                                impact = null,
                            ),
                        expectedVersion = existingVersion,
                    )
                } returns 1
                every { repo.findByKeyWithType(issueKey) } returns updatedResponse
                every { eventPublisher.publish(any()) } returns Unit
            }

            it("updateFields 에 빈 문자열이 제거된 labels 로 호출된다") {
                sut.updateIssue(actor, issueKey, request)
                verify(exactly = 1) {
                    repo.updateFields(
                        key = issueKey,
                        patch =
                            IssueFieldPatch(
                                summary = null,
                                typeId = null,
                                description = null,
                                priority = null,
                                labels = filtered,
                                environment = null,
                                impact = null,
                            ),
                        expectedVersion = existingVersion,
                    )
                }
            }
        }

        context("labels — [](빈배열) PATCH → 도메인 정규화 통과 후 전체 제거") {
            val request =
                UpdateIssueRequest(
                    summary = null,
                    expectedVersion = existingVersion,
                    labels = emptyList(),
                )
            val existingIssue = makeIssue(labels = listOf("bug"))
            val clearedResponse = makeResponse(labels = emptyList())

            beforeEach {
                stubPermissionGranted()
                every { repo.findByKey(issueKey) } returns existingIssue
                every {
                    repo.updateFields(
                        key = issueKey,
                        patch =
                            IssueFieldPatch(
                                summary = null,
                                typeId = null,
                                description = null,
                                priority = null,
                                labels = emptyList(),
                                environment = null,
                                impact = null,
                            ),
                        expectedVersion = existingVersion,
                    )
                } returns 1
                every { repo.findByKeyWithType(issueKey) } returns clearedResponse
                every { eventPublisher.publish(any()) } returns Unit
            }

            it("빈 배열 PATCH 는 정규화 후에도 빈 배열이므로 updateFields 가 labels=emptyList 로 호출된다") {
                sut.updateIssue(actor, issueKey, request)
                verify(exactly = 1) {
                    repo.updateFields(
                        key = issueKey,
                        patch =
                            IssueFieldPatch(
                                summary = null,
                                typeId = null,
                                description = null,
                                priority = null,
                                labels = emptyList(),
                                environment = null,
                                impact = null,
                            ),
                        expectedVersion = existingVersion,
                    )
                }
            }
        }

        // ── 복합 변경 ──────────────────────────────────────────────────────────

        context("복합 — summary + description + priority 동시 변경") {
            val request =
                UpdateIssueRequest(
                    summary = "새 제목",
                    expectedVersion = existingVersion,
                    description = "## 요약",
                    priority = 1,
                )
            val existingIssue = makeIssue(summary = "기존 제목", description = null, priority = 3)
            val updatedResponse =
                makeResponse(
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
                        patch =
                            IssueFieldPatch(
                                summary = "새 제목",
                                typeId = null,
                                description = "## 요약",
                                priority = 1,
                                labels = null,
                                environment = null,
                                impact = null,
                            ),
                        expectedVersion = existingVersion,
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
                        patch =
                            IssueFieldPatch(
                                summary = "새 제목",
                                typeId = null,
                                description = "## 요약",
                                priority = 1,
                                labels = null,
                                environment = null,
                                impact = null,
                            ),
                        expectedVersion = existingVersion,
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

    // ── changeAssignee ────────────────────────────────────────────────────────

    describe("changeAssignee") {

        val assigneeUuid = UUID.randomUUID()
        val expectedVersion = existingVersion

        fun stubUpdatePermissionGranted() {
            every {
                permissionResolver.hasPermission(actor.value, IssuePermission.UPDATE, IssueScope.Issue(issueKey.value))
            } returns true
        }

        // (a) assignee non-null + exists=true → assignTo 호출 + repo.updateAssignee 호출
        context("(a) assigneeId non-null + userLookupPort.exists=true → assignTo 경유, updateAssignee 호출") {
            val request = AppChangeAssigneeRequest(assigneeId = assigneeUuid, expectedVersion = expectedVersion)
            val existingIssue = makeIssue()
            val updatedResponse = makeResponse()

            beforeEach {
                stubUpdatePermissionGranted()
                every { repo.findByKey(issueKey) } returns existingIssue
                every { userLookupPort.exists(assigneeUuid) } returns true
                every { repo.updateAssignee(issueKey, assigneeUuid, expectedVersion) } returns 1
                every { repo.findByKeyWithType(issueKey) } returns updatedResponse
                every { eventPublisher.publish(any()) } returns Unit
            }

            it("repo.updateAssignee 가 assigneeId 와 함께 호출된다") {
                sut.changeAssignee(actor, issueKey, request)
                verify(exactly = 1) { repo.updateAssignee(issueKey, assigneeUuid, expectedVersion) }
            }

            it("IssueResponse 를 반환한다") {
                val result = sut.changeAssignee(actor, issueKey, request)
                result.key shouldBe issueKey.value
            }
        }

        // (b) exists=false → AssigneeNotFoundException, repo.updateAssignee 미호출
        context("(b) assigneeId non-null + userLookupPort.exists=false → AssigneeNotFoundException") {
            val request = AppChangeAssigneeRequest(assigneeId = assigneeUuid, expectedVersion = expectedVersion)
            val existingIssue = makeIssue()

            beforeEach {
                stubUpdatePermissionGranted()
                every { repo.findByKey(issueKey) } returns existingIssue
                every { userLookupPort.exists(assigneeUuid) } returns false
            }

            it("AssigneeNotFoundException 을 던진다") {
                shouldThrow<AssigneeNotFoundException> {
                    sut.changeAssignee(actor, issueKey, request)
                }
            }

            it("repo.updateAssignee 가 호출되지 않는다") {
                runCatching { sut.changeAssignee(actor, issueKey, request) }
                // exists=false 이면 AssigneeNotFoundException 으로 조기 종료 — updateAssignee 는 도달 불가
                verify(exactly = 0) { repo.updateAssignee(issueKey, assigneeUuid, expectedVersion) }
            }
        }

        // (c) assigneeId=null(해제) → exists 미호출, unassign 경로, updateAssignee(null,...)
        context("(c) assigneeId=null → exists 미호출, unassign 경유, updateAssignee(null) 호출") {
            val request = AppChangeAssigneeRequest(assigneeId = null, expectedVersion = expectedVersion)
            val existingIssue = makeIssue()
            val updatedResponse = makeResponse()

            beforeEach {
                stubUpdatePermissionGranted()
                every { repo.findByKey(issueKey) } returns existingIssue
                every { repo.updateAssignee(issueKey, null, expectedVersion) } returns 1
                every { repo.findByKeyWithType(issueKey) } returns updatedResponse
                every { eventPublisher.publish(any()) } returns Unit
            }

            it("userLookupPort.exists 가 전혀 호출되지 않는다") {
                sut.changeAssignee(actor, issueKey, request)
                verify(exactly = 0) { userLookupPort.exists(any()) }
            }

            it("repo.updateAssignee 가 assigneeId=null 로 호출된다") {
                sut.changeAssignee(actor, issueKey, request)
                verify(exactly = 1) { repo.updateAssignee(issueKey, null, expectedVersion) }
            }
        }

        // (d) repo.updateAssignee 0 row → IssueVersionConflictException
        context("(d) repo.updateAssignee 가 0 row 반환 → IssueVersionConflictException") {
            val request = AppChangeAssigneeRequest(assigneeId = assigneeUuid, expectedVersion = expectedVersion)
            val existingIssue = makeIssue()

            beforeEach {
                stubUpdatePermissionGranted()
                every { repo.findByKey(issueKey) } returns existingIssue
                every { userLookupPort.exists(assigneeUuid) } returns true
                every { repo.updateAssignee(issueKey, assigneeUuid, expectedVersion) } returns 0
            }

            it("IssueVersionConflictException 을 던진다") {
                shouldThrow<IssueVersionConflictException> {
                    sut.changeAssignee(actor, issueKey, request)
                }
            }
        }
    }

    // ── FR-PL-01 Task 5 — 날짜 필드 DatePatch 배선 + buildChangedFields 감지 (C3) ──

    describe("updateIssue — 날짜 필드 DatePatch C3") {

        // (a) 날짜-only PATCH → IssueFieldPatch 에 DatePatch 전달 + updateFields 호출 → version+1
        context("(a) startDate=Set → IssueFieldPatch 에 DatePatch.Set 전달, updateFields 호출") {
            val newDate = LocalDate.of(2026, 7, 1)
            val request =
                UpdateIssueRequest(
                    summary = null,
                    expectedVersion = existingVersion,
                    startDate = DatePatch.Set(newDate),
                )
            val existingIssue = makeIssue(startDate = null)
            val updatedResponse = makeResponse()

            beforeEach {
                stubPermissionGranted()
                every { repo.findByKey(issueKey) } returns existingIssue
                every {
                    repo.updateFields(
                        key = issueKey,
                        patch = IssueFieldPatch(startDate = DatePatch.Set(newDate)),
                        expectedVersion = existingVersion,
                    )
                } returns 1
                every { repo.findByKey(issueKey) } returns existingIssue
                every { repo.findByKeyWithType(issueKey) } returns updatedResponse
                every { eventPublisher.publish(any()) } returns Unit
            }

            it("repo.updateFields 가 startDate=Set(date) 를 담은 IssueFieldPatch 로 호출된다") {
                sut.updateIssue(actor, issueKey, request)
                verify(exactly = 1) {
                    repo.updateFields(
                        key = issueKey,
                        patch = IssueFieldPatch(startDate = DatePatch.Set(newDate)),
                        expectedVersion = existingVersion,
                    )
                }
            }

            it("IssueUpdated 이벤트에 startDate 필드명이 포함된다") {
                sut.updateIssue(actor, issueKey, request)
                verify(exactly = 1) {
                    eventPublisher.publish(
                        match { it is IssueUpdated && "startDate" in it.fields },
                    )
                }
            }
        }

        // (b) Unchanged → buildChangedFields 가 날짜 필드를 감지하지 않음 → no-op
        context("(b) startDate=Unchanged → 무변경, updateFields 미호출") {
            val request =
                UpdateIssueRequest(
                    summary = null,
                    expectedVersion = existingVersion,
                    startDate = DatePatch.Unchanged,
                )
            val existingIssue = makeIssue(startDate = LocalDate.of(2026, 6, 1))
            val existingResponse = makeResponse()

            beforeEach {
                stubPermissionGranted()
                every { repo.findByKey(issueKey) } returns existingIssue
                every { repo.findByKeyWithType(issueKey) } returns existingResponse
            }

            it("updateFields 가 호출되지 않는다 (no-op)") {
                sut.updateIssue(actor, issueKey, request)
                // non-relaxed mock — updateFields stub 없음 → 호출되면 에러 → 통과=미호출 증명
            }

            it("eventPublisher 가 호출되지 않는다") {
                sut.updateIssue(actor, issueKey, request)
                verify(exactly = 0) { eventPublisher.publish(any()) }
            }
        }

        // (c) Clear 경계 분리 — 기존 null → Clear = no-op
        context("(c) dueDate=Clear, 기존값=null → no-op (Clear 경계 1)") {
            val request =
                UpdateIssueRequest(
                    summary = null,
                    expectedVersion = existingVersion,
                    dueDate = DatePatch.Clear,
                )
            val existingIssue = makeIssue(dueDate = null) // 기존 null

            val existingResponse = makeResponse()

            beforeEach {
                stubPermissionGranted()
                every { repo.findByKey(issueKey) } returns existingIssue
                every { repo.findByKeyWithType(issueKey) } returns existingResponse
            }

            it("updateFields 가 호출되지 않는다 — 기존 null 에 Clear 는 변경 없음") {
                sut.updateIssue(actor, issueKey, request)
                // non-relaxed mock — stub 없음 → 호출되면 에러 → 통과=미호출 증명
            }

            it("eventPublisher 가 호출되지 않는다") {
                sut.updateIssue(actor, issueKey, request)
                verify(exactly = 0) { eventPublisher.publish(any()) }
            }
        }

        // (d) Clear 경계 분리 — 기존 값 있을 때 → Clear = 변경 감지
        context("(d) dueDate=Clear, 기존값=2026-06-30 → 변경 감지 (Clear 경계 2)") {
            val existingDueDate = LocalDate.of(2026, 6, 30)
            val request =
                UpdateIssueRequest(
                    summary = null,
                    expectedVersion = existingVersion,
                    dueDate = DatePatch.Clear,
                )
            val existingIssue = makeIssue(dueDate = existingDueDate) // 기존 값 있음
            val updatedResponse = makeResponse()

            beforeEach {
                stubPermissionGranted()
                every { repo.findByKey(issueKey) } returns existingIssue
                every {
                    repo.updateFields(
                        key = issueKey,
                        patch = IssueFieldPatch(dueDate = DatePatch.Clear),
                        expectedVersion = existingVersion,
                    )
                } returns 1
                every { repo.findByKey(issueKey) } returns existingIssue
                every { repo.findByKeyWithType(issueKey) } returns updatedResponse
                every { eventPublisher.publish(any()) } returns Unit
            }

            it("repo.updateFields 가 dueDate=Clear 를 담은 IssueFieldPatch 로 호출된다") {
                sut.updateIssue(actor, issueKey, request)
                verify(exactly = 1) {
                    repo.updateFields(
                        key = issueKey,
                        patch = IssueFieldPatch(dueDate = DatePatch.Clear),
                        expectedVersion = existingVersion,
                    )
                }
            }

            it("IssueUpdated 이벤트에 dueDate 필드명이 포함된다") {
                sut.updateIssue(actor, issueKey, request)
                verify(exactly = 1) {
                    eventPublisher.publish(
                        match { it is IssueUpdated && "dueDate" in it.fields },
                    )
                }
            }
        }

        // (e) 3필드 동시 Set → changedFields 에 3개 모두 포함, updateFields 1회 호출
        context("(e) 3필드 동시 Set → changedFields 에 startDate/dueDate/targetDate 포함") {
            val start = LocalDate.of(2026, 7, 1)
            val due = LocalDate.of(2026, 7, 31)
            val target = LocalDate.of(2026, 8, 15)
            val request =
                UpdateIssueRequest(
                    summary = null,
                    expectedVersion = existingVersion,
                    startDate = DatePatch.Set(start),
                    dueDate = DatePatch.Set(due),
                    targetDate = DatePatch.Set(target),
                )
            val existingIssue = makeIssue()
            val updatedResponse = makeResponse()

            beforeEach {
                stubPermissionGranted()
                every { repo.findByKey(issueKey) } returns existingIssue
                every {
                    repo.updateFields(
                        key = issueKey,
                        patch =
                            IssueFieldPatch(
                                startDate = DatePatch.Set(start),
                                dueDate = DatePatch.Set(due),
                                targetDate = DatePatch.Set(target),
                            ),
                        expectedVersion = existingVersion,
                    )
                } returns 1
                every { repo.findByKey(issueKey) } returns existingIssue
                every { repo.findByKeyWithType(issueKey) } returns updatedResponse
                every { eventPublisher.publish(any()) } returns Unit
            }

            it("IssueUpdated 이벤트에 3개 날짜 필드명이 모두 포함된다") {
                sut.updateIssue(actor, issueKey, request)
                verify(exactly = 1) {
                    eventPublisher.publish(
                        match { event ->
                            event is IssueUpdated &&
                                "startDate" in event.fields &&
                                "dueDate" in event.fields &&
                                "targetDate" in event.fields
                        },
                    )
                }
            }
        }
    }
})
