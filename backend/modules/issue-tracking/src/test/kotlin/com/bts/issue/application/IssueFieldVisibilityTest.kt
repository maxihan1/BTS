// IssueApplicationService 필드 수준 마스킹 단위 테스트 — TDD RED (FR-PM-07 Task-7)

package com.bts.issue.application

import com.bts.issue.adapter.inbound.rest.IssueResponse
import com.bts.issue.component.repository.ComponentRepository
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueKey
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.fieldpermission.adapter.AlwaysAllowFieldPermissionResolver
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
import io.kotest.matchers.types.shouldBeInstanceOf
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
        // editableFields 기본 stub — 모든 candidates 를 그대로 반환(전 필드 편집 허용).
        // 개별 context 에서 필요한 경우 재정의한다.
        every {
            fieldPermissionResolver.editableFields(actorId, projectId, any())
        } answers { thirdArg() }
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

    // ── noneditableFields — 보이지만 편집 불가한 필드 목록 (FR-PM-07 Task-1) ──────

    describe("findByKey — noneditableFields") {

        context("visible 이지만 editable 이 아닌 필드가 있을 때") {
            beforeEach {
                // 모든 필드 visible
                every {
                    fieldPermissionResolver.visibleFields(actorId, projectId, any())
                } answers { thirdArg() }
                // "secret" 커스텀 필드만 editable 에서 제외
                every {
                    fieldPermissionResolver.editableFields(actorId, projectId, any())
                } answers {
                    val candidates = thirdArg<Set<FieldRef>>()
                    candidates.filter { it.key != "secret" }.toSet()
                }
            }

            it("noneditableFields 에 secret 이 포함된다") {
                val result = sut.findByKey(actor, issueKey)
                result.noneditableFields shouldContain "secret"
            }

            it("noneditableFields 에 editable 필드는 포함되지 않는다") {
                val result = sut.findByKey(actor, issueKey)
                result.noneditableFields shouldNotContain "public"
            }

            it("restrictedFields(숨김)에 포함된 키는 noneditableFields 에서 제외된다(비중복)") {
                // 이 케이스: 모두 visible 이므로 restrictedFields 는 비어 있음 — 중복 없음 확인
                val result = sut.findByKey(actor, issueKey)
                result.restrictedFields shouldBe emptyList()
                // secret 은 visible·noneditable 이므로 noneditableFields 에 있고 restrictedFields 에 없어야 한다
                result.noneditableFields shouldContain "secret"
            }
        }

        context("visible 이지만 editable 이 아닌 CORE 필드(description)가 있을 때") {
            beforeEach {
                every {
                    fieldPermissionResolver.visibleFields(actorId, projectId, any())
                } answers { thirdArg() }
                // description 만 편집 불가
                every {
                    fieldPermissionResolver.editableFields(actorId, projectId, any())
                } answers {
                    val candidates = thirdArg<Set<FieldRef>>()
                    candidates.filter { !(it.kind == FieldKind.CORE && it.key == "description") }.toSet()
                }
            }

            it("noneditableFields 에 description 이 포함된다") {
                val result = sut.findByKey(actor, issueKey)
                result.noneditableFields shouldContain "description"
            }

            it("description 값은 마스킹되지 않는다 (visible 이므로)") {
                val result = sut.findByKey(actor, issueKey)
                result.description shouldBe "상세 설명"
            }
        }

        context("restrictedFields 에 있는 키는 noneditableFields 에 중복 수록되지 않는다") {
            beforeEach {
                // description 은 visible 에서 제거 (restricted)
                every {
                    fieldPermissionResolver.visibleFields(actorId, projectId, any())
                } answers {
                    val candidates = thirdArg<Set<FieldRef>>()
                    candidates.filter { !(it.kind == FieldKind.CORE && it.key == "description") }.toSet()
                }
                // 모든 visible 필드를 editable 로 반환 (description 은 visible 에 없으므로 candidates 에 없음)
                every {
                    fieldPermissionResolver.editableFields(actorId, projectId, any())
                } answers { thirdArg() }
            }

            it("description 은 restrictedFields 에만 있고 noneditableFields 에는 없다") {
                val result = sut.findByKey(actor, issueKey)
                result.restrictedFields shouldContain "description"
                result.noneditableFields shouldNotContain "description"
            }
        }

        context("모든 필드가 editable 일 때") {
            beforeEach {
                every {
                    fieldPermissionResolver.visibleFields(actorId, projectId, any())
                } answers { thirdArg() }
                every {
                    fieldPermissionResolver.editableFields(actorId, projectId, any())
                } answers { thirdArg() }
            }

            it("noneditableFields 가 비어 있다") {
                val result = sut.findByKey(actor, issueKey)
                result.noneditableFields shouldBe emptyList()
            }
        }
    }

    describe("listIssues — noneditableFields (목록 경로)") {

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

        context("목록에서 visible 이지만 editable 이 아닌 필드가 있을 때") {
            beforeEach {
                every {
                    fieldPermissionResolver.visibleFields(actorId, projectId, any())
                } answers { thirdArg() }
                every {
                    fieldPermissionResolver.editableFields(actorId, projectId, any())
                } answers {
                    val candidates = thirdArg<Set<FieldRef>>()
                    candidates.filter { it.key != "secret" }.toSet()
                }
            }

            it("목록의 모든 이슈에서 noneditableFields 에 secret 이 포함된다") {
                val result = sut.listIssues(actor, projectKey, pageable)
                result.content.forEach { it.noneditableFields shouldContain "secret" }
            }

            it("목록의 모든 이슈에서 restrictedFields 는 비어 있다 (모두 visible)") {
                val result = sut.listIssues(actor, projectKey, pageable)
                result.content.forEach { it.restrictedFields shouldBe emptyList() }
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
                componentRepository = mockk<ComponentRepository>(relaxed = true),
                projectLeadRepository = mockk<ProjectLeadRepository>(relaxed = true),
                clock = clock,
                // fieldPermissionResolver 미전달 — 기본값 사용
            )

        it("기본값 fieldPermissionResolver 는 AlwaysAllowFieldPermissionResolver 인스턴스여야 한다(non-null, null-skip 금지)") {
            // reflection 으로 private 필드 추출하여 타입 검증
            val field =
                IssueApplicationService::class.java.declaredFields
                    .first { it.name == "fieldPermissionResolver" }
            field.isAccessible = true
            val resolver = field.get(defaultSut)
            // null 이면 RED (현재 코드), AlwaysAllowFieldPermissionResolver 이면 GREEN (수정 후)
            resolver.shouldBeInstanceOf<AlwaysAllowFieldPermissionResolver>()
        }

        it("기본 resolver 로 findByKey 를 호출해도 NPE 없이 전 필드가 노출된다") {
            every { permissionResolver.hasPermission(actorId, IssuePermission.VIEW, IssueScope.Issue(issueKey.value)) } returns true
            every { repo.findByKeyWithType(issueKey) } returns makeFullResponse()
            every { repo.findActiveComponentIdsByIssue(issueId) } returns emptyList()
            every { repo.findProjectIdByKey(projectKey) } returns projectId

            val result = defaultSut.findByKey(actor, issueKey)
            // AlwaysAllow 는 모든 필드를 허용하므로 원본 그대로여야 한다
            result.customFields.containsKey("secret").shouldBeTrue()
            result.customFields.containsKey("public").shouldBeTrue()
            result.restrictedFields shouldBe emptyList()
        }
    }
})
