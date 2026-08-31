// BulkItemApplier — 이슈 변경 + SUCCEEDED 상태 기록을 REQUIRES_NEW 독립 트랜잭션으로 수행

package com.bts.issue.bulk.application

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
import com.bts.issue.domain.IssueNotFoundException
import com.bts.issue.domain.IssueVersionConflictException
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.event.IssueTransitioned
import com.bts.issue.history.IssueHistoryRecorder
import com.bts.issue.project.archive.ProjectArchiveGuard
import com.bts.issue.repository.IssueRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

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
 * @param eventPublisher STATUS_MIGRATION 이 [IssueTransitioned] 를 발행하는 아웃바운드 어댑터.
 *   Edit/Transition 가지는 [IssueApplicationService] 안에서 이미 발행하므로 여기서 쓰지 않는다.
 * @param historyRecorder STATUS_MIGRATION 이 상태 변경 이력을 남기는 facade. 위와 같은 이유로
 *   Edit/Transition 가지에서는 쓰지 않는다.
 * @param projectArchiveGuard 아카이브 프로젝트 쓰기 잠금 가드. **non-null 필수**다.
 *
 *   ## 왜 형제와 다른가 (게이트 2 리뷰 ①)
 *   [IssueApplicationService] 의 동명 파라미터는 nullable 이다. 그쪽은 가드가 없어도 쓰기 9종이
 *   **각각 자기 진입부에서** 가드를 부르는 구조라 한 곳이 비어도 나머지가 남는다. 여기는 다르다 —
 *   STATUS_MIGRATION 은 `issueService` 쓰기 초크포인트 **밖**에서 상태를 재작성하는 유일한 경로이고,
 *   그 경로에서 아카이브 잠금을 거는 자리는 [migrateStatus] 의 이 한 줄뿐이다. 의존도가 다르므로
 *   기본값도 다르다.
 *
 *   nullable 기본값은 「불명 = 허용」이다. Spring 의 Kotlin optional 파라미터 해석은 해결하지 못한
 *   인자를 **부팅 실패가 아니라 생략**으로 처리하므로, 결선이 끊겨도 아무도 모른 채 fail-open 이 된다.
 *   실제로 이 PR 의 통합 컨텍스트 3곳이 가드를 생략한 채 이관을 돌렸다. 불명은 **거부**가 기본이다.
 * @param clock 이벤트 `occurredAt` 의 시각원. [IssueApplicationService] 의 동명 파라미터와 같은
 *   fallback 형태다 — `Instant.now()` 직접 호출은 주입한 시각원을 우회해, 같은 BC 안에서
 *   시각을 고정한 테스트와 형제 발행부([IssueApplicationService] `Instant.now(clock)`)가 갈린다.
 *
 * `LongParameterList` 억제 이유. 협력자 7개는 전부 이 클래스가 **직접 부르는** 대상이고 묶을 축이 없다 —
 * 쓰기 3종(`issueService`·`issueRepository`·`bulkRepo`)과 부수효과 2종(`eventPublisher`·`historyRecorder`)은
 * 서로 다른 트랜잭션 의미를 가지며, `projectArchiveGuard` 는 non-null 이어야 하고(위 §), `clock` 은 기본값
 * fallback 이다. [IssueApplicationService] 가 같은 사유로 같은 국소 억제를 쓴다 — 전역 detekt 임계값이나
 * `detekt-baseline.xml` 은 건드리지 않는다.
 */
