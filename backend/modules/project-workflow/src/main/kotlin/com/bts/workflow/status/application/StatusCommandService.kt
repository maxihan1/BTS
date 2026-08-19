// 전역 상태 카탈로그 쓰기 유스케이스 — 키 불변·이름 유일·참조 가드·역조회 캐시 무효화

package com.bts.workflow.status.application

import com.bts.shared.permission.WorkflowDefinitionPermission
import com.bts.shared.permission.WorkflowDefinitionPermissionResolver
import com.bts.workflow.cache.WorkflowCache
import com.bts.workflow.status.application.command.CreateStatusCommand
import com.bts.workflow.status.application.command.UpdateStatusCommand
import com.bts.workflow.status.domain.exception.StatusInUseException
import com.bts.workflow.status.domain.exception.StatusKeyConflictException
import com.bts.workflow.status.domain.exception.StatusNameConflictException
import com.bts.workflow.status.domain.exception.StatusNotFoundException
import com.bts.workflow.status.domain.exception.StatusProtectedException
import com.bts.workflow.status.repository.StatusRepository
import com.bts.workflow.status.repository.StatusRow
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 전역 상태 카탈로그의 쓰기 유스케이스.
 *
 * ### 캐시 무효화가 워크플로우 쪽보다 까다롭다
 * 상태는 **여러 워크플로우가 공유**한다. 이름이나 카테고리를 고치면 그 상태를 편성한 워크플로우
 * 전부의 캐시가 낡는다. `WorkflowCache` 에는 상태→워크플로우 역인덱스가 없으므로
 * DB 로 역조회해 key 목록을 얻고 하나씩 무효화한다.
 * 역인덱스를 캐시에 새로 심지 않는 이유는 [StatusRepository.findWorkflowKeysUsing] KDoc 에 있다.
 *
 * ### 삭제가 막히는 두 가지 원인을 나눠서 알린다
 * 「워크플로우가 쓰고 있다」는 **떼면 지울 수 있고**, 「시스템 예약이다」는 **할 수 있는 일이 없다**.
 * 같은 409 라도 사용자가 다음에 할 행동이 다르므로 예외를 나눈다.
 */
@Service
class StatusCommandService(
    private val statusRepository: StatusRepository,
    private val workflowCache: WorkflowCache,
    private val permissionResolver: WorkflowDefinitionPermissionResolver,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 살아 있는 상태 전체. 읽기는 권한을 묻지 않는다 — 이슈 화면이 상태 목록을 그린다. */
    @Transactional(readOnly = true)
    fun list(): List<StatusRow> = statusRepository.findAllLive()

    /**
     * 상태를 만든다.
     *
     * @throws StatusKeyConflictException 살아 있는 상태가 이미 그 key 를 쓸 때 (409)
     * @throws StatusNameConflictException 이름이 대소문자 무시 기준으로 겹칠 때 (409)
     */
    @Transactional
    fun create(
        actorId: UUID,
        command: CreateStatusCommand,
    ): UUID {
        permissionResolver.requirePermission(actorId, WorkflowDefinitionPermission.CREATE)
        if (statusRepository.existsLiveByKey(command.key)) {
            throw StatusKeyConflictException(command.key)
        }
        if (statusRepository.existsLiveByLowerName(command.name)) {
            throw StatusNameConflictException(command.name)
        }

        val id = statusRepository.insert(command.key, command.name, command.description, command.category)
        log.info("전역 상태 생성 key={} category={}", command.key, command.category)
        return id
    }

    /**
     * 이름·설명·카테고리를 고친다. `key` 는 커맨드에 자리가 없다.
     *
     * @throws StatusNotFoundException 살아 있는 대상이 없을 때 (404)
     * @throws StatusNameConflictException 다른 상태와 이름이 겹칠 때 (409)
     */
    @Transactional
    fun update(
        actorId: UUID,
        id: UUID,
        command: UpdateStatusCommand,
    ) {
        permissionResolver.requirePermission(actorId, WorkflowDefinitionPermission.UPDATE)
        val status = statusRepository.findLiveById(id) ?: throw StatusNotFoundException(id)
        if (statusRepository.existsLiveByLowerName(command.name, excludeId = id)) {
            throw StatusNameConflictException(command.name)
        }

        statusRepository.update(id, command.name, command.description, command.category)
        invalidateWorkflowsUsing(id)
        log.info("전역 상태 수정 key={}", status.key)
    }

    /**
     * 소프트 삭제한다.
     *
     * @throws StatusNotFoundException 살아 있는 대상이 없을 때 (404)
     * @throws StatusProtectedException 시스템 예약 상태일 때 (409)
     * @throws StatusInUseException 어느 워크플로우가 편성 중일 때 (409)
     */
    @Transactional
    fun delete(
        actorId: UUID,
        id: UUID,
    ) {
        permissionResolver.requirePermission(actorId, WorkflowDefinitionPermission.DELETE)
        val status = statusRepository.findLiveById(id) ?: throw StatusNotFoundException(id)
        requireDeletable(status)

        statusRepository.softDelete(id)
        log.info("전역 상태 소프트 삭제 key={}", status.key)
    }

    /**
     * 지울 수 있는 상태인지 확인한다. 막히는 원인 둘을 **다른 예외로** 알린다.
     *
     * 「워크플로우가 쓰고 있다」는 떼면 지울 수 있고, 「시스템 예약이다」는 할 수 있는 일이 없다.
     * 문구를 같게 두면 사용자가 워크플로우를 뒤지며 시간을 버린다.
     */
    private fun requireDeletable(status: StatusRow) {
        if (status.isSystem) {
            throw StatusProtectedException(status.key)
        }
        val usage = statusRepository.countWorkflowUsage(status.id)
        if (usage > 0) {
            throw StatusInUseException(status.key, usage)
        }
    }

    /**
     * 이 상태를 편성한 워크플로우 전부의 캐시를 무효화한다.
     *
     * 무효화 대상을 **DB 역조회로** 얻는다. 캐시에 역인덱스를 심으면 편성이 바뀔 때마다
     * 갈라지는 두 번째 리스트가 된다.
     */
    private fun invalidateWorkflowsUsing(statusId: UUID) {
        val keys = statusRepository.findWorkflowKeysUsing(statusId)
        keys.forEach(workflowCache::invalidate)
        if (keys.isNotEmpty()) {
            log.debug("상태 변경으로 워크플로우 캐시 {}건 무효화: {}", keys.size, keys)
        }
    }
}
