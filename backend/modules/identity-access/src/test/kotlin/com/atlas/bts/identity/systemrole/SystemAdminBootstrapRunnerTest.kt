// 부트스트랩 시점 SYSTEM_ADMIN 멱등 승격 러너의 단위 테스트 (FR-PM-08 Task 5)

package com.atlas.bts.identity.systemrole

import com.atlas.bts.identity.user.User
import com.atlas.bts.identity.user.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.boot.DefaultApplicationArguments
import java.time.Instant
import java.util.UUID

/**
 * [SystemAdminBootstrapRunner] 단위 테스트 (FR-PM-08 Task 5).
 *
 * - [UserRepository] / [SystemRoleAssignmentRepository] 는 mockk 으로 대체한다.
 * - Testcontainers 불필요 — 순수 로직(설정값 분기 + 멱등 승격)만 검증한다.
 * - mockk 인자는 구체값을 사용한다 (any() 함정 회피).
 */
class SystemAdminBootstrapRunnerTest {
    private val userRepository = mockk<UserRepository>(relaxed = true)
    private val systemRoleAssignmentRepository = mockk<SystemRoleAssignmentRepository>(relaxed = true)

    private val args = DefaultApplicationArguments()

    private val adminUserId = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val adminUser =
        User(
            id = adminUserId,
            username = "admin",
            email = "admin@bts.local",
            displayName = "Admin",
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
        )

    private fun runner(adminUsername: String) =
        SystemAdminBootstrapRunner(
            userRepository = userRepository,
            systemRoleAssignmentRepository = systemRoleAssignmentRepository,
            adminUsername = adminUsername,
        )

    @Test
    fun `S1 - admin-username 설정 + user 존재 + 보유자 없음이면 assign을 1회 호출한다`() {
        every { systemRoleAssignmentRepository.existsByRole(SystemRole.SYSTEM_ADMIN) } returns false
        every { userRepository.findByUsername("admin") } returns adminUser

        runner("admin").run(args)

        verify(exactly = 1) { systemRoleAssignmentRepository.assign(adminUserId, SystemRole.SYSTEM_ADMIN) }
    }

    @Test
    fun `S2 - 이미 보유자가 존재하면 멱등 skip - assign 미호출`() {
        every { systemRoleAssignmentRepository.existsByRole(SystemRole.SYSTEM_ADMIN) } returns true

        runner("admin").run(args)

        verify(exactly = 0) { systemRoleAssignmentRepository.assign(any(), any()) }
        verify(exactly = 0) { userRepository.findByUsername(any()) }
    }

    @Test
    fun `EC1 - admin-username 빈 문자열이면 user 조회도 assign도 안 한다`() {
        runner("").run(args)

        verify(exactly = 0) { userRepository.findByUsername(any()) }
        verify(exactly = 0) { systemRoleAssignmentRepository.assign(any(), any()) }
    }

    @Test
    fun `EC1b - admin-username 공백 문자열이면 user 조회도 assign도 안 한다`() {
        runner("   ").run(args)

        verify(exactly = 0) { userRepository.findByUsername(any()) }
        verify(exactly = 0) { systemRoleAssignmentRepository.assign(any(), any()) }
    }

    @Test
    fun `EC2 - admin-username 설정됐으나 해당 user가 없으면 assign 미호출`() {
        every { systemRoleAssignmentRepository.existsByRole(SystemRole.SYSTEM_ADMIN) } returns false
        every { userRepository.findByUsername("ghost") } returns null

        runner("ghost").run(args)

        verify(exactly = 0) { systemRoleAssignmentRepository.assign(any(), any()) }
    }
}
