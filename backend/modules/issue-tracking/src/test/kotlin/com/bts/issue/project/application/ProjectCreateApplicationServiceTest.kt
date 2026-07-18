// ProjectCreateApplicationServiceTest — 협력 계약(insert → addCreatorAsAdmin) MockK 단위 테스트 (FR-PJ-01)

package com.bts.issue.project.application

import com.bts.issue.project.domain.Project
import com.bts.issue.project.repository.ProjectCreateRepository
import com.bts.shared.membership.ProjectMembershipWritePort
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import io.mockk.verifySequence
import java.util.UUID

/**
 * [ProjectCreateApplicationService] 단위 테스트.
 *
 * **여기서 검증하지 않는 것 — DoD-11(원자성 롤백) 은 T12(조립 부팅) 로 이동.**
 * 이 테스트는 [ProjectCreateRepository] 와 [ProjectMembershipWritePort] 를 MockK 로 대체하므로
 * 실제 트랜잭션 경계나 DB 롤백을 검증할 수 없다 — 두 협력자 호출 순서·인자만(협력 계약) 검증한다.
 * "같은 tx 에 참여해 insert 실패 시 멤버십도 안 남는다" 는 불변식(I1)의 실제 원자성 실증은
 * issue-tracking 모듈 격리 테스트 범위 밖(project_memberships 는 identity-access 소유 테이블,
 * 여기엔 실 어댑터가 없다) — 반드시 T12 조립 부팅(:modules:app) Testcontainers 통합에서 확인한다.
 */
class ProjectCreateApplicationServiceTest : DescribeSpec({

    val projectCreateRepo = mockk<ProjectCreateRepository>()
    val membershipWritePort = mockk<ProjectMembershipWritePort>()

    val sut =
        ProjectCreateApplicationService(
            projectCreateRepo = projectCreateRepo,
            membershipWritePort = membershipWritePort,
        )

    val creatorId = UUID.fromString("00000000-0000-4000-8000-000000000099")
    val key = "BTS"
    val name = "Project Atlas"
    val createdProjectId = UUID.fromString("00000000-0000-4000-8000-000000000001")
    val createdProject = Project(id = createdProjectId, key = key, name = name)

    afterEach { clearMocks(projectCreateRepo, membershipWritePort) }

    describe("create") {

        context("insert 가 성공하면") {
            it("projectCreateRepo.insert 후 membershipWritePort.addCreatorAsAdmin 을 순서대로 호출한다 (S1 협력)") {
                every { projectCreateRepo.insert(key, name) } returns createdProject
                every { membershipWritePort.addCreatorAsAdmin(createdProjectId, creatorId) } returns Unit

                val result = sut.create(creatorId, key, name)

                result shouldBe createdProject
                // 순서 판별자 — insert 가 addCreatorAsAdmin 보다 먼저 호출됐는지(단순 verify 로는 순서를
                // 못 잡으므로 verifyOrder 로 순서를, verifySequence 로 "이 두 호출만" 을 각각 확인한다.
                verifyOrder {
                    projectCreateRepo.insert(key, name)
                    membershipWritePort.addCreatorAsAdmin(createdProjectId, creatorId)
                }
                // 생성된 project.id 가 addCreatorAsAdmin 에 그대로 전달됐는지 인자 검증.
                verify(exactly = 1) { membershipWritePort.addCreatorAsAdmin(createdProjectId, creatorId) }
            }
        }

        context("insert 가 예외를 던지면") {
            it("addCreatorAsAdmin 은 호출되지 않는다 (단락)") {
                every { projectCreateRepo.insert(key, name) } throws IllegalStateException("insert failed")

                runCatching { sut.create(creatorId, key, name) }

                // 단락 판별자 — insert 예외 시 addCreatorAsAdmin 이 아예 호출 안 됐는지 exactly=0 로 확정.
                verify(exactly = 0) { membershipWritePort.addCreatorAsAdmin(any(), any()) }
                verifySequence { projectCreateRepo.insert(key, name) }
            }
        }
    }
})
