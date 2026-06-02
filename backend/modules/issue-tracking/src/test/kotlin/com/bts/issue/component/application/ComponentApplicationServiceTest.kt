// ComponentApplicationService 단위 테스트 — MockK 기반 6개 메서드 전체 경로 검증

package com.bts.issue.component.application

import com.bts.issue.component.domain.Component
import com.bts.issue.component.domain.ComponentAccessDeniedException
import com.bts.issue.component.domain.ComponentLeadNotFoundException
import com.bts.issue.component.domain.ComponentNotFoundException
import com.bts.issue.component.domain.ComponentProjectNotFoundException
import com.bts.issue.component.domain.DuplicateComponentNameException
import com.bts.issue.component.repository.ComponentRepository
import com.bts.shared.permission.ComponentPermission
import com.bts.shared.permission.ComponentPermissionResolver
import com.bts.shared.user.UserLookupPort
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
 * ComponentApplicationService MockK 단위 테스트.
 *
 * 각 메서드별 정상 경로(happy path)와 오류 경로(error path)를 검증한다.
 * 트랜잭션/DB 경계는 Testcontainers 통합 테스트로 별도 검증한다 (Task 6 이후).
 *
 * 테스트 대상 메서드.
 * - create: 권한 → 프로젝트 → 리드 검증 → 도메인 생성 → insert → 유니크 위반 변환
 * - update: 권한 → 프로젝트 → 컴포넌트 존재 → 도메인 경유 → repo 위임
 * - changeLead: 권한 → 존재 → 리드 검증 → 도메인 경유 → repo 위임
 * - delete: 권한 → 존재 → softDelete
 * - getById: 존재검증 → 반환
 * - listByProject: 프로젝트 존재 → 목록 반환
 */
