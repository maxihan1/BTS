// VersionApplicationService MockK 단위 테스트 — 6개 메서드 전체 경로 검증

package com.bts.issue.version.application

import com.bts.issue.project.ProjectLookup
import com.bts.issue.project.archive.ProjectArchiveGuard
import com.bts.issue.project.archive.ProjectArchivedException
import com.bts.issue.version.domain.DuplicateVersionNameException
import com.bts.issue.version.domain.Version
import com.bts.issue.version.domain.VersionAccessDeniedException
import com.bts.issue.version.domain.VersionNotFoundException
import com.bts.issue.version.domain.VersionProjectNotFoundException
import com.bts.issue.version.domain.VersionStatus
import com.bts.issue.version.domain.VersionTransitionNotAllowedException
import com.bts.issue.version.repository.VersionRepository
import com.bts.shared.permission.VersionPermission
import com.bts.shared.permission.VersionPermissionResolver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.dao.DataIntegrityViolationException
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * VersionApplicationService MockK 단위 테스트.
 *
 * 각 메서드별 정상 경로(happy path)와 오류 경로(error path)를 검증한다.
 * 트랜잭션/DB 경계는 Testcontainers 통합 테스트로 별도 검증한다.
 *
 * 테스트 대상 메서드.
 * - create: 날짜 有/無, 프로젝트 미존재, 권한 없음, 이름 중복(23505)
 * - update: name/description 변경, 미존재 케이스
 * - changeDates: 날짜 지정/해제, 미존재 케이스
 * - delete: 권한→존재→softDelete, ARCHIVED 거부(결함 A 회귀 가드)
 * - changeStatus: RELEASED/UNRELEASED/ARCHIVED 전이, 불허 전이 거부
 * - getById: 존재검증→반환
 * - listByProject: 프로젝트 존재→목록 반환
 */
