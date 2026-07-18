// ProjectRequire2faApplicationService MockK 단위 테스트 — 아카이브 잠금 D-ORDER 판별자 (FR-PJ-04 PR-4 Task 7)

package com.bts.issue.project.application

import com.bts.issue.project.ProjectLookup
import com.bts.issue.project.archive.ProjectArchiveGuard
import com.bts.issue.project.archive.ProjectArchivedException
import com.bts.issue.project.repository.ProjectRequire2faRepository
import com.bts.shared.permission.SystemPermissionResolver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID

/**
 * ProjectRequire2faApplicationService MockK 단위 테스트 (FR-PJ-04 PR-4 Task 7 — PJ3-2).
 *
 * toggle 의 아카이브 잠금 D-ORDER(SYSTEM_ADMIN 검증 → archiveGuard.check) 판별자.
 * [com.bts.issue.version.application.VersionApplicationServiceTest] 의 "아카이브 잠금" 섹션과
 * 동형 패턴 — 각 케이스마다 (아카이브 → [ProjectArchivedException] 전파) + (활성 →
 * `archiveGuard.check` 실제 호출 확인, 판별자 [[guard-handler-matrix-blindfold]]) 를 짝지어 검증한다.
 *
 * SYSTEM_ADMIN/404 경로는 [com.bts.issue.project.web.ProjectRequire2faControllerIntegrationTest] 가
 * 풀부팅 통합 레벨에서 이미 커버하므로 여기서는 아카이브 잠금에 집중한다.
 */
class ProjectRequire2faApplicationServiceTest : DescribeSpec({

    val repository = mockk<ProjectRequire2faRepository>()
    val projectLookup = mockk<ProjectLookup>()
    val systemPermissionResolver = mockk<SystemPermissionResolver>()

    // FR-PJ-04 PR-4 Task 7 — Unit 함수(archiveGuard.check 는 Unit 반환)만 relax. happy-path 는
    // 이 mock 을 stub 하지 않는 한 no-op 통과하고, 아카이브 판별자 테스트만 throws 로 override 한다.
    val archiveGuard = mockk<ProjectArchiveGuard>(relaxUnitFun = true)

    val sut =
        ProjectRequire2faApplicationService(
            repository = repository,
            projectLookup = projectLookup,
            systemPermissionResolver = systemPermissionResolver,
            archiveGuard = archiveGuard,
        )

    val actorId = UUID.randomUUID()
    val projectIdOrKey = "BTS"
    val projectId = UUID.randomUUID()

    afterEach { clearMocks(repository, projectLookup, systemPermissionResolver, archiveGuard) }

    val stubHappyPath: () -> Unit = {
        every { projectLookup.resolve(projectIdOrKey) } returns projectId
        every { systemPermissionResolver.isSystemAdmin(actorId) } returns true
        every { repository.updateRequire2fa(projectId, true) } returns Unit
    }

    describe("아카이브 잠금 (FR-PJ-04 PR-4 Task 7)") {
        context("아카이브된 프로젝트") {
            it("SYSTEM_ADMIN 통과 후 archiveGuard.check 가 ProjectArchivedException 을 던지면 그대로 전파된다") {
                stubHappyPath()
                every { archiveGuard.check(projectId) } throws ProjectArchivedException(projectId.toString())

                shouldThrow<ProjectArchivedException> {
                    sut.toggle(actorId, projectIdOrKey, true)
                }
            }
        }

        context("활성 프로젝트 (판별자 baseline)") {
            it("archiveGuard.check 가 실제로 호출되고 require_2fa 가 갱신된다 (2xx 통과 + 판별자)") {
                stubHappyPath()

                sut.toggle(actorId, projectIdOrKey, true)

                verify(exactly = 1) { archiveGuard.check(projectId) }
                verify(exactly = 1) { repository.updateRequire2fa(projectId, true) }
            }
        }
    }
})
