// BulkItemApplier — 이슈 변경 + SUCCEEDED 상태 기록을 REQUIRES_NEW 독립 트랜잭션으로 수행

package com.bts.issue.bulk.application

import com.bts.issue.application.IssueApplicationService
import com.bts.issue.application.TransitionIssueRequest
import com.bts.issue.application.UpdateIssueRequest
import com.bts.issue.bulk.domain.BulkOperationId
import com.bts.issue.bulk.domain.BulkOperationPayload
import com.bts.issue.bulk.domain.ItemStatus
import com.bts.issue.bulk.repository.BulkOperationRepository
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueKey
import com.bts.issue.repository.IssueRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 이슈 변경 + SUCCEEDED 상태 기록을 단일 REQUIRES_NEW 트랜잭션으로 수행하는 컴포넌트.
 *
 * ## 왜 별도 Bean 인가
 * Spring AOP @Transactional 은 프록시 경유 호출에만 적용된다.
 * [BulkItemExecutor] 와 별도 Bean 으로 분리해야 REQUIRES_NEW 트랜잭션이 AOP 프록시를 통해 생성된다.
 * self-invocation(같은 클래스 내 `this.method()`) 은 프록시를 우회해 REQUIRES_NEW 가 무시된다.
 *
 * ## C1 부분실패 창 제거
 * 이슈 변경과 SUCCEEDED 상태 기록이 같은 REQUIRES_NEW 트랜잭션 안에서 커밋된다.
 * 이슈 변경 커밋 후 상태 기록 전 크래시로 PENDING 항목이 잔존하는 C1 부분실패 창을 제거한다.
 *
 * @param issueService 이슈 변경 유스케이스.
 * @param bulkRepo 항목 상태 기록 Repository.
 * @param issueRepository STATUS_MIGRATION 이 워크플로우 엔진을 우회해 상태를 쓰는 Repository.
 */
@Component
class BulkItemApplier(
    private val issueService: IssueApplicationService,
    private val bulkRepo: BulkOperationRepository,
    private val issueRepository: IssueRepository,
) {
    /**
     * 이슈 변경과 SUCCEEDED 상태 기록을 단일 REQUIRES_NEW 트랜잭션으로 수행한다.
     *
     * 예외가 발생하면 트랜잭션이 롤백되며 호출자([BulkItemExecutor.executeItem]) 에게 전파된다.
     * 호출자는 이 예외를 catch 하여 [BulkItemFailureRecorder.recordFailure] 로 실패를 기록한다.
     *
     * repository 직행 금지 — 도메인 정규화·검증·권한 검증·전환 위임은
     * [IssueApplicationService] 를 통해 수행한다 (learnings: PATCH-merge-domain-bypass).
     *
     * ## BULK_TRANSITION + resolutionId
     * [BulkOperationPayload.Transition.resolutionId] 를 [TransitionIssueRequest.resolutionId] 에 그대로 전달한다.
     * 전체 일괄 항목에 동일한 resolutionId 가 적용된다.
     * null 이면 단건 전환과 동일하게 issues.resolution_id 를 clear 한다.
     * 존재하지 않는 resolutionId 는 [IssueApplicationService.transitionIssue] 에서 거부되어
     * 해당 항목이 FAILED 로 기록된다.
     *
     * @param actor 행위자.
     * @param operationId 부모 작업 식별자.
     * @param issueKey 처리 대상 이슈 키.
     * @param payload 일괄 작업 payload.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun applyAndRecordSuccess(
        actor: ActorId,
        operationId: BulkOperationId,
        issueKey: IssueKey,
        payload: BulkOperationPayload,
    ) {
        val existing = issueService.findByKey(actor, issueKey)

        when (payload) {
            is BulkOperationPayload.Edit -> {
                issueService.updateIssue(
                    actor,
                    issueKey,
                    UpdateIssueRequest(
                        summary = null,
                        priority = payload.priority,
                        impact = payload.impact,
                        expectedVersion = existing.version,
                    ),
                )
            }
            is BulkOperationPayload.Transition -> {
                issueService.transitionIssue(
                    actor,
                    issueKey,
                    TransitionIssueRequest(
                        toStateKey = payload.toStateKey,
                        expectedVersion = existing.version,
                        resolutionId = payload.resolutionId,
                    ),
                )
            }
            is BulkOperationPayload.StatusMigration -> {
                issueRepository.applyTransition(
                    key = issueKey,
                    toState = payload.mappings.values.first(),
                    expectedVersion = existing.version,
                    resolutionId = existing.resolutionId,
                )
            }
        }

        bulkRepo.updateItemResult(operationId, issueKey, ItemStatus.SUCCEEDED, null)
    }
}