@Suppress("LargeClass")
class ComponentApplicationServiceTest : DescribeSpec({

    val permissionResolver = mockk<ComponentPermissionResolver>()
    val projectLookup = mockk<ProjectLookup>()
    val userLookupPort = mockk<UserLookupPort>()
    val repo = mockk<ComponentRepository>()

    val sut = ComponentApplicationService(
        permissionResolver = permissionResolver,
        projectLookup = projectLookup,
        userLookupPort = userLookupPort,
        repo = repo,
    )

    val actorId = UUID.randomUUID()
    val projectIdOrKey = "BTS"
    val projectId = UUID.randomUUID()
    val componentId = UUID.randomUUID()

    val activeComponent = Component(
        id = componentId,
        projectId = projectId,
        name = "Backend",
        description = "백엔드 컴포넌트",
        leadUserId = null,
        deletedAt = null,
    )

    afterEach { clearMocks(permissionResolver, projectLookup, userLookupPort, repo) }

    // ── create ────────────────────────────────────────────────────────────────

    describe("create") {
        context("정상 경로 — 리드 없음") {
            it("권한 확인 → 프로젝트 resolve → 도메인 생성 → insert 반환") {
                every { permissionResolver.hasPermission(actorId, ComponentPermission.CREATE, projectId) } returns true
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                val inserted = activeComponent.copy(id = UUID.randomUUID())
                every { repo.insert(any()) } returns inserted

                val result = sut.create(
                    actorId = actorId,
                    projectIdOrKey = projectIdOrKey,
                    name = "Backend",
                    description = "백엔드 컴포넌트",
                    leadUserId = null,
                )

                result shouldBe inserted
                verify(exactly = 1) { repo.insert(any()) }
                verify(exactly = 0) { userLookupPort.exists(any()) }
            }
        }

        context("정상 경로 — 리드 있음") {
            it("리드 존재 검증 후 도메인 생성") {
                val leadId = UUID.randomUUID()
                every { permissionResolver.hasPermission(actorId, ComponentPermission.CREATE, projectId) } returns true
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                every { userLookupPort.exists(leadId) } returns true
                val inserted = activeComponent.copy(leadUserId = leadId)
                every { repo.insert(any()) } returns inserted

                val result = sut.create(
                    actorId = actorId,
                    projectIdOrKey = projectIdOrKey,
                    name = "Backend",
                    description = null,
                    leadUserId = leadId,
                )

                result.leadUserId shouldBe leadId
                verify(exactly = 1) { userLookupPort.exists(leadId) }
            }
        }

        context("오류 경로 — 권한 없음") {
            it("AccessDeniedException 발생") {
                every { permissionResolver.hasPermission(actorId, ComponentPermission.CREATE, projectId) } returns false
                every { projectLookup.resolve(projectIdOrKey) } returns projectId

                shouldThrow<ComponentAccessDeniedException> {
                    sut.create(actorId, projectIdOrKey, "X", null, null)
                }
                verify(exactly = 0) { repo.insert(any()) }
            }
        }

        context("오류 경로 — 프로젝트 미존재") {
            it("ComponentProjectNotFoundException 발생") {
                every { projectLookup.resolve(projectIdOrKey) } returns null

                shouldThrow<ComponentProjectNotFoundException> {
                    sut.create(actorId, projectIdOrKey, "X", null, null)
                }
                verify(exactly = 0) { repo.insert(any()) }
            }
        }

        context("오류 경로 — 리드 미존재") {
            it("ComponentLeadNotFoundException 발생") {
                val unknownLead = UUID.randomUUID()
                every { permissionResolver.hasPermission(actorId, ComponentPermission.CREATE, projectId) } returns true
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                every { userLookupPort.exists(unknownLead) } returns false

                shouldThrow<ComponentLeadNotFoundException> {
                    sut.create(actorId, projectIdOrKey, "X", null, unknownLead)
                }
                verify(exactly = 0) { repo.insert(any()) }
            }
        }

        context("오류 경로 — 이름 중복(23505)") {
            it("DuplicateComponentNameException 으로 변환") {
                every { permissionResolver.hasPermission(actorId, ComponentPermission.CREATE, projectId) } returns true
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                val cause = SQLException("unique_violation", "23505")
                every { repo.insert(any()) } throws DataIntegrityViolationException("dup", cause)

                shouldThrow<DuplicateComponentNameException> {
                    sut.create(actorId, projectIdOrKey, "Backend", null, null)
                }
            }
        }
    }

    // ── update ────────────────────────────────────────────────────────────────

    describe("update") {
        context("정상 경로 — name + description 변경") {
            it("도메인 rename/changeDescription 경유 후 repo.update 호출") {
                every { permissionResolver.hasPermission(actorId, ComponentPermission.UPDATE, projectId) } returns true
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                every { repo.findById(componentId, projectId) } returns activeComponent
                val updated = activeComponent.copy(name = "Frontend", description = "프론트")
                every { repo.update(any()) } returns updated

                val result = sut.update(
                    actorId = actorId,
                    projectIdOrKey = projectIdOrKey,
                    componentId = componentId,
                    name = "Frontend",
                    description = "프론트",
                )

                result.name shouldBe "Frontend"
                result.description shouldBe "프론트"
                verify(exactly = 1) { repo.update(any()) }
            }
        }

        context("정상 경로 — name 만 변경") {
            it("description 기존값 유지") {
                every { permissionResolver.hasPermission(actorId, ComponentPermission.UPDATE, projectId) } returns true
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                every { repo.findById(componentId, projectId) } returns activeComponent
                val updated = activeComponent.copy(name = "NewName")
                every { repo.update(any()) } returns updated

                val result = sut.update(
                    actorId = actorId,
                    projectIdOrKey = projectIdOrKey,
                    componentId = componentId,
                    name = "NewName",
                    description = null,
                )

                result.name shouldBe "NewName"
                verify(exactly = 1) { repo.update(any()) }
            }
        }

        context("오류 경로 — 컴포넌트 미존재") {
            it("ComponentNotFoundException 발생") {
                every { permissionResolver.hasPermission(actorId, ComponentPermission.UPDATE, projectId) } returns true
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                every { repo.findById(componentId, projectId) } returns null

                shouldThrow<ComponentNotFoundException> {
                    sut.update(actorId, projectIdOrKey, componentId, "X", null)
                }
                verify(exactly = 0) { repo.update(any()) }
            }
        }

        context("오류 경로 — 프로젝트 미존재") {
            it("ComponentProjectNotFoundException 발생") {
                every { projectLookup.resolve(projectIdOrKey) } returns null

                shouldThrow<ComponentProjectNotFoundException> {
                    sut.update(actorId, projectIdOrKey, componentId, "X", null)
                }
            }
        }
    }

    // ── changeLead ────────────────────────────────────────────────────────────

    describe("changeLead") {
        context("정상 경로 — 리드 지정") {
            it("사용자 존재 검증 → 도메인 changeLead 경유 → repo.update") {
                val leadId = UUID.randomUUID()
                every { permissionResolver.hasPermission(actorId, ComponentPermission.UPDATE, projectId) } returns true
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                every { repo.findById(componentId, projectId) } returns activeComponent
                every { userLookupPort.exists(leadId) } returns true
                val updated = activeComponent.copy(leadUserId = leadId)
                every { repo.update(any()) } returns updated

                val result = sut.changeLead(actorId, projectIdOrKey, componentId, leadId)

                result.leadUserId shouldBe leadId
                verify(exactly = 1) { userLookupPort.exists(leadId) }
                verify(exactly = 1) { repo.update(any()) }
            }
        }

        context("정상 경로 — 리드 해제(null)") {
            it("UserLookupPort 호출 없이 도메인 changeLead(null) 경유") {
                val componentWithLead = activeComponent.copy(leadUserId = UUID.randomUUID())
                every { permissionResolver.hasPermission(actorId, ComponentPermission.UPDATE, projectId) } returns true
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                every { repo.findById(componentId, projectId) } returns componentWithLead
                val updated = componentWithLead.copy(leadUserId = null)
                every { repo.update(any()) } returns updated

                val result = sut.changeLead(actorId, projectIdOrKey, componentId, null)

                result.leadUserId shouldBe null
                verify(exactly = 0) { userLookupPort.exists(any()) }
            }
        }

        context("오류 경로 — 리드 미존재") {
            it("ComponentLeadNotFoundException 발생") {
                val unknownLead = UUID.randomUUID()
                every { permissionResolver.hasPermission(actorId, ComponentPermission.UPDATE, projectId) } returns true
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                every { repo.findById(componentId, projectId) } returns activeComponent
                every { userLookupPort.exists(unknownLead) } returns false

                shouldThrow<ComponentLeadNotFoundException> {
                    sut.changeLead(actorId, projectIdOrKey, componentId, unknownLead)
                }
                verify(exactly = 0) { repo.update(any()) }
            }
        }

        context("오류 경로 — 컴포넌트 미존재") {
            it("ComponentNotFoundException 발생") {
                every { permissionResolver.hasPermission(actorId, ComponentPermission.UPDATE, projectId) } returns true
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                every { repo.findById(componentId, projectId) } returns null

                shouldThrow<ComponentNotFoundException> {
                    sut.changeLead(actorId, projectIdOrKey, componentId, null)
                }
            }
        }
    }

    // ── delete ────────────────────────────────────────────────────────────────

    describe("delete") {
        context("정상 경로") {
            it("권한 → 존재 검증 → softDelete 호출") {
                every { permissionResolver.hasPermission(actorId, ComponentPermission.DELETE, projectId) } returns true
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                every { repo.findById(componentId, projectId) } returns activeComponent
                every { repo.softDelete(componentId, projectId) } returns Unit

                sut.delete(actorId, projectIdOrKey, componentId)

                verify(exactly = 1) { repo.softDelete(componentId, projectId) }
            }
        }

        context("오류 경로 — 컴포넌트 미존재") {
            it("ComponentNotFoundException 발생, softDelete 미호출") {
                every { permissionResolver.hasPermission(actorId, ComponentPermission.DELETE, projectId) } returns true
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                every { repo.findById(componentId, projectId) } returns null

                shouldThrow<ComponentNotFoundException> {
                    sut.delete(actorId, projectIdOrKey, componentId)
                }
                verify(exactly = 0) { repo.softDelete(any(), any()) }
            }
        }

        context("오류 경로 — 권한 없음") {
            it("AccessDeniedException 발생") {
                every { permissionResolver.hasPermission(actorId, ComponentPermission.DELETE, projectId) } returns false
                every { projectLookup.resolve(projectIdOrKey) } returns projectId

                shouldThrow<ComponentAccessDeniedException> {
                    sut.delete(actorId, projectIdOrKey, componentId)
                }
                verify(exactly = 0) { repo.softDelete(any(), any()) }
            }
        }
    }

    // ── getById ───────────────────────────────────────────────────────────────

    describe("getById") {
        context("정상 경로") {
            it("프로젝트 resolve → findById → 반환") {
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                every { repo.findById(componentId, projectId) } returns activeComponent

                val result = sut.getById(actorId, projectIdOrKey, componentId)

                result shouldBe activeComponent
            }
        }

        context("오류 경로 — 프로젝트 미존재") {
            it("ComponentProjectNotFoundException 발생") {
                every { projectLookup.resolve(projectIdOrKey) } returns null

                shouldThrow<ComponentProjectNotFoundException> {
                    sut.getById(actorId, projectIdOrKey, componentId)
                }
            }
        }

        context("오류 경로 — 컴포넌트 미존재") {
            it("ComponentNotFoundException 발생") {
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                every { repo.findById(componentId, projectId) } returns null

                shouldThrow<ComponentNotFoundException> {
                    sut.getById(actorId, projectIdOrKey, componentId)
                }
            }
        }
    }

    // ── listByProject ─────────────────────────────────────────────────────────

    describe("listByProject") {
        context("정상 경로") {
            it("프로젝트 resolve → findByProject 반환") {
                val components = listOf(
                    activeComponent,
                    activeComponent.copy(id = UUID.randomUUID(), name = "Frontend"),
                )
                every { projectLookup.resolve(projectIdOrKey) } returns projectId
                every { repo.findByProject(projectId) } returns components

                val result = sut.listByProject(actorId, projectIdOrKey)

                result shouldBe components
                verify(exactly = 1) { repo.findByProject(projectId) }
            }
        }

        context("오류 경로 — 프로젝트 미존재") {
            it("ComponentProjectNotFoundException 발생") {
                every { projectLookup.resolve(projectIdOrKey) } returns null

                shouldThrow<ComponentProjectNotFoundException> {
                    sut.listByProject(actorId, projectIdOrKey)
                }
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
    }
})
