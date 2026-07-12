// automation 통합 테스트용 fail-safe IssueSnapshotPort stub — actor+issueKey 시드 매핑, 미시드는 null (FR-AT-03 Task 7)

package com.bts.automation

import com.bts.shared.issue.IssueSnapshot
import com.bts.shared.issue.IssueSnapshotPort
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * automation 통합 테스트가 실제 issue-tracking prod 어댑터
 * ([com.bts.shared.issue.IssueSnapshotPort] 의 `@Profile("prod")` 구현, Task 6) 없이도
 * [com.bts.automation.application.ActionExecutor] 의 조건 게이트를 구동할 수 있게 하는 fail-safe
 * [IssueSnapshotPort] stub (FR-AT-03 Task 7).
 *
 * automation BC 격리상 prod 구현은 automation 모듈의 컴파일/테스트 클래스패스에 존재하지 않는다 —
 * [com.bts.automation.application.ActionExecutor] 는 이 포트를 **non-null 생성자 주입**으로 요구하므로
 * ([[crossbc-resolver-nullable-fail-open]] 회귀 방지), automation test-boot 컨텍스트에는 이 stub 이
 * 대신 등록되어야 한다([StubIssueMutationPort] 와 정확히 동형인 consumer-owns-stub 패턴).
 *
 * ## fail-safe 기본값 — 시드되지 않은 (actor, issueKey) 는 항상 null
 *
 * 시드 맵에 없는 조합은 [fetch] 가 `null` 을 반환한다 — 조건 게이트가 이를 "스냅샷 조회 불가"로 보아
 * fail-safe 하게 액션을 건너뛰는(SKIPPED) 정상 경로다(`ActionExecutor` KDoc "조건 게이트" 참조). 조건
 * 컨텍스트 웹 계층/워커 슬라이스 테스트가 이 stub 을 명시 설정하지 않아도 컨텍스트 부팅 자체는
 * 예외 없이 통과한다(consumer 가 조건 충족 시나리오를 검증하려면 [seed] 로 명시 등록해야 한다).
 */
class StubIssueSnapshotPort : IssueSnapshotPort {
    private val snapshots: MutableMap<Pair<UUID, String>, IssueSnapshot> = ConcurrentHashMap()

    override fun fetch(
        actorUserId: UUID,
        issueKey: String,
    ): IssueSnapshot? = snapshots[actorUserId to issueKey]

    /** [actorUserId]+[issueKey] 조합으로 조회 시 [snapshot] 을 반환하도록 등록한다(테스트 픽스처 시딩). */
    fun seed(
        actorUserId: UUID,
        issueKey: String,
        snapshot: IssueSnapshot,
    ) {
        snapshots[actorUserId to issueKey] = snapshot
    }

    /** 시드된 매핑을 모두 초기화한다(테스트 격리용). */
    fun reset() {
        snapshots.clear()
    }
}
