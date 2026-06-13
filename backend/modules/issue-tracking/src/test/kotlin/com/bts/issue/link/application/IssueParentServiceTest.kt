// 부모-자식 관계 설정/해제 서비스 단위 테스트 — MockK, TDD RED 단계

package com.bts.issue.link.application

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.link.domain.LinkedIssueNotFoundException
import com.bts.issue.link.domain.ParentCycleException
import com.bts.issue.link.domain.ParentSelfReferenceException
import com.bts.issue.repository.IssueRepository
import com.bts.shared.issue.IssueTypeId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.util.UUID

class IssueParentServiceTest : DescribeSpec({

    val repo = mockk<IssueRepository>()
    val sut = IssueParentService(repo)

    // 공통 픽스처
    val childId = UUID.fromString("00000000-0000-4000-8000-000000000001")
    val parentId = UUID.fromString("00000000-0000-4000-8000-000000000002")
    val otherId = UUID.fromString("00000000-0000-4000-8000-000000000003")

    val childKey = IssueKey("BTS-1")
    val parentKey = IssueKey("BTS-2")

    fun makeIssue(
        id: UUID,
        key: IssueKey,
        pid: UUID? = null,
    ) = Issue(
        id = IssueId(id),
        key = key,
        projectId = UUID.fromString("00000000-0000-4000-8000-000000000010"),
        summary = "테스트 이슈",
        reporterId = ActorId(UUID.fromString("00000000-0000-4000-8000-000000000099")),
        currentStateKey = "open",
        version = 1L,
        deletedAt = null,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
        typeId = IssueTypeId(1L),
        parentId = pid,
    )

    beforeEach {
        clearMocks(repo, answers = false)
    }

    // ── setParent ──────────────────────────────────────────────────────────────

    describe("setParent") {

        context("child 이슈가 존재하지 않으면") {
            beforeEach {
                every { repo.findByKey(childKey) } returns null
            }

            it("LinkedIssueNotFoundException(404) 을 던진다") {
                shouldThrow<LinkedIssueNotFoundException> {
                    sut.setParent(childKey, parentKey)
                }
            }

            it("repo.updateParent 가 호출되지 않는다") {
                runCatching { sut.setParent(childKey, parentKey) }
                verify(exactly = 0) { repo.updateParent(any(), any()) }
            }
        }

        context("child 이슈가 소프트삭제(findByKey=null)되어 있으면") {
            // findByKey 는 deleted_at IS NULL 필터를 적용하므로 소프트삭제된 경우 null 반환
            beforeEach {
                every { repo.findByKey(childKey) } returns null
            }

            it("LinkedIssueNotFoundException(404) 을 던진다") {
                shouldThrow<LinkedIssueNotFoundException> {
                    sut.setParent(childKey, parentKey)
                }
            }
        }

        context("parent 이슈가 존재하지 않으면") {
            beforeEach {
                every { repo.findByKey(childKey) } returns makeIssue(childId, childKey)
                every { repo.findByKey(parentKey) } returns null
            }

            it("LinkedIssueNotFoundException(404) 을 던진다") {
                shouldThrow<LinkedIssueNotFoundException> {
                    sut.setParent(childKey, parentKey)
                }
            }

            it("repo.updateParent 가 호출되지 않는다") {
                runCatching { sut.setParent(childKey, parentKey) }
                verify(exactly = 0) { repo.updateParent(any(), any()) }
            }
        }

        context("childKey == parentKey (self 참조)") {
            beforeEach {
                every { repo.findByKey(childKey) } returns makeIssue(childId, childKey)
            }

            it("ParentSelfReferenceException(422) 을 던진다") {
                shouldThrow<ParentSelfReferenceException> {
                    sut.setParent(childKey, childKey)
                }
            }

            it("repo.updateParent 가 호출되지 않는다") {
                runCatching { sut.setParent(childKey, childKey) }
                verify(exactly = 0) { repo.updateParent(any(), any()) }
            }
        }

        context("child 와 parent 가 다른 이슈지만 ID 가 같은 경우 (id 기반 self 검사)") {
            // childKey != parentKey 이지만 DB에서 같은 UUID를 가리키는 비정상 상황은 없으므로
            // key 비교로 충분하다. 이 케이스는 childId == parentId 일 때를 확인.
            val sameId = UUID.fromString("00000000-0000-4000-8000-000000000001")
            val key1 = IssueKey("BTS-1")
            val key2 = IssueKey("BTS-2")

            beforeEach {
                // 두 키가 동일한 UUID 를 가진 이슈를 반환 — 구조적으로 불가능하지만 방어 테스트
                every { repo.findByKey(key1) } returns makeIssue(sameId, key1)
                every { repo.findByKey(key2) } returns makeIssue(sameId, key2)
                every { repo.collectAncestors(sameId) } returns emptyList()
            }

            it("childId == parentId 이면 ParentSelfReferenceException 을 던진다") {
                shouldThrow<ParentSelfReferenceException> {
                    sut.setParent(key1, key2)
                }
            }
        }

        context("parent 의 조상 체인에 child 가 포함되어 순환이 형성되면") {
            // 구조: otherId → parentId → childId (조상 체인)
            // setParent(child←parent) 시 parent 의 조상을 수집하면 childId 가 등장
            beforeEach {
                every { repo.findByKey(childKey) } returns makeIssue(childId, childKey)
                every { repo.findByKey(parentKey) } returns makeIssue(parentId, parentKey, pid = childId)
                every { repo.collectAncestors(parentId) } returns listOf(childId, otherId)
            }

            it("ParentCycleException(409) 을 던진다") {
                shouldThrow<ParentCycleException> {
                    sut.setParent(childKey, parentKey)
                }
            }

            it("repo.updateParent 가 호출되지 않는다") {
                runCatching { sut.setParent(childKey, parentKey) }
                verify(exactly = 0) { repo.updateParent(any(), any()) }
            }
        }

        context("parent 자체가 child 의 직계 자식 (단일 단계 순환)") {
            // parent 의 조상 수집: collectAncestors(parentId) → [childId]
            beforeEach {
                every { repo.findByKey(childKey) } returns makeIssue(childId, childKey)
                every { repo.findByKey(parentKey) } returns makeIssue(parentId, parentKey, pid = childId)
                every { repo.collectAncestors(parentId) } returns listOf(childId)
            }

            it("ParentCycleException(409) 을 던진다") {
                shouldThrow<ParentCycleException> {
                    sut.setParent(childKey, parentKey)
                }
            }
        }

        context("정상 — 미존재 조상 체인, cycle 없음") {
            beforeEach {
                every { repo.findByKey(childKey) } returns makeIssue(childId, childKey)
                every { repo.findByKey(parentKey) } returns makeIssue(parentId, parentKey)
                every { repo.collectAncestors(parentId) } returns emptyList()
                every { repo.updateParent(childId, parentId) } returns Unit
            }

            it("repo.updateParent(childId, parentId) 가 1회 호출된다") {
                sut.setParent(childKey, parentKey)
                verify(exactly = 1) { repo.updateParent(childId, parentId) }
            }
        }

        context("정상 — parent 에게 다른 조상이 있지만 child 는 포함되지 않음") {
            beforeEach {
                every { repo.findByKey(childKey) } returns makeIssue(childId, childKey)
                every { repo.findByKey(parentKey) } returns makeIssue(parentId, parentKey, pid = otherId)
                every { repo.collectAncestors(parentId) } returns listOf(otherId)
                every { repo.updateParent(childId, parentId) } returns Unit
            }

            it("repo.updateParent(childId, parentId) 가 1회 호출된다") {
                sut.setParent(childKey, parentKey)
                verify(exactly = 1) { repo.updateParent(childId, parentId) }
            }
        }
    }

    // ── clearParent ───────────────────────────────────────────────────────────

    describe("clearParent") {

        context("child 이슈가 존재하지 않으면") {
            beforeEach {
                every { repo.findByKey(childKey) } returns null
            }

            it("LinkedIssueNotFoundException(404) 을 던진다") {
                shouldThrow<LinkedIssueNotFoundException> {
                    sut.clearParent(childKey)
                }
            }

            it("repo.updateParent 가 호출되지 않는다") {
                runCatching { sut.clearParent(childKey) }
                verify(exactly = 0) { repo.updateParent(any(), any()) }
            }
        }

        context("정상 — 이슈가 존재하면") {
            beforeEach {
                every { repo.findByKey(childKey) } returns makeIssue(childId, childKey, pid = parentId)
                every { repo.updateParent(childId, null) } returns Unit
            }

            it("repo.updateParent(childId, null) 가 1회 호출된다") {
                sut.clearParent(childKey)
                verify(exactly = 1) { repo.updateParent(childId, null) }
            }
        }

        context("정상 — 부모가 없는 이슈(최상위)에도 clearParent 는 updateParent(null) 을 호출한다") {
            beforeEach {
                every { repo.findByKey(childKey) } returns makeIssue(childId, childKey, pid = null)
                every { repo.updateParent(childId, null) } returns Unit
            }

            it("repo.updateParent(childId, null) 가 1회 호출된다") {
                sut.clearParent(childKey)
                verify(exactly = 1) { repo.updateParent(childId, null) }
            }
        }
    }
})
