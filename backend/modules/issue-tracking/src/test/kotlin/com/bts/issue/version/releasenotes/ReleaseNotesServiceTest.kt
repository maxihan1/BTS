// ReleaseNotesService MockK 단위 테스트 — 조회 조합·Clock·예외 경로 검증
package com.bts.issue.version.releasenotes

import com.bts.issue.project.ProjectLookup
import com.bts.issue.project.repository.ProjectLookupRepository
import com.bts.issue.repository.IssueRepository
import com.bts.issue.repository.ReleaseNoteIssueRow
import com.bts.issue.resolution.domain.Resolution
import com.bts.issue.resolution.repository.ResolutionRepository
import com.bts.issue.version.domain.Version
import com.bts.issue.version.domain.VersionNotFoundException
import com.bts.issue.version.domain.VersionProjectNotFoundException
import com.bts.issue.version.domain.VersionStatus
import com.bts.issue.version.repository.VersionRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * [ReleaseNotesService.generate] MockK 단위 테스트.
 *
 * Spring 컨텍스트 없이 순수 단위 테스트로 실행한다.
 * 트랜잭션/DB 경계는 Testcontainers 통합 테스트로 별도 검증한다.
 *
 * 검증 대상.
 * - resolveProject null → [VersionProjectNotFoundException]
 * - findActiveVersion null → [VersionNotFoundException]
 * - 이슈 역방향 조회 + ResolutionRepository.findAllActive() 맵 주입
 * - projectKey 조회 + Generator 호출 → [ReleaseNotes] 반환
 * - Clock 고정 시 generatedAt 결정적
 * - resolution 맵 주입 (resolutionId → name)
 * - 0건 이슈 정상 처리
 */
