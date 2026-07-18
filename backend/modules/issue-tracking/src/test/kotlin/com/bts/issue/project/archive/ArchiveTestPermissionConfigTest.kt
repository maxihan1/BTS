// ArchiveTestPermissionConfig 의 제어형 fake ComponentPermissionResolver 동작 검증 (FR-PJ-04 PR-4 Task 4)

package com.bts.issue.project.archive

import com.bts.shared.permission.ComponentPermission
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [ArchiveTestPermissionConfig] 가 제공하는 제어형 fake [ControllableComponentPermissionResolver] 동작 검증.
 *
 * 이 fake 는 PR-4 아카이브 게이트(PROJECT_ADMIN = [ComponentPermission.UPDATE]) 통합 테스트에서
 * "관리자 → 허용 / 비관리자 → 거부" 를 **제어 가능**하게 재현하기 위해 존재한다.
 * 실 구현체([com.bts.issue.project.adapter] 밖의 identity-access 소유)가 issue-tracking 클래스패스에
 * 없고, non-prod stub [com.bts.issue.component.adapter.AlwaysAllowComponentPermissionResolver] 는
 * 항상 `true` 라 "비관리자 → 403" 테스트가 vacuous 하게 통과한다(C1). 그 회피가 이 fake 의 목적이다.
 *
 * ## ground-truth 판별
 * 하드코딩 `true` 가 아니라 등록된 admin actor 집합을 추적함을 증명하기 위해,
 * `grantAdmin`/`revokeAdmin` 으로 같은 actor 의 판정이 뒤집히는지까지 검증한다.
 */
class ArchiveTestPermissionConfigTest {
    private val projectId: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val stranger: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")

    private fun resolver() = ArchiveTestPermissionConfig().archiveComponentPermissionResolver()

    @Test
    fun `등록된 admin actor 는 true`() {
        val resolver = resolver()

        val granted =
            resolver.hasPermission(
                ArchiveTestPermissionConfig.ADMIN_ACTOR_ID,
                ComponentPermission.UPDATE,
                projectId,
            )

        assertThat(granted).isTrue()
    }

    @Test
    fun `미등록 actor 는 false`() {
        val resolver = resolver()

        val granted = resolver.hasPermission(stranger, ComponentPermission.UPDATE, projectId)

        assertThat(granted).isFalse()
    }

    @Test
    fun `grantAdmin 으로 등록하면 판정이 false 에서 true 로 뒤집힌다 — ground-truth 증명`() {
        val resolver = resolver()

        assertThat(resolver.hasPermission(stranger, ComponentPermission.UPDATE, projectId)).isFalse()

        resolver.grantAdmin(stranger)

        assertThat(resolver.hasPermission(stranger, ComponentPermission.UPDATE, projectId)).isTrue()
    }

    @Test
    fun `revokeAdmin 으로 제거하면 판정이 true 에서 false 로 뒤집힌다`() {
        val resolver = resolver()
        val admin = ArchiveTestPermissionConfig.ADMIN_ACTOR_ID

        assertThat(resolver.hasPermission(admin, ComponentPermission.UPDATE, projectId)).isTrue()

        resolver.revokeAdmin(admin)

        assertThat(resolver.hasPermission(admin, ComponentPermission.UPDATE, projectId)).isFalse()
    }
}
