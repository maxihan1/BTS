// 이슈의 담당자/리포터를 cross-BC 조회하는 포트 (notification 알림 수신자 해석용)

package com.bts.shared.issue

import java.util.UUID

/**
 * 이슈 알림 수신자(리포터·담당자) cross-BC 조회 포트 (FR-NT-02 Task 5).
 *
 * notification BC 가 이슈 이벤트 발생 시 알림 대상자를 결정하기 위해 이 인터페이스를 호출한다.
 * 구현체는 issue-tracking BC 가 제공(Task 6)하며, 양쪽 BC 는 shared-kernel 을 통해 간접 의존한다.
 * notification 은 issue-tracking 을 직접 gradle 의존하지 않는다.
 *
 * ### 의존 방향
 * ```
 * notification  ──(implementation)──▶  shared-kernel ◀──(implementation)──  issue-tracking
 * ```
 *
 * ### fail-safe 기본 구현
 * issue-tracking adapter 가 등록되지 않은 환경에서는 default 구현이 빈 수신자를 반환한다.
 * 빈 수신자 반환 = 알림 미발송(과발송 없음, 안전).
 * 이는 권한 resolver 의 fail-closed(deny) 와 다른 방향이다 — 수신자 조회 실패는 보안 판단이 아닌
 * 알림 누락(누락이 과발송보다 안전)으로 처리한다.
 */
interface IssueRecipientLookupPort {
    /**
     * 주어진 이슈 키의 현재 리포터·담당자 ID 를 반환한다.
     *
     * 이슈가 존재하지 않거나 adapter 가 부재한 경우 빈 수신자([IssueRecipients.empty])를 반환한다.
     * 이 메서드는 읽기 전용이며 부수 효과가 없다.
     *
     * @param issueKey "PROJ-1" 형태의 이슈 키
     * @return 리포터·담당자 UUID (알 수 없는 경우 null)
     */
    fun findRecipients(issueKey: String): IssueRecipients = IssueRecipients.empty()
}

/**
 * 이슈 알림 수신자 정보.
 *
 * @param reporterId 이슈 리포터 UUID. 알 수 없는 경우 null.
 * @param assigneeId 이슈 담당자 UUID. 담당자가 없거나 알 수 없는 경우 null.
 * @param watcherIds 이슈를 구독 중인 watcher UUID 목록. adapter 부재 시 빈 리스트.
 * @param componentLeadIds 이슈가 속한 컴포넌트의 리드 UUID 목록. adapter 부재 시 빈 리스트.
 * @param previousAssigneeId 직전 담당자 UUID (전이·재배정 이벤트 전용). 해당 없는 경우 null.
 */
data class IssueRecipients(
    val reporterId: UUID?,
    val assigneeId: UUID?,
    val watcherIds: List<UUID> = emptyList(),
    val componentLeadIds: List<UUID> = emptyList(),
    val previousAssigneeId: UUID? = null,
) {
    companion object {
        /** adapter 부재 또는 이슈 미존재 시 반환하는 fail-safe 빈 수신자. */
        fun empty(): IssueRecipients = IssueRecipients(
            reporterId = null,
            assigneeId = null,
            watcherIds = emptyList(),
            componentLeadIds = emptyList(),
            previousAssigneeId = null,
        )
    }
}
