// 이슈 권한 평가 outbound port — ADR 2026-05-22-issue-permission-resolver-port. FR-AU-12 시 identity-access adapter 로 교체

package com.bts.issue.port.outbound

import com.bts.issue.domain.ActorId

/**
 * 이슈 권한 평가 outbound port.
 *
 * issue-tracking BC 가 정의하고, identity-access BC 가 adapter 를 제공한다 (FR-AU-12 후속).
 * 본 PR 에서는 [com.bts.issue.adapter.outbound.AlwaysAllowIssuePermissionResolver] stub 만 활용한다.
 *
 * ## 호출 위치
 * [com.bts.issue.application.IssueApplicationService] 의 각 mutation 메서드 진입 직후.
 * `@PreAuthorize` SpEL 표현식 대신 명시적 메서드 호출로 권한을 검증한다.
 *
 * ```kotlin
 * @Transactional
 * fun createIssue(actor: ActorId, request: CreateIssueRequest): IssueKey {
 *     if (!permissionResolver.hasPermission(actor, IssuePermission.CREATE, IssueScope.Project(request.projectKey))) {
 *         throw IssueAccessDeniedException(actor, IssuePermission.CREATE, IssueScope.Project(request.projectKey))
 *     }
 *     // ...
 * }
 * ```
 *
 * ## FR-AU-12 도입 시 교체 흐름
 * 1. identity-access BC 가 `IdentityAccessIssuePermissionResolver` (`@Component @Profile("prod")`) 구현.
 * 2. [com.bts.issue.adapter.outbound.AlwaysAllowIssuePermissionResolver] 는 dev/staging 에서만 활성.
 * 3. 이 interface 를 사용하는 IssueApplicationService 코드는 **변경 없음** — adapter 추가만으로 교체 완료.
 *
 * ## ArchUnit 강제
 * - `IssueApplicationService` (Service 계층) 는 이 interface 만 의존한다. 구체 구현체 직접 import 금지.
 * - `com.bts.identity.*` 의 클래스를 issue-tracking 에서 직접 import 하면 빌드 실패 (BC 격리 룰).
 *
 * @see com.bts.issue.adapter.outbound.AlwaysAllowIssuePermissionResolver
 * @see docs/adr/2026-05-22-issue-permission-resolver-port.md
 */
interface IssuePermissionResolver {
    /**
     * 주어진 행위자([actorId])가 특정 범위([scope]) 내에서 요청 권한([permission])을 보유하는지 판정한다.
     *
     * @param actorId 권한 평가 대상 행위자. nil UUID 는 [ActorId] 생성 시점에 이미 거부된다.
     * @param permission 검증할 이슈 권한. [IssuePermission] 참조.
     * @param scope 권한 적용 범위. [IssueScope.Global], [IssueScope.Project], [IssueScope.Issue] 중 하나.
     * @return 권한 있으면 `true`, 없으면 `false`.
     */
    fun hasPermission(
        actorId: ActorId,
        permission: IssuePermission,
        scope: IssueScope,
    ): Boolean
}
