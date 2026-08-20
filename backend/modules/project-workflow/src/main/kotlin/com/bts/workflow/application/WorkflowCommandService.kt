// 워크플로우 쓰기 유스케이스 — 권한 게이트 · 참조 가드 · 캐시 무효화를 한 곳에 모은다

package com.bts.workflow.application

import com.bts.shared.permission.WorkflowDefinitionPermission
import com.bts.shared.permission.WorkflowDefinitionPermissionResolver
import com.bts.workflow.application.command.CreateWorkflowCommand
import com.bts.workflow.application.command.TransitionDefinitionCommand
import com.bts.workflow.application.command.UpdateWorkflowCommand
import com.bts.workflow.cache.WorkflowCache
import com.bts.workflow.domain.TransitionKind
import com.bts.workflow.domain.WorkflowTransition
import com.bts.workflow.domain.exception.WorkflowInUseException
import com.bts.workflow.domain.exception.WorkflowInvalidRequestException
import com.bts.workflow.domain.exception.WorkflowKeyConflictException
import com.bts.workflow.domain.exception.WorkflowLockedException
import com.bts.workflow.domain.exception.WorkflowNotFoundException
import com.bts.workflow.repository.WorkflowRepository
import com.bts.workflow.repository.WorkflowWriteRepository
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
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
 *
 * ### `TooManyFunctions` 억제 사유
 * 전환 정의 CRUD 3종과 그 검증 헬퍼가 붙어 함수 수가 detekt 한도(11)를 넘었다. 전환 쓰기를
 * `TransitionCommandService` 로 떼는 것이 정본 방향이고 **다음 PR 의 몫**이다 — 이 PR 의 파일
 * 범위(Task 8)에 새 서비스 파일이 없다. 전역 임계값은 건드리지 않는다.
 */
