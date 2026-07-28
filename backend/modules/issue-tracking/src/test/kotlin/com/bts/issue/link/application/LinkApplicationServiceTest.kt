// LinkApplicationServiceTest — LinkApplicationService 단위 테스트 (MockK, FR-LK-01 Task 5)

package com.bts.issue.link.application

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.link.domain.DuplicateLinkException
import com.bts.issue.link.domain.IssueLink
import com.bts.issue.link.domain.LinkCycleException
import com.bts.issue.link.domain.LinkNotFoundException
import com.bts.issue.link.domain.LinkSelfReferenceException
import com.bts.issue.link.domain.LinkType
import com.bts.issue.link.domain.LinkedIssueNotFoundException
import com.bts.issue.link.repository.IssueLinkRepository
import com.bts.issue.link.repository.LinkedIssueRow
import com.bts.issue.project.archive.ProjectArchiveGuard
import com.bts.issue.project.archive.ProjectArchivedException
import com.bts.issue.repository.IssueRepository
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.permission.IssuePermissionResolver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID

/**
 * [LinkApplicationService] 단위 테스트.
 *
 * MockK 로 [IssueLinkRepository] 와 [IssueRepository] 를 mock 한다.
 * 검증 분기(예외 케이스 + 정상 흐름)에 집중한다.
 */
