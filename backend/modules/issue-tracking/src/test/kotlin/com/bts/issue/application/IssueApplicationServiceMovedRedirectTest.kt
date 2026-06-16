// 이슈 이동 후 옛 키 조회 시 IssueMovedException 발생 단위 테스트 (FR-MV-01 Task 4 TDD)

package com.bts.issue.application

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueMovedException
import com.bts.issue.domain.IssueNotFoundException
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.repository.IssueKeyRedirectRepository
import com.bts.issue.repository.IssueRepository
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
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

/**
 * IssueApplicationService.findByKey 의 이슈 이동 리다이렉트 분기 단위 테스트.
 *
 * 검증 대상.
 * - 옛 키로 조회 시 issues 에 없고 redirect 체인이 존재하면 [IssueMovedException] 을 던진다.
 * - redirect 도 없으면 기존 [IssueNotFoundException] 을 던진다.
 * - [IssueMovedException.newKey] 는 findCurrentKey 가 반환한 최종 키여야 한다.
 */
class IssueApplicationServiceMovedRedirectTest : DescribeSpec({

    val repo = mockk<IssueRepository>()
    val issueTypeRepository = mockk<IssueTypeRepository>(relaxed = true)
    val resolutionRepository = mockk<com.bts.issue.resolution.repository.ResolutionRepository>(relaxed = true)
    val eventPublisher = mockk<IssueEventPublisher>(relaxed = true)
    val permissionResolver = mockk<IssuePermissionResolver>()
    val workflowPort = mockk<WorkflowTransitionPort>(relaxed = true)
    val workflowKeyResolver = mockk<WorkflowKeyResolver>(relaxed = true)
    val userLookupPort = mockk<UserLookupPort>(relaxed = true)
    val redirectRepository = mockk<IssueKeyRedirectRepository>()
    val clock = Clock.fixed(Instant.parse("2026-06-16T00:00:00Z"), ZoneOffset.UTC)

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
            keyRedirectRepository = redirectRepository,
        )

    val actor = ActorId(UUID.randomUUID())
    val oldKey = IssueKey("BTS-42")
    val newKey = IssueKey("NEW-15")

    beforeEach {
        clearMocks(repo, permissionResolver, redirectRepository, answers = false)
    }

    describe("findByKey — 이슈 이동 리다이렉트 분기") {

        context("VIEW 권한이 있고 이슈가 issues 에 없으며 redirect 체인이 존재할 때") {
            beforeEach {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.VIEW,
                        IssueScope.Issue(oldKey.value),
                    )
                } returns true
                every { repo.findByKeyWithType(oldKey) } returns null
                every { redirectRepository.findCurrentKey(oldKey) } returns newKey
            }

            it("IssueMovedException 을 던진다") {
                shouldThrow<IssueMovedException> {
                    sut.findByKey(actor, oldKey)
                }
            }

            it("IssueMovedException.newKey 가 최종 키와 일치한다") {
                val ex =
                    shouldThrow<IssueMovedException> {
                        sut.findByKey(actor, oldKey)
                    }
                ex.newKey shouldBe newKey.value
            }

            it("redirectRepository.findCurrentKey 가 호출된다") {
                runCatching { sut.findByKey(actor, oldKey) }
                verify(exactly = 1) { redirectRepository.findCurrentKey(oldKey) }
            }
        }

        context("VIEW 권한이 있고 이슈가 issues 에 없으며 redirect 도 없을 때") {
            beforeEach {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.VIEW,
                        IssueScope.Issue(oldKey.value),
                    )
                } returns true
                every { repo.findByKeyWithType(oldKey) } returns null
                every { redirectRepository.findCurrentKey(oldKey) } returns null
            }

            it("IssueNotFoundException 을 던진다 (기존 동작 유지)") {
                shouldThrow<IssueNotFoundException> {
                    sut.findByKey(actor, oldKey)
                }
            }

            it("IssueMovedException 을 던지지 않는다") {
                val result = runCatching { sut.findByKey(actor, oldKey) }
                result.exceptionOrNull()?.let { ex ->
                    (ex is IssueMovedException) shouldBe false
                }
            }
        }

        context("체인 이동 — A→B→C 순으로 이동한 경우 findCurrentKey 가 최종 키 C 를 반환할 때") {
            val chainFinalKey = IssueKey("NEW-99")

            beforeEach {
                every {
                    permissionResolver.hasPermission(
                        actor.value,
                        IssuePermission.VIEW,
                        IssueScope.Issue(oldKey.value),
                    )
                } returns true
                every { repo.findByKeyWithType(oldKey) } returns null
                // findCurrentKey 가 내부적으로 체인 순회 후 최종 키를 반환한다
                every { redirectRepository.findCurrentKey(oldKey) } returns chainFinalKey
            }

            it("IssueMovedException.newKey 가 체인 최종 키와 일치한다") {
                val ex =
                    shouldThrow<IssueMovedException> {
                        sut.findByKey(actor, oldKey)
                    }
                ex.newKey shouldBe chainFinalKey.value
            }
        }
    }
})
