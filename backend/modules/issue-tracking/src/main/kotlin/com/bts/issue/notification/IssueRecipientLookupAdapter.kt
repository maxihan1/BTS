// 이슈의 담당자/리포터를 조회해 IssueRecipientLookupPort를 구현하는 adapter (notification 알림 수신자 해석)

package com.bts.issue.notification

import com.bts.issue.domain.IssueKey
import com.bts.issue.repository.IssueRepository
import com.bts.shared.issue.IssueRecipientLookupPort
import com.bts.shared.issue.IssueRecipients
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * [IssueRecipientLookupPort] issue-tracking BC 구현체.
 *
 * notification BC 가 이슈 이벤트 발생 시 알림 수신자를 결정하기 위해 이 어댑터를 호출한다.
 * issues 테이블에서 issue_key 로 reporter_id / assignee_id 를 조회하고,
 * [IssueRecipients] 로 변환해 반환한다.
 *
 * ## BC 격리 — jOOQ 직접 참조 없음
 *
 * ArchUnit 룰 2(jooqGeneratedMustOnlyBeUsedInRepositoryLayer)에 따라 이 어댑터는
 * jOOQ 생성 코드를 직접 참조하지 않는다.
 * [IssueRepository.findByKey] 를 경유해 DB 에 접근한다.
 *
 * ## fail-safe 전략
 *
 * - 이슈가 존재하지 않거나 소프트 삭제된 경우 [IssueRecipients.empty] 를 반환한다.
 * - [IssueRepository.findByKey] 는 deleted_at IS NULL 조건을 내장하므로
 *   soft-delete/미존재 시 null 을 반환한다.
 * - 알림 누락은 과발송보다 안전하다(fail-safe 방향).
 *
 * @param issueRepository issues 테이블 jOOQ repository.
 */
@Component
class IssueRecipientLookupAdapter(
    private val issueRepository: IssueRepository,
) : IssueRecipientLookupPort {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 주어진 이슈 키의 리포터·담당자 UUID 를 반환한다.
     *
     * issues 테이블을 issue_key 로 조회하며 deleted_at IS NULL 필터가 적용된다.
     * 이슈가 없거나 소프트 삭제된 경우 [IssueRecipients.empty] 를 반환한다(fail-safe).
     *
     * @param issueKey "PROJ-1" 형태의 이슈 키.
     * @return 리포터·담당자 UUID. 이슈가 없거나 삭제된 경우 빈 수신자.
     */
    @Transactional(readOnly = true)
    override fun findRecipients(issueKey: String): IssueRecipients {
        log.debug("findRecipients issueKey={}", issueKey)

        val issue = issueRepository.findByKey(IssueKey(issueKey))
        if (issue == null) {
            log.debug("findRecipients issueKey={} — 이슈 없음 또는 소프트 삭제, 빈 수신자 반환", issueKey)
            return IssueRecipients.empty()
        }

        return IssueRecipients(
            reporterId = issue.reporterId.value,
            assigneeId = issue.assigneeId?.value,
        )
    }
}
