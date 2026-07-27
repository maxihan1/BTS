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
 * **import 전용 진입점.**
 * [recordImported] 는 외부 소스(Jira changelog 등)가 이미 완성한 [IssueChangeGroup] 을
 * detector/resolver 없이 그대로 기록하는 별도 진입점이다. [record] 와 함께 참고할 것.
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

    /**
     * import 전용 이력 기록 진입점 (FR-IM-01 PR4 Task 3).
     *
     * 외부 소스(Jira changelog 등)가 이미 완성한 [group] 을 detector/resolver 없이 그대로
     * [repository] 에 위임한다. [record] 는 before/after [Issue] 스냅샷으로 diff 를 계산해야
     * 하지만, import 는 원본 시스템이 이미 계산해 둔 변경 항목(items)·행위자(actorId)·
     * 발생 시각(createdAt)을 그대로 재생(replay)하므로 diff 계산이 불필요하고 무의미하다.
     *
     * **occurredAt 보존 근거.**
     * import 이력이 감사(append-only audit trail)로서 의미를 가지려면 원본 시스템에서
     * 실제로 변경이 발생한 시각을 보존해야 한다. [group.createdAt] 이 과거 시각으로 채워져
     * 있으면 [IssueChangeHistoryRepository.record] 구현체가 그 값을 그대로 저장한다
     * (import 시각 NOW() 로 덮어쓰지 않음). [group.createdAt] 이 null 이면(하위 호환) DB
     * DEFAULT NOW() 로 폴백한다 — [record] 가 만드는 그룹과 동일한 동작이다.
     *
     * **[record] 와의 차이.**
     * - [record] — before/after 로 diff 계산(detector) + 라벨 채움(resolver) 후 기록.
     * - [recordImported] — 이미 조립된 [group] 을 그대로 기록. detector/resolver 미경유.
     *
     * append-only 감사 이력 원칙(DATA.md §3)은 이 진입점에도 동일하게 적용된다 —
     * 기록 후 수정·삭제 없음.
     *
     * @param group 외부 소스에서 이미 완성한 변경 그룹(items/actorId/createdAt 포함).
     */
    @Transactional
    fun recordImported(group: IssueChangeGroup) {
        repository.record(group)
        log.info(
            "history_recorded_imported issueKey={} actor={} itemCount={} createdAt={}",
            group.issueKey,
            group.actorId,
            group.items.size,
            group.createdAt,
        )
    }

    /**
     * 댓글 본문 수정 이력을 기록한다 (FR-CO-02).
     *
     * @param issueId 댓글이 달린 이슈의 UUID.
     * @param issueKey 기록 시점의 이슈 키 스냅샷.
     * @param actor 본문을 수정한 행위자.
     * @param commentId 수정된 댓글의 UUID. [COMMENT_FIELD_PREFIX] 와 결합해 field 에 싣는다.
     * @param beforeBody 수정 전 본문.
     * @param afterBody 수정 후 본문.
     */
    @Transactional
    fun recordCommentEdited(
        issueId: UUID,
        issueKey: String,
        actor: ActorId,
        commentId: UUID,
        beforeBody: String,
        afterBody: String,
    ) {
        val group =
            IssueChangeGroup(
                issueId = issueId,
                issueKey = issueKey,
                actorId = actor.value,
                items =
                    listOf(
                        IssueChangeItem(
                            field = "$COMMENT_FIELD_PREFIX$commentId",
                            fromValue = beforeBody,
                            toValue = afterBody,
                        ),
                    ),
                // createdAt 미지정 → DB DEFAULT NOW(). import 와 달리 "지금" 이 정답이다.
            )
        repository.record(group)
        log.info(
            "history_recorded_comment_edited issueKey={} commentId={} actor={}",
            issueKey,
            commentId,
            actor.value,
        )
    }

    companion object {
        /**
         * 댓글 본문 변경 항목의 field prefix.
         *
         * `"comment:" + UUID(36자)` = 44자로 `issue_change_item.field VARCHAR(64)` 안에 들어간다.
         * Task 11 의 삭제 댓글 마스킹과 프론트 i18n 이 같은 값을 참조해야 하므로 public 이다.
         */
        const val COMMENT_FIELD_PREFIX = "comment:"
    }
}
