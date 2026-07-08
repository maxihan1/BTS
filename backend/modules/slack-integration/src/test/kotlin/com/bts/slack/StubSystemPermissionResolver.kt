// slack-integration 통합 테스트용 fail-closed SystemPermissionResolver stub — admins 집합만 관리자로 판정 (FR-SL-01 Task 9)

package com.bts.slack

import com.bts.shared.permission.SystemPermissionResolver
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 통합 테스트가 admin actor 를 명시 등록하는 fail-closed [SystemPermissionResolver] stub (FR-SL-01 Task 9).
 *
 * cross-BC 전역 관리자 판정 포트는 identity-access 가 adapter 를 제공하나 slack test-boot 컨텍스트에는
 * 실 구현이 없다. 이 stub 은 [admins] 집합에 등록된 UUID 만 SYSTEM_ADMIN 으로 판정하고, 집합이 비어
 * 있으면 **모두 거부(false)** 한다.
 *
 * ## fail-closed (교훈 crossbc-resolver-nullable-fail-open)
 * 기본값이 "전부 비-admin(거부)" 이므로 테스트가 admin 을 명시 등록하지 않는 한 아무도 통과하지 못한다.
 * prod resolver 미주입 시 조용히 전부 허용으로 떨어지는 fail-open 을 테스트에서 재현하지 않기 위한 것이다.
 * 판정 경로의 기본값·미등록은 모두 "거부" 로 수렴한다.
 *
 * webhook `WebhookIntegrationConfig.StubSystemPermissionResolver` 와 동일 정책이나, slack Task 9 는 이
 * stub 을 독립 파일로 두고 [SlackTestcontainersConfig] 가 `@Bean` 으로 등록해 컨텍스트 로드 테스트
 * ([SlackContextLoadTest])와 통합 테스트([SlackInstallIntegrationTest])가 공유한다.
 */
class StubSystemPermissionResolver : SystemPermissionResolver {
    /** SYSTEM_ADMIN 으로 취급할 actor UUID 집합. 테스트가 채우고 비운다(스레드 안전). */
    val admins: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    override fun isSystemAdmin(actorId: UUID): Boolean = actorId in admins
}
