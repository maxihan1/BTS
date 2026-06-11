// MfaEnforcementPolicy 단위 테스트 — MFA 강제 대상(관리자/민감프로젝트) × 등록여부 분기 검증 (FR-MF-04 Task 4)

package com.atlas.bts.identity.mfa

import com.atlas.bts.identity.project.ProjectMembershipRepository
import com.bts.shared.permission.SensitiveProjectResolver
import com.bts.shared.permission.SystemPermissionResolver
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [MfaEnforcementPolicy] 단위 테스트 (FR-MF-04 Task 4).
 *
 * MFA 강제 등록 필요 여부 = 강제 대상(관리자 OR 민감 프로젝트 멤버) AND 미설정 의 조합을 검증한다.
 * 강제 대상 판정의 단일 출처(게이트·JWT 클레임·whoami 공통)이므로 분기를 빠짐없이 고정한다.
 *
 * ## 검증 시나리오
 * - (a) 관리자 + MFA 미설정 → true (관리자는 강제, 미설정이라 등록 필요)
 * - (b) 민감 프로젝트 멤버 + 미설정 → true (멤버십 프로젝트 중 하나가 require_2fa)
 * - (c) 일반 사용자 + 미설정 → false (강제 대상 아님)
 * - (d) 관리자 + 이미 설정 → false (강제 대상이나 이미 등록 — EC9)
 * - (e) 멤버십 0 + 일반 → false (관리자 아님 + 빈 프로젝트 집합 — EC3)
 * - (f) 다중 프로젝트 중 하나만 민감 → true (anyRequiresMfa 가 OR 판정 — EC4)
 *
 * MockK 로 4개 협력자(SystemPermissionResolver / SensitiveProjectResolver /
 * ProjectMembershipRepository / MfaService)를 격리하여 조합 로직만 검증한다.
 */
class MfaEnforcementPolicyTest {
    private val systemPermissionResolver: SystemPermissionResolver = mockk()
    private val sensitiveResolver: SensitiveProjectResolver = mockk()
    private val membershipRepo: ProjectMembershipRepository = mockk()
    private val mfaService: MfaService = mockk()

    private val policy =
        MfaEnforcementPolicy(
            systemPermissionResolver = systemPermissionResolver,
            sensitiveResolver = sensitiveResolver,
            membershipRepo = membershipRepo,
            mfaService = mfaService,
        )

    private val user: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val projectA: UUID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001")
    private val projectB: UUID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002")

    @Test
    fun `관리자 + MFA 미설정이면 등록 필요(true)`() {
        every { systemPermissionResolver.isSystemAdmin(user) } returns true
        every { mfaService.isEnabled(user) } returns false

        assertThat(policy.evaluate(user)).isTrue()

        // 관리자면 멤버십·민감 프로젝트 조회 없이 단락(short-circuit) — 불필요한 cross-BC 호출 차단
        verify(exactly = 0) { membershipRepo.listProjectIdsByUser(any()) }
        verify(exactly = 0) { sensitiveResolver.anyRequiresMfa(any()) }
    }

    @Test
    fun `민감 프로젝트 멤버 + 미설정이면 등록 필요(true)`() {
        every { systemPermissionResolver.isSystemAdmin(user) } returns false
        every { membershipRepo.listProjectIdsByUser(user) } returns listOf(projectA)
        every { sensitiveResolver.anyRequiresMfa(setOf(projectA)) } returns true
        every { mfaService.isEnabled(user) } returns false

        assertThat(policy.evaluate(user)).isTrue()
    }

    @Test
    fun `일반 사용자(비관리자 + 비민감) + 미설정이면 등록 불필요(false)`() {
        every { systemPermissionResolver.isSystemAdmin(user) } returns false
        every { membershipRepo.listProjectIdsByUser(user) } returns listOf(projectA)
        every { sensitiveResolver.anyRequiresMfa(setOf(projectA)) } returns false
        every { mfaService.isEnabled(user) } returns false

        assertThat(policy.evaluate(user)).isFalse()
    }

    @Test
    fun `관리자이지만 이미 MFA 설정이면 등록 불필요(false) — EC9`() {
        every { systemPermissionResolver.isSystemAdmin(user) } returns true
        every { mfaService.isEnabled(user) } returns true

        assertThat(policy.evaluate(user)).isFalse()
    }

    @Test
    fun `멤버십 0 + 일반 사용자면 등록 불필요(false) — EC3`() {
        every { systemPermissionResolver.isSystemAdmin(user) } returns false
        every { membershipRepo.listProjectIdsByUser(user) } returns emptyList()
        every { sensitiveResolver.anyRequiresMfa(emptySet()) } returns false
        every { mfaService.isEnabled(user) } returns false

        assertThat(policy.evaluate(user)).isFalse()
    }

    @Test
    fun `다중 프로젝트 중 하나만 민감해도 등록 필요(true) — EC4`() {
        every { systemPermissionResolver.isSystemAdmin(user) } returns false
        every { membershipRepo.listProjectIdsByUser(user) } returns listOf(projectA, projectB)
        every { sensitiveResolver.anyRequiresMfa(setOf(projectA, projectB)) } returns true
        every { mfaService.isEnabled(user) } returns false

        assertThat(policy.evaluate(user)).isTrue()
    }
}
