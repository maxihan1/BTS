// FR-PM-07 Task-8 — 이슈 편집 시 필드 수준 편집 권한 게이트 단위 테스트 (TDD RED)

package com.bts.issue.application

import com.bts.issue.adapter.inbound.rest.IssueResponse
import com.bts.issue.component.repository.ComponentRepository
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.fieldpermission.adapter.AlwaysAllowFieldPermissionResolver
import com.bts.issue.project.repository.ProjectLeadRepository
import com.bts.issue.repository.IssueRepository
import com.bts.issue.resolution.repository.ResolutionRepository
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.permission.FieldKind
import com.bts.shared.permission.FieldPermissionResolver
import com.bts.shared.permission.FieldRef
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.user.UserLookupPort
import com.bts.shared.workflow.WorkflowKeyResolver
import com.bts.shared.workflow.WorkflowTransitionPort
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.registerInstanceFactory
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * FR-PM-07 Task-8 — 이슈 편집 게이트(fieldPermissionResolver.editableFields) 단위 테스트.
 *
 * updateIssue / changeAssignee 호출 시 실제로 값이 바뀌는 필드에 대해
 * [FieldPermissionResolver.editableFields] 를 거치며, editable 에 없는 필드 변경 시 403 을 던진다.
 *
 * 테스트 케이스.
 * - (a) EDIT 불가 코어 필드 값 변경 → 403 (S3/EC5)
 * - (b) no-op(기존값과 동일) 필드 → 통과 (S4/EC4)
 * - (c) EDIT 불가 커스텀 필드 설정 → 403 (EC6)
 * - (d) changeAssignee — assigneeId EDIT 불가 시 → 403
 * - (e) EDIT 가능 필드 변경 → 통과
 */
