// 워크플로우 전이 post-action 관리 서비스 — 검증·전이해석·캐시무효화

package com.bts.workflow.postaction

import com.bts.workflow.cache.WorkflowCache
import com.bts.workflow.engine.WorkflowPostActionFactory
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 전이별 post-action CRUD 관리 서비스.
 *
 * URL path 의 transitionKey(`fromStateKey__toStateKey`) 를 전이 UUID 로 해석하고,
 * [WorkflowPostActionFactory] 로 검증 후 [PostActionRepository] 에 영속한다.
 * 변이 성공 후 [WorkflowCache.invalidate] 로 캐시를 무효화해 다음 전이 실행에 반영한다.
 *
 * ### 검증 순서 (create/update)
 * 1. transitionKey `__` 분리 → 형식 오류 시 [PostActionNotFoundException].
 * 2. [PostActionTransitionResolver.resolveTransitionId] → 미존재 시 [PostActionNotFoundException].
 * 3. CALL_WEBHOOK url http/https 스킴 추가 체크 → 실패 시 [PostActionValidationException].
 * 4. [WorkflowPostActionFactory.create] dry-run → [IllegalArgumentException] → [PostActionValidationException].
 *
 * @param repository post-action CRUD jOOQ 리포지토리.
 * @param factory post-action type 검증용 팩토리.
 * @param transitionResolver transitionKey → transition_id UUID 해석기.
 * @param workflowCache 변이 후 무효화할 워크플로우 캐시.
 */
