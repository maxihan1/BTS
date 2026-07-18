// 이슈 부모-자식 관계 설정/해제 서비스 — 단일 부모·acyclic·self 금지 불변식 강제

package com.bts.issue.link.application

import com.bts.issue.domain.IssueKey
import com.bts.issue.link.domain.LinkedIssueNotFoundException
import com.bts.issue.link.domain.ParentCycleException
import com.bts.issue.link.domain.ParentSelfReferenceException
import com.bts.issue.project.archive.ProjectArchiveGuard
import com.bts.issue.repository.IssueRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 이슈 부모-자식 관계를 설정하거나 해제하는 서비스.
 *
 * 구조 불변식 (Maxi 확정 B — ADR FR-LK-01).
 * - **단일 부모**: `issues.parent_id` 컬럼이 최대 1개의 부모를 허용한다.
 * - **acyclic**: 조상 체인이 순환을 형성하면 안 된다.
 * - **self 금지**: 이슈가 자기 자신을 부모로 지정할 수 없다.
 * - **hierarchy_level 위계 검사 없음**: FR-IS-02 parent_id 강제가 이연됐으므로
 *   유형(epic/story/subtask) 단계 검증은 이번 구현 범위에 포함하지 않는다.
 *
 * 검증 순서.
 * 1. child 존재 확인(404) — actor 소유 이슈이므로 먼저.
 * 2. parent 존재 확인(404) — 존재하지 않는 parent 로 self/cycle 검사 불필요.
 * 3. self 검사(422).
 * 4. cycle 검사(409).
 * 5. [IssueRepository.updateParent] 호출.
 *
 * 트랜잭션 경계: 모든 public 메서드에 @Transactional 명시 (DEVELOPMENT.md §1.2).
 */
@Service
class IssueParentService(
    private val issueRepository: IssueRepository,
    private val archiveGuard: ProjectArchiveGuard,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * [childKey] 이슈의 부모를 [parentKey] 이슈로 설정한다.
     *
     * @param childKey 부모를 지정받을 이슈 키.
     * @param parentKey 부모로 지정할 이슈 키.
     * @throws LinkedIssueNotFoundException child 또는 parent 이슈가 존재하지 않거나 소프트삭제된 경우(404).
     * @throws ParentSelfReferenceException childKey 와 parentKey 가 같은 이슈를 가리키는 경우(422).
     * @throws ParentCycleException parent 의 조상 체인에 child 가 포함되어 순환이 형성되는 경우(409).
     */
    @Transactional
    @Suppress("ThrowsCount") // child 404 / parent 404 / self 422 / cycle 409 — 검증 단계별 명시적 throw 4개, 리팩토링 시 복잡도 증가
    fun setParent(
        childKey: IssueKey,
        parentKey: IssueKey,
    ) {
        // 아카이브 잠금 — child/parent 어느 한쪽이라도 아카이브된 프로젝트 소속이면 409.
        // TASK9-RED-PENDING archiveGuard.checkByIssue(childKey)
        // TASK9-RED-PENDING archiveGuard.checkByIssue(parentKey)

        val child =
            issueRepository.findByKey(childKey)
                ?: run {
                    log.debug("setParent: child not found key={}", childKey.value)
                    throw LinkedIssueNotFoundException(childKey)
                }

        val parent =
            issueRepository.findByKey(parentKey)
                ?: run {
                    log.debug("setParent: parent not found key={}", parentKey.value)
                    throw LinkedIssueNotFoundException(parentKey)
                }

        val childId = child.id.value
        val pId = parent.id.value

        if (childId == pId) {
            log.debug("setParent: self-reference detected issueId={}", childId)
            throw ParentSelfReferenceException(childId)
        }

        val ancestors = issueRepository.collectAncestors(pId)
        if (childId in ancestors) {
            log.debug(
                "setParent: cycle detected childId={} parentId={} ancestors={}",
                childId,
                pId,
                ancestors,
            )
            throw ParentCycleException(childId, pId)
        }

        log.debug("setParent: childId={} parentId={}", childId, pId)
        issueRepository.updateParent(childId, pId)
    }

    /**
     * [childKey] 이슈의 부모 지정을 해제하고 최상위 이슈로 승격한다.
     *
     * @param childKey 부모를 해제할 이슈 키.
     * @throws LinkedIssueNotFoundException child 이슈가 존재하지 않거나 소프트삭제된 경우(404).
     */
    @Transactional
    fun clearParent(childKey: IssueKey) {
        // TASK9-RED-PENDING archiveGuard.checkByIssue(childKey)

        val child =
            issueRepository.findByKey(childKey)
                ?: run {
                    log.debug("clearParent: child not found key={}", childKey.value)
                    throw LinkedIssueNotFoundException(childKey)
                }

        val childId = child.id.value
        log.debug("clearParent: childId={}", childId)
        issueRepository.updateParent(childId, null)
    }
}
