// automation 통합 테스트용 fail-safe IssuePermissionResolver stub — AlwaysAllow (FR-AT-04 Task 4)

package com.bts.automation

import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import java.util.UUID

/**
 * automation 통합 테스트가 실제 identity-access prod 어댑터
 * (`IdentityAccessIssuePermissionResolver`, [IssuePermissionResolver] 의 `@Profile("prod")` 구현 —
 * issue-tracking 이 이미 소비 중) 없이도 [com.bts.automation.application.RuleConflictAnalyzer] 를 구동할
 * 수 있게 하는 fail-safe [IssuePermissionResolver] stub (FR-AT-04 Task 4).
 *
 * automation BC 격리상 prod 구현은 automation 모듈의 컴파일/테스트 클래스패스에 존재하지 않는다 —
 * [com.bts.automation.application.RuleConflictAnalyzer] 는 이 포트를 **non-null 생성자 주입**으로
 * 요구하므로([[crossbc-resolver-nullable-fail-open]] 회귀 방지), automation test-boot 컨텍스트에는 이
 * stub 이 대신 등록되어야 한다([StubIssueMutationPort]/[StubAutomationPermissionResolver] 와 정확히
 * 동형인 consumer-owns-stub 패턴).
 *
 * ## AlwaysAllow — 항상 `true`
 *
 * 모든 [hasPermission] 호출에 무조건 `true` 를 반환한다(스펙 FR-6 Brainstorming #5 "non-prod stub 한계
 * 명시" — non-prod 환경에서는 `PERMISSION_MISSING` 이 검출되지 않는다, 이는 다른 cross-BC 권한 검증과
 * 동일한 한계다). 검출 로직 자체 검증은
 * [com.bts.automation.application.RuleConflictAnalyzerPermissionTest] 가 mockk 로 `false` 를 주입해
 * 별도로 담당한다 — 이 stub 은 그 외 테스트(컨트롤러/워커/리포지토리)의 컨텍스트 부팅용일 뿐이다.
 *
 * ## prod 어댑터 — non-prod 는 fail-safe, prod 는 fail-closed
 *
 * prod 조립(`:modules:app`)에서는 identity-access 의 `@Profile("prod")` 어댑터가 이 포트를 대체한다 —
 * issue-tracking 이 이미 소비 중인 동일 빈을 automation 도 그대로 공유한다(신규 어댑터 불필요).
 */
class StubIssuePermissionResolver : IssuePermissionResolver {
    override fun hasPermission(
        actorId: UUID,
        permission: IssuePermission,
        scope: IssueScope,
    ): Boolean = true
}
