// IssueApplicationService.updateIssue 단위 테스트 — MockK, TDD RED 단계

package com.bts.issue.application

import com.bts.issue.adapter.inbound.rest.IssueResponse
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueNotFoundException
import com.bts.issue.domain.IssueVersionConflictException
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.event.IssueUpdated
import com.bts.issue.port.outbound.IssuePermission
import com.bts.issue.port.outbound.IssuePermissionResolver
import com.bts.issue.port.outbound.IssueScope
import com.bts.issue.repository.IssueFieldPatch
import com.bts.issue.repository.IssueRepository
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.user.UserLookupPort
import com.bts.shared.workflow.WorkflowKeyResolver
import com.bts.shared.workflow.WorkflowTransitionPort
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class IssueApplicationServiceUpdateTest : DescribeSpec({

    val repo = mockk<IssueRepository>()
    val issueTypeRepository = mockk<IssueTypeRepository>(relaxed = true)
    val eventPublisher = mockk<IssueEventPublisher>()
    val permissionResolver = mockk<IssuePermissionResolver>()
    val workflowPort = mockk<WorkflowTransitionPort>()
    val workflowKeyResolver = mockk<WorkflowKeyResolver>()
    val userLookupPort = mockk<UserLookupPort>(relaxed = true)
    val clock = Clock.fixed(Instant.parse("2026-05-24T00:00:00Z"), ZoneOffset.UTC)

    val sut =
        IssueApplicationService(
            repo = repo,
            issueTypeRepository = issueTypeRepository,
            eventPublisher = eventPublisher,
            permissionResolver = permissionResolver,
            workflowPort = workflowPort,
            workflowKeyResolver = workflowKeyResolver,
            userLookupPort = userLookupPort,
            clock = clock,
        )

    val actor = ActorId(UUID.randomUUID())
    val issueKey = IssueKey("BTS-1")
    val existingVersion = 1L

    fun makeIssue(
        summary: String = "원래",
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
        createdAt = Instant.parse("2026-05-24T00:00:00Z"),
        updatedAt = Instant.parse("2026-05-24T00:00:00Z"),
        typeId = IssueTypeId(3L),
    )

    fun makeResponse(
        summary: String = "원래",
        version: Long = existingVersion,
    ) = IssueResponse(
        key = issueKey.value,
        id = UUID.randomUUID(),
        projectKey = issueKey.projectPrefix,
        summary = summary,
        currentStateKey = "open",
        reporterId = actor.value,
        version = version,
        createdAt = Instant.parse("2026-05-24T00:00:00Z"),
        updatedAt = Instant.parse("2026-05-24T00:00:00Z"),
        typeId = 3L,
        typeKey = "task",
        typeName = "Task",
    )

    beforeEach {
        clearMocks(repo, eventPublisher, permissionResolver, answers = false)
    }

    describe("updateIssue") {

        // T7-1: summary=null 이면 updateFields·eventPublisher 모두 호출하지 않고 기존 이슈를 그대로 반환한다
        context("T7-1 — summary null (RFC 7396 JSON Merge Patch: 필드 생략)") {
            val request = UpdateIssueRequest(summary = null, expectedVersion = existingVersion)
            val existingIssue = makeIssue(summary = "원래")
            val existingResponse = makeResponse(summary = "원래")

            beforeEach {
                every {
                    permissionResolver.hasPermission(actor, IssuePermission.UPDATE, IssueScope.Issue(issueKey.value))
                } returns true
                every { repo.findByKey(issueKey) } returns existingIssue
                every { repo.findByKeyWithType(issueKey) } returns existingResponse
            }

            it("updateFields 가 호출되지 않는다 (repo 가 non-relaxed mock 이라 호출 시 MockK 에러로 자동 실패)") {
                // IssueRepository 는 non-relaxed mock — updateFields stub 없으면 호출 시 즉시 에러
                // 이 테스트가 정상 완료 = updateFields 미호출 증명
                sut.updateIssue(actor, issueKey, request)
            }

            it("eventPublisher.publish 가 호출되지 않는다") {
                sut.updateIssue(actor, issueKey, request)
                verify(exactly = 0) { eventPublisher.publish(any()) }
            }

            it("기존 이슈의 summary 와 version 을 그대로 반환한다") {
                val result = sut.updateIssue(actor, issueKey, request)
                result.summary shouldBe "원래"
                result.version shouldBe existingVersion
            }
        }

        // T7-2: summary 가 새 값이면 updateFields 1회 + IssueUpdated(fields={"summary"}) 1회 발행
        context("T7-2 — summary 변경 (기존값과 다른 새 값)") {
            val request = UpdateIssueRequest(summary = "새 제목", expectedVersion = existingVersion)
            val existingIssue = makeIssue(summary = "원래")
            val updatedResponse = makeResponse(summary = "새 제목", version = existingVersion + 1)

            beforeEach {
                every {
                    permissionResolver.hasPermission(actor, IssuePermission.UPDATE, IssueScope.Issue(issueKey.value))
                } returns true
                every { repo.findByKey(issueKey) } returns existingIssue
                every { repo.updateFields(issueKey, IssueFieldPatch(summary = "새 제목"), existingVersion) } returns 1
                every { repo.findByKeyWithType(issueKey) } returns updatedResponse
                every { eventPublisher.publish(any()) } returns Unit
            }

            it("updateFields 가 1회 호출된다") {
                sut.updateIssue(actor, issueKey, request)
                verify(exactly = 1) { repo.updateFields(issueKey, IssueFieldPatch(summary = "새 제목"), existingVersion) }
            }

            it("IssueUpdated(fields={summary}) 이벤트가 1회 발행된다") {
                sut.updateIssue(actor, issueKey, request)
                verify(exactly = 1) {
                    eventPublisher.publish(
                        match { it is IssueUpdated && it.fields == setOf("summary") },
                    )
                }
            }

            it("응답 summary='새 제목', version=2 를 반환한다") {
                val result = sut.updateIssue(actor, issueKey, request)
                result.summary shouldBe "새 제목"
                result.version shouldBe existingVersion + 1
            }
        }

        // T7-3: summary 가 기존값과 동일하면 updateFields·eventPublisher 모두 호출하지 않는다
        context("T7-3 — summary 동일값 (변경 없음)") {
            val request = UpdateIssueRequest(summary = "원래", expectedVersion = existingVersion)
            val existingIssue = makeIssue(summary = "원래")
            val existingResponse = makeResponse(summary = "원래")

            beforeEach {
                every {
                    permissionResolver.hasPermission(actor, IssuePermission.UPDATE, IssueScope.Issue(issueKey.value))
                } returns true
                every { repo.findByKey(issueKey) } returns existingIssue
                every { repo.findByKeyWithType(issueKey) } returns existingResponse
            }

            it("updateFields 가 호출되지 않는다 (repo 가 non-relaxed mock 이라 호출 시 MockK 에러로 자동 실패)") {
                // IssueRepository 는 non-relaxed mock — updateFields stub 없으면 호출 시 즉시 에러
                // 이 테스트가 정상 완료 = updateFields 미호출 증명
                sut.updateIssue(actor, issueKey, request)
            }

            it("eventPublisher.publish 가 호출되지 않는다 (changedFields empty)") {
                sut.updateIssue(actor, issueKey, request)
                verify(exactly = 0) { eventPublisher.publish(any()) }
            }
        }

        context("version 불일치 — 낙관락 충돌") {
            val request = UpdateIssueRequest(summary = "새 제목", expectedVersion = existingVersion)

            beforeEach {
                every {
                    permissionResolver.hasPermission(actor, IssuePermission.UPDATE, IssueScope.Issue(issueKey.value))
                } returns true
                every { repo.findByKey(issueKey) } returns makeIssue(summary = "원래")
                every { repo.updateFields(issueKey, IssueFieldPatch(summary = "새 제목"), existingVersion) } returns 0
            }

            it("IssueVersionConflictException 을 던진다") {
                shouldThrow<IssueVersionConflictException> {
                    sut.updateIssue(actor, issueKey, request)
                }
            }

            it("이벤트가 발행되지 않는다") {
                runCatching { sut.updateIssue(actor, issueKey, request) }
                verify(exactly = 0) { eventPublisher.publish(any()) }
            }
        }

        context("이슈 미존재") {
            val request = UpdateIssueRequest(summary = "새 제목", expectedVersion = existingVersion)

            beforeEach {
                every {
                    permissionResolver.hasPermission(actor, IssuePermission.UPDATE, IssueScope.Issue(issueKey.value))
                } returns true
                every { repo.findByKey(issueKey) } returns null
            }

            it("IssueNotFoundException 을 던진다") {
                shouldThrow<IssueNotFoundException> {
                    sut.updateIssue(actor, issueKey, request)
                }
            }
        }

        context("권한 없을 때") {
            val request = UpdateIssueRequest(summary = "새 제목", expectedVersion = existingVersion)

            beforeEach {
                every {
                    permissionResolver.hasPermission(actor, IssuePermission.UPDATE, IssueScope.Issue(issueKey.value))
                } returns false
            }

            it("IssueAccessDeniedException 을 던진다") {
                shouldThrow<IssueAccessDeniedException> {
                    sut.updateIssue(actor, issueKey, request)
                }
            }

            it("repo 및 eventPublisher 가 호출되지 않는다") {
                runCatching { sut.updateIssue(actor, issueKey, request) }
                verify(exactly = 0) { repo.findByKey(issueKey) }
                verify(exactly = 0) { eventPublisher.publish(any()) }
            }
        }

        // T7-D6-1: typeId 변경 happy path — 유효 활성 typeId 로 update → version+1, 응답에 변경된 typeKey/typeName 반영
        context("T7-D6-1 — typeId 변경 (유효 활성 타입으로 변경)") {
            val newTypeId = IssueTypeId(5L)
            val request = UpdateIssueRequest(summary = null, typeId = newTypeId, expectedVersion = existingVersion)
            val existingIssue = makeIssue()
            val updatedResponse =
                makeResponse(version = existingVersion + 1).copy(
                    typeId = newTypeId.value,
                    typeKey = "bug",
                    typeName = "Bug",
                )

            beforeEach {
                every {
                    permissionResolver.hasPermission(actor, IssuePermission.UPDATE, IssueScope.Issue(issueKey.value))
                } returns true
                every { repo.findByKey(issueKey) } returns existingIssue
                every { issueTypeRepository.findById(newTypeId) } returns
                    com.bts.issue.type.domain.IssueType.BUG.copy(
                        id = newTypeId,
                    )
                every { repo.updateFields(issueKey, IssueFieldPatch(typeId = newTypeId), existingVersion) } returns 1
                every { repo.findByKeyWithType(issueKey) } returns updatedResponse
                every { eventPublisher.publish(any()) } returns Unit
            }

            it("updateFields 가 1회 호출된다") {
                sut.updateIssue(actor, issueKey, request)
                verify(exactly = 1) {
                    repo.updateFields(issueKey, IssueFieldPatch(typeId = newTypeId), existingVersion)
                }
            }

            it("IssueUpdated(fields={typeId}) 이벤트가 1회 발행된다") {
                sut.updateIssue(actor, issueKey, request)
                verify(exactly = 1) {
                    eventPublisher.publish(
                        match { it is IssueUpdated && it.fields == setOf("typeId") },
                    )
                }
            }

            it("응답에 변경된 typeKey='bug', typeName='Bug', version=2 를 반환한다") {
                val result = sut.updateIssue(actor, issueKey, request)
                result.typeKey shouldBe "bug"
                result.typeName shouldBe "Bug"
                result.version shouldBe existingVersion + 1
            }
        }

        // T7-D6-2: 존재하지 않거나 soft-deleted typeId → IssueTypeNotFoundException
        context("T7-D6-2 — 존재하지 않는 typeId") {
            val invalidTypeId = IssueTypeId(999L)
            val request = UpdateIssueRequest(summary = null, typeId = invalidTypeId, expectedVersion = existingVersion)
            val existingIssue = makeIssue()

            beforeEach {
                every {
                    permissionResolver.hasPermission(actor, IssuePermission.UPDATE, IssueScope.Issue(issueKey.value))
                } returns true
                every { repo.findByKey(issueKey) } returns existingIssue
                every { issueTypeRepository.findById(invalidTypeId) } returns null
            }

            it("IssueTypeNotFoundException 을 던진다") {
                shouldThrow<com.bts.issue.type.domain.IssueTypeNotFoundException> {
                    sut.updateIssue(actor, issueKey, request)
                }
            }

            it("updateFields 가 호출되지 않는다 (IssueTypeNotFoundException 이 먼저 발생하므로)") {
                // shouldThrow 가 예외를 잡아 검증 — IssueTypeNotFoundException 발생 후 updateFields 미도달
                shouldThrow<com.bts.issue.type.domain.IssueTypeNotFoundException> {
                    sut.updateIssue(actor, issueKey, request)
                }
                // repo 는 non-relaxed mock — updateFields stub 없어서 호출 시 에러. 에러 없이 완료 = 미호출.
            }

            it("이벤트가 발행되지 않는다") {
                runCatching { sut.updateIssue(actor, issueKey, request) }
                verify(exactly = 0) { eventPublisher.publish(any()) }
            }
        }

        // T7-D6-3: typeId=null + summary 변경 → 타입 변경 없음 (merge-patch 시맨틱)
        context("T7-D6-3 — typeId=null + summary 변경 (타입 변경 없음)") {
            val request = UpdateIssueRequest(summary = "새 제목", typeId = null, expectedVersion = existingVersion)
            val existingIssue = makeIssue(summary = "원래")
            val updatedResponse = makeResponse(summary = "새 제목", version = existingVersion + 1)

            beforeEach {
                every {
                    permissionResolver.hasPermission(actor, IssuePermission.UPDATE, IssueScope.Issue(issueKey.value))
                } returns true
                every { repo.findByKey(issueKey) } returns existingIssue
                every { repo.updateFields(issueKey, IssueFieldPatch(summary = "새 제목"), existingVersion) } returns 1
                every { repo.findByKeyWithType(issueKey) } returns updatedResponse
                every { eventPublisher.publish(any()) } returns Unit
                // issueTypeRepository 는 relaxed=true mock — findById 미호출 시 자동으로 null 반환.
                // typeId=null 이면 findById 호출 자체가 없어야 함.
                // 만약 호출되면 null 반환 → IssueTypeNotFoundException → 테스트 실패로 간접 검증.
            }

            it("issueTypeRepository.findById 가 호출되지 않는다 (typeId=null 이면 타입 검증 스킵)") {
                // typeId=null 이면 타입 검증을 건너뜀. 정상 완료 = findById 미호출 증명.
                // relaxed mock findById 가 호출됐다면 null 반환 → IssueTypeNotFoundException → 테스트 실패.
                sut.updateIssue(actor, issueKey, request)
            }

            it("IssueUpdated(fields={summary}) 이벤트가 발행된다") {
                sut.updateIssue(actor, issueKey, request)
                verify(exactly = 1) {
                    eventPublisher.publish(
                        match { it is IssueUpdated && it.fields == setOf("summary") },
                    )
                }
            }
        }

        // T7-D6-4: summary=null + typeId=non-null → 타입만 변경, no-op 아님 (회귀 가드)
        context("T7-D6-4 — summary=null + typeId=non-null (타입만 변경)") {
            val newTypeId = IssueTypeId(5L)
            val request = UpdateIssueRequest(summary = null, typeId = newTypeId, expectedVersion = existingVersion)
            // 기존 이슈의 typeId 는 3L (task). newTypeId=5L(bug) 로 변경 요청.
            val existingIssue = makeIssue()
            val updatedResponse =
                makeResponse(version = existingVersion + 1).copy(
                    typeId = newTypeId.value,
                    typeKey = "bug",
                    typeName = "Bug",
                )

            beforeEach {
                every {
                    permissionResolver.hasPermission(actor, IssuePermission.UPDATE, IssueScope.Issue(issueKey.value))
                } returns true
                every { repo.findByKey(issueKey) } returns existingIssue
                every { issueTypeRepository.findById(newTypeId) } returns
                    com.bts.issue.type.domain.IssueType.BUG.copy(
                        id = newTypeId,
                    )
                every { repo.updateFields(issueKey, IssueFieldPatch(typeId = newTypeId), existingVersion) } returns 1
                every { repo.findByKeyWithType(issueKey) } returns updatedResponse
                every { eventPublisher.publish(any()) } returns Unit
            }

            it("updateFields 가 호출된다 (no-op 이 아님)") {
                sut.updateIssue(actor, issueKey, request)
                verify(exactly = 1) {
                    repo.updateFields(issueKey, IssueFieldPatch(typeId = newTypeId), existingVersion)
                }
            }

            it("IssueUpdated(fields={typeId}) 이벤트가 발행된다") {
                sut.updateIssue(actor, issueKey, request)
                verify(exactly = 1) {
                    eventPublisher.publish(
                        match { it is IssueUpdated && it.fields == setOf("typeId") },
                    )
                }
            }
        }
    }
})
