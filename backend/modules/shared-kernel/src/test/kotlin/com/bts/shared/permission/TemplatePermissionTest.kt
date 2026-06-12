// 이슈 템플릿 권한 계약(enum 3종/포트 시그니처/권한코드 매핑)이 shared-kernel에 존재함을 단언

package com.bts.shared.permission

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [TemplatePermission] · [TemplatePermissionResolver] 의 계약 단위 테스트.
 *
 * FR-TM-01 Task 1 — 이슈 본문 템플릿 관리(CRUD) 권한 포트를 shared-kernel
 * (`com.bts.shared.permission`)에 [CustomFieldPermission] 동형으로 고정한다.
 * Spring 컨텍스트 없이 순수 단위 테스트로 실행한다.
 *
 * 검증 항목.
 * - permission_entries — [TemplatePermission] 항목 3종(CREATE, UPDATE, DELETE).
 * - permission_code_mapping — 세 항목 모두 `toPermissionCode()` 가 "MANAGE_TEMPLATES" 를 반환한다.
 * - resolver_signature_uuid — `hasPermission(actorId: UUID, permission, projectId: UUID): Boolean` 시그니처로 구현 가능하다.
 */
class TemplatePermissionTest {
    private val actorId: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val projectId: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")

    @Test
    fun `permission_entries — TemplatePermission 항목은 CREATE, UPDATE, DELETE 3종이다`() {
        assertThat(TemplatePermission.entries)
            .containsExactlyInAnyOrder(
                TemplatePermission.CREATE,
                TemplatePermission.UPDATE,
                TemplatePermission.DELETE,
            )
    }

    @Test
    fun `permission_code_mapping — 모든 항목의 toPermissionCode 는 MANAGE_TEMPLATES 다`() {
        assertThat(TemplatePermission.entries.map { it.toPermissionCode() })
            .containsOnly("MANAGE_TEMPLATES")
    }

    @Test
    fun `resolver_signature_uuid — hasPermission 은 actorId UUID, projectId UUID 시그니처를 가진다`() {
        var capturedActor: UUID? = null
        var capturedPermission: TemplatePermission? = null
        var capturedProject: UUID? = null

        val resolver =
            TemplatePermissionResolver { actor, permission, project ->
                capturedActor = actor
                capturedPermission = permission
                capturedProject = project
                true
            }

        val result = resolver.hasPermission(actorId, TemplatePermission.CREATE, projectId)

        assertThat(result).isTrue()
        assertThat(capturedActor).isEqualTo(actorId)
        assertThat(capturedPermission).isEqualTo(TemplatePermission.CREATE)
        assertThat(capturedProject).isEqualTo(projectId)
    }
}
