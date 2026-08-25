// 워크플로우 전환 post-action 관리 서비스 — 검증·전환해석

package com.bts.workflow.postaction

import com.bts.workflow.engine.WorkflowPostActionFactory
import com.bts.workflow.transition.TransitionKeyResolver
import com.bts.workflow.transition.requireContains
import com.bts.workflow.transition.resolveTransitionOrThrow
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 전환별 post-action CRUD 관리 서비스.
 *
 * URL path 의 transitionKey 를 전환 UUID 로 해석하고,
 * [WorkflowPostActionFactory] 로 검증 후 [PostActionRepository] 에 영속한다.
 *
 * ### transitionKey 는 id 이거나 종전 합성 키다
 * 경로 세그먼트가 UUID 로 파싱되면 `workflow_transitions.id` 로 읽는다 — 전환의 1급 식별자다
 * (ADR 2026-08-18 §D1). 아니면 종전 `fromStateKey__toStateKey` 합성 키로 읽는다. 새 라우트를
 * 만들지 않으므로 이미 나가 있는 키 경로가 그대로 산다.
 *
 * **캐시 무효화 불필요**: post-action 은 전환 실행 시 `WorkflowEngine` 의 post-action 실행 경로가
 * `DefaultWorkflowDefinitionRepository.findPostActions` 로 DB 를 직접 조회한다.
 * [com.bts.workflow.cache.WorkflowCache] 가 캐싱하는 것은 `Workflow` aggregate(= states · transitions)
 * 뿐이고 post-action 필드가 없으므로 별도 캐시 무효화가 불필요하다. **validator 도 같은 이유로 비캐시**다
 * — 두 컬렉션 다 전환 실행 시 DB 를 직접 친다.
 *
 * ### 검증 순서 (create/update)
 * 1. transitionKey 해석 → 형식 오류·미존재 시 [PostActionNotFoundException].
 * 2. 합성 키가 2건 이상에 걸리면 그 이름으로 대상을 특정할 수 없다 → 404.
 * 3. CALL_WEBHOOK url http/https 스킴 추가 체크 → 실패 시 [PostActionValidationException].
 * 4. [WorkflowPostActionFactory.create] dry-run → [IllegalArgumentException] → [PostActionValidationException].
 *
 * @param repository post-action CRUD jOOQ 리포지토리.
 * @param factory post-action type 검증용 팩토리.
 * @param transitionResolver transitionKey → transition_id UUID 해석기.
 */
@Service
class PostActionAdminService(
    private val repository: PostActionRepository,
    private val factory: WorkflowPostActionFactory,
    private val transitionResolver: TransitionKeyResolver,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 전환에 속한 post-action 목록을 반환한다.
     *
     * @param workflowKey 워크플로우 식별 키.
     * @param transitionKey 전환 지목값 — 전환 id(UUID) 또는 종전 `fromStateKey__toStateKey` 합성 키.
     * @return [PostActionRow] 목록 (displayOrder ASC).
     * @throws PostActionNotFoundException 전환 미존재 또는 transitionKey 형식 오류 시.
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
     * post-action 을 생성한다.
     *
     * @param workflowKey 워크플로우 식별 키.
     * @param transitionKey 전환 지목값 — 전환 id(UUID) 또는 종전 `fromStateKey__toStateKey` 합성 키.
     * @param type post-action 타입 식별자.
     * @param config 타입별 설정 Map.
     * @param displayOrder UI 표시 순서.
     * @return 삽입된 [PostActionRow].
     * @throws PostActionNotFoundException 전환 미존재 또는 transitionKey 형식 오류 시.
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
     * post-action 을 수정한다.
     *
     * @param workflowKey 워크플로우 식별 키.
     * @param transitionKey 전환 지목값 — 전환 id(UUID) 또는 종전 `fromStateKey__toStateKey` 합성 키.
     * @param id 수정할 post-action UUID.
     * @param type 변경할 타입.
     * @param config 변경할 config Map.
     * @param displayOrder 변경할 displayOrder.
     * @return 수정된 [PostActionRow].
     * @throws PostActionNotFoundException 전환 또는 id 미존재 시.
     * @throws PostActionValidationException 미지원 type, 필수키 누락, 비-http url 시.
     */
    @Suppress("LongParameterList")
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
     * post-action 을 삭제한다.
     *
     * @param workflowKey 워크플로우 식별 키.
     * @param transitionKey 전환 지목값 — 전환 id(UUID) 또는 종전 `fromStateKey__toStateKey` 합성 키.
     * @param id 삭제할 post-action UUID.
     * @throws PostActionNotFoundException 전환 또는 id 미존재 시.
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
        log.info(
            "PostActionAdminService.delete id={} workflowKey={} transitionKey={}",
            id,
            workflowKey,
            transitionKey,
        )
    }

    // ── private ──────────────────────────────────────────────────────────────

    /**
     * 경로 세그먼트를 전환 id 로 해석한다. UUID 로 파싱되면 id 로, 아니면 종전 합성 키로 읽는다.
     *
     * 판정 자체는 [resolveTransitionOrThrow] 하나뿐이고 — 형식 오류 · 미존재 · 합성 키가 2건
     * 이상이면 특정 불가 — 여기서는 그 실패를 post-action 의 404 예외로 옮기기만 한다. 형제인
     * `ValidatorAdminService` 가 **같은 구현**을 부르므로 모호성 정책이 두 벌로 갈리지 않는다.
     *
     * @throws PostActionNotFoundException 형식 오류 · 전환 미존재 · 키가 유일하지 않을 때.
     */
    private fun resolveOrThrow(
        workflowKey: String,
        transitionKey: String,
    ): UUID =
        transitionResolver.resolveTransitionOrThrow(workflowKey, transitionKey) {
            throw PostActionNotFoundException(it)
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
        repository.findByTransitionId(transitionId).requireContains(id, transitionId, "post-action") {
            throw PostActionNotFoundException(it)
        }
    }

    /**
     * type + config 검증.
     *
     * 1. CALL_WEBHOOK 은 url http/https 스킴 추가 체크.
     * 2. [WorkflowPostActionFactory.create] dry-run — [IllegalArgumentException] 을
     *    [PostActionValidationException] 로 변환.
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
            throw PostActionValidationException(ex.message ?: "검증 실패", ex)
        }
    }
}
