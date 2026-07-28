// 부모-자식 관계 설정/해제 서비스 단위 테스트 — MockK, TDD RED 단계

package com.bts.issue.link.application

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.link.domain.LinkedIssueNotFoundException
import com.bts.issue.link.domain.ParentCycleException
import com.bts.issue.link.domain.ParentSelfReferenceException
import com.bts.issue.project.archive.ProjectArchiveGuard
import com.bts.issue.project.archive.ProjectArchivedException
import com.bts.issue.repository.IssueRepository
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.permission.IssuePermissionResolver
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
    val archiveGuard = mockk<ProjectArchiveGuard>(relaxUnitFun = true)

    // 이 파일은 부모-자식 도메인 규칙(순환·자기참조·미존재)을 검증한다. 권한 판정은 관심사가 아니므로
    // 전부 허용으로 고정한다 — setParent 의 권한 거부 경로는 IssueLinkControllerIntegrationTest 의
    // SEC5/SEC6 이 덮는다. clearParent 는 어느 계층에도 거부 단언이 없어 아래 「UPDATE 권한이 없으면」
    // 컨텍스트가 전용 resolver 로 덮는다.
    val permissionResolver =
        mockk<IssuePermissionResolver> {
            every { hasPermission(any(), any(), any()) } returns true
        }
    val sut = IssueParentService(repo, archiveGuard, permissionResolver)

    /** 권한이 관심사가 아닌 테스트용 actor. */
    val actor = ActorId(UUID.fromString("11111111-1111-4111-8111-111111111111"))

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
        clearMocks(repo, archiveGuard, answers = false)
    }

    // ── setParent ──────────────────────────────────────────────────────────────

    describe("setParent") {

        context("child 이슈가 존재하지 않으면") {
            beforeEach {
                every { repo.findByKey(childKey) } returns null
            }

            it("LinkedIssueNotFoundException(404) 을 던진다") {
                shouldThrow<LinkedIssueNotFoundException> {
                    sut.setParent(actor, childKey, parentKey)
                }
            }

            it("repo.updateParent 가 호출되지 않는다") {
                runCatching { sut.setParent(actor, childKey, parentKey) }
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
                    sut.setParent(actor, childKey, parentKey)
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
                    sut.setParent(actor, childKey, parentKey)
                }
            }

            it("repo.updateParent 가 호출되지 않는다") {
                runCatching { sut.setParent(actor, childKey, parentKey) }
                verify(exactly = 0) { repo.updateParent(any(), any()) }
            }
        }

        context("childKey == parentKey (self 참조)") {
            beforeEach {
                every { repo.findByKey(childKey) } returns makeIssue(childId, childKey)
            }

            it("ParentSelfReferenceException(422) 을 던진다") {
                shouldThrow<ParentSelfReferenceException> {
                    sut.setParent(actor, childKey, childKey)
                }
            }

            it("repo.updateParent 가 호출되지 않는다") {
                runCatching { sut.setParent(actor, childKey, childKey) }
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
                    sut.setParent(actor, key1, key2)
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
                    sut.setParent(actor, childKey, parentKey)
                }
            }

            it("repo.updateParent 가 호출되지 않는다") {
                runCatching { sut.setParent(actor, childKey, parentKey) }
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
                    sut.setParent(actor, childKey, parentKey)
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
                sut.setParent(actor, childKey, parentKey)
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
                sut.setParent(actor, childKey, parentKey)
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
                    sut.clearParent(actor, childKey)
                }
            }

            it("repo.updateParent 가 호출되지 않는다") {
                runCatching { sut.clearParent(actor, childKey) }
                verify(exactly = 0) { repo.updateParent(any(), any()) }
            }
        }

        context("정상 — 이슈가 존재하면") {
            beforeEach {
                every { repo.findByKey(childKey) } returns makeIssue(childId, childKey, pid = parentId)
                every { repo.updateParent(childId, null) } returns Unit
            }

            it("repo.updateParent(childId, null) 가 1회 호출된다") {
                sut.clearParent(actor, childKey)
                verify(exactly = 1) { repo.updateParent(childId, null) }
            }
        }

        context("정상 — 부모가 없는 이슈(최상위)에도 clearParent 는 updateParent(null) 을 호출한다") {
            beforeEach {
                every { repo.findByKey(childKey) } returns makeIssue(childId, childKey, pid = null)
                every { repo.updateParent(childId, null) } returns Unit
            }

            it("repo.updateParent(childId, null) 가 1회 호출된다") {
                sut.clearParent(actor, childKey)
                verify(exactly = 1) { repo.updateParent(childId, null) }
            }
        }

        /**
         * ★clearParent 의 권한 게이트는 2026-07-27 봉합이 만든 7지점 중 **유일하게 거부 테스트가 없던
         * 지점**이었다. setParent 는 SEC5/SEC6(IssueLinkControllerIntegrationTest)이 양끝을 덮는데,
         * 부모 **해제**는 어느 계층에도 단언이 없어 `checkPermission` 줄을 지워도 전량 green 이었다.
         * 남의 이슈를 계층에서 떼어내는 것도 엄연한 변경이다.
         *
         * 상단 `permissionResolver` 는 전부 허용으로 고정돼 있고 이 파일은 SingleInstance 격리라
         * 스텁을 바꾸면 뒤 테스트로 누출된다. 그래서 거부 전용 resolver/sut 를 이 컨텍스트에서만 만든다.
         */
        context("UPDATE 권한이 없으면") {
            val denyingResolver =
                mockk<IssuePermissionResolver> {
                    every { hasPermission(any(), any(), any()) } returns false
                }
            val denyingSut = IssueParentService(repo, archiveGuard, denyingResolver)

            it("IssueAccessDeniedException 을 던지고 repo 조회·updateParent 미수행(probe 차단)") {
                shouldThrow<IssueAccessDeniedException> {
                    denyingSut.clearParent(actor, childKey)
                }

                // 권한 검사가 리소스 조회보다 먼저 — 404/200 차이로 이슈 실재를 열거당하지 않는다.
                verify(exactly = 0) { repo.findByKey(childKey) }
                verify(exactly = 0) { repo.updateParent(any(), any()) }
            }
        }
    }

    // ── 아카이브 잠금 (FR-PJ-04 PR-4 Task 9) ─────────────────────────────────────

    describe("아카이브 잠금 (FR-PJ-04 PR-4 Task 9)") {
        // 이 파일은 SingleInstance 격리(clearMocks answers=false) — archiveGuard throws stub 이 뒤 테스트로
        // 누출되지 않도록 이 describe 전용 afterEach 로 명시 초기화한다(answers 기본 true).
        afterEach { clearMocks(archiveGuard) }

        context("setParent — child 프로젝트가 아카이브된 경우") {
            it("ProjectArchivedException 을 던지고 repo.updateParent 미호출") {
                every { archiveGuard.checkByIssue(childKey) } throws ProjectArchivedException(childKey.value)

                shouldThrow<ProjectArchivedException> { sut.setParent(actor, childKey, parentKey) }
                verify(exactly = 0) { repo.updateParent(any(), any()) }
            }
        }

        context("setParent — parent 프로젝트가 아카이브된 경우") {
            it("ProjectArchivedException 을 던지고 repo.updateParent 미호출") {
                every { archiveGuard.checkByIssue(parentKey) } throws ProjectArchivedException(parentKey.value)

                shouldThrow<ProjectArchivedException> { sut.setParent(actor, childKey, parentKey) }
                verify(exactly = 0) { repo.updateParent(any(), any()) }
            }
        }

        context("setParent — 활성 프로젝트 (판별자 baseline)") {
            it("child/parent archiveGuard.checkByIssue 가 모두 호출되고 정상 설정된다") {
                every { repo.findByKey(childKey) } returns makeIssue(childId, childKey)
                every { repo.findByKey(parentKey) } returns makeIssue(parentId, parentKey)
                every { repo.collectAncestors(parentId) } returns emptyList()
                every { repo.updateParent(childId, parentId) } returns Unit

                sut.setParent(actor, childKey, parentKey)

                verify(exactly = 1) { archiveGuard.checkByIssue(childKey) }
                verify(exactly = 1) { archiveGuard.checkByIssue(parentKey) }
            }
        }

        context("clearParent — 아카이브된 프로젝트") {
            it("ProjectArchivedException 을 던지고 repo.updateParent 미호출") {
                every { archiveGuard.checkByIssue(childKey) } throws ProjectArchivedException(childKey.value)

                shouldThrow<ProjectArchivedException> { sut.clearParent(actor, childKey) }
                verify(exactly = 0) { repo.updateParent(any(), any()) }
            }
        }

        context("clearParent — 활성 프로젝트 (판별자 baseline)") {
            it("archiveGuard.checkByIssue 가 호출되고 정상 해제된다") {
                every { repo.findByKey(childKey) } returns makeIssue(childId, childKey, pid = parentId)
                every { repo.updateParent(childId, null) } returns Unit

                sut.clearParent(actor, childKey)

                verify(exactly = 1) { archiveGuard.checkByIssue(childKey) }
            }
        }
    }
})
