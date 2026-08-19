// 워크플로우 쓰기 유스케이스 — 권한 게이트 · 참조 가드 · 캐시 무효화를 한 곳에 모은다

package com.bts.workflow.application

import com.bts.shared.permission.WorkflowDefinitionPermission
import com.bts.shared.permission.WorkflowDefinitionPermissionResolver
import com.bts.workflow.application.command.CreateWorkflowCommand
import com.bts.workflow.application.command.UpdateWorkflowCommand
import com.bts.workflow.cache.WorkflowCache
import com.bts.workflow.domain.exception.WorkflowInUseException
import com.bts.workflow.domain.exception.WorkflowKeyConflictException
import com.bts.workflow.domain.exception.WorkflowLockedException
import com.bts.workflow.domain.exception.WorkflowNotFoundException
import com.bts.workflow.repository.WorkflowRepository
import com.bts.workflow.repository.WorkflowWriteRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 워크플로우 정의의 쓰기 유스케이스.
 *
 * ### 세 가지를 빠뜨리지 않는 것이 이 클래스의 존재 이유다
 * 1. **권한** — 모든 진입점이 [WorkflowDefinitionPermissionResolver] 를 먼저 부른다
 * 2. **참조 가드** — 사용 중인 워크플로우를 지우지 않는다
 * 3. **캐시 무효화** — 쓰기 끝에 [WorkflowCache.invalidate] 를 부른다.
 *    빠뜨리면 편집이 런타임 전환 계산에 반영되지 않고, 그 실패는 **조용하다**
 *
 * ### 검사 순서 — 권한이 먼저, 존재 확인이 나중
 * `VersionApplicationService.kt:136-138` 관례를 따른다. 존재 probe 를 막기 위한 의도된 정책이며,
 * 결과적으로 권한 없는 행위자는 대상이 있든 없든 거부를 받는다.
 * 비-prod 는 `AlwaysAllow` stub 이라 이 순서의 효과가 로컬에서 보이지 않는다.
 */
@Service
class WorkflowCommandService(
    private val writeRepository: WorkflowWriteRepository,
    private val workflowRepository: WorkflowRepository,
    private val workflowCache: WorkflowCache,
    private val permissionResolver: WorkflowDefinitionPermissionResolver,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 새 워크플로우를 만들고 상태를 편성한다.
     *
     * @return 생성된 워크플로우의 id
     * @throws WorkflowKeyConflictException 살아 있는 워크플로우가 이미 그 key 를 쓸 때 (409)
     * @throws IllegalArgumentException 상태 씨앗이 비었을 때 (400) — `Workflow.of()` invariant
     */
    @Transactional
    fun create(
        actorId: UUID,
        command: CreateWorkflowCommand,
    ): UUID {
        permissionResolver.requirePermission(actorId, WorkflowDefinitionPermission.CREATE)
        require(command.statuses.isNotEmpty()) {
            "워크플로우에는 상태가 하나 이상 있어야 한다: '${command.key}'"
        }
        if (writeRepository.existsByKey(command.key)) {
            throw WorkflowKeyConflictException(command.key)
        }

        val workflowId = writeRepository.insertWorkflow(command.key, command.name, command.description)
        writeRepository.attachStatuses(workflowId, command.statuses)
        workflowCache.invalidate(command.key)
        log.info("워크플로우 생성 key={} statuses={}", command.key, command.statuses.size)
        return workflowId
    }

    /**
     * 이름·설명을 고친다. `key` 는 바꾸지 않는다.
     *
     * @throws WorkflowNotFoundException 살아 있는 대상이 없을 때 (404)
     * @throws WorkflowLockedException 편집이 잠겼을 때 (409)
     */
    @Transactional
    fun update(
        actorId: UUID,
        key: String,
        command: UpdateWorkflowCommand,
    ) {
        permissionResolver.requirePermission(actorId, WorkflowDefinitionPermission.UPDATE)
        val workflowId = requireLiveWorkflow(key)
        if (writeRepository.isLocked(workflowId)) {
            throw WorkflowLockedException(key)
        }

        writeRepository.updateNameAndDescription(workflowId, command.name, command.description)
        workflowCache.invalidate(key)
        log.info("워크플로우 수정 key={}", key)
    }

    /**
     * 소프트 삭제한다. 스킴 매핑이 참조 중이면 거부한다.
     *
     * @throws WorkflowNotFoundException 살아 있는 대상이 없을 때 (404)
     * @throws WorkflowInUseException 스킴 매핑이 참조 중일 때 (409)
     * @throws WorkflowLockedException 편집이 잠겼을 때 (409)
     */
    @Transactional
    fun delete(
        actorId: UUID,
        key: String,
    ) {
        permissionResolver.requirePermission(actorId, WorkflowDefinitionPermission.DELETE)
        val workflowId = requireLiveWorkflow(key)
        if (writeRepository.isLocked(workflowId)) {
            throw WorkflowLockedException(key)
        }
        val references = writeRepository.countSchemeReferences(workflowId)
        if (references > 0) {
            throw WorkflowInUseException(key, references)
        }

        writeRepository.softDelete(workflowId)
        workflowCache.invalidate(key)
        log.info("워크플로우 소프트 삭제 key={}", key)
    }

    /**
     * 워크플로우를 복제한다. 상태 편성과 전환을 함께 복사하고 `origin='CUSTOM'` 으로 만든다.
     *
     * @return 복제본의 id
     * @throws WorkflowNotFoundException 원본이 없을 때 (404)
     * @throws WorkflowKeyConflictException 새 key 가 이미 쓰일 때 (409)
     */
    @Transactional
    fun duplicate(
        actorId: UUID,
        sourceKey: String,
        newKey: String,
        newName: String,
    ): UUID {
        permissionResolver.requirePermission(actorId, WorkflowDefinitionPermission.CREATE)
        val sourceId = requireLiveWorkflow(sourceKey)
        if (writeRepository.existsByKey(newKey)) {
            throw WorkflowKeyConflictException(newKey)
        }

        val source = workflowRepository.findByKey(sourceKey) ?: throw WorkflowNotFoundException(sourceKey)
        val targetId = writeRepository.insertWorkflow(newKey, newName, source.description)
        writeRepository.copyStatusComposition(sourceId, targetId)
        writeRepository.copyLegacyStatesAndTransitions(sourceId, targetId)
        workflowCache.invalidate(newKey)
        log.info("워크플로우 복제 source={} target={}", sourceKey, newKey)
        return targetId
    }

    /** 살아 있는 워크플로우의 id 를 준다. 없으면 404 예외. */
    private fun requireLiveWorkflow(key: String): UUID {
        // 블록 본문으로 둔다 — 식 본문이면 ktlint 가 「한 줄로 합쳐라」를, detekt 가 「120자를 넘지 마라」를
        // 동시에 요구해 교착이 된다. 두 도구의 기준이 다른 지점이다.
        return writeRepository.findLiveIdByKey(key) ?: throw WorkflowNotFoundException(key)
    }
}
