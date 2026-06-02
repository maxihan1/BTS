// 이슈 권한 판정 포트(공용). actorId는 UUID(BC별 ActorId/UserId 별칭의 공통 분모).

package com.bts.shared.permission

import java.util.UUID

/**
 * 이슈 권한 평가 outbound port — 전 BC 공용.
 *
 * issue-tracking BC 가 이 interface 를 통해 권한 판정을 요청하고,
 * identity-access BC 가 [com.atlas.bts.identity.permission.IdentityAccessIssuePermissionResolver]
 * (`@Profile("prod")`) 로 adapter 를 제공한다 (FR-PM-02).
 * 개발/스테이징 환경에서는 `AlwaysAllowIssuePermissionResolver` stub 이 활성화된다.
 *
 * ## 배선 B (결정 G7)
 * 계약 타입(이 interface + [IssuePermission] + [IssueScope])을 shared-kernel 에 배치하여
 * issue-tracking 과 identity-access 모두 이 모듈만 의존한다.
 * identity-access 가 issue-tracking 내부를 역방향 의존하는 god-dependency 를 차단한다.
 *
 * ## actorId 타입 — UUID (BC 공통 분모)
 * 각 BC 는 `ActorId`, `UserId` 등 자체 별칭을 사용할 수 있으나,
 * 공용 포트 시그니처는 `java.util.UUID` 를 사용해 BC 간 타입 결합을 제거한다.
 * 호출자는 `actor.value` 로 UUID 를 추출하여 전달한다.
 *
 * ## 호출 위치
 * [com.bts.issue.application.IssueApplicationService] 의 각 mutation 메서드 진입 직후.
 * `@PreAuthorize` SpEL 표현식 대신 명시적 메서드 호출로 권한을 검증한다 (결정 G4).
 *
 * ## ArchUnit 강제
 * - `IssueApplicationService` (Service 계층) 는 이 interface 만 의존한다. 구체 구현체 직접 import 금지.
 * - `com.atlas.bts.identity.*` 의 클래스를 issue-tracking 에서 직접 import 하면 빌드 실패 (BC 격리 룰).
 *
 * @see IssuePermission
 * @see IssueScope
 */
interface IssuePermissionResolver {
    /**
     * 주어진 행위자([actorId])가 특정 범위([scope]) 내에서 요청 권한([permission])을 보유하는지 판정한다.
     *
     * @param actorId 권한 평가 대상 행위자 UUID. nil UUID 는 호출 이전 인증 단계에서 이미 거부된다.
     * @param permission 검증할 이슈 권한. [IssuePermission] 참조.
     * @param scope 권한 적용 범위. [IssueScope.Global], [IssueScope.Project], [IssueScope.Issue] 중 하나.
     * @return 권한 있으면 `true`, 없으면 `false`.
     */
    fun hasPermission(
        actorId: UUID,
        permission: IssuePermission,
        scope: IssueScope,
    ): Boolean
}
