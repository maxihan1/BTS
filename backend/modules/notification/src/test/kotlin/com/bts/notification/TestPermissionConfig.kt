// notification 통합 테스트 전용 fake SystemPermissionResolver 설정

package com.bts.notification

import com.bts.shared.permission.SystemPermissionResolver
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import java.util.UUID

/**
 * 통합 테스트 전용 fake [SystemPermissionResolver] 설정 클래스.
 *
 * identity-access BC 의 prod 어댑터는 notification 단독 컨텍스트에서 존재하지 않으므로,
 * 테스트에서 admin / 비-admin 두 케이스를 재현할 수 있는 fake 구현을 제공한다.
 * (memory: crossbc-resolver-nullable-fail-open — 빈 부재 시 fail-open 방지를 위해 명시적 fake 제공.)
 * (memory: profile-scoped-bean-boot-failure — test 프로파일에서 빈 부재로 부팅 실패 방지.)
 *
 * ## actor 분류 규칙
 * - [ADMIN_ACTOR_ID] → `isSystemAdmin` = true (관리자)
 * - 그 외 모든 UUID → `isSystemAdmin` = false (일반 사용자)
 *
 * [NotificationPolicyEndToEndIntegrationTest] 의 [adminActorId] / [regularActorId] 와 동일하게 유지한다.
 */
@TestConfiguration
class TestPermissionConfig {
    companion object {
        /** 관리자로 판정될 UUID — 테스트에서 직접 참조하지 않고 문자열로 동기화한다 */
        val ADMIN_ACTOR_ID: UUID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    }

    /**
     * 테스트 전용 [SystemPermissionResolver] 빈.
     *
     * [ADMIN_ACTOR_ID] 이면 관리자, 나머지는 비-관리자로 판정한다.
     *
     * @return fake [SystemPermissionResolver] 구현
     */
    @Bean
    fun systemPermissionResolver(): SystemPermissionResolver =
        object : SystemPermissionResolver {
            override fun isSystemAdmin(actorId: UUID): Boolean = actorId == ADMIN_ACTOR_ID
        }
}
