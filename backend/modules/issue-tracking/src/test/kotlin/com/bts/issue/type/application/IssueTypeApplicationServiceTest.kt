// IssueTypeApplicationService 단위 테스트 — MockK, TDD RED (CRUD + 재할당 트랜잭션)

package com.bts.issue.type.application

import com.bts.issue.type.domain.IssueType
import com.bts.issue.type.domain.IssueTypeInUseException
import com.bts.issue.type.domain.IssueTypeKeyDuplicateException
import com.bts.issue.type.domain.IssueTypeKeyInvalidException
import com.bts.issue.type.domain.IssueTypeNotFoundException
import com.bts.issue.type.domain.IssueTypeReassignTargetInvalidException
import com.bts.issue.type.domain.IssueTypeStandardImmutableException
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.issue.IssueTypeKey
import com.bts.workflow.scheme.application.port.IssueTypeUsagePort
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

class IssueTypeApplicationServiceTest : DescribeSpec({

    val repo = mockk<IssueTypeRepository>()
    val usagePort = mockk<IssueTypeUsagePort>()

    val sut = IssueTypeApplicationService(repo, usagePort)

    // ── 테스트 픽스처 헬퍼 ──────────────────────────────────────────────────────

    fun makeCustomType(
        id: Long = 10L,
        key: String = "feature",
        name: String = "Feature",
        isStandard: Boolean = false,
        deletedAt: Instant? = null,
    ) = IssueType(
        id = IssueTypeId(id),
        key = IssueTypeKey(key),
        name = name,
        description = null,
        iconName = null,
        isStandard = isStandard,
        hierarchyLevel = 0,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
        deletedAt = deletedAt,
    )

    fun makeStandardType(
        id: Long = 1L,
        key: String = "task",
    ) = makeCustomType(id = id, key = key, isStandard = true)

    beforeEach {
        clearMocks(repo, usagePort, answers = false)
    }

    // ── @Transactional 선언 검증 ────────────────────────────────────────────────

    it("IssueTypeApplicationService 클래스는 @Transactional 을 선언한다") {
        val annotation = IssueTypeApplicationService::class.java.getAnnotation(Transactional::class.java)
        annotation shouldNotBe null
    }

    // ══════════════════════════════════════════════════════════════════════════
    // create
    // ══════════════════════════════════════════════════════════════════════════

    describe("create") {

        context("key 중복 — findByKey 가 기존 타입을 반환할 때") {
            it("IssueTypeKeyDuplicateException 을 던진다") {
                val req = CreateIssueTypeRequest(
                    key = "feature",
                    name = "Feature",
                    description = null,
                    iconName = null,
                    hierarchyLevel = 0,
                )
                every { repo.findByKey(IssueTypeKey("feature")) } returns makeCustomType(key = "feature")

                shouldThrow<IssueTypeKeyDuplicateException> {
                    sut.create(req)
                }
                verify(exactly = 0) { repo.insert(any()) }
            }
        }

        context("key 형식 위반 — 대문자 포함 등 IssueTypeKey 생성 실패") {
            it("IssueTypeKeyInvalidException 으로 변환해 던진다") {
                val req = CreateIssueTypeRequest(
                    key = "INVALID_KEY",
                    name = "Bad",
                    description = null,
                    iconName = null,
                    hierarchyLevel = 0,
                )

                shouldThrow<IssueTypeKeyInvalidException> {
                    sut.create(req)
                }
                verify(exactly = 0) { repo.insert(any()) }
            }
        }

        context("정상 생성 — key 미중복, 형식 유효") {
            it("isStandard=false 를 강제하고 repo.insert 를 호출한다") {
                val req = CreateIssueTypeRequest(
                    key = "feature",
                    name = "Feature",
                    description = "Custom feature type",
                    iconName = "star",
                    hierarchyLevel = 0,
                )
                val savedType = makeCustomType(id = 99L, key = "feature")
                every { repo.findByKey(IssueTypeKey("feature")) } returns null
                every { repo.insert(any()) } returns savedType

                val result = sut.create(req)

                result shouldBe savedType
                verify {
                    repo.insert(match { it.isStandard == false && it.key == IssueTypeKey("feature") })
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // update
    // ══════════════════════════════════════════════════════════════════════════

    describe("update") {

        context("표준 타입(isStandard=true) 수정 시도") {
            it("IssueTypeStandardImmutableException 을 던진다") {
                val id = IssueTypeId(1L)
                every { repo.findById(id) } returns makeStandardType(id = 1L, key = "task")

                val req = UpdateIssueTypeRequest(
                    name = "Renamed Task",
                    description = null,
                    iconName = null,
                    hierarchyLevel = 0,
                )

                shouldThrow<IssueTypeStandardImmutableException> {
                    sut.update(id, req)
                }
                verify(exactly = 0) { repo.update(any()) }
            }
        }

        context("커스텀 타입 수정") {
            it("name/description/iconName/hierarchyLevel 만 반영하고 key/isStandard 는 불변이다") {
                val id = IssueTypeId(10L)
                val existing = makeCustomType(id = 10L, key = "feature", name = "Feature")
                every { repo.findById(id) } returns existing
                every { repo.update(any()) } returns Unit

                val req = UpdateIssueTypeRequest(
                    name = "Updated Feature",
                    description = "Updated desc",
                    iconName = "new-icon",
                    hierarchyLevel = 1,
                )

                sut.update(id, req)

                verify {
                    repo.update(match { updated ->
                        updated.name == "Updated Feature" &&
                            updated.description == "Updated desc" &&
                            updated.iconName == "new-icon" &&
                            updated.hierarchyLevel == 1 &&
                            updated.key == IssueTypeKey("feature") &&
                            updated.isStandard == false
                    })
                }
            }
        }

        context("존재하지 않는 타입 수정") {
            it("IssueTypeNotFoundException 을 던진다") {
                val id = IssueTypeId(999L)
                every { repo.findById(id) } returns null

                val req = UpdateIssueTypeRequest(
                    name = "X",
                    description = null,
                    iconName = null,
                    hierarchyLevel = 0,
                )

                shouldThrow<IssueTypeNotFoundException> {
                    sut.update(id, req)
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // delete
    // ══════════════════════════════════════════════════════════════════════════

    describe("delete") {

        context("표준 타입 삭제 시도") {
            it("IssueTypeStandardImmutableException 을 던진다") {
                val id = IssueTypeId(1L)
                every { repo.findById(id) } returns makeStandardType(id = 1L)

                shouldThrow<IssueTypeStandardImmutableException> {
                    sut.delete(id, reassignTo = null)
                }
                verify(exactly = 0) { repo.softDelete(any()) }
            }
        }

        context("커스텀 타입 — 미사용(이슈 0건, 스킴 매핑 0건)") {
            it("softDelete 를 호출한다") {
                val id = IssueTypeId(10L)
                every { repo.findById(id) } returns makeCustomType(id = 10L)
                every { repo.countIssuesByTypeId(10L) } returns 0L
                every { usagePort.countSchemeMappings(10L) } returns 0L
                every { repo.softDelete(id) } returns Unit

                sut.delete(id, reassignTo = null)

                verify { repo.softDelete(id) }
            }
        }

        context("커스텀 타입 — 이슈 사용 중, reassignTo 없음") {
            it("IssueTypeInUseException 을 던진다(usageCount>0, schemeMappingCount=0)") {
                val id = IssueTypeId(10L)
                every { repo.findById(id) } returns makeCustomType(id = 10L)
                every { repo.countIssuesByTypeId(10L) } returns 5L
                every { usagePort.countSchemeMappings(10L) } returns 0L

                val ex = shouldThrow<IssueTypeInUseException> {
                    sut.delete(id, reassignTo = null)
                }
                ex.usageCount shouldBe 5L
                ex.schemeMappingCount shouldBe 0L
                // value class 를 any() 로 verify 하면 MockK reflection 이 IssueTypeId(0) 을 생성해 IAE 발생
                // — 구체 id 값으로 verify 한다
                verify(exactly = 0) { repo.softDelete(id) }
            }
        }

        context("커스텀 타입 — 스킴 매핑 참조 중") {
            it("IssueTypeInUseException 을 던진다(schemeMappingCount>0) — reassignTo 로도 해소 불가") {
                val id = IssueTypeId(10L)
                val reassignId = IssueTypeId(2L)
                every { repo.findById(id) } returns makeCustomType(id = 10L)
                every { repo.countIssuesByTypeId(10L) } returns 0L
                every { usagePort.countSchemeMappings(10L) } returns 3L

                val ex = shouldThrow<IssueTypeInUseException> {
                    sut.delete(id, reassignTo = reassignId)
                }
                ex.schemeMappingCount shouldBe 3L
                verify(exactly = 0) { repo.softDelete(id) }
                verify(exactly = 0) { repo.reassignIssues(10L, 2L) }
            }
        }

        context("커스텀 타입 — reassignTo 지정, 유효한 대상") {
            it("reassignIssues(from,to) 후 softDelete 를 같은 트랜잭션 내에서 순서대로 호출한다") {
                val id = IssueTypeId(10L)
                val targetId = IssueTypeId(2L)
                val targetType = makeCustomType(id = 2L, key = "story")

                every { repo.findById(id) } returns makeCustomType(id = 10L)
                every { repo.findById(targetId) } returns targetType
                every { repo.countIssuesByTypeId(10L) } returns 3L
                every { usagePort.countSchemeMappings(10L) } returns 0L
                every { repo.reassignIssues(10L, 2L) } returns 3L
                every { repo.softDelete(id) } returns Unit

                sut.delete(id, reassignTo = targetId)

                verifyOrder {
                    repo.reassignIssues(10L, 2L)
                    repo.softDelete(id)
                }
            }
        }

        context("커스텀 타입 — reassignTo = 자기 자신") {
            it("IssueTypeReassignTargetInvalidException 을 던진다") {
                val id = IssueTypeId(10L)
                every { repo.findById(id) } returns makeCustomType(id = 10L)
                every { repo.countIssuesByTypeId(10L) } returns 1L
                every { usagePort.countSchemeMappings(10L) } returns 0L

                shouldThrow<IssueTypeReassignTargetInvalidException> {
                    sut.delete(id, reassignTo = id)
                }
                verify(exactly = 0) { repo.softDelete(id) }
                verify(exactly = 0) { repo.reassignIssues(10L, 10L) }
            }
        }

        context("커스텀 타입 — reassignTo 가 존재하지 않거나 소프트 삭제된 타입") {
            it("IssueTypeReassignTargetInvalidException 을 던진다") {
                val id = IssueTypeId(10L)
                val targetId = IssueTypeId(999L)
                every { repo.findById(id) } returns makeCustomType(id = 10L)
                every { repo.findById(targetId) } returns null
                every { repo.countIssuesByTypeId(10L) } returns 1L
                every { usagePort.countSchemeMappings(10L) } returns 0L

                shouldThrow<IssueTypeReassignTargetInvalidException> {
                    sut.delete(id, reassignTo = targetId)
                }
                verify(exactly = 0) { repo.softDelete(id) }
            }
        }

        context("SPI 실패 — usagePort 가 예외를 던질 때") {
            it("fail-closed: 삭제를 거부하고 예외를 전파한다") {
                val id = IssueTypeId(10L)
                every { repo.findById(id) } returns makeCustomType(id = 10L)
                every { repo.countIssuesByTypeId(10L) } returns 0L
                every { usagePort.countSchemeMappings(10L) } throws RuntimeException("SPI unavailable")

                shouldThrow<RuntimeException> {
                    sut.delete(id, reassignTo = null)
                }
                verify(exactly = 0) { repo.softDelete(id) }
            }
        }

        context("존재하지 않는 타입 삭제") {
            it("IssueTypeNotFoundException 을 던진다") {
                val id = IssueTypeId(999L)
                every { repo.findById(id) } returns null

                shouldThrow<IssueTypeNotFoundException> {
                    sut.delete(id, reassignTo = null)
                }
            }
        }
    }
})