class ReleaseNotesServiceTest : DescribeSpec({

    val projectLookup = mockk<ProjectLookup>()
    val projectLookupRepo = mockk<ProjectLookupRepository>()
    val versionRepo = mockk<VersionRepository>()
    val issueRepo = mockk<IssueRepository>()
    val resolutionRepo = mockk<ResolutionRepository>()

    /** 결정론적 Clock — 2026-06-10T12:00:00Z 고정. */
    val fixedInstant = Instant.parse("2026-06-10T12:00:00Z")
    val fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC)

    val sut =
        ReleaseNotesService(
            projectLookup = projectLookup,
            projectLookupRepository = projectLookupRepo,
            versionRepository = versionRepo,
            issueRepository = issueRepo,
            resolutionRepository = resolutionRepo,
            clock = fixedClock,
        )

    val actorId = UUID.randomUUID()
    val projectId = UUID.randomUUID()
    val versionId = UUID.randomUUID()
    val resolutionId = UUID.randomUUID()

    val activeVersion =
        Version(
            id = versionId,
            projectId = projectId,
            name = "v1.0.0",
            description = null,
            startDate = null,
            releaseDate = LocalDate.of(2026, 6, 10),
            status = VersionStatus.UNRELEASED,
            releasedAt = null,
            deletedAt = null,
        )

    beforeEach {
        clearMocks(projectLookup, projectLookupRepo, versionRepo, issueRepo, resolutionRepo)
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** 기본 이슈 row 생성 헬퍼 — Bug 타입. */
    fun bugRow(
        key: String = "BTS-1",
        summary: String = "버그 수정",
        resolutionId: UUID? = null,
    ) = ReleaseNoteIssueRow(
        key = key,
        summary = summary,
        typeId = 1L,
        typeKey = "bug",
        typeName = "Bug",
        hierarchyLevel = 0,
        resolutionId = resolutionId,
    )

    /** Resolution 도메인 객체 생성 헬퍼 (id 명시). */
    fun resolution(
        id: UUID,
        name: String,
    ): Resolution =
        Resolution(
            id = id,
            key = name.lowercase().replace(" ", ""),
            name = name,
            description = null,
            displayOrder = 1,
            isStandard = true,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            deletedAt = null,
        )

    // ── 예외 경로 ──────────────────────────────────────────────────────────────

    describe("generate") {
        context("프로젝트 미존재") {
            it("resolveProject null 이면 VersionProjectNotFoundException") {
                every { projectLookup.resolve("BTS") } returns null

                shouldThrow<VersionProjectNotFoundException> {
                    sut.generate(actorId, "BTS", versionId)
                }

                verify(exactly = 1) { projectLookup.resolve("BTS") }
                verify(exactly = 0) { versionRepo.findById(any(), any()) }
            }
        }

        context("버전 미존재") {
            it("findById null 이면 VersionNotFoundException") {
                every { projectLookup.resolve("BTS") } returns projectId
                every { versionRepo.findById(versionId, projectId) } returns null

                shouldThrow<VersionNotFoundException> {
                    sut.generate(actorId, "BTS", versionId)
                }

                verify(exactly = 1) { versionRepo.findById(versionId, projectId) }
            }
        }

        context("이슈 0건 정상 처리") {
            it("rows 빈 리스트여도 ReleaseNotes 를 반환한다") {
                every { projectLookup.resolve("BTS") } returns projectId
                every { versionRepo.findById(versionId, projectId) } returns activeVersion
                every { issueRepo.findFixVersionIssuesForReleaseNotes(versionId) } returns emptyList()
                every { resolutionRepo.findAllActive() } returns emptyList()
                every { projectLookupRepo.findProjectKeyById(projectId) } returns "BTS"

                val result = sut.generate(actorId, "BTS", versionId)

                result.versionId shouldBe versionId
                result.projectKey shouldBe "BTS"
                result.versionName shouldBe "v1.0.0"
                result.issueCount shouldBe 0
                result.generatedAt shouldBe fixedInstant
            }
        }

        context("정상 흐름 — resolution 맵 주입") {
            it("resolutionId 가 있으면 resolutionName 이 매핑된다") {
                val row = bugRow(key = "BTS-1", resolutionId = resolutionId)
                val fixedResolution = resolution(resolutionId, "Fixed")

                every { projectLookup.resolve("BTS") } returns projectId
                every { versionRepo.findById(versionId, projectId) } returns activeVersion
                every { issueRepo.findFixVersionIssuesForReleaseNotes(versionId) } returns listOf(row)
                every { resolutionRepo.findAllActive() } returns listOf(fixedResolution)
                every { projectLookupRepo.findProjectKeyById(projectId) } returns "BTS"

                val result = sut.generate(actorId, "BTS", versionId)

                result.issueCount shouldBe 1
                result.markdown shouldBe result.markdown // Generator 위임 — markdown 존재 확인
                result.markdown.contains("Fixed") shouldBe true
            }

            it("resolutionId null 이면 resolutionName 이 null 로 매핑된다") {
                val row = bugRow(key = "BTS-2", resolutionId = null)

                every { projectLookup.resolve("BTS") } returns projectId
                every { versionRepo.findById(versionId, projectId) } returns activeVersion
                every { issueRepo.findFixVersionIssuesForReleaseNotes(versionId) } returns listOf(row)
                every { resolutionRepo.findAllActive() } returns emptyList()
                every { projectLookupRepo.findProjectKeyById(projectId) } returns "BTS"

                val result = sut.generate(actorId, "BTS", versionId)

                result.issueCount shouldBe 1
                // resolutionName 미표시 — "Fixed", "Done" 등 resolution 이름이 markdown 에 없음
                result.markdown.contains("Fixed") shouldBe false
                result.markdown.contains("Done") shouldBe false
            }
        }

        context("ReleaseNotes 필드 검증") {
            it("Clock 고정 시 generatedAt 이 결정적이다") {
                every { projectLookup.resolve("BTS") } returns projectId
                every { versionRepo.findById(versionId, projectId) } returns activeVersion
                every { issueRepo.findFixVersionIssuesForReleaseNotes(versionId) } returns emptyList()
                every { resolutionRepo.findAllActive() } returns emptyList()
                every { projectLookupRepo.findProjectKeyById(projectId) } returns "BTS"

                val result = sut.generate(actorId, "BTS", versionId)

                result.generatedAt shouldBe fixedInstant
            }

            it("versionStatus, releaseDate 가 ReleaseNotes 에 올바르게 매핑된다") {
                val releasedVersion =
                    activeVersion.copy(
                        status = VersionStatus.RELEASED,
                        releaseDate = LocalDate.of(2026, 6, 10),
                    )

                every { projectLookup.resolve("BTS") } returns projectId
                every { versionRepo.findById(versionId, projectId) } returns releasedVersion
                every { issueRepo.findFixVersionIssuesForReleaseNotes(versionId) } returns emptyList()
                every { resolutionRepo.findAllActive() } returns emptyList()
                every { projectLookupRepo.findProjectKeyById(projectId) } returns "BTS"

                val result = sut.generate(actorId, "BTS", versionId)

                result.versionStatus shouldBe VersionStatus.RELEASED
                result.releaseDate shouldBe LocalDate.of(2026, 6, 10)
            }

            it("resolutionRepo.findAllActive() 는 1회만 호출된다 (N+1 회피)") {
                val rows =
                    listOf(
                        bugRow("BTS-1", resolutionId = resolutionId),
                        bugRow("BTS-2", resolutionId = resolutionId),
                        bugRow("BTS-3", resolutionId = null),
                    )

                every { projectLookup.resolve("BTS") } returns projectId
                every { versionRepo.findById(versionId, projectId) } returns activeVersion
                every { issueRepo.findFixVersionIssuesForReleaseNotes(versionId) } returns rows
                every { resolutionRepo.findAllActive() } returns listOf(resolution(resolutionId, "Fixed"))
                every { projectLookupRepo.findProjectKeyById(projectId) } returns "BTS"

                sut.generate(actorId, "BTS", versionId)

                // N+1 회피 — 이슈 수와 무관하게 findAllActive 는 1회
                verify(exactly = 1) { resolutionRepo.findAllActive() }
            }
        }
    }
})
