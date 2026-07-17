// IdentityAccessSystemPermissionResolver 단위테스트 — 전역 SYSTEM_ADMIN 판정 (FR-PM-08 Task 3)

package com.atlas.bts.identity.permission

import com.atlas.bts.identity.systemrole.SystemRole
import com.atlas.bts.identity.systemrole.SystemRoleAssignmentRepository
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [IdentityAccessSystemPermissionResolver] 단위테스트.
 *
 * ## 검증 시나리오 (plan Task 3 RED)
 * (a) SYSTEM_ADMIN 보유 사용자 → isSystemAdmin == true.
 * (b) 역할 미보유(빈 집합) 사용자 → isSystemAdmin == false.
 *
 * MockK로 [SystemRoleAssignmentRepository]를 격리하여 adapter 위임 로직만 검증한다.
 * 메모리 교훈(mockk any() 함정 회피)에 따라 `findRolesByUser`는 구체 UUID로 stub한다.
 */
class IdentityAccessSystemPermissionResolverTest {
    private val repo: SystemRoleAssignmentRepository = mockk()

    /**
     * FR-PM-10 이 추가한 생성자 협력자. 본 테스트는 [IdentityAccessSystemPermissionResolver.isSystemAdmin]
     * 만 호출하고 그 경로는 grant 축을 타지 않으므로 스텁하지 않는다 — 만약 이 목이 호출되면
     * non-relaxed mockk 가 예외로 알려준다(조용한 통과 없음).
     *
     * `hasGlobalPermission` 판정식(grant OR isSystemAdmin)의 검증은 실 DB 가 필요하므로
     * [IdentityAccessSystemPermissionResolverGlobalPermissionTest] 가 담당한다.
     */
    private val grantRepo: GlobalPermissionGrantRepository = mockk()

    private val resolver = IdentityAccessSystemPermissionResolver(repo = repo, grantRepo = grantRepo)

    private val adminUser: UUID = UUID.fromString("00000000-0000-4000-8000-000000000001")
    private val plainUser: UUID = UUID.fromString("00000000-0000-4000-8000-000000000002")

    @Test
    fun `SYSTEM_ADMIN 보유 사용자는 isSystemAdmin true`() {
        every { repo.findRolesByUser(adminUser) } returns setOf(SystemRole.SYSTEM_ADMIN)

        assertThat(resolver.isSystemAdmin(adminUser)).isTrue()
    }

    @Test
    fun `역할 미보유 사용자는 isSystemAdmin false`() {
        every { repo.findRolesByUser(plainUser) } returns emptySet()

        assertThat(resolver.isSystemAdmin(plainUser)).isFalse()
    }
}
