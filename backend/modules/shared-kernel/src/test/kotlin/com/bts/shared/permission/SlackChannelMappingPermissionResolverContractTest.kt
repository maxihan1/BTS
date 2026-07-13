// Slack 채널 매핑 권한 포트 계약(원시 타입 시그니처·Boolean·default 부재·fail-closed) 단언 — 구현 없이 RED

package com.bts.shared.permission

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.valueParameters

/**
 * [SlackChannelMappingPermissionResolver] cross-BC 권한 포트의 계약 단위 테스트.
 *
 * FR-SL-06 Task 3 — slack-integration BC 의 채널 매핑 CRUD 가 "행위자가 대상 프로젝트에서
 * 채널 매핑을 관리할 수 있는가"를 판정 요청하는 outbound port 의 계약을 고정한다.
 * [AutomationPermissionResolver] 를 미러한 순수 인터페이스이므로 Spring 컨텍스트 없이
 * 순수 단위 테스트로 실행한다.
 *
 * 검증 항목.
 * - is_interface — 포트는 interface 여야 한다(fail-closed, 구체 default 구현 부재).
 * - signature_uuid_string_boolean — `hasManageChannelMapping(actorId: UUID, projectKey: String): Boolean`
 *   시그니처로 구현 가능하고 인자를 그대로 위임받는다.
 * - fail_closed_stub_returns_false — 판정 불명을 `false` 로 수렴시키는 스텁이 컴파일·동작한다.
 * - primitive_types_only — 값 파라미터는 [UUID], [String] 원시 타입뿐이고 반환은 [Boolean] 이다.
 * - no_default_method — 메서드는 추상이며 Kotlin `DefaultImpls` 가 생성되지 않는다(default 구현 금지).
 */
class SlackChannelMappingPermissionResolverContractTest {
    private val actorId: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
    private val projectKey: String = "ATLAS"

    @Test
    fun `is_interface — 포트는 interface 다 (fail-closed, default 구현 부재)`() {
        assertThat(SlackChannelMappingPermissionResolver::class.java.isInterface)
            .`as`("SlackChannelMappingPermissionResolver must be an interface — fail-closed, no default allowed")
            .isTrue()
    }

    @Test
    fun `signature_uuid_string_boolean — hasManageChannelMapping 는 인자를 그대로 위임받는다`() {
        var capturedActor: UUID? = null
        var capturedProjectKey: String? = null

        val resolver =
            object : SlackChannelMappingPermissionResolver {
                override fun hasManageChannelMapping(
                    actorId: UUID,
                    projectKey: String,
                ): Boolean {
                    capturedActor = actorId
                    capturedProjectKey = projectKey
                    return true
                }
            }

        val allowed = resolver.hasManageChannelMapping(actorId, projectKey)

        assertThat(allowed).isTrue()
        assertThat(capturedActor).isEqualTo(actorId)
        assertThat(capturedProjectKey).isEqualTo(projectKey)
    }

    @Test
    fun `fail_closed_stub_returns_false — 불명 판정은 false 로 수렴한다`() {
        val denyingResolver =
            object : SlackChannelMappingPermissionResolver {
                override fun hasManageChannelMapping(
                    actorId: UUID,
                    projectKey: String,
                ): Boolean = false
            }

        assertThat(denyingResolver.hasManageChannelMapping(actorId, projectKey)).isFalse()
    }

    @Test
    fun `primitive_types_only — 값 파라미터는 UUID·String, 반환은 Boolean 이다`() {
        val method =
            SlackChannelMappingPermissionResolver::class.memberFunctions
                .firstOrNull { it.name == "hasManageChannelMapping" }
                ?: error("SlackChannelMappingPermissionResolver 에 hasManageChannelMapping 메서드가 없습니다.")

        assertThat(method.returnType.classifier).isEqualTo(Boolean::class)

        val paramClassifiers = method.valueParameters.map { it.type.classifier }
        assertThat(paramClassifiers).containsExactly(UUID::class, String::class)
    }

    @Test
    fun `no_default_method — 메서드는 추상이고 DefaultImpls 가 없다 (default 구현 금지)`() {
        // 값 클래스 파라미터가 없어 JVM 메서드 이름은 mangling 되지 않는다.
        val jvmMethod =
            SlackChannelMappingPermissionResolver::class.java.declaredMethods
                .firstOrNull { it.name == "hasManageChannelMapping" }
                ?: error("JVM 에서 hasManageChannelMapping 메서드를 찾을 수 없습니다.")

        // (1) `-Xjvm-default=all` 모드에서 default 메서드는 non-abstract 가 되므로 추상성 확인.
        assertThat(java.lang.reflect.Modifier.isAbstract(jvmMethod.modifiers))
            .`as`("hasManageChannelMapping must be abstract — no default body")
            .isTrue()

        // (2) 기본(DefaultImpls) 모드에서 default 본문은 별도 `$DefaultImpls` 클래스로 생성된다.
        //     해당 클래스가 없어야 default 구현이 전혀 없다는 뜻이다.
        val defaultImplsAbsent =
            runCatching {
                Class.forName("com.bts.shared.permission.SlackChannelMappingPermissionResolver\$DefaultImpls")
            }.isFailure
        assertThat(defaultImplsAbsent)
            .`as`("SlackChannelMappingPermissionResolver must not generate a DefaultImpls class — no default allowed")
            .isTrue()
    }
}
