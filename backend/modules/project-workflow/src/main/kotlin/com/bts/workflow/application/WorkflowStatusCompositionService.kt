// 워크플로우↔상태 편성 유스케이스 — 추가·제거·순서변경과 그 가드

package com.bts.workflow.application

import com.bts.shared.permission.WorkflowDefinitionPermission
import com.bts.shared.permission.WorkflowDefinitionPermissionResolver
import com.bts.workflow.application.port.IssueStatusUsagePort
import com.bts.workflow.cache.WorkflowCache
import com.bts.workflow.domain.exception.WorkflowNotFoundException
import com.bts.workflow.domain.exception.WorkflowStatusCompositionException
import com.bts.workflow.domain.exception.WorkflowStatusInUseException
import com.bts.workflow.repository.WorkflowRepository
import com.bts.workflow.repository.WorkflowStatusCompositionRepository
import com.bts.workflow.repository.WorkflowWriteRepository
import com.bts.workflow.status.domain.exception.StatusNotFoundException
import com.bts.workflow.status.repository.StatusRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 워크플로우에 상태를 넣고 빼고 순서를 바꾸는 유스케이스.
 *
 * ### 네 가지 가드가 이 클래스의 존재 이유다
 * 1. **마지막 상태는 못 뺀다** — 상태 0개 워크플로우는 `Workflow.of()` invariant 상 조회가 죽는다.
 *    화면에서 되살릴 방법이 없으므로 애초에 만들지 않는다
 * 2. **이슈가 쓰는 상태는 못 뺀다** — 빼면 이슈가 「워크플로우에 없는 상태」에 남는다
 * 3. **전환이 가리키는 상태는 못 뺀다** — 빼면 그 전환이 `ON DELETE CASCADE` 로 하드 삭제된다
 * 4. **순서 요청은 전체 집합이어야 한다** — 부분 목록이면 빠진 상태의 순서가 암묵적으로 정해진다
 *
 * 네 가지 모두 **조용한 데이터 손상**을 막는 것이고, 그래서 요청을 거부하는 쪽을 택한다.
 */
