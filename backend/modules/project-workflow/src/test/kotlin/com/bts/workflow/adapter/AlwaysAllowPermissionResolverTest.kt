// AlwaysAllowPermissionResolver — test profile hasPermission true + prod profile Bean 부재 검증

package com.bts.workflow.adapter

import com.bts.workflow.port.outbound.ActorId
import com.bts.workflow.port.outbound.PermissionResolver
import com.bts.workflow.port.outbound.Scope
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * AlwaysAllowPermissionResolver 검증.
 *
 * 검증 항목.
 * 1. test profile — Bean 로드 + hasPermission 항상 true 반환
 * 2. prod profile — Bean 미등록 확인 (운영 환경에서 stub 권한 우회 차단)
 */
class AlwaysAllowPermissionResolverTest {

    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(AlwaysAllowPermissionResolver::class.java)

    // ── 1. test profile: Bean 로드 + hasPermission 항상 true ──────────────────

    @Test
    fun `test profile 에서 AlwaysAllowPermissionResolver 는 Bean 으로 등록돼야 한다`() {
        contextRunner
            .withPropertyValues("spring.profiles.active=test")
            .run { ctx ->
                assertThat(ctx).hasSingleBean(PermissionResolver::class.java)
                assertThat(ctx.getBean(PermissionResolver::class.java))
                    .isInstanceOf(AlwaysAllowPermissionResolver::class.java)
            }
    }

    @Test
    fun `test profile 에서 hasPermission 은 모든 인자 조합에 대해 true 를 반환해야 한다`() {
        contextRunner
            .withPropertyValues("spring.profiles.active=test")
            .run { ctx ->
                val resolver = ctx.getBean(PermissionResolver::class.java)

                assertThat(resolver.hasPermission(ActorId("user-1"), "CREATE_ISSUE", Scope.Global)).isTrue()
                assertThat(resolver.hasPermission(ActorId("user-2"), "ADMIN_WORKFLOW", Scope.Project("ATLAS"))).isTrue()
                assertThat(resolver.hasPermission(ActorId("user-3"), "DELETE_ISSUE", Scope.Issue("ATLAS-99"))).isTrue()
            }
    }

    // ── 2. prod profile: Bean 미등록 ─────────────────────────────────────────

    /**
     * prod profile 활성화 시 @Profile("!prod") 조건으로 Bean 자체가 등록되지 않음을 검증한다.
     *
     * 운영 환경에서 stub 이 동작하면 모든 권한 검사를 우회하므로 (hasPermission 항상 true),
     * 이 테스트가 실패하는 것은 즉각적인 보안 위협을 의미한다.
     */
    @Test
    fun `prod profile 에서 AlwaysAllowPermissionResolver 는 Bean 으로 등록되지 않아야 한다`() {
        contextRunner
            .withPropertyValues("spring.profiles.active=prod")
            .run { ctx ->
                assertThat(ctx).doesNotHaveBean(AlwaysAllowPermissionResolver::class.java)
                assertThat(ctx).doesNotHaveBean(PermissionResolver::class.java)
            }
    }
}