class IssueFieldEditGateTest : DescribeSpec({

    val repo = mockk<IssueRepository>()
    val issueTypeRepository = mockk<IssueTypeRepository>(relaxed = true)
    val resolutionRepository = mockk<ResolutionRepository>(relaxed = true)
    val eventPublisher = mockk<IssueEventPublisher>(relaxed = true)
    val permissionResolver = mockk<IssuePermissionResolver>()
    val workflowPort = mockk<WorkflowTransitionPort>()
    val workflowKeyResolver = mockk<WorkflowKeyResolver>()
    val userLookupPort = mockk<UserLookupPort>(relaxed = true)
    val componentRepository = mockk<ComponentRepository>(relaxed = true)
    val projectLeadRepository = mockk<ProjectLeadRepository>(relaxed = true)
    val fieldPermissionResolver = mockk<FieldPermissionResolver>()
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
            userLookupPort = userLookupPort,
            componentRepository = componentRepository,
            projectLeadRepository = projectLeadRepository,
            versionRepository = mockk(relaxed = true),
            clock = clock,
            fieldPermissionResolver = fieldPermissionResolver,
        )

    val actor = ActorId(UUID.randomUUID())
    val projectId = UUID.randomUUID()
    val issueKey = IssueKey("BTS-1")
    val existingVersion = 1L

    /** 테스트용 기본 Issue — projectId 고정 */
    @Suppress("LongParameterList")
    fun makeIssue(
        summary: String = "기존 제목",
        description: String? = null,
        priority: Int = 3,
        labels: List<String> = emptyList(),
        environment: String? = null,
        impact: Int? = null,
        assigneeId: ActorId? = null,
        customFields: Map<String, Any?> = emptyMap(),
    ) = Issue(
        id = IssueId(UUID.randomUUID()),
        key = issueKey,
        projectId = projectId,
        summary = summary,
        reporterId = actor,
        currentStateKey = "open",
        version = existingVersion,
        deletedAt = null,
        createdAt = Instant.parse("2026-06-08T00:00:00Z"),
        updatedAt = Instant.parse("2026-06-08T00:00:00Z"),
        typeId = IssueTypeId(3L),
        description = description,
        priority = priority,
        labels = labels,
        environment = environment,
        impact = impact,
        assigneeId = assigneeId,
        customFields = customFields,
    )

    /** 테스트용 기본 IssueResponse */
    fun makeResponse(version: Long = existingVersion) =
        IssueResponse(
            key = issueKey.value,
            id = UUID.randomUUID(),
            projectKey = issueKey.projectPrefix,
            summary = "기존 제목",
            currentStateKey = "open",
            reporterId = actor.value,
            version = version,
            createdAt = Instant.parse("2026-06-08T00:00:00Z"),
            updatedAt = Instant.parse("2026-06-08T00:00:00Z"),
            typeId = 3L,
            typeKey = "task",
            typeName = "Task",
        )

    beforeSpec {
        // IssueKey value class dummy factory 등록 — MockK 1.13.x 가 String dummy 를 IssueKey 생성자에
        // 전달하면 regex validation 실패가 발생한다. 유효한 IssueKey 인스턴스를 팩토리로 등록한다.
        registerInstanceFactory { IssueKey("BTS-1") }
        // IssueKey 파라미터를 받는 메서드(findByKeyWithType, updateFields, updateAssignee 등)는
        // spec 당 1회만 stub 등록하여 beforeEach 재등록 시 MockK recording 충돌을 방지한다.
        // updateAssignee 는 no-op 경로에서 호출되지 않으므로(early-return) 여기서 등록하지 않는다.
        every { repo.findByKeyWithType(issueKey) } returns makeResponse()
        every { repo.updateFields(issueKey, any(), any()) } returns 1
    }

    beforeEach {
        // answers = false: stub 정의를 유지하고 invocation 기록만 초기화
        clearMocks(repo, permissionResolver, fieldPermissionResolver, answers = false)
        // UPDATE 권한 기본 통과
        every {
            permissionResolver.hasPermission(actor.value, IssuePermission.UPDATE, IssueScope.Issue(issueKey.value))
        } returns true
        // findProjectIdByKey — projectId 고정
        every { repo.findProjectIdByKey(issueKey.projectPrefix) } returns projectId
        // withSingleDetail() 내부 findActiveComponentIdsByIssue stub
        every { repo.findActiveComponentIdsByIssue(any()) } returns emptyList()
    }

    // ── (a) EDIT 불가 코어 필드 변경 → 403 ────────────────────────────────────
    describe("(a) EDIT 불가 코어 필드 값 변경 → 403") {

        context("S3/EC5 — description 편집 불가 actor 가 description 을 변경하면") {
            val existing = makeIssue(description = null)
            val request =
                UpdateIssueRequest(
                    summary = null,
                    expectedVersion = existingVersion,
                    description = "새 설명",
                )

            beforeEach {
                every { repo.findByKey(issueKey) } returns existing
                // editableFields: description 은 제외(editable 아님)
                every {
                    fieldPermissionResolver.editableFields(
                        actor.value,
                        projectId,
                        match { it.contains(FieldRef(FieldKind.CORE, "description")) },
                    )
                } returns emptySet()
            }

            it("403 ResponseStatusException 을 던진다") {
                val ex =
                    shouldThrow<ResponseStatusException> {
                        sut.updateIssue(actor, issueKey, request)
                    }
                ex.statusCode shouldBe HttpStatus.FORBIDDEN
            }
        }

        context("S3/EC5 — summary 편집 불가 actor 가 summary 를 변경하면") {
            val existing = makeIssue(summary = "원래 제목")
            val request =
                UpdateIssueRequest(
                    summary = "새 제목",
                    expectedVersion = existingVersion,
                )

            beforeEach {
                every { repo.findByKey(issueKey) } returns existing
                every {
                    fieldPermissionResolver.editableFields(
                        actor.value,
                        projectId,
                        match { it.contains(FieldRef(FieldKind.CORE, "summary")) },
                    )
                } returns emptySet()
            }

            it("403 ResponseStatusException 을 던진다") {
                val ex =
                    shouldThrow<ResponseStatusException> {
                        sut.updateIssue(actor, issueKey, request)
                    }
                ex.statusCode shouldBe HttpStatus.FORBIDDEN
            }
        }

        context("S3/EC5 — priority 편집 불가 actor 가 priority 를 변경하면") {
            val existing = makeIssue(priority = 3)
            val request =
                UpdateIssueRequest(
                    summary = null,
                    expectedVersion = existingVersion,
                    priority = 1,
                )

            beforeEach {
                every { repo.findByKey(issueKey) } returns existing
                every {
                    fieldPermissionResolver.editableFields(
                        actor.value,
                        projectId,
                        any(),
                    )
                } returns emptySet()
            }

            it("403 ResponseStatusException 을 던진다") {
                val ex =
                    shouldThrow<ResponseStatusException> {
                        sut.updateIssue(actor, issueKey, request)
                    }
                ex.statusCode shouldBe HttpStatus.FORBIDDEN
            }
        }
    }

    // ── (b) no-op(기존값과 동일) → 통과 ─────────────────────────────────────
    describe("(b) no-op — 기존값과 동일한 필드 변경 요청 → editableFields 호출 없이 통과") {

        context("S4/EC4 — summary 동일값 PATCH") {
            val existing = makeIssue(summary = "기존 제목")
            val request =
                UpdateIssueRequest(
                    summary = "기존 제목",
                    expectedVersion = existingVersion,
                )

            beforeEach {
                every { repo.findByKey(issueKey) } returns existing
                // editableFields 는 candidates 가 비어있을 때 호출될 수 있으므로 emptySet 반환
                every { fieldPermissionResolver.editableFields(any(), any(), emptySet()) } returns emptySet()
                // findByKeyWithType 는 beforeSpec 에서 1회 등록 (IssueKey value class MockK 재등록 충돌 방지)
            }

            it("403 없이 정상 반환한다") {
                // 변경 필드가 없으면 게이트를 통과하고(또는 게이트 자체를 건너뛰고) no-op 반환한다
                val result = sut.updateIssue(actor, issueKey, request)
                result.summary shouldBe "기존 제목"
            }
        }
    }

    // ── (c) EDIT 불가 커스텀 필드 설정 → 403 ─────────────────────────────────
    describe("(c) EDIT 불가 커스텀 필드 설정 → 403") {

        context("EC6 — 커스텀 필드 'cf_secret' 편집 불가 actor 가 값을 변경하면") {
            val existing = makeIssue(customFields = mapOf("cf_secret" to "old"))
            val request =
                UpdateIssueRequest(
                    summary = null,
                    expectedVersion = existingVersion,
                    customFields = mapOf("cf_secret" to "new"),
                )

            beforeEach {
                every { repo.findByKey(issueKey) } returns existing
                every {
                    fieldPermissionResolver.editableFields(
                        actor.value,
                        projectId,
                        match { it.contains(FieldRef(FieldKind.CUSTOM, "cf_secret")) },
                    )
                } returns emptySet()
            }

            it("403 ResponseStatusException 을 던진다") {
                val ex =
                    shouldThrow<ResponseStatusException> {
                        sut.updateIssue(actor, issueKey, request)
                    }
                ex.statusCode shouldBe HttpStatus.FORBIDDEN
            }
        }

        context("EC6 — 커스텀 필드 'cf_public' 은 editable, 'cf_secret' 은 not editable — 복합") {
            val existing = makeIssue(customFields = mapOf("cf_public" to "old", "cf_secret" to "old"))
            val request =
                UpdateIssueRequest(
                    summary = null,
                    expectedVersion = existingVersion,
                    customFields = mapOf("cf_public" to "new", "cf_secret" to "new"),
                )

            beforeEach {
                every { repo.findByKey(issueKey) } returns existing
                every {
                    fieldPermissionResolver.editableFields(
                        actor.value,
                        projectId,
                        match {
                            it.contains(FieldRef(FieldKind.CUSTOM, "cf_public")) &&
                                it.contains(FieldRef(FieldKind.CUSTOM, "cf_secret"))
                        },
                    )
                } returns setOf(FieldRef(FieldKind.CUSTOM, "cf_public")) // cf_secret 제외
            }

            it("403 ResponseStatusException 을 던진다") {
                val ex =
                    shouldThrow<ResponseStatusException> {
                        sut.updateIssue(actor, issueKey, request)
                    }
                ex.statusCode shouldBe HttpStatus.FORBIDDEN
            }
        }
    }

    // ── (d) changeAssignee — assigneeId EDIT 불가 → 403 ─────────────────────
    describe("(d) changeAssignee — assigneeId EDIT 불가 actor → 403") {

        context("assigneeId 필드가 editable 목록에 없을 때") {
            val newAssigneeId = UUID.randomUUID()
            val existing = makeIssue(assigneeId = null)
            val request =
                AppChangeAssigneeRequest(
                    assigneeId = newAssigneeId,
                    expectedVersion = existingVersion,
                )

            beforeEach {
                every { repo.findByKey(issueKey) } returns existing
                every { userLookupPort.exists(newAssigneeId) } returns true
                every {
                    fieldPermissionResolver.editableFields(
                        actor.value,
                        projectId,
                        match { it.contains(FieldRef(FieldKind.CORE, "assigneeId")) },
                    )
                } returns emptySet()
            }

            it("403 ResponseStatusException 을 던진다") {
                val ex =
                    shouldThrow<ResponseStatusException> {
                        sut.changeAssignee(actor, issueKey, request)
                    }
                ex.statusCode shouldBe HttpStatus.FORBIDDEN
            }
        }

        context("assigneeId 동일값(no-op) 이면 게이트 통과") {
            val existingAssigneeId = UUID.randomUUID()
            val existing = makeIssue(assigneeId = ActorId(existingAssigneeId))
            val request =
                AppChangeAssigneeRequest(
                    assigneeId = existingAssigneeId,
                    expectedVersion = existingVersion,
                )

            beforeEach {
                every { repo.findByKey(issueKey) } returns existing
                // no-op: assigneeChanged = false → early-return 경로.
                // assertEditableOrForbidden / userLookupPort / updateAssignee 모두 건너뜀.
                // findByKeyWithType 는 beforeSpec 에서 1회 등록.
            }

            it("403 없이 정상 반환한다") {
                sut.changeAssignee(actor, issueKey, request)
            }
        }
    }

    // ── (e) EDIT 가능 필드 변경 → 통과 ──────────────────────────────────────
    describe("(e) EDIT 가능 필드 변경 → 정상 통과") {

        context("description 이 editable 일 때 변경하면") {
            val existing = makeIssue(description = null)
            val request =
                UpdateIssueRequest(
                    summary = null,
                    expectedVersion = existingVersion,
                    description = "새 설명",
                )

            beforeEach {
                every { repo.findByKey(issueKey) } returns existing
                every {
                    fieldPermissionResolver.editableFields(any(), any(), any())
                } returns setOf(FieldRef(FieldKind.CORE, "description"))
                // updateFields, findByKeyWithType 는 beforeSpec 에서 1회 등록 (IssueKey value class MockK 재등록 충돌 방지)
            }

            it("403 없이 정상 반환한다") {
                sut.updateIssue(actor, issueKey, request)
            }
        }

        context("커스텀 필드 'cf_public' 이 editable 일 때 변경하면") {
            val existing = makeIssue(customFields = mapOf("cf_public" to "old"))
            val request =
                UpdateIssueRequest(
                    summary = null,
                    expectedVersion = existingVersion,
                    customFields = mapOf("cf_public" to "new"),
                )
            beforeEach {
                every { repo.findByKey(issueKey) } returns existing
                every {
                    fieldPermissionResolver.editableFields(any(), any(), any())
                } returns setOf(FieldRef(FieldKind.CUSTOM, "cf_public"))
                // updateFields, findByKeyWithType 는 beforeSpec 에서 1회 등록 (IssueKey value class MockK 재등록 충돌 방지)
            }

            it("403 없이 정상 반환한다") {
                sut.updateIssue(actor, issueKey, request)
            }
        }
    }

    // ── fail-open 방지 — 기본 생성자(fieldPermissionResolver 미주입) ─────────────

    describe("fail-open 방지 — fieldPermissionResolver 기본값은 AlwaysAllow (non-null)") {

        /**
         * fieldPermissionResolver 를 명시적으로 전달하지 않은 기본 생성자 sut.
         * 현재 코드(?: return null-skip)라면 null 이므로 이 테스트가 실패해야 한다(RED).
         * 수정 후(AlwaysAllow 기본값)에는 통과한다(GREEN).
         */
        val defaultSut =
            IssueApplicationService(
                repo = repo,
                issueTypeRepository = issueTypeRepository,
                resolutionRepository = resolutionRepository,
                eventPublisher = eventPublisher,
                permissionResolver = permissionResolver,
                workflowPort = workflowPort,
                workflowKeyResolver = workflowKeyResolver,
                userLookupPort = userLookupPort,
                componentRepository = componentRepository,
                projectLeadRepository = projectLeadRepository,
                versionRepository = mockk(relaxed = true),
                clock = clock,
                // fieldPermissionResolver 미전달 — 기본값 사용
            )

        it("기본값 fieldPermissionResolver 는 AlwaysAllowFieldPermissionResolver 인스턴스여야 한다(non-null, null-skip 금지)") {
            val field =
                IssueApplicationService::class.java.declaredFields
                    .first { it.name == "fieldPermissionResolver" }
            field.isAccessible = true
            val resolver = field.get(defaultSut)
            // null 이면 RED (현재 코드), AlwaysAllowFieldPermissionResolver 이면 GREEN (수정 후)
            resolver.shouldBeInstanceOf<AlwaysAllowFieldPermissionResolver>()
        }

        it("기본 resolver 로 assertEditableOrForbidden 경로가 실행돼도 NPE 없이 통과한다(모든 필드 allow)") {
            val existing = makeIssue(description = null)
            val request =
                UpdateIssueRequest(
                    summary = null,
                    expectedVersion = existingVersion,
                    description = "새 설명",
                )
            every { repo.findByKey(issueKey) } returns existing
            every { repo.findProjectIdByKey(issueKey.projectPrefix) } returns projectId
            every {
                permissionResolver.hasPermission(actor.value, IssuePermission.UPDATE, IssueScope.Issue(issueKey.value))
            } returns true
            every { repo.findActiveComponentIdsByIssue(any()) } returns emptyList()

            // AlwaysAllow 는 모든 필드를 editable 로 반환하므로 403 없이 통과해야 한다
            defaultSut.updateIssue(actor, issueKey, request)
        }
    }
})