@Service
// Suppress 근거. `LongParameterList` — DI 생성자다. 가드 넷이 각각 다른 사실(편성·이슈·전환·권한)을
// 물어야 해서 협력자가 7개다. 묶어 내리면 어떤 가드가 무엇을 보는지가 시그니처에서 사라진다.
@Suppress("LongParameterList")
class WorkflowStatusCompositionService(
    private val compositionRepository: WorkflowStatusCompositionRepository,
    private val workflowWriteRepository: WorkflowWriteRepository,
    private val workflowRepository: WorkflowRepository,
    private val statusRepository: StatusRepository,
    private val issueStatusUsagePort: IssueStatusUsagePort,
    private val workflowCache: WorkflowCache,
    private val permissionResolver: WorkflowDefinitionPermissionResolver,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 카탈로그의 상태를 이 워크플로우에 편성한다. 이미 있으면 표시 순서만 갱신한다.
     *
     * @throws WorkflowNotFoundException 워크플로우가 없을 때 (404)
     * @throws StatusNotFoundException 카탈로그에 그 상태가 없을 때 (404)
     */
    @Transactional
    fun addStatus(
        actorId: UUID,
        workflowKey: String,
        statusId: UUID,
        displayOrder: Int,
    ) {
        permissionResolver.requirePermission(actorId, WorkflowDefinitionPermission.UPDATE)
        val workflowId = requireLiveWorkflow(workflowKey)
        statusRepository.findLiveById(statusId) ?: throw StatusNotFoundException(statusId)

        compositionRepository.attach(workflowId, statusId, displayOrder)
        workflowCache.invalidate(workflowKey)
        log.info("상태 편성 추가 workflow={} status={}", workflowKey, statusId)
    }

    /**
     * 편성을 뗀다. 카탈로그의 상태 자체는 남는다.
     *
     * @throws WorkflowStatusCompositionException 마지막 상태를 빼려 할 때 (400)
     * @throws WorkflowStatusInUseException 이슈가 그 상태를 쓰고 있을 때 (409)
     * @throws WorkflowStatusReferencedByTransitionException 전환이 그 상태를 가리킬 때 (409)
     */
    @Transactional
    fun removeStatus(
        actorId: UUID,
        workflowKey: String,
        statusId: UUID,
    ) {
        permissionResolver.requirePermission(actorId, WorkflowDefinitionPermission.UPDATE)
        val workflowId = requireLiveWorkflow(workflowKey)
        val composition = compositionRepository.findComposition(workflowId)
        val target =
            composition.firstOrNull { it.statusId == statusId }
                ?: throw WorkflowStatusCompositionException(workflowKey, "그 워크플로우에 편성되지 않은 상태다")

        requireRemovable(workflowKey, workflowId, composition.size, target.statusKey)

        compositionRepository.detach(workflowId, statusId)
        workflowCache.invalidate(workflowKey)
        log.info("상태 편성 제거 workflow={} status={}", workflowKey, target.statusKey)
    }

    /**
     * 표시 순서를 통째로 다시 정한다. 요청은 그 워크플로우의 상태 **전부**를 정확히 담아야 한다.
     *
     * @throws WorkflowStatusCompositionException 집합이 어긋날 때 (400)
     */
    @Transactional
    fun reorder(
        actorId: UUID,
        workflowKey: String,
        orderedStatusIds: List<UUID>,
    ) {
        permissionResolver.requirePermission(actorId, WorkflowDefinitionPermission.UPDATE)
        val workflowId = requireLiveWorkflow(workflowKey)
        val current = compositionRepository.findComposition(workflowId).map { it.statusId }.toSet()

        requireSameSet(workflowKey, current, orderedStatusIds)

        orderedStatusIds.forEachIndexed { index, statusId ->
            compositionRepository.updateDisplayOrder(workflowId, statusId, index)
        }
        workflowCache.invalidate(workflowKey)
        log.info("상태 순서 변경 workflow={} count={}", workflowKey, orderedStatusIds.size)
    }

    /** 뺄 수 있는 상태인지 확인한다. 막히는 원인 셋을 각각 다른 예외로 알린다. */
    private fun requireRemovable(
        workflowKey: String,
        workflowId: UUID,
        compositionSize: Int,
        statusKey: String,
    ) {
        if (compositionSize <= 1) {
            throw WorkflowStatusCompositionException(
                workflowKey,
                "마지막 남은 상태는 뺄 수 없다 — 상태가 0개면 워크플로우를 조회할 수 없게 된다",
            )
        }
        val issueCount = issueStatusUsagePort.countIssuesInStatus(statusKey)
        if (issueCount > 0) {
            throw WorkflowStatusInUseException(workflowKey, statusKey, issueCount)
        }
        requireNoReferencingTransition(workflowKey, workflowId, statusKey)
    }

    /**
     * 그 편성을 가리키는 전환이 있으면 막는다.
     *
     * ### 왜 서비스 계층이 막는가
     * `workflow_transitions.from_status_id`·`to_status_id` 는 `ON DELETE CASCADE`(V207 ③)다.
     * 편성을 떼는 순간 그 전환이 **하드 삭제**되고, 매달린 validator·post-action 까지 FK CASCADE 로
     * 함께 사라진다 — 소프트 삭제도 감사 로그도 없어 되살릴 방법이 없다(`DATA.md §1.2`).
     * 사라진 것이 최초 전환이면 이슈 생성 진입 상태가 표시 순서 폴백으로 **조용히** 바뀌기까지 한다.
     *
     * FK 를 `RESTRICT` 로 바꾸는 것이 정공법이나 그것은 마이그레이션이라 `workflow_states` 를
     * 떨어뜨리는 3단 분할 2단계의 몫이다. 그때까지 이 계층이 막는다.
     *
     * ### ★V207 의 「구 컬럼과 같은 규칙」을 그대로 믿지 마라
     * `V207:71-73` 이 이 CASCADE 를 「구 컬럼(V200)과 같은 규칙」이라 적는다. **FK 절에 한하면 참**이다 —
     * 양쪽 다 `ON DELETE CASCADE`. 그러나 **도달 가능성은 같지 않다.** 구 컬럼 CASCADE 는
     * `workflow_states` 를 지우는 코드가 저장소에 0건이라 발화할 길이 없었고, 이 PR 이 편성 삭제
     * 엔드포인트를 통해 **처음으로 도달 가능하게** 만들었다. 그 주석은 이미 적용된 마이그레이션이라
     * 체크섬 때문에 못 고친다 — 사정은 `TODOS.md` 부채 `91` 에 있다. V207 을 읽고 여기 온 사람이
     * 「종전과 같으니 새 위험 없음」으로 판단하지 않도록 이 자리에 적어 둔다.
     */
    private fun requireNoReferencingTransition(
        workflowKey: String,
        workflowId: UUID,
        statusKey: String,
    ) {
        val workflow = workflowRepository.findById(workflowId) ?: throw WorkflowNotFoundException(workflowKey)
        val blocking = workflow.transitions.filter { it.fromStateKey == statusKey || it.toStateKey == statusKey }
        if (blocking.isNotEmpty()) {
            throw WorkflowStatusReferencedByTransitionException(workflowKey, statusKey, blocking.map { it.name })
        }
    }

    /**
     * 순서 요청이 현재 편성과 **정확히 같은 집합**인지 확인한다.
     *
     * 중복도 막는다 — 같은 id 를 두 번 넣으면 크기는 맞는데 어떤 상태의 순서가 사라진다.
     */
    private fun requireSameSet(
        workflowKey: String,
        current: Set<UUID>,
        requested: List<UUID>,
    ) {
        if (requested.size != requested.toSet().size) {
            throw WorkflowStatusCompositionException(workflowKey, "순서 요청에 같은 상태가 두 번 들어 있다")
        }
        if (requested.toSet() != current) {
            val missing = current - requested.toSet()
            val unknown = requested.toSet() - current
            throw WorkflowStatusCompositionException(
                workflowKey,
                "순서 요청은 그 워크플로우의 상태 전부를 담아야 한다 (빠짐 ${missing.size}건 · 모르는 상태 ${unknown.size}건)",
            )
        }
    }

    private fun requireLiveWorkflow(key: String): UUID {
        return workflowWriteRepository.findLiveIdByKey(key) ?: throw WorkflowNotFoundException(key)
    }
}