@Suppress("TooManyFunctions")
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
     * @throws WorkflowInvalidRequestException 상태 씨앗이 비었을 때 (400) — `Workflow.of()` invariant
     */
    @Transactional
    fun create(
        actorId: UUID,
        command: CreateWorkflowCommand,
    ): UUID {
        permissionResolver.requirePermission(actorId, WorkflowDefinitionPermission.CREATE)
        if (command.statuses.isEmpty()) {
            throw WorkflowInvalidRequestException(
                command.key,
                "워크플로우에는 상태가 하나 이상 있어야 한다 — 상태가 0개면 조회 자체가 불가능하다",
            )
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

    // ── 전환 정의 CRUD (FR-WF-05 F6) ──────────────────────────────────────────

    /**
     * 전환 정의를 만든다.
     *
     * @return 만들어진 전환. `id` 는 DB 가 정한 값이다
     * @throws WorkflowNotFoundException 살아 있는 워크플로우가 없을 때 (404)
     * @throws WorkflowLockedException 편집이 잠겼을 때 (409)
     * @throws WorkflowInvalidRequestException 종류와 출발지의 조합이 어긋나거나 상태가 편성돼 있지 않을 때 (400)
     * @throws TransitionConflictException 최초 전환이 이미 있을 때 (409)
     */
    @Transactional
    fun createTransition(
        actorId: UUID,
        workflowKey: String,
        command: TransitionDefinitionCommand,
    ): WorkflowTransition {
        permissionResolver.requirePermission(actorId, WorkflowDefinitionPermission.UPDATE)
        val workflowId = requireLiveWorkflow(workflowKey)
        if (writeRepository.isLocked(workflowId)) {
            throw WorkflowLockedException(workflowKey)
        }

        val shape = resolveShape(workflowKey, workflowId, command, excludingId = null)
        val transitionId =
            writeRepository.insertTransition(
                workflowId = workflowId,
                kind = shape.kind.name,
                name = command.name,
                fromStatusId = shape.fromStatusId,
                toStatusId = shape.toStatusId,
            )
        workflowCache.invalidate(workflowKey)
        log.info("전환 생성 workflow={} kind={} id={}", workflowKey, shape.kind, transitionId)
        return command.toTransition(transitionId, shape.kind)
    }

    /**
     * 전환 정의를 통째로 갈아 끼운다. `PUT` 이므로 부분 수정이 아니다.
     *
     * @return 수정된 전환
     * @throws WorkflowNotFoundException 워크플로우가 없거나 그 워크플로우의 전환이 아닐 때 (404 · spec E8·E9)
     */
    @Transactional
    fun updateTransition(
        actorId: UUID,
        workflowKey: String,
        transitionId: UUID,
        command: TransitionDefinitionCommand,
    ): WorkflowTransition {
        permissionResolver.requirePermission(actorId, WorkflowDefinitionPermission.UPDATE)
        val workflowId = requireLiveWorkflow(workflowKey)
        if (writeRepository.isLocked(workflowId)) {
            throw WorkflowLockedException(workflowKey)
        }
        requireOwnedTransition(workflowKey, workflowId, transitionId)

        val shape = resolveShape(workflowKey, workflowId, command, excludingId = transitionId)
        writeRepository.updateTransition(
            transitionId = transitionId,
            kind = shape.kind.name,
            name = command.name,
            fromStatusId = shape.fromStatusId,
            toStatusId = shape.toStatusId,
        )
        workflowCache.invalidate(workflowKey)
        log.info("전환 수정 workflow={} id={}", workflowKey, transitionId)
        return command.toTransition(transitionId, shape.kind)
    }

    /**
     * 전환 정의를 지운다. 매달린 validator·post-action 은 FK CASCADE 로 함께 사라진다 (spec E6).
     *
     * @throws WorkflowNotFoundException 워크플로우가 없거나 그 워크플로우의 전환이 아닐 때 (404)
     * @throws TransitionConflictException 최초 전환을 지우려 할 때 (409 · spec E5)
     */
    @Transactional
    fun deleteTransition(
        actorId: UUID,
        workflowKey: String,
        transitionId: UUID,
    ) {
        permissionResolver.requirePermission(actorId, WorkflowDefinitionPermission.UPDATE)
        val workflowId = requireLiveWorkflow(workflowKey)
        if (writeRepository.isLocked(workflowId)) {
            throw WorkflowLockedException(workflowKey)
        }
        val kind = requireOwnedTransition(workflowKey, workflowId, transitionId)
        if (kind == TransitionKind.INITIAL.name) {
            throw TransitionConflictException(
                "최초 전환은 삭제할 수 없습니다. 이슈가 처음 놓일 상태가 사라지면 이슈를 만들 수 없게 됩니다.",
            )
        }

        writeRepository.deleteTransition(transitionId)
        workflowCache.invalidate(workflowKey)
        log.info("전환 삭제 workflow={} id={}", workflowKey, transitionId)
    }

    /**
     * 요청을 저장 가능한 형태로 굳힌다 — 종류를 정하고, 규칙을 대조하고, 상태 키를 편성 id 로 바꾼다.
     *
     * ### 왜 `Workflow.of()` 에 맡기지 않는가
     * 그 invariant 5·6 이 같은 규칙을 검증하지만 전부 `IllegalArgumentException` 한 종류로 나온다.
     * 그러면 「종류와 출발지의 조합이 틀렸다」(400)와 「최초 전환이 이미 있다」(409)를 **응답 코드로
     * 가를 수 없다**. 정의 시점에 원인을 갈라 말하는 것이 이 함수의 일이고, `Workflow.of()` 는
     * 조회 경로의 마지막 방어선으로 남는다 (spec §정정 3 · ADR §D4).
     *
     * @param excludingId 최초 전환 중복 판정에서 뺄 전환. 수정할 때 자기 자신이다
     */
    private fun resolveShape(
        workflowKey: String,
        workflowId: UUID,
        command: TransitionDefinitionCommand,
        excludingId: UUID?,
    ): TransitionShape {
        val kind = parseKind(workflowKey, command.kind)
        requireOriginMatchesKind(workflowKey, kind, command.fromStatusKey)
        if (kind == TransitionKind.INITIAL && writeRepository.hasInitialTransition(workflowId, excludingId)) {
            throw TransitionConflictException(
                "최초 전환은 워크플로우당 하나입니다. 기존 최초 전환을 고치거나 지운 뒤 다시 시도해 주세요.",
            )
        }
        return TransitionShape(
            kind = kind,
            fromStatusId = command.fromStatusKey?.let { requireComposedStatus(workflowKey, workflowId, it) },
            toStatusId = requireComposedStatus(workflowKey, workflowId, command.toStatusKey),
        )
    }

    /** 전환 종류. 생략하면 `NORMAL` 이고, 모르는 값은 400 이다. */
    private fun parseKind(
        workflowKey: String,
        raw: String?,
    ): TransitionKind {
        if (raw == null) return TransitionKind.NORMAL
        return TransitionKind.entries.firstOrNull { it.name == raw }
            ?: throw WorkflowInvalidRequestException(
                workflowKey,
                "알 수 없는 전환 종류 '$raw' 입니다. NORMAL · GLOBAL · INITIAL 중 하나여야 합니다.",
            )
    }

    /** 종류와 출발지의 조합 (spec E3·E4). `Workflow.of()` invariant 5 와 같은 규칙이다. */
    private fun requireOriginMatchesKind(
        workflowKey: String,
        kind: TransitionKind,
        fromStatusKey: String?,
    ) {
        if (kind == TransitionKind.NORMAL && fromStatusKey == null) {
            throw WorkflowInvalidRequestException(
                workflowKey,
                "보통 전환에는 출발 상태가 필요합니다. 출발 상태를 고르거나 전환 종류를 바꿔 주세요.",
            )
        }
        if (kind != TransitionKind.NORMAL && fromStatusKey != null) {
            throw WorkflowInvalidRequestException(
                workflowKey,
                "$kind 전환에는 출발 상태를 둘 수 없습니다. 출발지가 없다는 것이 그 종류의 정의입니다.",
            )
        }
    }

    /** 상태 키를 그 워크플로우의 편성 id 로 바꾼다. 편성돼 있지 않으면 400. */
    private fun requireComposedStatus(
        workflowKey: String,
        workflowId: UUID,
        statusKey: String,
    ): UUID =
        writeRepository.findStatusCompositionId(workflowId, statusKey)
            ?: throw WorkflowInvalidRequestException(
                workflowKey,
                "'$statusKey' 는 이 워크플로우에 편성된 상태가 아닙니다. 상태를 먼저 편성해 주세요.",
            )

    /**
     * 그 워크플로우에 속한 전환인지 확인하고 종류를 준다.
     *
     * 남의 워크플로우 전환 id 는 **없는 것과 같이** 404 다 (spec E9) — 다른 응답을 주면 경로만 바꿔
     * 남의 워크플로우에 어떤 전환이 있는지 알아낼 수 있다.
     */
    private fun requireOwnedTransition(
        workflowKey: String,
        workflowId: UUID,
        transitionId: UUID,
    ): String {
        return writeRepository.findTransitionKind(workflowId, transitionId)
            ?: throw WorkflowNotFoundException("$workflowKey::transition::$transitionId")
    }

    /** 살아 있는 워크플로우의 id 를 준다. 없으면 404 예외. */
    private fun requireLiveWorkflow(key: String): UUID {
        // 블록 본문으로 둔다 — 식 본문이면 ktlint 가 「한 줄로 합쳐라」를, detekt 가 「120자를 넘지 마라」를
        // 동시에 요구해 교착이 된다. 두 도구의 기준이 다른 지점이다.
        return writeRepository.findLiveIdByKey(key) ?: throw WorkflowNotFoundException(key)
    }
}

/**
 * [resolveShape] 가 굳힌 전환의 저장 형태. 종류와 편성 id 두 벌을 함께 옮긴다.
 *
 * @property kind 확정된 전환 종류
 * @property fromStatusId 출발 상태의 `workflow_statuses.id`. `GLOBAL`·`INITIAL` 은 null
 * @property toStatusId 도착 상태의 `workflow_statuses.id`
 */
private data class TransitionShape(
    val kind: TransitionKind,
    val fromStatusId: UUID?,
    val toStatusId: UUID,
)

/** 저장이 끝난 커맨드를 도메인 전환으로 되돌린다. 응답에 실릴 값의 정본은 방금 저장한 그 값이다. */
private fun TransitionDefinitionCommand.toTransition(
    transitionId: UUID,
    kind: TransitionKind,
): WorkflowTransition =
    WorkflowTransition(
        fromStateKey = fromStatusKey,
        toStateKey = toStatusKey,
        name = name,
        id = transitionId,
        kind = kind,
    )

/**
 * 전환 정의가 「워크플로우당 최초 전환 1개」 규칙과 부딪힐 때 던진다. → 409
 *
 * ### 왜 도메인 예외 + advice 가 아니라 [ResponseStatusException] 인가
 * 이 BC 의 409 매핑은 `WorkflowExceptionHandler` 가 들고 있는데 그 advice 는 이미 detekt
 * `TooManyFunctions` 한도에 닿아 있고(`WorkflowStatusCompositionExceptionHandler` KDoc 이 그 이유로
 * 갈라져 나왔다), Task 8 의 파일 허용 범위에 예외 정의 파일과 advice 파일이 둘 다 없다.
 * 그래서 `agile-planning` 의 `BoardQuickFilterExceptions.kt` 선례를 따라 **application 계층 예외가
 * 스스로 상태 코드를 지고** 간다.
 *
 * ### 남는 차이 — 본문 형식
 * 이 예외는 표준 `{ "error": { "code", "message" } }` 가 아니라 Spring 의 `ProblemDetail` 로 나간다.
 * 전환 편집 화면(로드맵 PR 8)이 이 코드를 읽기 전에 도메인 예외 + advice 로 접는 것이 정본 방향이다.
 */
class TransitionConflictException(
    reason: String,
) : ResponseStatusException(HttpStatus.CONFLICT, reason)
