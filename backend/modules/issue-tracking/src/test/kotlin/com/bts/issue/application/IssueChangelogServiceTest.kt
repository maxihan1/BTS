// IssueChangelogService 단위 테스트 — 이슈 변경 이력 조회 서비스 (MockK)

package com.bts.issue.application

import com.bts.issue.adapter.inbound.rest.IssueResponse
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueImpact
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueNotFoundException
import com.bts.issue.domain.IssuePriority
import com.bts.issue.history.IssueChangeGroup
import com.bts.issue.history.IssueChangeHistoryRepository
import com.bts.issue.history.IssueChangeItem
import com.bts.shared.user.UserLookupPort
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.data.domain.PageRequest
import java.time.Instant
import java.util.UUID

class IssueChangelogServiceTest : DescribeSpec({

    val issueApplicationService = mockk<IssueApplicationService>()
    val changeHistoryRepository = mockk<IssueChangeHistoryRepository>()
    val userLookupPort = mockk<UserLookupPort>()

    val sut = IssueChangelogService(
        issueApplicationService = issueApplicationService,
        changeHistoryRepository = changeHistoryRepository,
        userLookupPort = userLookupPort,
    )

    val actor = ActorId(UUID.randomUUID())
    val issueKey = IssueKey("BTS-1")
    val issueId = UUID.randomUUID()
    val actorId1 = UUID.randomUUID()
    val actorId2 = UUID.randomUUID()

    fun makeIssueResponse(id: UUID = issueId): IssueResponse =
        IssueResponse(
            key = issueKey.value,
            id = id,
            projectKey = "BTS",
            summary = "테스트 이슈",
            currentStateKey = "open",
            reporterId = UUID.randomUUID(),
            version = 1L,
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
            typeId = 1L,
            typeKey = "task",
            typeName = "Task",
        )

    fun makeGroup(
        actorId: UUID? = actorId1,
        createdAt: Instant = Instant.parse("2026-06-01T10:00:00Z"),
    ): IssueChangeGroup =
        IssueChangeGroup(
            issueId = issueId,
            issueKey = issueKey.value,
            actorId = actorId,
            items = listOf(
                IssueChangeItem(field = "priority", fromValue = "2", toValue = "3"),
            ),
            createdAt = createdAt,
        )

    beforeEach {
        clearMocks(issueApplicationService, changeHistoryRepository, userLookupPort, answers = false)
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // (a) VIEW 권한 없음 / 미존재 / 소프트 삭제 → IssueNotFoundException 전파
    // ──────────────────────────────────────────────────────────────────────────────
    describe("findChangelog — VIEW 가드 위반") {

        context("issueApplicationService.findByKey 가 IssueNotFoundException 을 던짐") {

            beforeEach {
                every {
                    issueApplicationService.findByKey(actor, issueKey)
                } throws IssueNotFoundException(issueKey)
            }

            it("IssueNotFoundException 을 그대로 전파한다") {
                shouldThrow<IssueNotFoundException> {
                    sut.findChangelog(actor, issueKey, PageRequest.of(0, 20))
                }
            }

            it("changeHistoryRepository 를 호출하지 않는다") {
                runCatching { sut.findChangelog(actor, issueKey, PageRequest.of(0, 20)) }
                verify(exactly = 0) { changeHistoryRepository.findByIssuePaged(any(), any(), any()) }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // (b) actorId 있는 그룹 → UserLookupPort 로 actorName 채워짐
    // ──────────────────────────────────────────────────────────────────────────────
    describe("findChangelog — actorName 해석") {

        context("두 그룹, actorId 모두 존재") {

            val groups = listOf(
                makeGroup(actorId = actorId1),
                makeGroup(actorId = actorId2),
            )

            beforeEach {
                every { issueApplicationService.findByKey(actor, issueKey) } returns makeIssueResponse()
                every {
                    changeHistoryRepository.findByIssuePaged(issueId, 20, 0)
                } returns groups
                every { changeHistoryRepository.countByIssue(issueId) } returns 2L
                every {
                    userLookupPort.findDisplayNamesByIds(setOf(actorId1, actorId2))
                } returns mapOf(actorId1 to "Alice", actorId2 to "Bob")
            }

            it("각 그룹의 actorName 이 표시명으로 채워진다") {
                val page = sut.findChangelog(actor, issueKey, PageRequest.of(0, 20))
                page.content[0].actorName shouldBe "Alice"
                page.content[1].actorName shouldBe "Bob"
            }

            it("actorId 도 그대로 포함된다") {
                val page = sut.findChangelog(actor, issueKey, PageRequest.of(0, 20))
                page.content[0].actorId shouldBe actorId1
                page.content[1].actorId shouldBe actorId2
            }

            it("items 가 도메인 IssueChangeItem 그대로 포함된다") {
                val page = sut.findChangelog(actor, issueKey, PageRequest.of(0, 20))
                page.content[0].items shouldHaveSize 1
                page.content[0].items[0].field shouldBe "priority"
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // (c) actorId=null(시스템) 또는 lookup 결과 없음 / 포트 예외 → actorName=null graceful degrade
    // ──────────────────────────────────────────────────────────────────────────────
    describe("findChangelog — graceful degrade") {

        context("actorId=null 인 시스템 그룹") {

            val groups = listOf(makeGroup(actorId = null))

            beforeEach {
                every { issueApplicationService.findByKey(actor, issueKey) } returns makeIssueResponse()
                every {
                    changeHistoryRepository.findByIssuePaged(issueId, 20, 0)
                } returns groups
                every { changeHistoryRepository.countByIssue(issueId) } returns 1L
                // actorId 없으면 lookup 호출 불필요 — 호출 자체가 일어나지 않아도 됨
                every {
                    userLookupPort.findDisplayNamesByIds(emptySet())
                } returns emptyMap()
            }

            it("actorName=null 로 이력을 반환한다") {
                val page = sut.findChangelog(actor, issueKey, PageRequest.of(0, 20))
                page.content shouldHaveSize 1
                page.content[0].actorName.shouldBeNull()
            }
        }

        context("lookup 결과에 actorId 없음 (미존재 사용자)") {

            val groups = listOf(makeGroup(actorId = actorId1))

            beforeEach {
                every { issueApplicationService.findByKey(actor, issueKey) } returns makeIssueResponse()
                every {
                    changeHistoryRepository.findByIssuePaged(issueId, 20, 0)
                } returns groups
                every { changeHistoryRepository.countByIssue(issueId) } returns 1L
                every {
                    userLookupPort.findDisplayNamesByIds(setOf(actorId1))
                } returns emptyMap()
            }

            it("actorName=null 로 이력을 반환한다") {
                val page = sut.findChangelog(actor, issueKey, PageRequest.of(0, 20))
                page.content[0].actorName.shouldBeNull()
            }
        }

        context("UserLookupPort 가 예외를 던짐") {

            val groups = listOf(makeGroup(actorId = actorId1))

            beforeEach {
                every { issueApplicationService.findByKey(actor, issueKey) } returns makeIssueResponse()
                every {
                    changeHistoryRepository.findByIssuePaged(issueId, 20, 0)
                } returns groups
                every { changeHistoryRepository.countByIssue(issueId) } returns 1L
                every {
                    userLookupPort.findDisplayNamesByIds(setOf(actorId1))
                } throws RuntimeException("identity-access unavailable")
            }

            it("예외를 전파하지 않고 actorName=null 로 이력을 반환한다") {
                val page = sut.findChangelog(actor, issueKey, PageRequest.of(0, 20))
                page.content shouldHaveSize 1
                page.content[0].actorName.shouldBeNull()
            }

            it("이력의 items 도 그대로 포함된다") {
                val page = sut.findChangelog(actor, issueKey, PageRequest.of(0, 20))
                page.content[0].items shouldHaveSize 1
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // (d) 페이징 위임 — pageable → limit/offset 변환 + PageImpl 구성
    // ──────────────────────────────────────────────────────────────────────────────
    describe("findChangelog — 페이징") {

        context("2페이지(0-indexed), pageSize=10, 총 25건") {

            val groups = listOf(makeGroup())

            beforeEach {
                every { issueApplicationService.findByKey(actor, issueKey) } returns makeIssueResponse()
                every {
                    changeHistoryRepository.findByIssuePaged(issueId, 10, 20) // page=2, size=10 → offset=20
                } returns groups
                every { changeHistoryRepository.countByIssue(issueId) } returns 25L
                every {
                    userLookupPort.findDisplayNamesByIds(setOf(actorId1))
                } returns mapOf(actorId1 to "Alice")
            }

            it("limit=10, offset=20 으로 repository 를 호출한다") {
                sut.findChangelog(actor, issueKey, PageRequest.of(2, 10))
                verify(exactly = 1) { changeHistoryRepository.findByIssuePaged(issueId, 10, 20) }
            }

            it("totalElements=25 를 포함한 Page 를 반환한다") {
                val page = sut.findChangelog(actor, issueKey, PageRequest.of(2, 10))
                page.totalElements shouldBe 25L
            }

            it("totalPages=3 이다") {
                val page = sut.findChangelog(actor, issueKey, PageRequest.of(2, 10))
                page.totalPages shouldBe 3
            }
        }

        context("첫 페이지(page=0), pageSize=20, 총 5건") {

            val groups = (1..5).map { i ->
                makeGroup(
                    actorId = actorId1,
                    createdAt = Instant.parse("2026-06-0${i}T10:00:00Z"),
                )
            }

            beforeEach {
                every { issueApplicationService.findByKey(actor, issueKey) } returns makeIssueResponse()
                every {
                    changeHistoryRepository.findByIssuePaged(issueId, 20, 0)
                } returns groups
                every { changeHistoryRepository.countByIssue(issueId) } returns 5L
                every {
                    userLookupPort.findDisplayNamesByIds(setOf(actorId1))
                } returns mapOf(actorId1 to "Alice")
            }

            it("5건 반환 + isLast=true") {
                val page = sut.findChangelog(actor, issueKey, PageRequest.of(0, 20))
                page.content shouldHaveSize 5
                page.isLast shouldBe true
            }
        }
    }
})
