// IssueTemplateApplicationService 단위 테스트 — MockK 기반 CRUD+resolve 전체 경로 검증

package com.bts.issue.template.application

import com.bts.issue.project.archive.ProjectArchiveGuard
import com.bts.issue.project.archive.ProjectArchivedException
import com.bts.issue.template.domain.DuplicateIssueTemplateException
import com.bts.issue.template.domain.InvalidIssueTemplateException
import com.bts.issue.template.domain.IssueTemplate
import com.bts.issue.template.domain.IssueTemplateAccessDeniedException
import com.bts.issue.template.domain.IssueTemplateNotFoundException
import com.bts.issue.template.repository.IssueTemplateRepository
import com.bts.issue.type.domain.IssueTypeNotFoundException
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.permission.TemplatePermission
import com.bts.shared.permission.TemplatePermissionResolver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.dao.DataIntegrityViolationException
import java.sql.SQLException
import java.time.Instant
import java.util.UUID

/**
 * IssueTemplateApplicationService MockK 단위 테스트.
 *
 * 각 메서드의 정상 경로 + 오류 경로(권한 거부, issueType 미존재, 템플릿 미존재, 이름 중복)를 검증한다.
 * 트랜잭션/DB 경계는 Testcontainers 통합 테스트(IssueTemplateRepositoryIntegrationTest)로 별도 검증한다.
 */
