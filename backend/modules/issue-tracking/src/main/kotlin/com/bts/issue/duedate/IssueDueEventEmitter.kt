// 마감일 이벤트(IssueDueSoon·IssueOverdue) 를 per-이슈 트랜잭션으로 발행하는 어댑터

package com.bts.issue.duedate

import com.bts.issue.event.IssueDueSoon
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.event.IssueOverdue
import com.bts.issue.repository.IssueDueScanItem
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 마감 임박·마감 초과 도메인 이벤트를 발행하는 어댑터.
 *
 * ## 왜 별도 빈으로 분리하는가 (self-invocation 함정)
 * [IssueDueDateScanWorker] 안에서 `@Transactional` 메서드를 self-call 하면
 * Spring AOP 프록시를 우회하여 트랜잭션이 실제로 열리지 않는다.
 * 별도 빈으로 분리해 주입받아 호출해야 프록시를 경유하므로
 * [IssueEventPublisher] 가 요구하는 MANDATORY 트랜잭션이 보장된다.
 *
 * ## 트랜잭션 격리
 * 각 메서드가 독립된 REQUIRED 트랜잭션을 시작한다. 하나의 이슈 발행이 실패해도
 * 다른 이슈의 트랜잭션에는 영향이 없다 (per-이슈 결함격리).
 *
 * @param publisher [IssueEventPublisher] — pgmq q_issue_events 큐 enqueue 담당.
 */
@Component
class IssueDueEventEmitter(
    private val publisher: IssueEventPublisher,
) {
    /**
     * 마감 임박([IssueDueSoon]) 이벤트를 발행한다.
     *
     * @param item 스캔 결과 이슈 키·프로젝트 키 projection.
     * @param occurredAt 스캔 기준 UTC 자정 Instant. 소비자 dedupKey 기준 시각.
     */
    @Transactional
    fun emitDueSoon(
        item: IssueDueScanItem,
        occurredAt: Instant,
    ) {
        publisher.publish(
            IssueDueSoon(
                issueKey = item.issueKey,
                projectKey = item.projectKey,
                occurredAt = occurredAt,
            ),
        )
    }

    /**
     * 마감 초과([IssueOverdue]) 이벤트를 발행한다.
     *
     * @param item 스캔 결과 이슈 키·프로젝트 키 projection.
     * @param occurredAt 스캔 기준 UTC 자정 Instant. 소비자 dedupKey 기준 시각.
     */
    @Transactional
    fun emitOverdue(
        item: IssueDueScanItem,
        occurredAt: Instant,
    ) {
        publisher.publish(
            IssueOverdue(
                issueKey = item.issueKey,
                projectKey = item.projectKey,
                occurredAt = occurredAt,
            ),
        )
    }
}
