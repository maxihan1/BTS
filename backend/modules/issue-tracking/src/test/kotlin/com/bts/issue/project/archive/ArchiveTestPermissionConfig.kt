// 아카이브 통합 테스트 전용 제어형 fake ComponentPermissionResolver 설정 — PROJECT_ADMIN(UPDATE) 게이트 토글

package com.bts.issue.project.archive

import com.bts.shared.permission.ComponentPermission
import com.bts.shared.permission.ComponentPermissionResolver
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import java.util.UUID

/**
 * issue-tracking 통합 테스트 전용 제어형 fake [ComponentPermissionResolver] 설정 클래스.
 *
 * `Require2faTestPermissionConfig`(SystemPermissionResolver 판) 동형이다.
 *
 * ## 왜 prod 프로파일이 아니라 test 프로파일 + fake 인가 (D-TESTPROFILE)
 * PR-4 아카이브 게이트의 PROJECT_ADMIN 판정은 [ComponentPermissionResolver] 명시 호출로 이뤄지는데,
 * 이 포트의 실 구현체(`IdentityAccessComponentPermissionResolver`)는 **identity-access BC 소유**라
 * issue-tracking 단독 테스트 클래스패스에 존재하지 않는다. 따라서 스펙 C1/DoD-4 의
 * `@ActiveProfiles("prod")` 를 문자 그대로 적용하면 issue-tracking 에서 부팅 불가(빈 미해소)이거나 vacuous 하다.
 *
 * non-prod stub [com.bts.issue.component.adapter.AlwaysAllowComponentPermissionResolver]
 * (`@Profile("!prod")`) 는 항상 `true` 를 반환하므로, 이를 그대로 쓰면 "비관리자 → 403" 테스트가
 * 무조건 통과해 아무것도 검증하지 못한다(C1 vacuous). 그래서 admin actor 집합을 제어 가능한
 * ground-truth fake 를 `@ActiveProfiles("test")` 에서 주입해, Task 5/7/9 의 비관리자 거부 테스트가
 * 실제 판정을 관통하도록 한다.
 * (memory: crossbc-resolver-nullable-fail-open — 빈 부재 시 fail-open 방지.)
 * (memory: profile-scoped-bean-boot-failure — test 프로파일 빈 미해소 부팅 실패 방지.)
 *
 * @see ControllableComponentPermissionResolver
 */
@TestConfiguration
class ArchiveTestPermissionConfig {
    companion object {
        /** PROJECT_ADMIN 으로 판정될 기본 admin UUID. */
        val ADMIN_ACTOR_ID: UUID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
    }

    /**
     * 테스트 전용 [ComponentPermissionResolver] 빈.
     *
     * [ADMIN_ACTOR_ID] 를 초기 admin 집합으로 갖는 [ControllableComponentPermissionResolver] 를 반환한다.
     * 통합 테스트는 이 빈을 `ControllableComponentPermissionResolver` 타입으로 autowire 하여
     * `grantAdmin`/`revokeAdmin` 으로 시나리오별 권한을 제어한다.
     *
     * @return 제어형 fake resolver
     */
    @Bean
    fun archiveComponentPermissionResolver(): ControllableComponentPermissionResolver =
        ControllableComponentPermissionResolver(setOf(ADMIN_ACTOR_ID))
}

/**
 * ground-truth 로 제어되는 fake [ComponentPermissionResolver].
 *
 * [hasPermission] 은 하드코딩 `true` 가 아니라 등록된 admin [actorIds] 집합에 [actorId] 가
 * 포함되는지로 판정한다. 테스트는 [grantAdmin]/[revokeAdmin] 으로 이 집합을 조정해
 * "관리자 → 허용 / 비관리자 → 거부" 를 명시적으로 재현한다.
 *
 * @param actorIds 초기 admin actor UUID 집합.
 */
class ControllableComponentPermissionResolver(
    actorIds: Set<UUID> = emptySet(),
) : ComponentPermissionResolver {
    private val adminActorIds: MutableSet<UUID> = actorIds.toMutableSet()

    /**
     * [actorId] 가 등록된 admin 집합에 있으면 `true`.
     *
     * [permission]·[projectId] 는 포트 시그니처 준수를 위해 받되, 이 fake 는 actor 단위로만
     * 판정한다(아카이브 게이트가 단일 [ComponentPermission.UPDATE] 만 사용하므로 충분).
     *
     * @param actorId 권한 평가 대상 UUID.
     * @param permission 검증 권한(무시 — actor 단위 판정).
     * @param projectId 프로젝트 UUID(무시 — actor 단위 판정).
     * @return admin 집합 포함 여부.
     */
    override fun hasPermission(
        actorId: UUID,
        permission: ComponentPermission,
        projectId: UUID,
    ): Boolean = actorId in adminActorIds

    /** [actorId] 를 admin 으로 등록해 이후 [hasPermission] 이 `true` 가 되게 한다. */
    fun grantAdmin(actorId: UUID) {
        adminActorIds.add(actorId)
    }

    /** [actorId] 의 admin 등록을 해제해 이후 [hasPermission] 이 `false` 가 되게 한다. */
    fun revokeAdmin(actorId: UUID) {
        adminActorIds.remove(actorId)
    }
}
