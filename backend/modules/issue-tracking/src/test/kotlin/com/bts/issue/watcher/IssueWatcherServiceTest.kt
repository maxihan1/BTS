// IssueWatcherService 단위 테스트 — MockK. watch/unwatch/list + 권한 분기(self=VIEW/타인=UPDATE) + 사용자 실재 검증 (FR-WT-01).

package com.bts.issue.watcher

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueNotFoundException
import com.bts.issue.repository.IssueRepository
import com.bts.issue.watcher.application.IssueWatcherService
import com.bts.issue.watcher.application.WatcherUserNotFoundException
import com.bts.issue.watcher.repository.IssueWatcherRepository
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.user.UserLookupPort
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import java.util.UUID

/**
 * IssueWatcherService 단위 테스트.
 *
 * watcherRepository / permissionResolver / userLookupPort / issueRepository 를 MockK 로 stub.
 *
 * 검증 목록.
 * - watch(본인): VIEW 권한 미보유 → IssueAccessDeniedException
 * - watch(본인): VIEW 권한 보유 시 repository.add 호출 + userLookupPort.exists 미호출
 * - watch(타인): UPDATE 권한 미보유 → IssueAccessDeniedException
 * - watch(타인): 대상 사용자 미존재 → WatcherUserNotFoundException (422)
 * - watch(타인): 권한 보유 + 사용자 존재 → repository.add 호출
 * - watch: 이슈 미존재 → IssueNotFoundException (404)
 * - watch: 권한 체크 → 이슈 조회 순서 보장 (존재 probe 차단)
 * - unwatch: VIEW 권한 미보유 → IssueAccessDeniedException
 * - unwatch: 이슈 미존재 → IssueNotFoundException
 * - unwatch: 정상 → repository.remove 호출
 * - listWatchers: VIEW 권한 미보유 → IssueAccessDeniedException
 * - listWatchers: 정상 → watchers(userId+displayName) + count + isWatching(actor 기준)
 * - watch 멱등: 이미 watch 중 재추가 → 예외 없음 (ON CONFLICT는 repo가 보장, 서비스 투명 통과)
 */
