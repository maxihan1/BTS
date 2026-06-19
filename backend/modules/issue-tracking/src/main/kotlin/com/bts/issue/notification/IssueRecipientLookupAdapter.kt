// 이슈의 담당자/리포터/워처/컴포넌트리드/직전담당자를 조회해 IssueRecipientLookupPort를 구현하는 adapter (notification 알림 수신자 해석)

package com.bts.issue.notification

import com.bts.issue.component.repository.ComponentRepository
import com.bts.issue.domain.IssueKey
import com.bts.issue.history.IssueChangeHistoryRepository
import com.bts.issue.repository.IssueRepository
import com.bts.issue.watcher.repository.IssueWatcherRepository
import com.bts.shared.issue.IssueRecipientLookupPort
import com.bts.shared.issue.IssueRecipients
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * [IssueRecipientLookupPort] issue-tracking BC 구현체.
 *
 * notification BC 가 이슈 이벤트 발생 시 알림 수신자를 결정하기 위해 이 어댑터를 호출한다.
 *
 * ## 조회 필드
 * - **reporter / assignee** — issues 테이블 (issueKey → issue 조회)
 * - **watcherIds** — issue_watchers 테이블 ([IssueWatcherRepository.listByIssue])
 * - **componentLeadIds** — issue_components ⋈ components ([ComponentRepository.findLeadUserIdsByIssue])
 * - **previousAssigneeId** — issue_change_item field='assignee' 최근 from_value 파싱
 *   ([IssueChangeHistoryRepository.findLatestAssigneeChangeFromValue])
 *
 * ## BC 격리 — jOOQ 직접 참조 없음
 *
 * ArchUnit 룰 2(jooqGeneratedMustOnlyBeUsedInRepositoryLayer)에 따라 이 어댑터는
 * jOOQ 생성 코드를 직접 참조하지 않는다.
 * 각 Repository 를 경유해 DB 에 접근한다.
 *
 * ## fail-safe 전략
 *
 * - 이슈가 존재하지 않거나 소프트 삭제된 경우 [IssueRecipients.empty] 를 반환한다.
 * - [IssueRepository.findByKey] 는 deleted_at IS NULL 조건을 내장하므로
 *   soft-delete/미존재 시 null 을 반환한다.
 * - previousAssigneeId 의 from_value 파싱 실패(UUID 형식 오류, 'NONE' 등) → null 반환(fail-safe).
 * - 알림 누락은 과발송보다 안전하다(fail-safe 방향).
 *
 * @param issueRepository issues 테이블 jOOQ repository.
 * @param watcherRepository issue_watchers 테이블 repository.
 * @param componentRepository components/issue_components 테이블 repository.
 * @param historyRepository issue_change_group/item 테이블 repository.
 */
@Component
class IssueRecipientLookupAdapter(
    private val issueRepository: IssueRepository,
    private val watcherRepository: IssueWatcherRepository,
    private val componentRepository: ComponentRepository,
    private val historyRepository: IssueChangeHistoryRepository,
) : IssueRecipientLookupPort {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 주어진 이슈 키의 알림 수신자 정보를 반환한다.
     *
     * issues 테이블을 issue_key 로 조회하며 deleted_at IS NULL 필터가 적용된다.
     * 이슈가 없거나 소프트 삭제된 경우 [IssueRecipients.empty] 를 반환한다(fail-safe).
     *
     * 이슈가 존재하면 동일 issueId 로 watcher 목록·component lead·직전 assignee 를 단일 트랜잭션 내에서
     * 각각 조회해 [IssueRecipients] 를 구성한다. N+1 없음 — 각 필드당 단일 쿼리.
     *
     * @param issueKey "PROJ-1" 형태의 이슈 키.
     * @return 수신자 정보. 이슈가 없거나 삭제된 경우 빈 수신자.
     */
    @Transactional(readOnly = true)
    override fun findRecipients(issueKey: String): IssueRecipients {
        log.debug("findRecipients issueKey={}", issueKey)

        val issue = issueRepository.findByKey(IssueKey(issueKey))
        if (issue == null) {
            log.debug("findRecipients issueKey={} — 이슈 없음 또는 소프트 삭제, 빈 수신자 반환", issueKey)
            return IssueRecipients.empty()
        }

        val issueId = issue.id.value

        val watcherIds = watcherRepository.listByIssue(issueId).map { it.userId }
        val componentLeadIds = componentRepository.findLeadUserIdsByIssue(issueId)
        val previousAssigneeId =
            resolveFromValue(
                historyRepository.findLatestAssigneeChangeFromValue(issueId),
            )

        return IssueRecipients(
            reporterId = issue.reporterId.value,
            assigneeId = issue.assigneeId?.value,
            watcherIds = watcherIds,
            componentLeadIds = componentLeadIds,
            previousAssigneeId = previousAssigneeId,
        )
    }

    /**
     * assignee from_value 문자열을 UUID 로 파싱한다.
     *
     * - null 또는 빈 문자열 → null
     * - 'NONE' 등 UUID 형식이 아닌 값 → null (fail-safe)
     * - UUID 형식의 문자열 → [UUID] 변환
     *
     * @param fromValue issue_change_item.from_value 원시 값.
     * @return 파싱된 UUID. 파싱 불가능하거나 null 이면 null.
     */
    private fun resolveFromValue(fromValue: String?): UUID? {
        if (fromValue.isNullOrBlank()) return null
        return try {
            UUID.fromString(fromValue)
        } catch (e: IllegalArgumentException) {
            log.debug("previousAssigneeId 파싱 실패 (fail-safe null 반환) fromValue={} error={}", fromValue, e.message)
            null
        }
    }
}