@Service
class PostActionAdminService(
    private val repository: PostActionRepository,
    private val factory: WorkflowPostActionFactory,
    private val transitionResolver: PostActionTransitionResolver,
    private val workflowCache: WorkflowCache,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 전이에 속한 post-action 목록을 반환한다.
     *
     * @param workflowKey 워크플로우 식별 키.
     * @param transitionKey `fromStateKey__toStateKey` 형식의 전이 자연키.
     * @return [PostActionRow] 목록 (displayOrder ASC).
     * @throws PostActionNotFoundException 전이 미존재 또는 transitionKey 형식 오류 시.
     */
    @Transactional(readOnly = true)
    fun listForTransition(
        workflowKey: String,
        transitionKey: String,
    ): List<PostActionRow> {
        val transitionId = resolveOrThrow(workflowKey, transitionKey)
        return repository.findByTransitionId(transitionId)
    }

    /**
     * post-action 을 생성하고 캐시를 무효화한다.
     *
     * @param workflowKey 워크플로우 식별 키.
     * @param transitionKey `fromStateKey__toStateKey` 형식의 전이 자연키.
     * @param type post-action 타입 식별자.
     * @param config 타입별 설정 Map.
     * @param displayOrder UI 표시 순서.
     * @return 삽입된 [PostActionRow].
     * @throws PostActionNotFoundException 전이 미존재 또는 transitionKey 형식 오류 시.
     * @throws PostActionValidationException 미지원 type, 필수키 누락, 비-http url 시.
     */
    @Transactional
    fun create(
        workflowKey: String,
        transitionKey: String,
        type: String,
        config: Map<String, Any?>,
        displayOrder: Int,
    ): PostActionRow {
        val transitionId = resolveOrThrow(workflowKey, transitionKey)
        validateConfig(type, config)
        val row = repository.insert(transitionId, type, config, displayOrder)
        workflowCache.invalidate(workflowKey)
        log.info(
            "PostActionAdminService.create id={} workflowKey={} transitionKey={} type={}",
            row.id,
            workflowKey,
            transitionKey,
            type,
        )
        return row
    }

    /**
     * post-action 을 수정하고 캐시를 무효화한다.
     *
     * @param workflowKey 워크플로우 식별 키.
     * @param transitionKey `fromStateKey__toStateKey` 형식의 전이 자연키.
     * @param id 수정할 post-action UUID.
     * @param type 변경할 타입.
     * @param config 변경할 config Map.
     * @param displayOrder 변경할 displayOrder.
     * @return 수정된 [PostActionRow].
     * @throws PostActionNotFoundException 전이 또는 id 미존재 시.
     * @throws PostActionValidationException 미지원 type, 필수키 누락, 비-http url 시.
     */
    @Transactional
    fun update(
        workflowKey: String,
        transitionKey: String,
        id: UUID,
        type: String,
        config: Map<String, Any?>,
        displayOrder: Int,
    ): PostActionRow {
        val transitionId = resolveOrThrow(workflowKey, transitionKey)
        ensurePostActionBelongsToTransition(id, transitionId)
        validateConfig(type, config)
        val row = repository.update(id, type, config, displayOrder)
        workflowCache.invalidate(workflowKey)
        log.info(
            "PostActionAdminService.update id={} workflowKey={} transitionKey={} type={}",
            id,
            workflowKey,
            transitionKey,
            type,
        )
        return row
    }

    /**
     * post-action 을 삭제하고 캐시를 무효화한다.
     *
     * @param workflowKey 워크플로우 식별 키.
     * @param transitionKey `fromStateKey__toStateKey` 형식의 전이 자연키.
     * @param id 삭제할 post-action UUID.
     * @throws PostActionNotFoundException 전이 또는 id 미존재 시.
     */
    @Transactional
    fun delete(
        workflowKey: String,
        transitionKey: String,
        id: UUID,
    ) {
        val transitionId = resolveOrThrow(workflowKey, transitionKey)
        ensurePostActionBelongsToTransition(id, transitionId)
        repository.deleteById(id)
        workflowCache.invalidate(workflowKey)
        log.info(
            "PostActionAdminService.delete id={} workflowKey={} transitionKey={}",
            id,
            workflowKey,
            transitionKey,
        )
    }

    // ── private ──────────────────────────────────────────────────────────────

    /**
     * transitionKey 를 `__` 로 분리해 fromStateKey / toStateKey 를 추출하고
     * [PostActionTransitionResolver] 로 transition_id 를 해석한다.
     *
     * @throws PostActionNotFoundException transitionKey 형식 오류 또는 전이 미존재 시.
     */
    private fun resolveOrThrow(
        workflowKey: String,
        transitionKey: String,
    ): UUID {
        val parts = transitionKey.split("__")
        if (parts.size != 2) {
            throw PostActionNotFoundException(
                "transitionKey 형식 오류 — '__' 구분자 필요: '$transitionKey'",
            )
        }
        val (fromStateKey, toStateKey) = parts
        return transitionResolver.resolveTransitionId(workflowKey, fromStateKey, toStateKey)
            ?: throw PostActionNotFoundException(
                "전이 미존재 — workflowKey='$workflowKey' fromStateKey='$fromStateKey' toStateKey='$toStateKey'",
            )
    }

    /**
     * post-action id 가 해당 transitionId 에 속하는지 확인한다.
     *
     * @throws PostActionNotFoundException id 가 속하지 않거나 미존재 시.
     */
    private fun ensurePostActionBelongsToTransition(
        id: UUID,
        transitionId: UUID,
    ) {
        val exists = repository.findByTransitionId(transitionId).any { it.id == id }
        if (!exists) {
            throw PostActionNotFoundException(
                "post-action 미존재 — id='$id' transitionId='$transitionId'",
            )
        }
    }

    /**
     * type + config 검증.
     *
     * 1. CALL_WEBHOOK 은 url http/https 스킴 추가 체크.
     * 2. [WorkflowPostActionFactory.create] dry-run — [IllegalArgumentException] 을 [PostActionValidationException] 로 변환.
     *
     * @throws PostActionValidationException 검증 실패 시.
     */
    private fun validateConfig(
        type: String,
        config: Map<String, Any?>,
    ) {
        if (type == "CALL_WEBHOOK") {
            val url = config["url"] as? String ?: ""
            if (url.isBlank() || (!url.startsWith("http://") && !url.startsWith("https://"))) {
                throw PostActionValidationException(
                    "CALL_WEBHOOK url 은 http:// 또는 https:// 로 시작해야 합니다: '$url'",
                )
            }
        }
        try {
            factory.create(type, config)
        } catch (ex: IllegalArgumentException) {
            throw PostActionValidationException(ex.message ?: "검증 실패")
        }
    }
}