@Suppress("LongParameterList")
@Component
class BulkItemApplier(
    private val issueService: IssueApplicationService,
    private val bulkRepo: BulkOperationRepository,
    private val issueRepository: IssueRepository,
    private val eventPublisher: IssueEventPublisher,
    private val historyRecorder: IssueHistoryRecorder,
    // STATUS_MIGRATION 은 issueService 쓰기 초크포인트를 우회하므로 이 가지가 직접 가드를 부른다.
    // Edit/Transition 가지는 issueService 안에서 이미 통과하므로 여기서 중복 호출하지 않는다.
    private val projectArchiveGuard: ProjectArchiveGuard,
    private val clock: Clock = Clock.systemUTC(),
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
                // existing 을 넘기지 않는다 — 이관은 비관락을 잡은 뒤의 재조회가 정본이다([migrateStatus]).
                migrateStatus(actor, issueKey, payload.mappings)
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
     * - **유지** — ② 아카이브 가드([ProjectArchiveGuard.checkByIssue]) ·
     *   ③ [IssueRepository.findByKeyForUpdate] 의 비관락 ·
     *   ⑥ [IssueRepository.applyTransition] 의 OCC 와 해결책 · ⑦ [IssueTransitioned] 발행.
     *
     * ## 대상은 항목마다 다르다 (F7 · J7)
     * 매핑은 「빠지는 상태 → 새 상태」 목록이라 이슈의 **현재 상태로 조회**해야 대상이 정해진다.
     * 첫 항목으로 고정하면 다른 출발 상태의 이슈가 남의 대상으로 밀려간다.
     *
     * ## 비관락을 잡고, 잡은 뒤 다시 읽는다 (D3 ③)
     * 쓰기 전에 [IssueRepository.findByKeyForUpdate] 로 행을 잠근다 — 동시 편집과의 경합을 그대로 막는
     * 것이 spec §D3 ③ 이다. 락은 이 메서드를 감싸는 [applyAndRecordSuccess] 의 REQUIRES_NEW 트랜잭션이
     * 커밋될 때 풀린다.
     *
     * **락을 잡았어도 락 밖에서 읽은 값으로 판단하면 락이 무력해진다.** 그래서 상태·버전·해결책을 전부
     * 락 뒤 재조회 결과에서 취한다. [applyAndRecordSuccess] 가 먼저 읽는 `existing`(BROWSE 권한 검증 겸
     * Edit/Transition 가지의 입력)은 잠그지 않은 읽기라 이 가지에서는 쓰지 않는다.
     *
     * ## `expectedVersion` 은 락 뒤 버전이다 — 그리고 그 이유
     * 락 밖에서 읽은 버전을 쓰면 락이 장식이 된다. 앞선 편집이 커밋되기를 기다렸다가, 기다린 보람 없이
     * 낡은 버전으로 써서 그 건이 [IssueVersionConflictException] 으로 **실패**하기 때문이다. 이관은
     * 사용자가 낸 요청이 아니라 「이 상태에 남은 것을 전부 옮겨라」는 일괄 지시이므로, 지켜야 할 선행조건은
     * 「내가 조금 전에 본 버전 그대로인가」가 아니라 **「지금 이 이슈가 매핑된 상태에 있는가」** 다.
     * 그 선행조건은 락 뒤 상태로 매핑을 다시 찾는 것으로 검사한다 — 그 사이 누가 옮겼다면
     * [StateNotInMigrationMappingException] 이 되어 `VERSION_CONFLICT` 보다 정확한 진단이 남는다.
     * 상태를 안 건드린 편집(우선순위·담당자 등)은 이제 실패하지 않고 이관된다.
     *
     * 그 결과 [IssueRepository.applyTransition] 의 0행은 락을 쥔 동안에는 사실상 나오지 않는다. 그래도
     * 유지하는 이유는 두 가지다 — spec §D3 ⑥ 이 OCC 유지를 못박았고, **락이 사라지는 회귀**(이 PR 이
     * 실제로 한 번 겪었다)에서 그것이 마지막 방어선이기 때문이다.
     *
     * ## 해결책은 그대로 실어 보낸다 (F10)
     * [IssueRepository.applyTransition] 은 `resolutionId=null` 을 받으면 `resolution_id` 를 **지운다**
     * (비DONE 재전환 clear 시맨틱). 이관은 상태만 옮기는 것이므로 기존 값을 그대로 넘긴다.
     * 그 값도 락 뒤 재조회에서 취한다 — 락 밖에서 읽은 값을 되쓰면 그 사이의 해결책 변경을 되돌린다.
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
     * `findByKey`(BROWSE 권한 검증) 뒤 · 비관락 획득 **앞**에 온다 — 권한보다 락이 앞서면 미인가
     * 사용자가 행을 잠글 수 있다.
     *
     * ## 매핑에 없는 상태는 밀지 않는다 (E8 · F21)
     * 항목 적재 이후 누군가 그 이슈를 옮겼다는 뜻이다. 임의 대상으로 밀어 넣지 않고
     * [StateNotInMigrationMappingException] 을 던져 그 건만
     * [FailureReasonCode.STATE_NOT_IN_MAPPING] 으로 FAILED 로 남긴다.
     * 장부를 이 자리에서 직접 적지 않는 이유 — 정상 반환하면 [BulkItemExecutor] 가 성공 로그
     * (`bulk_op_item_succeeded`)를 찍어 FAILED 장부와 어긋난다. 실패 기록은 executor 한 곳이 맡는다.
     *
     * ## 무엇을 발행하고 무엇을 안 하는가 (F11 · F12 · J8)
     * - **발행한다** — [IssueTransitioned]. `cause = `[CAUSE_STATUS_MIGRATION] 을 실어 이관임을 알린다.
     * - **기록한다** — [IssueHistoryRecorder.record] 로 `issue_change_item` 상태 변경 1건.
     *   엔진을 건너뛴다고 감사 추적까지 끊지 않는다.
     * - **하지 않는다** — 후처리(`plan.emitEvents`). 엔진을 안 타서 `plan` 자체가 없다.
     *   Jira 도 이관에서 "_Perform actions_ rules won't automatically do anything" 이라 결과가 같다(J8).
     *
     * ## 왜 `cause` 를 실었나 — 끄는 쪽이 더 위험했다 (ceo BLOCKER C-B1)
     * [IssueTransitioned] 소비자에 **사외 아웃바운드 웹훅**(`search-export-import` 의
     * `WebhookDispatchWorker`)이 있다. 이관 N건은 웹훅 N건이고 **나간 웹훅은 되돌릴 수 없다.**
     * 그렇다고 이 가지에서 발행을 끄면 같은 이벤트를 구독하는 검색 색인·보드·자동화가 옮겨간
     * 이슈를 모른 채 남아 **DB 는 맞는데 화면이 틀린** 상태가 된다(G2). 둘 다 나쁘므로 발행은
     * 유지하고 **표시만 실어** 거를지 말지를 소비자가 정하게 했다.
     *
     * ★**이 표시를 소비자가 실제로 거르는지는 이 PR 이 검증하지 못한다.** 현재
     * `WebhookDispatchWorker` 는 `issueKey`/`projectKey`/`fromState`/`toState` 만 허용목록으로
     * 뽑아 보내므로 `cause` 를 보지도, 그것으로 거르지도 않는다. 웹훅·알림 소비자 결선과 그 검증은
     * **PR 7b** 다 — 표시를 두는 것과 실제로 걸러지는 것은 다르다.
     *
     * ## 아직 열려 있는 창 (E15 · 부채 143)
     * 큐잉 → 실행 사이에 그 상태로 들어온 이슈(E12)는 워커가 실행 시점에 다시 긁어 담으므로 이관된다(F15).
     * 그러나 **이관 완료 이후** 워크플로우 정의 교체 전에 또 들어오면 이 경로는 그것을 모른다.
     * 발행 경로가 「이관 → 재확인 → 교체」 루프를 돌아야 닫히고 그것은 project-workflow 소관이라 PR 7b 다.
     *
     * @param actor 이관을 수행한 행위자. 이벤트와 이력에 그대로 실린다.
     * @param issueKey 이관 대상 이슈 키.
     * @param mappings 출발 상태 키 → 대상 상태 키.
     * @throws com.bts.issue.project.archive.ProjectArchivedException 이슈의 프로젝트가 아카이브 상태일 때.
     * @throws StateNotInMigrationMappingException 현재 상태가 [mappings] 에 없을 때.
     * @throws IssueNotFoundException 비관락 획득 시점에 이슈가 없거나 소프트 삭제됐을 때.
     * @throws IssueVersionConflictException OCC 충돌로 0행이 갱신됐을 때.
     *
     * `ThrowsCount` 억제 이유. 세 예외는 각각 **다른 실패 코드**로 번역된다 —
     * `PROJECT_ARCHIVED` · `STATE_NOT_IN_MAPPING` · `VERSION_CONFLICT`. 하나로 합치면
     * [BulkItemExecutor] 가 구분할 수단을 잃고 F21 이 되살리려던 `UNKNOWN` 뭉갬으로 되돌아간다.
     * [IssueApplicationService.transitionIssue] 가 같은 사유로 같은 국소 억제를 쓴다 —
     * 전역 detekt 임계값이나 `detekt-baseline.xml` 은 건드리지 않는다.
     */
    @Suppress("ThrowsCount")
    private fun migrateStatus(
        actor: ActorId,
        issueKey: IssueKey,
        mappings: Map<String, String>,
    ) {
        projectArchiveGuard.checkByIssue(issueKey)

        // 비관락 + 락 뒤 재조회. 이 한 번의 읽기가 상태·버전·해결책과 이력 before 스냅샷의 단일 출처다.
        // 이력이 도메인 Issue 를 받는 것(projectId 가 REST DTO 에 없다)도 같은 읽기로 함께 해결된다.
        val before = issueRepository.findByKeyForUpdate(issueKey) ?: throw IssueNotFoundException(issueKey)
        val target =
            mappings[before.currentStateKey]
                ?: throw StateNotInMigrationMappingException(issueKey, before.currentStateKey)
        val updatedRows =
            issueRepository.applyTransition(
                key = issueKey,
                toState = target,
                expectedVersion = before.version,
                resolutionId = before.resolutionId,
            )
        if (updatedRows == 0) {
            throw IssueVersionConflictException(issueKey, before.version)
        }
        eventPublisher.publish(
            IssueTransitioned(
                issueKey = issueKey,
                fromState = before.currentStateKey,
                toState = target,
                actorId = actor,
                occurredAt = Instant.now(clock),
                cause = CAUSE_STATUS_MIGRATION,
            ),
        )
        historyRecorder.record(
            before = before,
            after = before.copy(currentStateKey = target),
            actor = actor,
            projectId = before.projectId,
        )
    }

    companion object {
        /** [IssueTransitioned.cause] 에 싣는 이관 표시. 일반 전환은 이 값을 싣지 않는다(=`null`). */
        const val CAUSE_STATUS_MIGRATION = "STATUS_MIGRATION"
    }
}
