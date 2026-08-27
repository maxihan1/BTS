// BulkItemApplier — 이슈 변경 + SUCCEEDED 상태 기록을 REQUIRES_NEW 독립 트랜잭션으로 수행

package com.bts.issue.bulk.application

import com.bts.issue.adapter.inbound.rest.IssueResponse
import com.bts.issue.application.IssueApplicationService
import com.bts.issue.application.TransitionIssueRequest
import com.bts.issue.application.UpdateIssueRequest
import com.bts.issue.bulk.domain.BulkOperationId
import com.bts.issue.bulk.domain.BulkOperationPayload
import com.bts.issue.bulk.domain.FailureReasonCode
import com.bts.issue.bulk.domain.ItemStatus
import com.bts.issue.bulk.domain.StateNotInMigrationMappingException
import com.bts.issue.bulk.repository.BulkOperationRepository
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueVersionConflictException
import com.bts.issue.project.archive.ProjectArchiveGuard
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
 * @param projectArchiveGuard 아카이브 프로젝트 쓰기 잠금 가드. null 이면 검사를 skip 한다
 *   (기존 단위 테스트 호환용 fallback — [IssueApplicationService] 의 동명 파라미터와 같은 형태).
 *   Spring 컨텍스트에서는 ProjectArchiveGuard(@Component) Bean 이 주입된다.
 */
