// 워크플로우↔상태 편성 유스케이스 — 추가·제거·순서변경과 그 가드

package com.bts.workflow.application

import com.bts.shared.permission.WorkflowDefinitionPermission
import com.bts.shared.permission.WorkflowDefinitionPermissionResolver
import com.bts.workflow.application.port.IssueStatusUsagePort
import com.bts.workflow.cache.WorkflowCache
import com.bts.workflow.domain.exception.WorkflowNotFoundException
import com.bts.workflow.domain.exception.WorkflowStatusCompositionException
import com.bts.workflow.domain.exception.WorkflowStatusInUseException
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
 * ### 세 가지 가드가 이 클래스의 존재 이유다
 * 1. **마지막 상태는 못 뺀다** — 상태 0개 워크플로우는 `Workflow.of()` invariant 상 조회가 죽는다.
 *    화면에서 되살릴 방법이 없으므로 애초에 만들지 않는다
 * 2. **이슈가 쓰는 상태는 못 뺀다** — 빼면 이슈가 「워크플로우에 없는 상태」에 남는다
 * 3. **순서 요청은 전체 집합이어야 한다** — 부분 목록이면 빠진 상태의 순서가 암묵적으로 정해진다
 *
 * 세 가지 모두 **조용한 데이터 손상**을 막는 것이고, 그래서 요청을 거부하는 쪽을 택한다.
 */
@Service
class WorkflowStatusCompositionService(
    private val compositionRepository: WorkflowStatusCompositionRepository,
    private val workflowWriteRepository: WorkflowWriteRepository,
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

        requireRemovable(workflowKey, composition.size, target.statusKey)

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

    /** 뺄 수 있는 상태인지 확인한다. 막히는 원인 둘을 다른 예외로 알린다. */
    private fun requireRemovable(
        workflowKey: String,
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