@Suppress("LargeClass")
class IssueTemplateApplicationServiceTest : DescribeSpec({

    val permissionResolver = mockk<TemplatePermissionResolver>()
    val repo = mockk<IssueTemplateRepository>()
    val issueTypeRepo = mockk<IssueTypeRepository>()
    val archiveGuard = mockk<ProjectArchiveGuard>(relaxUnitFun = true)

    val sut =
        IssueTemplateApplicationService(
            permissionResolver = permissionResolver,
            repo = repo,
            issueTypeRepository = issueTypeRepo,
            archiveGuard = archiveGuard,
        )

    val actorId = UUID.randomUUID()
    val projectId = UUID.randomUUID()
    val templateId = UUID.randomUUID()
    val issueTypeId = 1L

    val existingTemplate =
        IssueTemplate(
            id = templateId,
            projectId = projectId,
            issueTypeId = issueTypeId,
            name = "버그 리포트",
            content = "## 증상\n\n## 재현 방법\n",
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
            deletedAt = null,
        )

    afterEach { clearMocks(permissionResolver, repo, issueTypeRepo, archiveGuard) }

    // ── create ────────────────────────────────────────────────────────────────

    describe("create") {
        context("정상 경로") {
            it("권한 OK + issueType 존재 → insert 호출 후 반환") {
                every {
                    permissionResolver.hasPermission(actorId, TemplatePermission.CREATE, projectId)
                } returns true
                every { issueTypeRepo.findById(IssueTypeId(issueTypeId)) } returns mockk()
                every { repo.insert(any()) } returns existingTemplate

                val result =
                    sut.create(
                        actorId = actorId,
                        projectId = projectId,
                        issueTypeId = issueTypeId,
                        name = "버그 리포트",
                        content = "## 증상\n\n## 재현 방법\n",
                    )

                result shouldBe existingTemplate
                verify(exactly = 1) { repo.insert(any()) }
            }
        }

        context("오류 경로 — 권한 없음") {
            it("IssueTemplateAccessDeniedException 발생, insert 미호출") {
                every {
                    permissionResolver.hasPermission(actorId, TemplatePermission.CREATE, projectId)
                } returns false

                shouldThrow<IssueTemplateAccessDeniedException> {
                    sut.create(actorId, projectId, issueTypeId, "버그 리포트", "내용")
                }
                verify(exactly = 0) { repo.insert(any()) }
            }
        }

        context("오류 경로 — issueTypeId 미존재") {
            it("IssueTypeNotFoundException 발생, insert 미호출") {
                every {
                    permissionResolver.hasPermission(actorId, TemplatePermission.CREATE, projectId)
                } returns true
                every { issueTypeRepo.findById(IssueTypeId(issueTypeId)) } returns null

                shouldThrow<IssueTypeNotFoundException> {
                    sut.create(actorId, projectId, issueTypeId, "버그 리포트", "내용")
                }
                verify(exactly = 0) { repo.insert(any()) }
            }
        }

        context("오류 경로 — 이름 중복(23505 DataIntegrityViolationException)") {
            it("DuplicateIssueTemplateException 으로 변환") {
                every {
                    permissionResolver.hasPermission(actorId, TemplatePermission.CREATE, projectId)
                } returns true
                every { issueTypeRepo.findById(IssueTypeId(issueTypeId)) } returns mockk()
                val cause = SQLException("unique_violation", "23505")
                every { repo.insert(any()) } throws DataIntegrityViolationException("dup", cause)

                shouldThrow<DuplicateIssueTemplateException> {
                    sut.create(actorId, projectId, issueTypeId, "버그 리포트", "내용")
                }
            }
        }

        context("오류 경로 — 이름 중복(23505 jOOQ IntegrityConstraintViolationException)") {
            it("DuplicateIssueTemplateException 으로 변환") {
                every {
                    permissionResolver.hasPermission(actorId, TemplatePermission.CREATE, projectId)
                } returns true
                every { issueTypeRepo.findById(IssueTypeId(issueTypeId)) } returns mockk()
                val sqlEx = SQLException("unique_violation", "23505")
                val jooqEx = org.jooq.exception.IntegrityConstraintViolationException("dup", sqlEx)
                every { repo.insert(any()) } throws jooqEx

                shouldThrow<DuplicateIssueTemplateException> {
                    sut.create(actorId, projectId, issueTypeId, "버그 리포트", "내용")
                }
            }
        }

        context("오류 경로 — 기타 DataIntegrityViolationException(23505 아님)") {
            it("원 예외를 re-throw 한다") {
                every {
                    permissionResolver.hasPermission(actorId, TemplatePermission.CREATE, projectId)
                } returns true
                every { issueTypeRepo.findById(IssueTypeId(issueTypeId)) } returns mockk()
                val cause = SQLException("fk_violation", "23503")
                val ex = DataIntegrityViolationException("fk", cause)
                every { repo.insert(any()) } throws ex

                shouldThrow<DataIntegrityViolationException> {
                    sut.create(actorId, projectId, issueTypeId, "버그 리포트", "내용")
                }
            }
        }
    }

    // ── update ────────────────────────────────────────────────────────────────

    describe("update") {
        context("정상 경로 — name 변경, content null") {
            it("권한 OK + 존재 → update 후 변경된 객체 반환") {
                every {
                    permissionResolver.hasPermission(actorId, TemplatePermission.UPDATE, projectId)
                } returns true
                every { repo.findById(templateId) } returns existingTemplate
                // withChanges 는 content.trim() 을 적용하므로 trim 된 값으로 stub
                val trimmedContent = existingTemplate.content.trim()
                every { repo.update(templateId, "수정된 리포트", trimmedContent) } returns Unit

                val result = sut.update(actorId, projectId, templateId, "수정된 리포트", null)

                result.name shouldBe "수정된 리포트"
                result.content shouldBe trimmedContent
                verify(exactly = 1) { repo.update(templateId, "수정된 리포트", trimmedContent) }
            }
        }

        context("오류 경로 — 권한 없음") {
            it("IssueTemplateAccessDeniedException 발생, update 미호출") {
                every {
                    permissionResolver.hasPermission(actorId, TemplatePermission.UPDATE, projectId)
                } returns false

                shouldThrow<IssueTemplateAccessDeniedException> {
                    sut.update(actorId, projectId, templateId, "수정", "내용")
                }
                verify(exactly = 0) { repo.update(any(), any(), any()) }
            }
        }

        context("오류 경로 — 템플릿 미존재") {
            it("IssueTemplateNotFoundException 발생") {
                every {
                    permissionResolver.hasPermission(actorId, TemplatePermission.UPDATE, projectId)
                } returns true
                every { repo.findById(templateId) } returns null

                shouldThrow<IssueTemplateNotFoundException> {
                    sut.update(actorId, projectId, templateId, "수정", "내용")
                }
                verify(exactly = 0) { repo.update(any(), any(), any()) }
            }
        }

        context("오류 경로 — content 빈 문자열(도메인 불변식 위반)") {
            it("InvalidIssueTemplateException 발생, repo.update 미호출") {
                every {
                    permissionResolver.hasPermission(actorId, TemplatePermission.UPDATE, projectId)
                } returns true
                every { repo.findById(templateId) } returns existingTemplate

                shouldThrow<InvalidIssueTemplateException> {
                    sut.update(actorId, projectId, templateId, null, "")
                }
                verify(exactly = 0) { repo.update(any(), any(), any()) }
            }
        }

        context("오류 경로 — name 빈 문자열(도메인 불변식 위반)") {
            it("InvalidIssueTemplateException 발생, repo.update 미호출") {
                every {
                    permissionResolver.hasPermission(actorId, TemplatePermission.UPDATE, projectId)
                } returns true
                every { repo.findById(templateId) } returns existingTemplate

                shouldThrow<InvalidIssueTemplateException> {
                    sut.update(actorId, projectId, templateId, "", null)
                }
                verify(exactly = 0) { repo.update(any(), any(), any()) }
            }
        }

        context("정상 경로 — name null 은 기존 name 유지") {
            it("repo.update 에 기존 name 전달") {
                every {
                    permissionResolver.hasPermission(actorId, TemplatePermission.UPDATE, projectId)
                } returns true
                every { repo.findById(templateId) } returns existingTemplate
                every { repo.update(templateId, existingTemplate.name, "새 내용") } returns Unit

                val result = sut.update(actorId, projectId, templateId, null, "새 내용")

                result.name shouldBe existingTemplate.name
                result.content shouldBe "새 내용"
                verify(exactly = 1) { repo.update(templateId, existingTemplate.name, "새 내용") }
            }
        }
    }

    // ── delete ────────────────────────────────────────────────────────────────

    describe("delete") {
        context("정상 경로") {
            it("권한 OK + 존재 → softDelete 호출") {
                every {
                    permissionResolver.hasPermission(actorId, TemplatePermission.DELETE, projectId)
                } returns true
                every { repo.findById(templateId) } returns existingTemplate
                every { repo.softDelete(templateId) } returns Unit

                sut.delete(actorId, projectId, templateId)

                verify(exactly = 1) { repo.softDelete(templateId) }
            }
        }

        context("오류 경로 — 권한 없음") {
            it("IssueTemplateAccessDeniedException 발생, softDelete 미호출") {
                every {
                    permissionResolver.hasPermission(actorId, TemplatePermission.DELETE, projectId)
                } returns false

                shouldThrow<IssueTemplateAccessDeniedException> {
                    sut.delete(actorId, projectId, templateId)
                }
                verify(exactly = 0) { repo.softDelete(any()) }
            }
        }

        context("오류 경로 — 템플릿 미존재") {
            it("IssueTemplateNotFoundException 발생, softDelete 미호출") {
                every {
                    permissionResolver.hasPermission(actorId, TemplatePermission.DELETE, projectId)
                } returns true
                every { repo.findById(templateId) } returns null

                shouldThrow<IssueTemplateNotFoundException> {
                    sut.delete(actorId, projectId, templateId)
                }
                verify(exactly = 0) { repo.softDelete(any()) }
            }
        }
    }

    // ── 아카이브 잠금 (FR-PJ-04 PR-4 Task 9) ─────────────────────────────────────

    /** 쓰기 3종(create/update/delete)의 아카이브 잠금 판별자. VersionApplicationServiceTest 동형. */
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
                        permissionResolver.hasPermission(actorId, TemplatePermission.CREATE, projectId)
                    } returns true
                    every { issueTypeRepo.findById(IssueTypeId(issueTypeId)) } returns mockk()
                    every { repo.insert(any()) } returns existingTemplate
                },
                invoke = { sut.create(actorId, projectId, issueTypeId, "버그 리포트", "## 증상\n\n## 재현 방법\n") },
            ),
            ArchiveWriteCase(
                label = "update",
                stubHappyPath = {
                    every {
                        permissionResolver.hasPermission(actorId, TemplatePermission.UPDATE, projectId)
                    } returns true
                    every { repo.findById(templateId) } returns existingTemplate
                    every { repo.update(templateId, existingTemplate.name, existingTemplate.content.trim()) } returns Unit
                },
                invoke = { sut.update(actorId, projectId, templateId, null, null) },
            ),
            ArchiveWriteCase(
                label = "delete",
                stubHappyPath = {
                    every {
                        permissionResolver.hasPermission(actorId, TemplatePermission.DELETE, projectId)
                    } returns true
                    every { repo.findById(templateId) } returns existingTemplate
                    every { repo.softDelete(templateId) } returns Unit
                },
                invoke = { sut.delete(actorId, projectId, templateId) },
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
        context("정상 경로 — READ 미게이트") {
            it("권한 검증 없이 findById → 반환") {
                every { repo.findById(templateId) } returns existingTemplate

                val result = sut.getById(actorId, templateId)

                result shouldBe existingTemplate
                verify(exactly = 0) { permissionResolver.hasPermission(any(), any(), any()) }
            }
        }

        context("오류 경로 — 미존재") {
            it("IssueTemplateNotFoundException 발생") {
                every { repo.findById(templateId) } returns null

                shouldThrow<IssueTemplateNotFoundException> {
                    sut.getById(actorId, templateId)
                }
            }
        }
    }

    // ── listByProject ─────────────────────────────────────────────────────────

    describe("listByProject") {
        context("정상 경로 — READ 미게이트") {
            it("권한 검증 없이 findByProject → 반환") {
                val templates = listOf(existingTemplate)
                every { repo.findByProject(projectId) } returns templates

                val result = sut.listByProject(actorId, projectId)

                result shouldBe templates
                verify(exactly = 0) { permissionResolver.hasPermission(any(), any(), any()) }
            }
        }

        context("정상 경로 — 빈 목록") {
            it("빈 리스트 반환") {
                every { repo.findByProject(projectId) } returns emptyList()

                val result = sut.listByProject(actorId, projectId)

                result shouldBe emptyList()
            }
        }
    }

    // ── resolve ───────────────────────────────────────────────────────────────

    describe("resolve") {
        context("정상 경로 — 활성 템플릿 존재") {
            it("content 반환") {
                every {
                    repo.findActiveContentByProjectAndType(projectId, issueTypeId)
                } returns "## 증상\n\n"

                val result = sut.resolve(projectId, issueTypeId)

                result shouldBe "## 증상\n\n"
                verify(exactly = 0) { permissionResolver.hasPermission(any(), any(), any()) }
            }
        }

        context("정상 경로 — 활성 템플릿 없음") {
            it("null 반환") {
                every { repo.findActiveContentByProjectAndType(projectId, issueTypeId) } returns null

                val result = sut.resolve(projectId, issueTypeId)

                result shouldBe null
            }
        }
    }
})
