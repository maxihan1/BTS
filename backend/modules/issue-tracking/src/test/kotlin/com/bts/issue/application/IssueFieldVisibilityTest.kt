// IssueApplicationService 필드 수준 마스킹 단위 테스트 — TDD RED (FR-PM-07 Task-7)

package com.bts.issue.application

import com.bts.issue.adapter.inbound.rest.IssueResponse
import com.bts.issue.component.repository.ComponentRepository
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueKey
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.project.repository.ProjectLeadRepository
import com.bts.issue.repository.IssueRepository
import com.bts.issue.resolution.repository.ResolutionRepository
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.permission.FieldKind
import com.bts.shared.permission.FieldPermissionResolver
import com.bts.shared.permission.FieldRef
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.permission.IssueSecurityAccess
import com.bts.shared.user.UserLookupPort
import com.bts.shared.workflow.WorkflowKeyResolver
import com.bts.shared.workflow.WorkflowTransitionPort
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * IssueApplicationService 필드 수준 열람 마스킹 검증.
 *
 * spec §3.1 / §F4 규칙 기준.
 * - CUSTOM 필드: visible 집합에 없는 key 는 customFields 맵에서 제거 (S1).
 * - nullable CORE(description·environment·impact·assigneeId·labels): visible 에 없으면 null/빈리스트.
 * - non-null CORE(summary·priority): 마스킹 안 함 — 항상 노출.
 * - 마스킹된 key 는 restrictedFields 에 수록.
 * - 단건(findByKey) · 목록(listIssues) 양 경로 모두 검증.
 */