@Suppress("LargeClass")
class VersionApplicationServiceTest : DescribeSpec({

    val permissionResolver = mockk<VersionPermissionResolver>()
    val projectLookup = mockk<ProjectLookup>()
    val repo = mockk<VersionRepository>()
    // FR-PJ-04 PR-4 Task 9 — Unit 함수(archiveGuard.check 는 Unit 반환)만 relax. 기존 happy-path 테스트는
    // 이 mock 을 stub 하지 않으므로 그대로 no-op 통과하고, 아카이브 판별자 테스트만 throws 로 override 한다.
    val archiveGuard = mockk<ProjectArchiveGuard>(relaxUnitFun = true)

    /** 결정론적 Clock — 2026-06-10T12:00:00Z 고정. */
    val fixedInstant = Instant.parse("2026-06-10T12:00:00Z")
    val fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC)

    val sut =
        VersionApplicationService(
            permissionResolver = permissionResolver,
            projectLookup = projectLookup,
            repo = repo,
            archiveGuard = archiveGuard,
            clock = fixedClock,
        )

    val actorId = UUID.randomUUID()
    val projectIdOrKey = "BTS"
    val projectId = UUID.randomUUID()
    val versionId = UUID.randomUUID()

    val activeVersion =
        Version(
            id = versionId,
            projectId = projectId,
            name = "v1.0.0",
            description = "첫 릴리스",
            startDate = null,
            releaseDate = null,
            deletedAt = null,
        )

    afterEach { clearMocks(permissionResolver, projectLookup, repo, archiveGuard) }

    // ── create ────────────────────────────────────────────────────────────────

    describe("create") {
        context("정상 경로 — 날짜 없음") {
            it("권한 확인 → 프로젝트 resolve → 도메인 생성 → insert 반환") {
                every { permissionResolver.hasPermission(actorId, VersionPermission.CREATE, projectId) } returns true
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                val inserted = activeVersion.copy(id = UUID.randomUUID())
                every { repo.insert(any()) } returns inserted

                val result =
                    sut.create(
                        actorId = actorId,
                        projectIdOrKey = projectIdOrKey,
                        name = "v1.0.0",
                        description = "첫 릴리스",
                        startDate = null,
                        releaseDate = null,
                    )

                result shouldBe inserted
                verify(exactly = 1) { repo.insert(any()) }
            }
        }

        context("정상 경로 — 날짜 있음") {
            it("startDate, releaseDate 를 도메인에 전달하고 insert 반환") {
                val start = LocalDate.of(2026, 6, 1)
                val release = LocalDate.of(2026, 6, 30)
                every { permissionResolver.hasPermission(actorId, VersionPermission.CREATE, projectId) } returns true
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                val inserted = activeVersion.copy(startDate = start, releaseDate = release)
                every { repo.insert(any()) } returns inserted

                val result =
                    sut.create(
                        actorId = actorId,
                        projectIdOrKey = projectIdOrKey,
                        name = "v1.0.0",
                        description = null,
                        startDate = start,
                        releaseDate = release,
                    )

                result.startDate shouldBe start
                result.releaseDate shouldBe release
                verify(exactly = 1) { repo.insert(any()) }
            }
        }

        context("오류 경로 — 프로젝트 미존재") {
            it("VersionProjectNotFoundException 발생") {
                every { projectLookup.resolve(projectIdOrKey) } returns null

                shouldThrow<VersionProjectNotFoundException> {
                    sut.create(actorId, projectIdOrKey, "v1.0.0", null, null, null)
                }
                verify(exactly = 0) { repo.insert(any()) }
            }
        }

        context("오류 경로 — 권한 없음") {
            it("VersionAccessDeniedException 발생") {
                every { permissionResolver.hasPermission(actorId, VersionPermission.CREATE, projectId) } returns false
                every { projectLookup.resolve(projectIdOrKey) } returns projectId

                shouldThrow<VersionAccessDeniedException> {
                    sut.create(actorId, projectIdOrKey, "v1.0.0", null, null, null)
                }
                verify(exactly = 0) { repo.insert(any()) }
            }
        }

        context("오류 경로 — 이름 중복(23505)") {
            it("DuplicateVersionNameException 으로 변환") {
                every { permissionResolver.hasPermission(actorId, VersionPermission.CREATE, projectId) } returns true
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                val cause = SQLException("unique_violation", "23505")
                every { repo.insert(any()) } throws DataIntegrityViolationException("dup", cause)

                shouldThrow<DuplicateVersionNameException> {
                    sut.create(actorId, projectIdOrKey, "v1.0.0", null, null, null)
                }
            }
        }
    }

    // ── update ────────────────────────────────────────────────────────────────

    describe("update") {
        context("정상 경로 — name + description 변경") {
            it("도메인 rename/changeDescription 경유 후 repo.update 호출") {
                every { permissionResolver.hasPermission(actorId, VersionPermission.UPDATE, projectId) } returns true
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                every { repo.findById(versionId, projectId) } returns activeVersion
                val updated = activeVersion.copy(name = "v2.0.0", description = "두 번째")
                every { repo.update(any()) } returns updated

                val result =
                    sut.update(
                        actorId = actorId,
                        projectIdOrKey = projectIdOrKey,
                        versionId = versionId,
                        name = "v2.0.0",
                        description = "두 번째",
                    )

                result.name shouldBe "v2.0.0"
                result.description shouldBe "두 번째"
                verify(exactly = 1) { repo.update(any()) }
            }
        }

        context("정상 경로 — name 만 변경") {
            it("description null 이면 기존 값 유지") {
                every { permissionResolver.hasPermission(actorId, VersionPermission.UPDATE, projectId) } returns true
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                every { repo.findById(versionId, projectId) } returns activeVersion
                val updated = activeVersion.copy(name = "v2.0.0")
                every { repo.update(any()) } returns updated

                val result =
                    sut.update(
                        actorId = actorId,
                        projectIdOrKey = projectIdOrKey,
                        versionId = versionId,
                        name = "v2.0.0",
                        description = null,
                    )

                result.name shouldBe "v2.0.0"
                verify(exactly = 1) { repo.update(any()) }
            }
        }

        context("오류 경로 — 버전 미존재") {
            it("VersionNotFoundException 발생") {
                every { permissionResolver.hasPermission(actorId, VersionPermission.UPDATE, projectId) } returns true
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                every { repo.findById(versionId, projectId) } returns null

                shouldThrow<VersionNotFoundException> {
                    sut.update(actorId, projectIdOrKey, versionId, "v2.0.0", null)
                }
                verify(exactly = 0) { repo.update(any()) }
            }
        }

        context("오류 경로 — 프로젝트 미존재") {
            it("VersionProjectNotFoundException 발생") {
                every { projectLookup.resolve(projectIdOrKey) } returns null

                shouldThrow<VersionProjectNotFoundException> {
                    sut.update(actorId, projectIdOrKey, versionId, "v2.0.0", null)
                }
            }
        }

        context("오류 경로 — 권한 없음") {
            it("VersionAccessDeniedException 발생") {
                every { permissionResolver.hasPermission(actorId, VersionPermission.UPDATE, projectId) } returns false
                every { projectLookup.resolve(projectIdOrKey) } returns projectId

                shouldThrow<VersionAccessDeniedException> {
                    sut.update(actorId, projectIdOrKey, versionId, "v2.0.0", null)
                }
            }
        }

        context("오류 경로 — 활성 동명으로 rename 시 23505 위반") {
            it("DataIntegrityViolationException(23505) → DuplicateVersionNameException(409 parity)") {
                every { permissionResolver.hasPermission(actorId, VersionPermission.UPDATE, projectId) } returns true
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                every { repo.findById(versionId, projectId) } returns activeVersion
                val cause = SQLException("unique_violation", "23505")
                every { repo.update(any()) } throws DataIntegrityViolationException("dup", cause)

                shouldThrow<DuplicateVersionNameException> {
                    sut.update(actorId, projectIdOrKey, versionId, "v-existing", null)
                }
            }
        }
    }

    // ── changeDates ───────────────────────────────────────────────────────────

    describe("changeDates") {
        context("정상 경로 — 날짜 지정") {
            it("도메인 changeDates 경유 후 repo.update 호출") {
                val start = LocalDate.of(2026, 6, 1)
                val release = LocalDate.of(2026, 6, 30)
                every { permissionResolver.hasPermission(actorId, VersionPermission.UPDATE, projectId) } returns true
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                every { repo.findById(versionId, projectId) } returns activeVersion
                val updated = activeVersion.copy(startDate = start, releaseDate = release)
                every { repo.update(any()) } returns updated

                val result =
                    sut.changeDates(
                        actorId = actorId,
                        projectIdOrKey = projectIdOrKey,
                        versionId = versionId,
                        startDate = start,
                        releaseDate = release,
                    )

                result.startDate shouldBe start
                result.releaseDate shouldBe release
                verify(exactly = 1) { repo.update(any()) }
            }
        }

        context("정상 경로 — 날짜 해제(null)") {
            it("두 날짜 모두 null 전달 시 해제") {
                val versionWithDates =
                    activeVersion.copy(
                        startDate = LocalDate.of(2026, 1, 1),
                        releaseDate = LocalDate.of(2026, 3, 1),
                    )
                every { permissionResolver.hasPermission(actorId, VersionPermission.UPDATE, projectId) } returns true
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                every { repo.findById(versionId, projectId) } returns versionWithDates
                val updated = versionWithDates.copy(startDate = null, releaseDate = null)
                every { repo.update(any()) } returns updated

                val result =
                    sut.changeDates(
                        actorId = actorId,
                        projectIdOrKey = projectIdOrKey,
                        versionId = versionId,
                        startDate = null,
                        releaseDate = null,
                    )

                result.startDate shouldBe null
                result.releaseDate shouldBe null
                verify(exactly = 1) { repo.update(any()) }
            }
        }

        context("오류 경로 — 버전 미존재") {
            it("VersionNotFoundException 발생") {
                every { permissionResolver.hasPermission(actorId, VersionPermission.UPDATE, projectId) } returns true
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                every { repo.findById(versionId, projectId) } returns null

                shouldThrow<VersionNotFoundException> {
                    sut.changeDates(actorId, projectIdOrKey, versionId, null, null)
                }
                verify(exactly = 0) { repo.update(any()) }
            }
        }

        context("오류 경로 — 권한 없음") {
            it("VersionAccessDeniedException 발생") {
                every { permissionResolver.hasPermission(actorId, VersionPermission.UPDATE, projectId) } returns false
                every { projectLookup.resolve(projectIdOrKey) } returns projectId

                shouldThrow<VersionAccessDeniedException> {
                    sut.changeDates(actorId, projectIdOrKey, versionId, null, null)
                }
            }
        }
    }

    // ── delete ────────────────────────────────────────────────────────────────

    describe("delete") {
        context("정상 경로") {
            it("권한 → 존재 검증 → softDelete 호출") {
                every { permissionResolver.hasPermission(actorId, VersionPermission.DELETE, projectId) } returns true
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                every { repo.findById(versionId, projectId) } returns activeVersion
                every { repo.softDelete(versionId, projectId) } returns Unit

                sut.delete(actorId, projectIdOrKey, versionId)

                verify(exactly = 1) { repo.softDelete(versionId, projectId) }
            }
        }

        context("오류 경로 — 버전 미존재") {
            it("VersionNotFoundException 발생, softDelete 미호출") {
                every { permissionResolver.hasPermission(actorId, VersionPermission.DELETE, projectId) } returns true
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                every { repo.findById(versionId, projectId) } returns null

                shouldThrow<VersionNotFoundException> {
                    sut.delete(actorId, projectIdOrKey, versionId)
                }
                verify(exactly = 0) { repo.softDelete(any(), any()) }
            }
        }

        context("오류 경로 — 권한 없음") {
            it("VersionAccessDeniedException 발생") {
                every { permissionResolver.hasPermission(actorId, VersionPermission.DELETE, projectId) } returns false
                every { projectLookup.resolve(projectIdOrKey) } returns projectId

                shouldThrow<VersionAccessDeniedException> {
                    sut.delete(actorId, projectIdOrKey, versionId)
                }
                verify(exactly = 0) { repo.softDelete(any(), any()) }
            }
        }

        context("오류 경로 — 프로젝트 미존재") {
            it("VersionProjectNotFoundException 발생") {
                every { projectLookup.resolve(projectIdOrKey) } returns null

                shouldThrow<VersionProjectNotFoundException> {
                    sut.delete(actorId, projectIdOrKey, versionId)
                }
            }
        }

        context("오류 경로 — ARCHIVED 버전 삭제 시도 (결함 A 회귀 가드)") {
            it("VersionTransitionNotAllowedException 발생, repo.softDelete 미호출") {
                val archivedVersion = activeVersion.copy(status = VersionStatus.ARCHIVED)
                every { permissionResolver.hasPermission(actorId, VersionPermission.DELETE, projectId) } returns true
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                every { repo.findById(versionId, projectId) } returns archivedVersion

                shouldThrow<VersionTransitionNotAllowedException> {
                    sut.delete(actorId, projectIdOrKey, versionId)
                }
                verify(exactly = 0) { repo.softDelete(any(), any()) }
            }
        }
    }

    // ── changeStatus ──────────────────────────────────────────────────────────

    describe("changeStatus") {
        context("정상 경로 — UNRELEASED → RELEASED") {
            it("도메인 release() 경유, releasedAt=fixedInstant, repo.update 호출") {
                val unreleasedVersion = activeVersion.copy(status = VersionStatus.UNRELEASED, releasedAt = null)
                val expectedReleased =
                    unreleasedVersion.copy(
                        status = VersionStatus.RELEASED,
                        releasedAt = fixedInstant,
                    )
                every { permissionResolver.hasPermission(actorId, VersionPermission.UPDATE, projectId) } returns true
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                every { repo.findById(versionId, projectId) } returns unreleasedVersion
                every { repo.update(any()) } returns expectedReleased

                val result = sut.changeStatus(actorId, projectIdOrKey, versionId, VersionStatus.RELEASED)

                result.status shouldBe VersionStatus.RELEASED
                result.releasedAt shouldBe fixedInstant
                verify(exactly = 1) {
                    repo.update(match { it.status == VersionStatus.RELEASED && it.releasedAt == fixedInstant })
                }
            }
        }

        context("정상 경로 — RELEASED → UNRELEASED (unrelease)") {
            it("도메인 unrelease() 경유, releasedAt=null, repo.update 호출") {
                val releasedVersion = activeVersion.copy(status = VersionStatus.RELEASED, releasedAt = fixedInstant)
                val expectedUnreleased = releasedVersion.copy(status = VersionStatus.UNRELEASED, releasedAt = null)
                every { permissionResolver.hasPermission(actorId, VersionPermission.UPDATE, projectId) } returns true
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                every { repo.findById(versionId, projectId) } returns releasedVersion
                every { repo.update(any()) } returns expectedUnreleased

                val result = sut.changeStatus(actorId, projectIdOrKey, versionId, VersionStatus.UNRELEASED)

                result.status shouldBe VersionStatus.UNRELEASED
                result.releasedAt shouldBe null
                verify(exactly = 1) {
                    repo.update(match { it.status == VersionStatus.UNRELEASED && it.releasedAt == null })
                }
            }
        }

        context("정상 경로 — RELEASED → ARCHIVED") {
            it("도메인 archive() 경유, releasedAt 유지, repo.update 호출") {
                val releasedVersion = activeVersion.copy(status = VersionStatus.RELEASED, releasedAt = fixedInstant)
                val expectedArchived = releasedVersion.copy(status = VersionStatus.ARCHIVED)
                every { permissionResolver.hasPermission(actorId, VersionPermission.UPDATE, projectId) } returns true
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                every { repo.findById(versionId, projectId) } returns releasedVersion
                every { repo.update(any()) } returns expectedArchived

                val result = sut.changeStatus(actorId, projectIdOrKey, versionId, VersionStatus.ARCHIVED)

                result.status shouldBe VersionStatus.ARCHIVED
                result.releasedAt shouldNotBe null
                verify(exactly = 1) { repo.update(match { it.status == VersionStatus.ARCHIVED }) }
            }
        }

        context("정상 경로 — ARCHIVED → UNRELEASED (unarchive)") {
            it("도메인 unarchive() 경유, releasedAt=null, repo.update 호출") {
                val archivedVersion = activeVersion.copy(status = VersionStatus.ARCHIVED, releasedAt = null)
                val expectedUnreleased = archivedVersion.copy(status = VersionStatus.UNRELEASED, releasedAt = null)
                every { permissionResolver.hasPermission(actorId, VersionPermission.UPDATE, projectId) } returns true
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                every { repo.findById(versionId, projectId) } returns archivedVersion
                every { repo.update(any()) } returns expectedUnreleased

                val result = sut.changeStatus(actorId, projectIdOrKey, versionId, VersionStatus.UNRELEASED)

                result.status shouldBe VersionStatus.UNRELEASED
                result.releasedAt shouldBe null
                verify(exactly = 1) { repo.update(any()) }
            }
        }

        context("오류 경로 — 불허 전이 (ARCHIVED → RELEASED)") {
            it("VersionTransitionNotAllowedException 발생, repo.update 미호출") {
                val archivedVersion = activeVersion.copy(status = VersionStatus.ARCHIVED, releasedAt = null)
                every { permissionResolver.hasPermission(actorId, VersionPermission.UPDATE, projectId) } returns true
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                every { repo.findById(versionId, projectId) } returns archivedVersion

                shouldThrow<VersionTransitionNotAllowedException> {
                    sut.changeStatus(actorId, projectIdOrKey, versionId, VersionStatus.RELEASED)
                }
                verify(exactly = 0) { repo.update(any()) }
            }
        }

        context("오류 경로 — ARCHIVED 버전 update(rename) 시도") {
            it("도메인 assertNotArchived 가드가 VersionTransitionNotAllowedException 던짐") {
                val archivedVersion = activeVersion.copy(status = VersionStatus.ARCHIVED)
                every { permissionResolver.hasPermission(actorId, VersionPermission.UPDATE, projectId) } returns true
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                every { repo.findById(versionId, projectId) } returns archivedVersion

                shouldThrow<VersionTransitionNotAllowedException> {
                    sut.update(actorId, projectIdOrKey, versionId, "new-name", null)
                }
                verify(exactly = 0) { repo.update(any()) }
            }
        }

        context("오류 경로 — ARCHIVED 버전 changeDates 시도") {
            it("도메인 assertNotArchived 가드가 VersionTransitionNotAllowedException 던짐") {
                val archivedVersion = activeVersion.copy(status = VersionStatus.ARCHIVED)
                every { permissionResolver.hasPermission(actorId, VersionPermission.UPDATE, projectId) } returns true
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                every { repo.findById(versionId, projectId) } returns archivedVersion

                shouldThrow<VersionTransitionNotAllowedException> {
                    sut.changeDates(actorId, projectIdOrKey, versionId, null, null)
                }
                verify(exactly = 0) { repo.update(any()) }
            }
        }

        context("오류 경로 — 버전 미존재") {
            it("VersionNotFoundException 발생") {
                every { permissionResolver.hasPermission(actorId, VersionPermission.UPDATE, projectId) } returns true
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                every { repo.findById(versionId, projectId) } returns null

                shouldThrow<VersionNotFoundException> {
                    sut.changeStatus(actorId, projectIdOrKey, versionId, VersionStatus.RELEASED)
                }
                verify(exactly = 0) { repo.update(any()) }
            }
        }

        context("오류 경로 — 권한 없음") {
            it("VersionAccessDeniedException 발생") {
                every { permissionResolver.hasPermission(actorId, VersionPermission.UPDATE, projectId) } returns false
                every { projectLookup.resolve(projectIdOrKey) } returns projectId

                shouldThrow<VersionAccessDeniedException> {
                    sut.changeStatus(actorId, projectIdOrKey, versionId, VersionStatus.RELEASED)
                }
            }
        }
    }

    // ── 아카이브 잠금 (FR-PJ-04 PR-4 Task 9) ─────────────────────────────────────

    /**
     * 프로젝트 스코프 쓰기 5종(create/update/changeDates/delete/changeStatus)의 아카이브 잠금 판별자.
     *
     * 각 케이스마다 (아카이브 → ProjectArchivedException 전파) + (활성 → archiveGuard.check 호출 확인,
     * 판별자 [[guard-handler-matrix-blindfold]]) 를 짝지어 검증한다. D-ORDER: assertPermission 통과 후
     * archiveGuard.check 호출 — stubHappyPath 가 permission/project/repo 를 모두 통과시킨 상태에서
     * archiveGuard 만 토글한다.
     */
    data class ArchiveWriteCase(
        val label: String,
        val stubHappyPath: () -> Unit,
        val invoke: () -> Unit,
    )

    val archiveWriteCases =
        listOf(
            ArchiveWriteCase(
                label = "create",
                stubHappyPath = {
                    every {
                        permissionResolver.hasPermission(actorId, VersionPermission.CREATE, projectId)
                    } returns true
                    every { projectLookup.resolve(projectIdOrKey) } returns projectId
                    every { repo.insert(any()) } returns activeVersion.copy(id = UUID.randomUUID())
                },
                invoke = { sut.create(actorId, projectIdOrKey, "v1.0.0", null, null, null) },
            ),
            ArchiveWriteCase(
                label = "update",
                stubHappyPath = {
                    every {
                        permissionResolver.hasPermission(actorId, VersionPermission.UPDATE, projectId)
                    } returns true
                    every { projectLookup.resolve(projectIdOrKey) } returns projectId
                    every { repo.findById(versionId, projectId) } returns activeVersion
                    every { repo.update(any()) } returns activeVersion
                },
                invoke = { sut.update(actorId, projectIdOrKey, versionId, "v2.0.0", null) },
            ),
            ArchiveWriteCase(
                label = "changeDates",
                stubHappyPath = {
                    every {
                        permissionResolver.hasPermission(actorId, VersionPermission.UPDATE, projectId)
                    } returns true
                    every { projectLookup.resolve(projectIdOrKey) } returns projectId
                    every { repo.findById(versionId, projectId) } returns activeVersion
                    every { repo.update(any()) } returns activeVersion
                },
                invoke = { sut.changeDates(actorId, projectIdOrKey, versionId, null, null) },
            ),
            ArchiveWriteCase(
                label = "delete",
                stubHappyPath = {
                    every {
                        permissionResolver.hasPermission(actorId, VersionPermission.DELETE, projectId)
                    } returns true
                    every { projectLookup.resolve(projectIdOrKey) } returns projectId
                    every { repo.findById(versionId, projectId) } returns activeVersion
                    every { repo.softDelete(versionId, projectId) } returns Unit
                },
                invoke = { sut.delete(actorId, projectIdOrKey, versionId) },
            ),
            ArchiveWriteCase(
                label = "changeStatus",
                stubHappyPath = {
                    every {
                        permissionResolver.hasPermission(actorId, VersionPermission.UPDATE, projectId)
                    } returns true
                    every { projectLookup.resolve(projectIdOrKey) } returns projectId
                    every { repo.findById(versionId, projectId) } returns
                        activeVersion.copy(status = VersionStatus.UNRELEASED)
                    every { repo.update(any()) } returns activeVersion
                },
                invoke = { sut.changeStatus(actorId, projectIdOrKey, versionId, VersionStatus.RELEASED) },
            ),
        )

    describe("아카이브 잠금 (FR-PJ-04 PR-4 Task 9)") {
        archiveWriteCases.forEach { case ->
            context("${case.label} — 아카이브된 프로젝트") {
                it("permission 통과 후 archiveGuard.check 가 ProjectArchivedException 을 던지면 그대로 전파된다") {
                    case.stubHappyPath()
                    every { archiveGuard.check(projectId) } throws ProjectArchivedException(projectId.toString())

                    shouldThrow<ProjectArchivedException> { case.invoke() }
                }
            }

            context("${case.label} — 활성 프로젝트 (판별자 baseline)") {
                it("archiveGuard.check 가 실제로 호출된다 (2xx 통과 + 판별자)") {
                    case.stubHappyPath()

                    case.invoke()

                    verify(exactly = 1) { archiveGuard.check(projectId) }
                }
            }
        }
    }

    // ── getById ───────────────────────────────────────────────────────────────

    describe("getById") {
        context("정상 경로") {
            it("프로젝트 resolve → findById → 반환") {
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                every { repo.findById(versionId, projectId) } returns activeVersion

                val result = sut.getById(actorId, projectIdOrKey, versionId)

                result shouldBe activeVersion
            }
        }

        context("오류 경로 — 프로젝트 미존재") {
            it("VersionProjectNotFoundException 발생") {
                every { projectLookup.resolve(projectIdOrKey) } returns null

                shouldThrow<VersionProjectNotFoundException> {
                    sut.getById(actorId, projectIdOrKey, versionId)
                }
            }
        }

        context("오류 경로 — 버전 미존재") {
            it("VersionNotFoundException 발생") {
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                every { repo.findById(versionId, projectId) } returns null

                shouldThrow<VersionNotFoundException> {
                    sut.getById(actorId, projectIdOrKey, versionId)
                }
            }
        }
    }

    // ── listByProject ─────────────────────────────────────────────────────────

    describe("listByProject") {
        context("정상 경로") {
            it("프로젝트 resolve → findByProject 반환") {
                val versions =
                    listOf(
                        activeVersion,
                        activeVersion.copy(id = UUID.randomUUID(), name = "v2.0.0"),
                    )
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                every { repo.findByProject(projectId) } returns versions

                val result = sut.listByProject(actorId, projectIdOrKey)

                result shouldBe versions
                verify(exactly = 1) { repo.findByProject(projectId) }
            }
        }

        context("정상 경로 — 빈 목록") {
            it("빈 리스트 반환") {
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                every { repo.findByProject(projectId) } returns emptyList()

                val result = sut.listByProject(actorId, projectIdOrKey)

                result shouldBe emptyList()
            }
        }

        context("오류 경로 — 프로젝트 미존재") {
            it("VersionProjectNotFoundException 발생") {
                every { projectLookup.resolve(projectIdOrKey) } returns null

                shouldThrow<VersionProjectNotFoundException> {
                    sut.listByProject(actorId, projectIdOrKey)
                }
            }
        }
    }
})
