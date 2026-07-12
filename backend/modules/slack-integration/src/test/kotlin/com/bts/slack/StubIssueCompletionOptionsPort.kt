// slack test-boot용 seedable IssueCompletionOptionsPort stub — issueKey별 시드값만 반환(미시드 null) (FR-SL-05 Task 9)

package com.bts.slack

import com.bts.shared.issue.IssueCompletionOptions
import com.bts.shared.issue.IssueCompletionOptionsPort
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 통합 테스트가 이슈별 완료 옵션 스냅샷을 명시 등록하는 fail-closed [IssueCompletionOptionsPort] stub
 * (FR-SL-05 PR1 Task 9).
 *
 * cross-BC 결합 조회 포트는 prod 에서 issue-tracking `IssueCompletionOptionsAdapter`가 제공하나 slack
 * test-boot 컨텍스트에는 실 구현이 없다. [com.bts.slack.interaction.SlackInteractionService] 생성자가
 * non-null [IssueCompletionOptionsPort]를 요구하므로, 이 stub 을 [SlackTestcontainersConfig] 가 `@Bean`
 * 으로 등록해 컨텍스트 로드를 복구한다([SlackContextLoadTest] 회귀 방지, [StubIssueUnfurlPort] 동형).
 *
 * ## settable 선례 ([StubIssueUnfurlPort] 동형) — issueKey 기준 등록, 미등록 = fail-closed
 * [completionOptionsByIssueKey]에 등록된 issueKey 만 완료 옵션을 돌려주고, 미등록 issueKey 는 null 이다
 * (완료 불가/볼 수 없음으로 수렴). 실 issue-tracking 어댑터는 [viewerUserId]의 실제 열람 권한까지
 * 판정하지만, 이 stub 의 책임은 "판정 결과를 테스트가 미리 정한 대로 재현"하는 것이지 판정 로직 자체를
 * 재구현하는 것이 아니다 — 통합 테스트가 시나리오별로 [completionOptionsByIssueKey]를 채우거나 비워
 * 완료 가능/무권한을 재현한다.
 */
class StubIssueCompletionOptionsPort : IssueCompletionOptionsPort {
    /** issueKey → 완료 옵션 스냅샷. 테스트가 채우고 비운다(스레드 안전). 미등록 = 볼 수 없음(fail-closed). */
    val completionOptionsByIssueKey: MutableMap<String, IssueCompletionOptions> = ConcurrentHashMap()

    override fun getCompletionOptions(
        issueKey: String,
        viewerUserId: UUID,
    ): IssueCompletionOptions? = completionOptionsByIssueKey[issueKey]
}
