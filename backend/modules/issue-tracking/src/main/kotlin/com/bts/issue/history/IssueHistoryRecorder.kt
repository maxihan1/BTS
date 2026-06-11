// 이슈 변경 이력 기록 facade — detector/resolver/repository 를 조합해 단일 진입점 제공

package com.bts.issue.history

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 이슈 변경 이력 기록 facade.
 *
 * [IssueChangeDetector], [IssueChangeLabelResolver], [IssueChangeHistoryRepository] 를
 * 조합하여 호출자가 한 번의 [record] 호출로 이력을 기록할 수 있도록 캡슐화한다.
 *
 * **진입점 별 동작.**
 * - `before=null` (이슈 생성): [IssueChangeDetector.created] 로 lifecycle 마커를 생성한다.
 * - `after=null` (이슈 소프트 삭제): [IssueChangeDetector.deleted] 로 lifecycle 마커를 생성한다.
 * - `before, after` 모두 non-null (이슈 수정): [IssueChangeDetector.detect] 로 변경 항목을 탐지한다.
 *   **items 가 비어 있으면 no-op — repository.record 를 호출하지 않는다.**
 *
 * **트랜잭션.**
 * 별도 @Service 빈이므로 [IssueApplicationService] 의 트랜잭션(REQUIRED)에 참여한다.
 * self-invocation 함정 없음 (메모리 트랜잭션-self-invocation-REQUIRES_NEW).
 *
 * @see IssueChangeDetector
 * @see IssueChangeLabelResolver
 * @see IssueChangeHistoryRepository
 */
@Service
class IssueHistoryRecorder(
    private val detector: IssueChangeDetector,
    private val resolver: IssueChangeLabelResolver,
    private val repository: IssueChangeHistoryRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 이슈 변경 이력을 기록한다.
     *
     * @param before 변경 전 이슈 상태. null 이면 생성(created) 경로.
     * @param after 변경 후 이슈 상태. null 이면 소프트 삭제(deleted) 경로.
     * @param actor 변경을 수행한 행위자. null 이면 시스템 자동 처리.
     * @param projectId 소속 프로젝트 UUID. 라벨 resolver 에 전달한다.
     */
    @Transactional
    fun record(
        before: Issue?,
        after: Issue?,
        actor: ActorId?,
        projectId: UUID,
    ) {
        val (issue, items) =
            when {
                before == null && after != null -> after to detector.created(after)
                after == null && before != null -> before to detector.deleted(before)
                before != null && after != null -> {
                    val detected = detector.detect(before, after)
                    if (detected.isEmpty()) {
                        log.debug("history_record_noop issueKey={}", before.key.value)
                        return
                    }
                    after to detected
                }
                else -> {
                    log.warn("history_record_skipped: before 와 after 모두 null — 기록 대상 없음")
                    return
                }
            }

        val resolvedItems = resolver.resolveLabels(items, projectId)
        val group =
            IssueChangeGroup(
                issueId = issue.id.value,
                issueKey = issue.key.value,
                actorId = actor?.value,
                items = resolvedItems,
            )
        repository.record(group)
        log.info(
            "history_recorded issueKey={} actor={} itemCount={}",
            issue.key.value,
            actor?.value,
            resolvedItems.size,
        )
    }
}