class IssueWatcherServiceTest : DescribeSpec({

    val watcherRepository = mockk<IssueWatcherRepository>()
    val permissionResolver = mockk<IssuePermissionResolver>()
    val userLookupPort = mockk<UserLookupPort>()
    val issueRepository = mockk<IssueRepository>()

    val sut =
        IssueWatcherService(
            watcherRepository = watcherRepository,
            permissionResolver = permissionResolver,
            userLookupPort = userLookupPort,
            issueRepository = issueRepository,
        )

    val actorId = ActorId(UUID.randomUUID())
    val issueKey = IssueKey("PROJ-1")
    val issueId = UUID.randomUUID()
    val scope = IssueScope.Issue(issueKey.value)

    fun stubIssueExists() {
        val issue = mockk<com.bts.issue.domain.Issue>()
        every { issue.id } returns IssueId(issueId)
        every { issueRepository.findByKey(issueKey) } returns issue
    }

    fun stubIssueNotFound() {
        every { issueRepository.findByKey(issueKey) } returns null
    }

    fun stubPermission(
        permission: IssuePermission,
        allowed: Boolean,
    ) {
        every {
            permissionResolver.hasPermission(actorId.value, permission, scope)
        } returns allowed
    }

    beforeEach { clearMocks(watcherRepository, permissionResolver, userLookupPort, issueRepository) }

    // ── watch (본인) ───────────────────────────────────────────────────────────

    describe("watch — 본인(targetUserId=null)") {
        it("VIEW 권한 미보유 → IssueAccessDeniedException") {
            stubPermission(IssuePermission.VIEW, false)

            shouldThrow<IssueAccessDeniedException> {
                sut.watch(issueKey, actorId, targetUserId = null)
            }
        }

        it("VIEW 권한 보유 + 이슈 존재 → repository.add 호출, userLookupPort.exists 미호출") {
            stubPermission(IssuePermission.VIEW, true)
            stubIssueExists()
            every { watcherRepository.add(issueId, actorId.value) } returns Unit

            sut.watch(issueKey, actorId, targetUserId = null)

            verify { watcherRepository.add(issueId, actorId.value) }
            verify(exactly = 0) { userLookupPort.exists(any()) }
        }

        it("이슈 미존재 → IssueNotFoundException") {
            stubPermission(IssuePermission.VIEW, true)
            stubIssueNotFound()

            shouldThrow<IssueNotFoundException> {
                sut.watch(issueKey, actorId, targetUserId = null)
            }
        }

        it("권한 체크를 이슈 조회보다 먼저 수행 (존재 probe 차단)") {
            stubPermission(IssuePermission.VIEW, false)
            // issueRepository.findByKey 가 호출되지 않아야 한다 — 권한 거부 시 이슈 존재 여부 누출 차단.

            shouldThrow<IssueAccessDeniedException> {
                sut.watch(issueKey, actorId, targetUserId = null)
            }

            verify(exactly = 0) { issueRepository.findByKey(any()) }
        }
    }

    // ── watch (타인) ───────────────────────────────────────────────────────────

    describe("watch — 타인(targetUserId 지정)") {
        val targetId = UUID.randomUUID()

        it("UPDATE 권한 미보유 → IssueAccessDeniedException") {
            every {
                permissionResolver.hasPermission(actorId.value, IssuePermission.UPDATE, scope)
            } returns false

            shouldThrow<IssueAccessDeniedException> {
                sut.watch(issueKey, actorId, targetUserId = targetId)
            }
        }

        it("UPDATE 권한 보유 + 대상 사용자 미존재 → WatcherUserNotFoundException") {
            every {
                permissionResolver.hasPermission(actorId.value, IssuePermission.UPDATE, scope)
            } returns true
            every { userLookupPort.exists(targetId) } returns false

            shouldThrow<WatcherUserNotFoundException> {
                sut.watch(issueKey, actorId, targetUserId = targetId)
            }
        }

        it("UPDATE 권한 보유 + 사용자 존재 + 이슈 존재 → repository.add 호출") {
            every {
                permissionResolver.hasPermission(actorId.value, IssuePermission.UPDATE, scope)
            } returns true
            every { userLookupPort.exists(targetId) } returns true
            stubIssueExists()
            every { watcherRepository.add(issueId, targetId) } returns Unit

            sut.watch(issueKey, actorId, targetUserId = targetId)

            verify { watcherRepository.add(issueId, targetId) }
        }

        it("UPDATE 권한 보유 + 사용자 존재 + 이슈 미존재 → IssueNotFoundException") {
            every {
                permissionResolver.hasPermission(actorId.value, IssuePermission.UPDATE, scope)
            } returns true
            every { userLookupPort.exists(targetId) } returns true
            stubIssueNotFound()

            shouldThrow<IssueNotFoundException> {
                sut.watch(issueKey, actorId, targetUserId = targetId)
            }
        }

        it("권한 거부 시 이슈 조회 미수행 (존재 probe 차단)") {
            every {
                permissionResolver.hasPermission(actorId.value, IssuePermission.UPDATE, scope)
            } returns false

            shouldThrow<IssueAccessDeniedException> {
                sut.watch(issueKey, actorId, targetUserId = targetId)
            }

            verify(exactly = 0) { issueRepository.findByKey(any()) }
        }
    }

    // ── watch 멱등 ─────────────────────────────────────────────────────────────

    describe("watch 멱등") {
        it("이미 watch 중 재추가 → 예외 없이 성공 (ON CONFLICT DO NOTHING은 repo가 처리)") {
            stubPermission(IssuePermission.VIEW, true)
            stubIssueExists()
            // repository.add 는 ON CONFLICT DO NOTHING 으로 멱등 — 예외 없음.
            every { watcherRepository.add(issueId, actorId.value) } returns Unit

            sut.watch(issueKey, actorId, targetUserId = null)
            sut.watch(issueKey, actorId, targetUserId = null) // 2회 호출 — 예외 없음

            verify(exactly = 2) { watcherRepository.add(issueId, actorId.value) }
        }
    }

    // ── unwatch ────────────────────────────────────────────────────────────────

    describe("unwatch") {
        it("VIEW 권한 미보유 → IssueAccessDeniedException") {
            stubPermission(IssuePermission.VIEW, false)

            shouldThrow<IssueAccessDeniedException> {
                sut.unwatch(issueKey, actorId, targetUserId = actorId.value)
            }
        }

        it("VIEW 권한 보유 + 이슈 미존재 → IssueNotFoundException") {
            stubPermission(IssuePermission.VIEW, true)
            stubIssueNotFound()

            shouldThrow<IssueNotFoundException> {
                sut.unwatch(issueKey, actorId, targetUserId = actorId.value)
            }
        }

        it("VIEW 권한 보유 + 이슈 존재 → repository.remove 호출") {
            stubPermission(IssuePermission.VIEW, true)
            stubIssueExists()
            every { watcherRepository.remove(issueId, actorId.value) } returns true

            sut.unwatch(issueKey, actorId, targetUserId = actorId.value)

            verify { watcherRepository.remove(issueId, actorId.value) }
        }

        it("타인 unwatch는 UPDATE 권한 필요") {
            val targetId = UUID.randomUUID()
            every {
                permissionResolver.hasPermission(actorId.value, IssuePermission.UPDATE, scope)
            } returns false

            shouldThrow<IssueAccessDeniedException> {
                sut.unwatch(issueKey, actorId, targetUserId = targetId)
            }
        }

        it("타인 unwatch — UPDATE 권한 보유 + 이슈 존재 → repository.remove 호출") {
            val targetId = UUID.randomUUID()
            every {
                permissionResolver.hasPermission(actorId.value, IssuePermission.UPDATE, scope)
            } returns true
            stubIssueExists()
            every { watcherRepository.remove(issueId, targetId) } returns true

            sut.unwatch(issueKey, actorId, targetUserId = targetId)

            verify { watcherRepository.remove(issueId, targetId) }
        }
    }

    // ── listWatchers ───────────────────────────────────────────────────────────

    describe("listWatchers") {
        it("VIEW 권한 미보유 → IssueAccessDeniedException") {
            stubPermission(IssuePermission.VIEW, false)

            shouldThrow<IssueAccessDeniedException> {
                sut.listWatchers(issueKey, actorId)
            }
        }

        it("VIEW 권한 보유 + 이슈 미존재 → IssueNotFoundException") {
            stubPermission(IssuePermission.VIEW, true)
            stubIssueNotFound()

            shouldThrow<IssueNotFoundException> {
                sut.listWatchers(issueKey, actorId)
            }
        }

        it("정상 조회 — watchers 목록 + count + isWatching(actor 기준) 반환") {
            val userId1 = UUID.randomUUID()
            val userId2 = actorId.value
            val displayNames = mapOf(userId1 to "Alice", userId2 to "Bob")
            val watcherRows =
                listOf(
                    com.bts.issue.watcher.repository.WatcherRow(userId1, java.time.Instant.now()),
                    com.bts.issue.watcher.repository.WatcherRow(userId2, java.time.Instant.now()),
                )

            stubPermission(IssuePermission.VIEW, true)
            stubIssueExists()
            every { watcherRepository.listByIssue(issueId) } returns watcherRows
            every { watcherRepository.countByIssue(issueId) } returns 2
            every { watcherRepository.existsForUser(issueId, actorId.value) } returns true
            every { userLookupPort.findDisplayNamesByIds(setOf(userId1, userId2)) } returns displayNames

            val result = sut.listWatchers(issueKey, actorId)

            result.count shouldBe 2
            result.isWatching shouldBe true
            result.watchers.map { it.userId } shouldBe listOf(userId1, userId2)
            result.watchers.find { it.userId == userId1 }?.displayName shouldBe "Alice"
            result.watchers.find { it.userId == userId2 }?.displayName shouldBe "Bob"
        }

        it("actor 가 watcher 아닐 때 isWatching = false") {
            stubPermission(IssuePermission.VIEW, true)
            stubIssueExists()
            every { watcherRepository.listByIssue(issueId) } returns emptyList()
            every { watcherRepository.countByIssue(issueId) } returns 0
            every { watcherRepository.existsForUser(issueId, actorId.value) } returns false
            every { userLookupPort.findDisplayNamesByIds(emptySet()) } returns emptyMap()

            val result = sut.listWatchers(issueKey, actorId)

            result.isWatching shouldBe false
            result.count shouldBe 0
            result.watchers shouldBe emptyList()
        }

        it("권한 체크를 이슈 조회보다 먼저 수행") {
            stubPermission(IssuePermission.VIEW, false)

            shouldThrow<IssueAccessDeniedException> {
                sut.listWatchers(issueKey, actorId)
            }

            verify(exactly = 0) { issueRepository.findByKey(any()) }
        }

        it("listWatchers — 권한 체크 후 이슈 조회 순서 보장") {
            stubPermission(IssuePermission.VIEW, true)
            stubIssueExists()
            every { watcherRepository.listByIssue(issueId) } returns emptyList()
            every { watcherRepository.countByIssue(issueId) } returns 0
            every { watcherRepository.existsForUser(issueId, actorId.value) } returns false
            every { userLookupPort.findDisplayNamesByIds(emptySet()) } returns emptyMap()

            sut.listWatchers(issueKey, actorId)

            verifyOrder {
                permissionResolver.hasPermission(actorId.value, IssuePermission.VIEW, scope)
                issueRepository.findByKey(issueKey)
            }
        }
    }
})
