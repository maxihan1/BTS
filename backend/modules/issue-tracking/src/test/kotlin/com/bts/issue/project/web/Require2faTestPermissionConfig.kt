// issue-tracking 통합 테스트 전용 fake SystemPermissionResolver — SYSTEM_ADMIN/비관리자 토글

package com.bts.issue.project.web

import com.bts.shared.permission.SystemPermissionResolver
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import java.util.UUID

/**
 * issue-tracking 통합 테스트 전용 fake [SystemPermissionResolver] 설정 클래스.
 *
 * identity-access BC 의 prod 어댑터는 issue-tracking 단독 컨텍스트에서 존재하지 않으므로,
 * 테스트에서 SYSTEM_ADMIN / 비관리자 두 케이스를 재현할 수 있는 fake 구현을 제공한다.
 * (memory: crossbc-resolver-nullable-fail-open — 빈 부재 시 fail-open 방지.)
 * (memory: profile-scoped-bean-boot-failure — test 프로파일에서 빈 부재로 부팅 실패 방지.)
 *
 * ## actor 분류 규칙
 * - [ADMIN_ACTOR_ID] → `isSystemAdmin` = true (SYSTEM_ADMIN)
 * - 그 외 모든 UUID → `isSystemAdmin` = false (일반 사용자)
 *
 * [ProjectRequire2faControllerIntegrationTest] 의 `adminActorId` / `regularActorId` 와 동일하게 유지한다.
 */
@TestConfiguration
class Require2faTestPermissionConfig {
    companion object {
        /** SYSTEM_ADMIN 으로 판정될 UUID */
        val ADMIN_ACTOR_ID: UUID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    }

    /**
     * 테스트 전용 [SystemPermissionResolver] 빈.
     *
     * [ADMIN_ACTOR_ID] 이면 SYSTEM_ADMIN, 나머지는 비관리자로 판정한다.
     *
     * @return fake [SystemPermissionResolver] 구현
     */
    @Bean
    fun systemPermissionResolver(): SystemPermissionResolver =
        object : SystemPermissionResolver {
            override fun isSystemAdmin(actorId: UUID): Boolean = actorId == ADMIN_ACTOR_ID
        }
}