class IssueFieldVisibilityTest : DescribeSpec({

    val repo = mockk<IssueRepository>()
    val issueTypeRepository = mockk<IssueTypeRepository>(relaxed = true)
    val resolutionRepository = mockk<ResolutionRepository>(relaxed = true)
    val eventPublisher = mockk<IssueEventPublisher>()
    val permissionResolver = mockk<IssuePermissionResolver>()
    val workflowPort = mockk<WorkflowTransitionPort>()
    val workflowKeyResolver = mockk<WorkflowKeyResolver>()
    val userLookupPort = mockk<UserLookupPort>(relaxed = true)
    val fieldPermissionResolver = mockk<FieldPermissionResolver>()
    val clock = Clock.fixed(Instant.parse("2026-06-08T00:00:00Z"), ZoneOffset.UTC)

    val projectKey = "BTS"
    val projectId = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001")
    val actorId = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002")
    val actor = ActorId(actorId)
    val issueKey = IssueKey("BTS-1")
    val issueId = UUID.fromString("cccccccc-0000-0000-0000-000000000003")
    val now = Instant.parse("2026-06-08T00:00:00Z")

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
            componentRepository = mockk<ComponentRepository>(relaxed = true),
            projectLeadRepository = mockk<ProjectLeadRepository>(relaxed = true),
            clock = clock,
            fieldPermissionResolver = fieldPermissionResolver,
        )

    /** description·environment·impact·assigneeId·labels·customField "secret" 이 모두 채워진 응답 픽스처. */
    fun makeFullResponse(customFields: Map<String, Any?> = mapOf("secret" to "val", "public" to 42)) =
        IssueResponse(
            key = issueKey.value,
            id = issueId,
            projectKey = projectKey,
            summary = "이슈 제목",
            currentStateKey = "open",
            reporterId = actorId,
            version = 1L,
            createdAt = now,
            updatedAt = now,
            typeId = 3L,
            typeKey = "task",
            typeName = "Task",
            description = "상세 설명",
            priority = 3,
            labels = listOf("backend", "urgent"),
            environment = "production",
            impact = 1,
            assigneeId = UUID.fromString("dddddddd-0000-0000-0000-000000000004"),
            customFields = customFields,
        )

    beforeEach {
        clearMocks(repo, permissionResolver, fieldPermissionResolver, answers = false)
        // findByKey 경로 기본 설정
        every { permissionResolver.hasPermission(actorId, IssuePermission.VIEW, IssueScope.Issue(issueKey.value)) } returns true
        every { repo.findByKeyWithType(issueKey) } returns makeFullResponse()
        every { repo.findActiveComponentIdsByIssue(issueId) } returns emptyList()
        every { repo.findProjectIdByKey(projectKey) } returns projectId
    }

    // ── 단건(findByKey) 경로 ────────────────────────────────────────────────────

    describe("findByKey — 필드 마스킹") {

        context("열람 불가 CUSTOM 키가 있을 때 (S1)") {
            beforeEach {
                // "public" 만 허용, "secret" 은 거부
                every {
                    fieldPermissionResolver.visibleFields(
                        actorId,
                        projectId,
                        any(),
                    )
                } answers {
                    val candidates = thirdArg<Set<FieldRef>>()
                    candidates.filter { it.key != "secret" }.toSet()
                }
            }

            it("customFields 에서 secret 키가 제거된다") {
                val result = sut.findByKey(actor, issueKey)
                result.customFields.containsKey("secret").shouldBeFalse()
            }

            it("customFields 에서 public 키는 유지된다") {
                val result = sut.findByKey(actor, issueKey)
                result.customFields.containsKey("public").shouldBeTrue()
            }

            it("restrictedFields 에 secret 이 포함된다") {
                val result = sut.findByKey(actor, issueKey)
                result.restrictedFields shouldContain "secret"
            }
        }

        context("열람 불가 nullable CORE 필드(description)가 있을 때") {
            beforeEach {
                every {
                    fieldPermissionResolver.visibleFields(actorId, projectId, any())
                } answers {
                    val candidates = thirdArg<Set<FieldRef>>()
                    // description 만 제거
                    candidates.filter { !(it.kind == FieldKind.CORE && it.key == "description") }.toSet()
                }
            }

            it("description 이 null 로 마스킹된다") {
                val result = sut.findByKey(actor, issueKey)
                result.description.shouldBeNull()
            }

            it("restrictedFields 에 description 이 포함된다") {
                val result = sut.findByKey(actor, issueKey)
                result.restrictedFields shouldContain "description"
            }
        }

        context("열람 불가 nullable CORE 필드(labels)가 있을 때") {
            beforeEach {
                every {
                    fieldPermissionResolver.visibleFields(actorId, projectId, any())
                } answers {
                    val candidates = thirdArg<Set<FieldRef>>()
                    candidates.filter { !(it.kind == FieldKind.CORE && it.key == "labels") }.toSet()
                }
            }

            it("labels 가 빈 리스트로 마스킹된다") {
                val result = sut.findByKey(actor, issueKey)
                result.labels shouldBe emptyList()
            }

            it("restrictedFields 에 labels 가 포함된다") {
                val result = sut.findByKey(actor, issueKey)
                result.restrictedFields shouldContain "labels"
            }
        }

        context("열람 불가 nullable CORE 필드(impact)가 있을 때") {
            beforeEach {
                every {
                    fieldPermissionResolver.visibleFields(actorId, projectId, any())
                } answers {
                    val candidates = thirdArg<Set<FieldRef>>()
                    candidates.filter { !(it.kind == FieldKind.CORE && it.key == "impact") }.toSet()
                }
            }

            it("impact 가 null 로 마스킹된다") {
                val result = sut.findByKey(actor, issueKey)
                result.impact.shouldBeNull()
            }

            it("impactName 도 null 로 마스킹된다") {
                val result = sut.findByKey(actor, issueKey)
                result.impactName.shouldBeNull()
            }

            it("restrictedFields 에 impact 가 포함된다") {
                val result = sut.findByKey(actor, issueKey)
                result.restrictedFields shouldContain "impact"
            }
        }

        context("열람 불가 nullable CORE 필드(assigneeId)가 있을 때") {
            beforeEach {
                every {
                    fieldPermissionResolver.visibleFields(actorId, projectId, any())
                } answers {
                    val candidates = thirdArg<Set<FieldRef>>()
                    candidates.filter { !(it.kind == FieldKind.CORE && it.key == "assigneeId") }.toSet()
                }
            }

            it("assigneeId 가 null 로 마스킹된다") {
                val result = sut.findByKey(actor, issueKey)
                result.assigneeId.shouldBeNull()
            }

            it("restrictedFields 에 assigneeId 가 포함된다") {
                val result = sut.findByKey(actor, issueKey)
                result.restrictedFields shouldContain "assigneeId"
            }
        }

        context("non-null CORE 필드(summary·priority)는 마스킹 대상이 아닐 때") {
            beforeEach {
                // 모든 필드를 거부 — summary/priority 마스킹 여부 검증
                every {
                    fieldPermissionResolver.visibleFields(actorId, projectId, any())
                } returns emptySet()
            }

            it("summary 는 마스킹되지 않는다") {
                val result = sut.findByKey(actor, issueKey)
                result.summary shouldBe "이슈 제목"
            }

            it("priority 는 마스킹되지 않는다") {
                val result = sut.findByKey(actor, issueKey)
                result.priority shouldBe 3
            }

            it("restrictedFields 에 summary 는 포함되지 않는다") {
                val result = sut.findByKey(actor, issueKey)
                result.restrictedFields shouldNotContain "summary"
            }

            it("restrictedFields 에 priority 는 포함되지 않는다") {
                val result = sut.findByKey(actor, issueKey)
                result.restrictedFields shouldNotContain "priority"
            }
        }

        context("모든 필드가 허용될 때") {
            beforeEach {
                // candidates 를 그대로 반환 (AlwaysAllow 패턴)
                every {
                    fieldPermissionResolver.visibleFields(actorId, projectId, any())
                } answers { thirdArg() }
            }

            it("customFields 가 원본 그대로 유지된다") {
                val result = sut.findByKey(actor, issueKey)
                result.customFields.containsKey("secret").shouldBeTrue()
                result.customFields.containsKey("public").shouldBeTrue()
            }

            it("restrictedFields 가 비어 있다") {
                val result = sut.findByKey(actor, issueKey)
                result.restrictedFields shouldBe emptyList()
            }
        }
    }

    // ── 목록(listIssues) 경로 ────────────────────────────────────────────────────

    describe("listIssues — 필드 마스킹 (페이지당 resolver 1회 호출 EC14)") {

        val pageable = PageRequest.of(0, 20)
        val responses =
            listOf(
                makeFullResponse(mapOf("secret" to "val1", "public" to 1)),
                makeFullResponse(mapOf("secret" to "val2", "public" to 2)),
            )
        val page = PageImpl(responses, pageable, 2L)

        beforeEach {
            every {
                permissionResolver.hasPermission(actorId, IssuePermission.BROWSE, IssueScope.Project(projectKey))
            } returns true
            every { repo.listWithType(projectKey, pageable, actorId, any<IssueSecurityAccess>()) } returns page
        }

        context("열람 불가 CUSTOM 키 secret 이 목록 이슈에 존재할 때") {
            beforeEach {
                every {
                    fieldPermissionResolver.visibleFields(actorId, projectId, any())
                } answers {
                    val candidates = thirdArg<Set<FieldRef>>()
                    candidates.filter { it.key != "secret" }.toSet()
                }
            }

            it("목록의 모든 이슈에서 secret 이 제거된다") {
                val result = sut.listIssues(actor, projectKey, pageable)
                result.content.forEach { it.customFields.containsKey("secret").shouldBeFalse() }
            }

            it("목록의 모든 이슈에서 public 은 유지된다") {
                val result = sut.listIssues(actor, projectKey, pageable)
                result.content.forEach { it.customFields.containsKey("public").shouldBeTrue() }
            }

            it("목록의 모든 이슈에서 restrictedFields 에 secret 이 포함된다") {
                val result = sut.listIssues(actor, projectKey, pageable)
                result.content.forEach { it.restrictedFields shouldContain "secret" }
            }
        }
    }
})
