// slack-integration 통합 테스트용 settable IssueUnfurlPort stub — issueKey별 시드된 뷰만 fail-closed 반환 (FR-SL-03 Task 12)

package com.bts.slack

import com.bts.shared.issue.IssueUnfurlPort
import com.bts.shared.issue.IssueUnfurlView
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 통합 테스트가 이슈별 가시 카드 스냅샷을 명시 등록하는 fail-closed [IssueUnfurlPort] stub (FR-SL-03 Task 12).
 *
 * cross-BC 결합 조회 포트는 prod 에서 issue-tracking `IssueUnfurlAdapter`가 제공하나 slack test-boot
 * 컨텍스트에는 실 구현이 없다. [com.bts.slack.unfurl.SlackUnfurlService] 생성자가 non-null [IssueUnfurlPort]
 * 를 요구하므로, 이 stub 을 [SlackTestcontainersConfig] 가 `@Bean` 으로 등록해 컨텍스트 로드를 복구한다
 * ([SlackContextLoadTest] 회귀 방지, [StubUserLookupPort] 동형).
 *
 * ## settable 선례 ([StubUserLookupPort] 동형) — issueKey 기준 등록, 미등록 = fail-closed
 * [visibleIssues] 에 등록된 issueKey 만 카드를 돌려주고, 미등록 issueKey 는 null 이다(볼 수 없음으로
 * 수렴). 실 issue-tracking 어댑터는 [viewerUserId] 의 실제 열람 권한까지 판정하지만, 이 stub 의 책임은
 * "권한 판정 결과를 테스트가 미리 정한 대로 재현"하는 것이지 판정 로직 자체를 재구현하는 것이 아니다 —
 * 통합 테스트가 시나리오별로 [visibleIssues] 를 채우거나 비워 가시/무권한을 재현한다.
 */
class StubIssueUnfurlPort : IssueUnfurlPort {
    /** issueKey → 카드 스냅샷. 테스트가 채우고 비운다(스레드 안전). 미등록 = 볼 수 없음(fail-closed). */
    val visibleIssues: MutableMap<String, IssueUnfurlView> = ConcurrentHashMap()

    override fun getVisibleIssueCard(
        issueKey: String,
        viewerUserId: UUID,
    ): IssueUnfurlView? = visibleIssues[issueKey]
}