@Component
class BulkItemApplier(
    private val issueService: IssueApplicationService,
    private val bulkRepo: BulkOperationRepository,
    private val issueRepository: IssueRepository,
    // STATUS_MIGRATION 은 issueService 쓰기 초크포인트를 우회하므로 이 가지가 직접 가드를 부른다.
    // Edit/Transition 가지는 issueService 안에서 이미 통과하므로 여기서 중복 호출하지 않는다.
    private val projectArchiveGuard: ProjectArchiveGuard? = null,
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
     * ## STATUS_MIGRATION
     * 워크플로우 엔진을 우회하는 유일한 가지다. 우회 경계와 그 근거는 [migrateStatus] KDoc 이 정본이다.
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
                migrateStatus(issueKey, existing, payload.mappings)
            }
        }

        bulkRepo.updateItemResult(operationId, issueKey, ItemStatus.SUCCEEDED, null)
    }

    /**
     * 상태 이관 1건을 워크플로우 엔진을 우회해 재작성한다 (STATUS_MIGRATION).
     *
     * ## 무엇을 우회하고 무엇을 지키는가
     * 정본은 spec §D3 (`docs/specs/2026-08-27-issue-tracking-status-migration.md`) 의 8단계 표다.
     * - **우회** — ① per-issue TRANSITION 권한(위조 차단은 호출자의 발행 권한 책임 · 편차 X4) ·
     *   ⑤ 워크플로우 엔진 `plan()`(이관 대상은 유효한 전환이 0이다 · Jira J8) ·
     *   ⑧ 후처리(`plan.emitEvents`)는 plan 자체가 없어 N/A.
     * - **유지** — ② 아카이브 가드([ProjectArchiveGuard.checkByIssue]) · ③ 비관락 ·
     *   ⑥ [IssueRepository.applyTransition] 의 OCC 와 해결책.
     *   ⑦ `IssueTransitioned` 발행은 Task 6 이 이 자리에 붙인다.
     *
     * ## 대상은 항목마다 다르다 (F7 · J7)
     * 매핑은 「빠지는 상태 → 새 상태」 목록이라 이슈의 **현재 상태로 조회**해야 대상이 정해진다.
     * 첫 항목으로 고정하면 다른 출발 상태의 이슈가 남의 대상으로 밀려간다.
     *
     * ## 해결책은 그대로 실어 보낸다 (F10)
     * [IssueRepository.applyTransition] 은 `resolutionId=null` 을 받으면 `resolution_id` 를 **지운다**
     * (비DONE 재전환 clear 시맨틱). 이관은 상태만 옮기는 것이므로 기존 값을 그대로 넘긴다.
     *
     * ## 0행을 성공으로 적지 않는다 (F9 · E10)
     * 0행은 다른 트랜잭션이 먼저 버전을 올렸다는 뜻이다. 그대로 SUCCEEDED 를 찍으면 이슈는 옛 상태에
     * 남았는데 장부만 옮겼다고 말한다 — 이 기능이 없애려는 유령 상태를 이 기능이 만드는 형태다.
     * [IssueApplicationService.transitionIssue] 와 같은 형태로 [IssueVersionConflictException] 을 던지고,
     * [BulkItemExecutor] 가 이를 [FailureReasonCode.VERSION_CONFLICT] 로 기록한다.
     *
     * ## 아카이브 프로젝트는 이관도 예외가 아니다 (E14 · D3 ②)
     * 이 가지는 [IssueApplicationService] 의 쓰기 초크포인트를 우회하므로 가드가 함께 빠진다.
     * 그래서 여기서 직접 [ProjectArchiveGuard.checkByIssue] 를 부른다 — 다른 모든 쓰기가 거부하는 일을
     * 이관만 조용히 해내면 아카이브가 잠금이 아니게 된다. 호출 순서는 D-ORDER 를 지켜
     * `findByKey`(BROWSE 권한 검증) 뒤에 온다.
     *
     * ## 매핑에 없는 상태는 밀지 않는다 (E8 · F21)
     * 항목 적재 이후 누군가 그 이슈를 옮겼다는 뜻이다. 임의 대상으로 밀어 넣지 않고
     * [StateNotInMigrationMappingException] 을 던져 그 건만
     * [FailureReasonCode.STATE_NOT_IN_MAPPING] 으로 FAILED 로 남긴다.
     * 장부를 이 자리에서 직접 적지 않는 이유 — 정상 반환하면 [BulkItemExecutor] 가 성공 로그
     * (`bulk_op_item_succeeded`)를 찍어 FAILED 장부와 어긋난다. 실패 기록은 executor 한 곳이 맡는다.
     *
     * ## 아직 열려 있는 창 (E15 · 부채 143)
     * 큐잉 → 실행 사이에 그 상태로 들어온 이슈(E12)는 워커가 실행 시점에 다시 긁어 담으므로 이관된다(F15).
     * 그러나 **이관 완료 이후** 워크플로우 정의 교체 전에 또 들어오면 이 경로는 그것을 모른다.
     * 발행 경로가 「이관 → 재확인 → 교체」 루프를 돌아야 닫히고 그것은 project-workflow 소관이라 PR 7b 다.
     *
     * @param issueKey 이관 대상 이슈 키.
     * @param existing 처리 시점에 읽은 이슈. 현재 상태·버전·해결책의 출처다.
     * @param mappings 출발 상태 키 → 대상 상태 키.
     * @throws com.bts.issue.project.archive.ProjectArchivedException 이슈의 프로젝트가 아카이브 상태일 때.
     * @throws StateNotInMigrationMappingException 현재 상태가 [mappings] 에 없을 때.
     * @throws IssueVersionConflictException OCC 충돌로 0행이 갱신됐을 때.
     */
    private fun migrateStatus(
        issueKey: IssueKey,
        existing: IssueResponse,
        mappings: Map<String, String>,
    ) {
        projectArchiveGuard?.checkByIssue(issueKey)

        val target =
            mappings[existing.currentStateKey]
                ?: throw StateNotInMigrationMappingException(issueKey, existing.currentStateKey)
        val updatedRows =
            issueRepository.applyTransition(
                key = issueKey,
                toState = target,
                expectedVersion = existing.version,
                resolutionId = existing.resolutionId,
            )
        if (updatedRows == 0) {
            throw IssueVersionConflictException(issueKey, existing.version)
        }
    }
}
