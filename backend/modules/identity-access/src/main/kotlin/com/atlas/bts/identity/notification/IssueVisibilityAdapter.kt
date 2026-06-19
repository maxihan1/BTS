// 알림 수신자 가시성 필터 — 단건 VIEW 판정(매트릭스+보안등급)을 재사용해 볼 수 있는 사용자만 남긴다 (FR-NT-03 Task 6)

package com.atlas.bts.identity.notification

import com.atlas.bts.identity.issuesecurity.IssueSecurityLookup
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.permission.IssueVisibilityPort
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * [IssueVisibilityPort] identity-access 구현체 (FR-NT-03 Task 6).
 *
 * ## 단건 VIEW 판정의 source of truth 재사용 — 새 보안 경로 없음
 * 단건 이슈 VIEW 가시성의 단일 진실은 **VIEW_ISSUE 매트릭스 권한 + 보안등급 게이트의 결합**이다
 * (ADR 2026-06-06 issue-security-level-scheme-model §결정4·5). 이 adapter 는 별도 보안 판정 로직을
 * 만들지 않고, 이미 그 결합 판정을 수행하는 [IssuePermissionResolver.hasPermission]
 * ([com.atlas.bts.identity.permission.IdentityAccessIssuePermissionResolver] 의 VIEW 경로 — VIEW_ISSUE
 * 매트릭스 통과 후 `passesSecurityGate` 가 [com.atlas.bts.identity.issuesecurity.IssueSecurityDecider] 로
 * 위임)에 후보별로 위임한다. 판정 로직 복제를 금지해 가시성 누출 표면을 한 곳으로 모은다(리뷰 BLOCKER 해소).
 *
 * ## 관리자 우회 없음 (ADR §결정5)
 * 위임 대상 resolver 는 isSystemAdmin 을 호출하지 않으므로, SYSTEM_ADMIN/PROJECT_ADMIN 도 등급 멤버가
 * 아니면 통과하지 못한다. 이 adapter 도 admin 단락 경로를 추가하지 않는다.
 *
 * ## N+1 회피 (C2 — 메시지당 쿼리 폭증 방지)
 * 가시성 판정 전에 [IssueSecurityLookup.lookup] 으로 이슈 존재 여부를 1회 확인한다. 소프트삭제·미존재
 * 이슈는 후보 수와 무관하게 단 1쿼리로 빈 집합을 반환해 단락한다. 활성 이슈만 후보별 resolver 위임으로
 * 진행한다. (이슈 컨텍스트/등급 멤버를 메모리에서 후보별로 재판정하려면 판정 로직 복제가 불가피하므로,
 * "복제 금지"를 우선해 후보별 위임을 택했다. resolver 의 보안 게이트는 동일 등급 멤버 목록을 후보마다
 * 재조회하나, 알림 수신자 후보군은 소규모라 실용상 안전하다.)
 *
 * ## 빈 등록 / 프로파일
 * `@Profile` 을 두지 않아 모든 프로파일에서 단일 가시성 빈으로 등록된다. prod 에서는 위임 대상이
 * [com.atlas.bts.identity.permission.IdentityAccessIssuePermissionResolver](`@Profile("prod")`)로,
 * non-prod 에서는 `DevAllowIssuePermissionResolver`(`@Profile("!prod")`)로 자동 해석된다.
 *
 * ## cross-BC 결선 한계
 * BTS 는 cross-BC 배포 조립이 부재하므로(learnings [[no-cross-bc-deployment-assembly]]), notification
 * 컨텍스트가 이 포트를 실제로 주입받는 prod 부팅은 별도 조립 없이는 실증 불가하다. 본 포트 구현의 검증은
 * test-assembled 통합테스트([IssueVisibilityAdapterIntegrationTest])가 표준이다.
 *
 * @see IssueVisibilityPort
 * @see IssuePermissionResolver
 * @see IssueSecurityLookup
 */
@Component
class IssueVisibilityAdapter(
    private val permissionResolver: IssuePermissionResolver,
    private val securityLookup: IssueSecurityLookup,
) : IssueVisibilityPort {
    // ReturnCount: 빈 후보 단락 + 이슈 미존재 단락 + 후보별 위임 결과 — 3개 guard-clause early return.
    // DEVELOPMENT.md §2.3 Early return 권장에 부합(전역 임계 완화 대신 국소 Suppress).
    @Suppress("ReturnCount")
    @Transactional(readOnly = true)
    override fun filterVisibleUserIds(
        issueKey: String,
        candidateUserIds: Set<UUID>,
    ): Set<UUID> {
        if (candidateUserIds.isEmpty()) return emptySet()
        // 소프트삭제·미존재 이슈는 후보 수와 무관하게 1쿼리로 빈 집합 단락(C2).
        securityLookup.lookup(issueKey) ?: return emptySet()
        // 활성 이슈 — 후보별 단건 VIEW 판정(매트릭스+보안등급)을 검증된 resolver 에 위임한다.
        return candidateUserIds.filterTo(mutableSetOf()) { candidateId ->
            permissionResolver.hasPermission(
                actorId = candidateId,
                permission = IssuePermission.VIEW,
                scope = IssueScope.Issue(issueKey),
            )
        }
    }
}
