// CustomFieldApplicationService 단위 테스트 — MockK 기반 CRUD 전체 경로 검증

package com.bts.issue.customfield.application

import com.bts.issue.customfield.domain.CustomFieldDefinition
import com.bts.issue.customfield.domain.CustomFieldNotFoundException
import com.bts.issue.customfield.domain.DuplicateCustomFieldKeyException
import com.bts.issue.customfield.domain.FieldType
import com.bts.issue.customfield.domain.ImmutableFieldTypeChangeException
import com.bts.issue.customfield.repository.CustomFieldDefinitionRepository
import com.bts.issue.project.ProjectLookup
import com.bts.shared.permission.CustomFieldPermission
import com.bts.shared.permission.CustomFieldPermissionResolver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.dao.DataIntegrityViolationException
import java.sql.SQLException
import java.util.UUID

/**
 * CustomFieldApplicationService MockK 단위 테스트.
 *
 * 각 CRUD 메서드의 정상 경로 + 오류 경로(권한 거부, 프로젝트 미존재,
 * 필드 미존재, fieldType/key 불변, 키 중복)를 검증한다.
 *
 * 트랜잭션/DB 경계는 Testcontainers 통합 테스트(Task 10)로 별도 검증한다.
 */
@Suppress("LargeClass")
class CustomFieldApplicationServiceTest : DescribeSpec({

    val permissionResolver = mockk<CustomFieldPermissionResolver>()
    val projectLookup = mockk<ProjectLookup>()
    val repo = mockk<CustomFieldDefinitionRepository>()

    val sut =
        CustomFieldApplicationService(
            permissionResolver = permissionResolver,
            projectLookup = projectLookup,
            repo = repo,
        )

    val actorId = UUID.randomUUID()
    val projectIdOrKey = "BTS"
    val projectId = UUID.randomUUID()
    val fieldId = UUID.randomUUID()

    val existingField =
        CustomFieldDefinition(
            id = fieldId,
            projectId = projectId,
            key = "salary_impact",
            name = "급여 영향도",
            fieldType = FieldType.SHORT_TEXT,
            required = false,
            displayOrder = 0,
            options = emptyList(),
        )

    afterEach { clearMocks(permissionResolver, projectLookup, repo) }

    // ── create ────────────────────────────────────────────────────────────────

    describe("create") {
        context("정상 경로") {
            it("권한 확인 → 프로젝트 resolve → 도메인 생성 → save 반환") {
                every {
                    permissionResolver.hasPermission(actorId, CustomFieldPermission.CREATE, projectId)
                } returns true
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                val saved = existingField.copy(id = UUID.randomUUID())
                every { repo.save(any()) } returns saved

                val result =
                    sut.create(
                        actorId = actorId,
                        projectIdOrKey = projectIdOrKey,
                        key = "salary_impact",
                        name = "급여 영향도",
                        fieldType = FieldType.SHORT_TEXT,
                        required = false,
                        displayOrder = 0,
                        options = emptyList(),
                    )

                result shouldBe saved
                verify(exactly = 1) { repo.save(any()) }
            }
        }

        context("오류 경로 — 권한 없음") {
            it("CustomFieldAccessDeniedException 발생, save 미호출") {
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                every {
                    permissionResolver.hasPermission(actorId, CustomFieldPermission.CREATE, projectId)
                } returns false

                shouldThrow<CustomFieldAccessDeniedException> {
                    sut.create(actorId, projectIdOrKey, "k", "N", FieldType.SHORT_TEXT, false, 0, emptyList())
                }
                verify(exactly = 0) { repo.save(any()) }
            }
        }

        context("오류 경로 — 프로젝트 미존재") {
            it("CustomFieldProjectNotFoundException 발생") {
                every { projectLookup.resolve(projectIdOrKey) } returns null

                shouldThrow<CustomFieldProjectNotFoundException> {
                    sut.create(actorId, projectIdOrKey, "k", "N", FieldType.SHORT_TEXT, false, 0, emptyList())
                }
                verify(exactly = 0) { repo.save(any()) }
            }
        }

        context("오류 경로 — 키 중복(23505)") {
            it("DuplicateCustomFieldKeyException 으로 변환") {
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                every {
                    permissionResolver.hasPermission(actorId, CustomFieldPermission.CREATE, projectId)
                } returns true
                val cause = SQLException("unique_violation", "23505")
                every { repo.save(any()) } throws DataIntegrityViolationException("dup", cause)

                shouldThrow<DuplicateCustomFieldKeyException> {
                    sut.create(actorId, projectIdOrKey, "salary_impact", "급여", FieldType.SHORT_TEXT, false, 0, emptyList())
                }
            }
        }
    }

    // ── update ────────────────────────────────────────────────────────────────

    describe("update") {
        context("정상 경로 — name 변경") {
            it("권한 → 프로젝트 resolve → 기존 필드 조회 → 불변식 검증 통과 → update 반환") {
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                every {
                    permissionResolver.hasPermission(actorId, CustomFieldPermission.UPDATE, projectId)
                } returns true
                every { repo.findById(fieldId, projectId) } returns existingField
                val updated = existingField.copy(name = "급여 영향")
                every { repo.update(any()) } returns updated

                val result =
                    sut.update(
                        actorId = actorId,
                        projectIdOrKey = projectIdOrKey,
                        fieldId = fieldId,
                        name = "급여 영향",
                        fieldType = FieldType.SHORT_TEXT,
                        key = "salary_impact",
                        required = null,
                        displayOrder = null,
                    )

                result.name shouldBe "급여 영향"
                verify(exactly = 1) { repo.update(any()) }
            }
        }

        context("오류 경로 — fieldType 변경 시도") {
            it("ImmutableFieldTypeChangeException(422 소스) 발생") {
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                every {
                    permissionResolver.hasPermission(actorId, CustomFieldPermission.UPDATE, projectId)
                } returns true
                every { repo.findById(fieldId, projectId) } returns existingField

                shouldThrow<ImmutableFieldTypeChangeException> {
                    sut.update(
                        actorId = actorId,
                        projectIdOrKey = projectIdOrKey,
                        fieldId = fieldId,
                        name = null,
                        fieldType = FieldType.NUMBER,
                        key = "salary_impact",
                        required = null,
                        displayOrder = null,
                    )
                }
                verify(exactly = 0) { repo.update(any()) }
            }
        }

        context("오류 경로 — key 변경 시도") {
            it("ImmutableFieldTypeChangeException(422 소스) 발생") {
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                every {
                    permissionResolver.hasPermission(actorId, CustomFieldPermission.UPDATE, projectId)
                } returns true
                every { repo.findById(fieldId, projectId) } returns existingField

                shouldThrow<ImmutableFieldTypeChangeException> {
                    sut.update(
                        actorId = actorId,
                        projectIdOrKey = projectIdOrKey,
                        fieldId = fieldId,
                        name = null,
                        fieldType = FieldType.SHORT_TEXT,
                        key = "different_key",
                        required = null,
                        displayOrder = null,
                    )
                }
                verify(exactly = 0) { repo.update(any()) }
            }
        }

        context("오류 경로 — 권한 없음") {
            it("CustomFieldAccessDeniedException 발생") {
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                every {
                    permissionResolver.hasPermission(actorId, CustomFieldPermission.UPDATE, projectId)
                } returns false

                shouldThrow<CustomFieldAccessDeniedException> {
                    sut.update(actorId, projectIdOrKey, fieldId, null, FieldType.SHORT_TEXT, "k", null, null)
                }
                verify(exactly = 0) { repo.update(any()) }
            }
        }

        context("오류 경로 — 필드 미존재") {
            it("CustomFieldNotFoundException(404 소스) 발생") {
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                every {
                    permissionResolver.hasPermission(actorId, CustomFieldPermission.UPDATE, projectId)
                } returns true
                every { repo.findById(fieldId, projectId) } returns null

                shouldThrow<CustomFieldNotFoundException> {
                    sut.update(actorId, projectIdOrKey, fieldId, null, FieldType.SHORT_TEXT, "salary_impact", null, null)
                }
                verify(exactly = 0) { repo.update(any()) }
            }
        }

        context("오류 경로 — 프로젝트 미존재") {
            it("CustomFieldProjectNotFoundException 발생") {
                every { projectLookup.resolve(projectIdOrKey) } returns null

                shouldThrow<CustomFieldProjectNotFoundException> {
                    sut.update(actorId, projectIdOrKey, fieldId, null, FieldType.SHORT_TEXT, "k", null, null)
                }
            }
        }
    }

    // ── softDelete ────────────────────────────────────────────────────────────

    describe("softDelete") {
        context("정상 경로") {
            it("권한 → 존재 검증 → softDelete 호출") {
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                every {
                    permissionResolver.hasPermission(actorId, CustomFieldPermission.DELETE, projectId)
                } returns true
                every { repo.findById(fieldId, projectId) } returns existingField
                every { repo.softDelete(fieldId, projectId) } returns Unit

                sut.softDelete(actorId, projectIdOrKey, fieldId)

                verify(exactly = 1) { repo.softDelete(fieldId, projectId) }
            }
        }

        context("오류 경로 — 권한 없음") {
            it("CustomFieldAccessDeniedException 발생, softDelete 미호출") {
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                every {
                    permissionResolver.hasPermission(actorId, CustomFieldPermission.DELETE, projectId)
                } returns false

                shouldThrow<CustomFieldAccessDeniedException> {
                    sut.softDelete(actorId, projectIdOrKey, fieldId)
                }
                verify(exactly = 0) { repo.softDelete(any(), any()) }
            }
        }

        context("오류 경로 — 필드 미존재") {
            it("CustomFieldNotFoundException 발생, softDelete 미호출") {
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                every {
                    permissionResolver.hasPermission(actorId, CustomFieldPermission.DELETE, projectId)
                } returns true
                every { repo.findById(fieldId, projectId) } returns null

                shouldThrow<CustomFieldNotFoundException> {
                    sut.softDelete(actorId, projectIdOrKey, fieldId)
                }
                verify(exactly = 0) { repo.softDelete(any(), any()) }
            }
        }

        context("오류 경로 — 프로젝트 미존재") {
            it("CustomFieldProjectNotFoundException 발생") {
                every { projectLookup.resolve(projectIdOrKey) } returns null

                shouldThrow<CustomFieldProjectNotFoundException> {
                    sut.softDelete(actorId, projectIdOrKey, fieldId)
                }
            }
        }
    }

    // ── getById ───────────────────────────────────────────────────────────────

    describe("getById") {
        context("정상 경로") {
            it("프로젝트 resolve → findById → 반환") {
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                every { repo.findById(fieldId, projectId) } returns existingField

                val result = sut.getById(actorId, projectIdOrKey, fieldId)

                result shouldBe existingField
            }
        }

        context("오류 경로 — 필드 미존재") {
            it("CustomFieldNotFoundException 발생") {
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                every { repo.findById(fieldId, projectId) } returns null

                shouldThrow<CustomFieldNotFoundException> {
                    sut.getById(actorId, projectIdOrKey, fieldId)
                }
            }
        }

        context("오류 경로 — 프로젝트 미존재") {
            it("CustomFieldProjectNotFoundException 발생") {
                every { projectLookup.resolve(projectIdOrKey) } returns null

                shouldThrow<CustomFieldProjectNotFoundException> {
                    sut.getById(actorId, projectIdOrKey, fieldId)
                }
            }
        }
    }

    // ── listByProject ─────────────────────────────────────────────────────────

    describe("listByProject") {
        context("정상 경로") {
            it("프로젝트 resolve → findActiveByProject 반환") {
                val fields = listOf(existingField, existingField.copy(id = UUID.randomUUID(), key = "other"))
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                every { repo.findActiveByProject(projectId) } returns fields

                val result = sut.listByProject(actorId, projectIdOrKey)

                result shouldBe fields
                verify(exactly = 1) { repo.findActiveByProject(projectId) }
            }
        }

        context("정상 경로 — 빈 목록") {
            it("빈 리스트 반환") {
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                every { repo.findActiveByProject(projectId) } returns emptyList()

                val result = sut.listByProject(actorId, projectIdOrKey)

                result shouldBe emptyList()
            }
        }

        context("오류 경로 — 프로젝트 미존재") {
            it("CustomFieldProjectNotFoundException 발생") {
                every { projectLookup.resolve(projectIdOrKey) } returns null

                shouldThrow<CustomFieldProjectNotFoundException> {
                    sut.listByProject(actorId, projectIdOrKey)
                }
            }
        }
    }
})