class LinkApplicationServiceTest : DescribeSpec({

    val issueRepository = mockk<IssueRepository>()
    val linkRepository = mockk<IssueLinkRepository>()
    val archiveGuard = mockk<ProjectArchiveGuard>(relaxUnitFun = true)

    // 이 파일은 링크 도메인 규칙(중복·순환·미존재)을 검증한다. 권한 판정은 관심사가 아니므로
    // 전부 허용으로 고정한다 — 권한 거부 경로는 IssueLinkControllerIntegrationTest 가 덮는다.
    val permissionResolver =
        mockk<IssuePermissionResolver> {
            every { hasPermission(any(), any(), any()) } returns true
        }

    val sut =
        LinkApplicationService(
            issueRepository = issueRepository,
            linkRepository = linkRepository,
            archiveGuard = archiveGuard,
            permissionResolver = permissionResolver,
        )

    /** 권한이 관심사가 아닌 테스트용 actor. */
    val actor = ActorId(UUID.fromString("11111111-1111-4111-8111-111111111111"))

    // ── 공통 픽스처 ─────────────────────────────────────────────────────────────

    val sourceKey = IssueKey("BTS-1")
    val targetKey = IssueKey("BTS-2")
    val sourceId = UUID.fromString("00000000-0000-4000-8000-000000000001")
    val targetId = UUID.fromString("00000000-0000-4000-8000-000000000002")
    val linkTypeCode = "blocks"

    fun makeIssue(
        id: UUID,
        key: IssueKey,
    ): Issue =
        Issue.create(
            id = IssueId(id),
            key = key,
            projectId = UUID.fromString("00000000-0000-4000-8000-100000000000"),
            typeId = IssueTypeId(1L),
            summary = "테스트 이슈 ${key.value}",
            reporterId = ActorId(UUID.fromString("00000000-0000-4000-8000-200000000000")),
            currentStateKey = "open",
        )

    val sourceIssue = makeIssue(sourceId, sourceKey)
    val targetIssue = makeIssue(targetId, targetKey)

    afterEach { clearMocks(issueRepository, linkRepository, archiveGuard) }

    // ── createLink ─────────────────────────────────────────────────────────────

    describe("createLink") {

        context("source 이슈가 없거나 소프트삭제된 경우") {
            it("LinkedIssueNotFoundException 을 던진다") {
                every { issueRepository.findByKey(sourceKey) } returns null

                shouldThrow<LinkedIssueNotFoundException> {
                    sut.createLink(actor, sourceKey, targetKey, linkTypeCode)
                }
            }
        }

        context("target 이슈가 없거나 소프트삭제된 경우") {
            it("LinkedIssueNotFoundException 을 던진다") {
                every { issueRepository.findByKey(sourceKey) } returns sourceIssue
                every { issueRepository.findByKey(targetKey) } returns null

                shouldThrow<LinkedIssueNotFoundException> {
                    sut.createLink(actor, sourceKey, targetKey, linkTypeCode)
                }
            }
        }

        context("sourceKey == targetKey (자기 참조)") {
            it("LinkSelfReferenceException 을 던진다") {
                every { issueRepository.findByKey(sourceKey) } returns sourceIssue

                shouldThrow<LinkSelfReferenceException> {
                    sut.createLink(actor, sourceKey, sourceKey, linkTypeCode)
                }
            }
        }

        context("동일 source-target-linkType 링크가 이미 존재하는 경우") {
            it("DuplicateLinkException 을 던진다") {
                every { issueRepository.findByKey(sourceKey) } returns sourceIssue
                every { issueRepository.findByKey(targetKey) } returns targetIssue
                every {
                    linkRepository.existsLink(sourceId, targetId, LinkType.BLOCKS)
                } returns true

                shouldThrow<DuplicateLinkException> {
                    sut.createLink(actor, sourceKey, targetKey, linkTypeCode)
                }
            }
        }

        context("linkType=BLOCKS 이고 역방향 blocks 경로가 이미 존재하는 경우 (순환)") {
            it("LinkCycleException 을 던진다") {
                every { issueRepository.findByKey(sourceKey) } returns sourceIssue
                every { issueRepository.findByKey(targetKey) } returns targetIssue
                every {
                    linkRepository.existsLink(sourceId, targetId, LinkType.BLOCKS)
                } returns false
                // target -> source 방향 경로가 존재 → 순환
                every { linkRepository.existsBlocksPath(targetId, sourceId) } returns true

                shouldThrow<LinkCycleException> {
                    sut.createLink(actor, sourceKey, targetKey, linkTypeCode)
                }
            }
        }

        context("linkType=RELATES 는 순환 검사를 하지 않는다") {
            it("성공 시 LinkResult 를 반환한다") {
                val relatesCode = "relates"
                val relatesIssue = makeIssue(sourceId, sourceKey)
                every { issueRepository.findByKey(sourceKey) } returns relatesIssue
                every { issueRepository.findByKey(targetKey) } returns targetIssue
                every {
                    linkRepository.existsLink(sourceId, targetId, LinkType.RELATES)
                } returns false
                val savedLink =
                    IssueLink(
                        id = 1L,
                        sourceId = sourceId,
                        targetId = targetId,
                        linkType = LinkType.RELATES,
                    )
                every { linkRepository.insert(any()) } returns savedLink

                val result = sut.createLink(actor, sourceKey, targetKey, relatesCode)

                result.linkId shouldBe 1L
                result.linkType shouldBe LinkType.RELATES
                verify(exactly = 0) { linkRepository.existsBlocksPath(any(), any()) }
            }
        }

        context("정상 흐름 — BLOCKS 순환 없음") {
            it("insert 를 호출하고 LinkResult 를 반환한다") {
                every { issueRepository.findByKey(sourceKey) } returns sourceIssue
                every { issueRepository.findByKey(targetKey) } returns targetIssue
                every {
                    linkRepository.existsLink(sourceId, targetId, LinkType.BLOCKS)
                } returns false
                every { linkRepository.existsBlocksPath(targetId, sourceId) } returns false
                val savedLink =
                    IssueLink(
                        id = 42L,
                        sourceId = sourceId,
                        targetId = targetId,
                        linkType = LinkType.BLOCKS,
                    )
                every { linkRepository.insert(any()) } returns savedLink

                val result = sut.createLink(actor, sourceKey, targetKey, linkTypeCode)

                result.linkId shouldBe 42L
                result.linkType shouldBe LinkType.BLOCKS
                verify(exactly = 1) { linkRepository.insert(any()) }
            }
        }
    }

    // ── listLinks ──────────────────────────────────────────────────────────────

    describe("listLinks") {

        context("outward + inward 링크를 모두 반환하고 소프트삭제된 상대는 제외된다") {
            it("JOIN 결과를 LinkListResult 로 변환한다") {
                every { issueRepository.findByKey(sourceKey) } returns sourceIssue

                val otherKey = "BTS-3"
                val otherId = UUID.fromString("00000000-0000-4000-8000-000000000003")

                val outwardRow =
                    LinkedIssueRow(
                        linkId = 1L,
                        linkType = LinkType.BLOCKS,
                        otherIssueId = targetId,
                        otherIssueKey = targetKey.value,
                        otherIssueSummary = "타겟 이슈",
                        otherCurrentStateKey = "open",
                    )
                val inwardRow =
                    LinkedIssueRow(
                        linkId = 2L,
                        linkType = LinkType.RELATES,
                        otherIssueId = otherId,
                        otherIssueKey = otherKey,
                        otherIssueSummary = "다른 이슈",
                        otherCurrentStateKey = "in-progress",
                    )

                every { linkRepository.findOutwardWithIssue(sourceId) } returns listOf(outwardRow)
                every { linkRepository.findInwardWithIssue(sourceId) } returns listOf(inwardRow)

                val result = sut.listLinks(actor, sourceKey)

                result.outward.size shouldBe 1
                result.inward.size shouldBe 1

                val outwardEntry = result.outward[0]
                outwardEntry.linkId shouldBe 1L
                outwardEntry.linkType shouldBe LinkType.BLOCKS
                outwardEntry.relationLabel shouldBe "blocks" // outwardLabel

                val inwardEntry = result.inward[0]
                inwardEntry.linkId shouldBe 2L
                inwardEntry.linkType shouldBe LinkType.RELATES
                inwardEntry.relationLabel shouldBe "relates to" // inwardLabel (symmetric)
            }
        }

        context("이슈가 없는 경우") {
            it("LinkedIssueNotFoundException 을 던진다") {
                every { issueRepository.findByKey(sourceKey) } returns null

                shouldThrow<LinkedIssueNotFoundException> {
                    sut.listLinks(actor, sourceKey)
                }
            }
        }
    }

    // ── deleteLink ─────────────────────────────────────────────────────────────

    describe("deleteLink") {

        context("linkId 에 해당하는 링크가 없는 경우") {
            it("LinkNotFoundException 을 던진다") {
                every { issueRepository.findByKey(sourceKey) } returns sourceIssue
                every { linkRepository.deleteByIdAndIssue(99L, sourceIssue.id.value) } returns false

                shouldThrow<LinkNotFoundException> {
                    sut.deleteLink(actor, sourceKey, 99L)
                }
            }
        }

        context("정상 흐름 — 링크 삭제 성공") {
            it("deleteByIdAndIssue 를 이슈 id 로 좁혀 호출한다") {
                every { issueRepository.findByKey(sourceKey) } returns sourceIssue
                every { linkRepository.deleteByIdAndIssue(1L, sourceIssue.id.value) } returns true

                sut.deleteLink(actor, sourceKey, 1L)

                verify(exactly = 1) { linkRepository.deleteByIdAndIssue(1L, sourceIssue.id.value) }
            }
        }
    }

    // ── 아카이브 잠금 (FR-PJ-04 PR-4 Task 9) ─────────────────────────────────────

    describe("아카이브 잠금 (FR-PJ-04 PR-4 Task 9)") {
        context("createLink — source 프로젝트가 아카이브된 경우") {
            it("ProjectArchivedException 을 던지고 linkRepository.insert 미호출") {
                every { archiveGuard.checkByIssue(sourceKey) } throws ProjectArchivedException(sourceKey.value)

                shouldThrow<ProjectArchivedException> {
                    sut.createLink(actor, sourceKey, targetKey, linkTypeCode)
                }
                verify(exactly = 0) { linkRepository.insert(any()) }
            }
        }

        context("createLink — target 프로젝트가 아카이브된 경우") {
            it("ProjectArchivedException 을 던지고 linkRepository.insert 미호출") {
                every { archiveGuard.checkByIssue(targetKey) } throws ProjectArchivedException(targetKey.value)

                shouldThrow<ProjectArchivedException> {
                    sut.createLink(actor, sourceKey, targetKey, linkTypeCode)
                }
                verify(exactly = 0) { linkRepository.insert(any()) }
            }
        }

        context("createLink — 활성 프로젝트 (판별자 baseline)") {
            it("source/target archiveGuard.checkByIssue 가 모두 호출되고 정상 생성된다") {
                every { issueRepository.findByKey(sourceKey) } returns sourceIssue
                every { issueRepository.findByKey(targetKey) } returns targetIssue
                every { linkRepository.existsLink(sourceId, targetId, LinkType.BLOCKS) } returns false
                every { linkRepository.existsBlocksPath(targetId, sourceId) } returns false
                every { linkRepository.insert(any()) } returns
                    IssueLink(id = 1L, sourceId = sourceId, targetId = targetId, linkType = LinkType.BLOCKS)

                sut.createLink(actor, sourceKey, targetKey, linkTypeCode)

                verify(exactly = 1) { archiveGuard.checkByIssue(sourceKey) }
                verify(exactly = 1) { archiveGuard.checkByIssue(targetKey) }
            }
        }

        context("deleteLink — 아카이브된 프로젝트") {
            it("ProjectArchivedException 을 던지고 linkRepository.deleteById 미호출") {
                every { archiveGuard.checkByIssue(sourceKey) } throws ProjectArchivedException(sourceKey.value)

                shouldThrow<ProjectArchivedException> {
                    sut.deleteLink(actor, sourceKey, 1L)
                }
                verify(exactly = 0) { linkRepository.deleteByIdAndIssue(any(), any()) }
            }
        }

        context("deleteLink — 활성 프로젝트 (판별자 baseline)") {
            it("archiveGuard.checkByIssue 가 호출되고 정상 삭제된다") {
                every { issueRepository.findByKey(sourceKey) } returns sourceIssue
                every { linkRepository.deleteByIdAndIssue(1L, sourceIssue.id.value) } returns true

                sut.deleteLink(actor, sourceKey, 1L)

                verify(exactly = 1) { archiveGuard.checkByIssue(sourceKey) }
            }
        }
    }
})