/**
 * 전환이 가리키는 상태를 워크플로우에서 빼려 할 때 던진다. → 409
 *
 * [WorkflowStatusInUseException] 과 같은 모양이다 — 「무엇이 그 상태를 붙잡고 있는가」를 담고,
 * 사용자가 그것을 먼저 치우면 다시 시도할 수 있다. 다른 점은 붙잡는 주체가 이슈가 아니라
 * **전환 정의**라는 것뿐이라 개수 대신 [transitionNames] 를 싣는다. 이름이 없으면 사용자는
 * 편집기에서 어떤 선을 지워야 할지 알 수 없다.
 *
 * ### 왜 `WorkflowExceptions.kt` 가 아니라 여기 있는가
 * 던지는 곳 옆에 두는 [com.bts.workflow.cache.WorkflowCacheLockTimeoutException] 과 같은 배치다.
 *
 * @property workflowKey 편성을 떼려 한 워크플로우 키.
 * @property statusKey 떼려 한 상태 키.
 * @property transitionNames 그 상태를 가리켜 삭제를 막은 전환들의 표시 이름.
 */
class WorkflowStatusReferencedByTransitionException(
    val workflowKey: String,
    val statusKey: String,
    val transitionNames: List<String>,
) : RuntimeException(
        "Status '$statusKey' in workflow '$workflowKey' is referenced by " +
            "${transitionNames.size} transition(s): ${transitionNames.joinToString(", ")}",
    )
