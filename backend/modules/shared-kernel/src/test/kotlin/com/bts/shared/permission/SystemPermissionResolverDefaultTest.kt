// SystemPermissionResolver.hasGlobalPermission default 구현 계약 검증 — 미override 구현체의 fail-safe 위임

package com.bts.shared.permission

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [SystemPermissionResolver.hasGlobalPermission] default 구현 단위 테스트.
 *
 * isSystemAdmin 만 구현한 fake 로 새 default 메서드를 호출해, 기존 구현체 6곳이
 * override 없이도 fail-safe 하게 동작하는지 검증한다 (FR-PM-10).
 * Spring 컨텍스트 없이 순수 단위 테스트로 실행한다.
 */
class SystemPermissionResolverDefaultTest {
    /** override 하지 않은 구현체 — 기존 6개 구현체가 이 상태다. */
    private class OnlyIsSystemAdmin(
        private val admin: Boolean,
    ) : SystemPermissionResolver {
        override fun isSystemAdmin(actorId: UUID): Boolean = admin
    }

    @Test
    fun `default 는 isSystemAdmin 에 위임한다 - 관리자면 전역권한 보유`() {
        val resolver = OnlyIsSystemAdmin(admin = true)

        assertThat(resolver.hasGlobalPermission(UUID.randomUUID(), "CREATE_PROJECT")).isTrue()
    }

    @Test
    fun `default 는 isSystemAdmin 에 위임한다 - 비관리자면 미보유 (fail-closed)`() {
        val resolver = OnlyIsSystemAdmin(admin = false)

        assertThat(resolver.hasGlobalPermission(UUID.randomUUID(), "CREATE_PROJECT")).isFalse()
    }
}
