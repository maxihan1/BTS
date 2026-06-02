// VersionApplicationService MockK 단위 테스트 — 6개 메서드 전체 경로 검증

package com.bts.issue.version.application

import com.bts.issue.project.ProjectLookup
import com.bts.issue.version.domain.DuplicateVersionNameException
import com.bts.issue.version.domain.Version
import com.bts.issue.version.domain.VersionAccessDeniedException
import com.bts.issue.version.domain.VersionNotFoundException
import com.bts.issue.version.domain.VersionProjectNotFoundException
import com.bts.issue.version.repository.VersionRepository
import com.bts.shared.permission.VersionPermission
import com.bts.shared.permission.VersionPermissionResolver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.dao.DataIntegrityViolationException
import java.sql.SQLException
import java.time.LocalDate
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
 * - delete: 권한→존재→softDelete
 * - getById: 존재검증→반환
 * - listByProject: 프로젝트 존재→목록 반환
 */
@Suppress("LargeClass")
class VersionApplicationServiceTest : DescribeSpec({

    val permissionResolver = mockk<VersionPermissionResolver>()
    val projectLookup = mockk<ProjectLookup>()
    val repo = mockk<VersionRepository>()

    val sut =
        VersionApplicationService(
            permissionResolver = permissionResolver,
            projectLookup = projectLookup,
            repo = repo,
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

    afterEach { clearMocks(permissionResolver, projectLookup, repo) }

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
