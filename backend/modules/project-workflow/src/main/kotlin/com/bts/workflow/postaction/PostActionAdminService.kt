// 워크플로우 전환 post-action 관리 서비스 — 검증·전환해석

package com.bts.workflow.postaction

import com.bts.workflow.engine.WorkflowPostActionFactory
import com.bts.workflow.transition.TransitionKeyResolver
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
 * **캐시 무효화 불필요**: post-action 은 전환 실행 시
 * `DefaultWorkflowDefinitionRepository.findPostActions` 가 DB 직접 조회(WorkflowEngine.kt:302 경유)한다.
 * [com.bts.workflow.cache.WorkflowCache] 가 캐싱하는 `Workflow` aggregate 에는 post-action 필드가 없으므로
 * 별도 캐시 무효화가 불필요하다(WorkflowCache 는 states/transitions/validator 만 캐싱, post-action 비캐시).
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
     * 경로 세그먼트를 전환 id 로 해석한다. UUID 로 파싱되면 id 로, 아니면 합성 키로 읽는다.
     *
     * @throws PostActionNotFoundException 형식 오류 · 전환 미존재 시.
     */
    private fun resolveOrThrow(
        workflowKey: String,
        transitionKey: String,
    ): UUID {
        val transitionId =
            transitionKey.toTransitionIdOrNull()
                ?: return resolveByCompositeKey(workflowKey, transitionKey)
        return transitionResolver.resolveById(workflowKey, transitionId)
            ?: throw PostActionNotFoundException(
                "전환 미존재 — workflowKey='$workflowKey' transitionId='$transitionId'",
            )
    }

    /**
     * 종전 `fromStateKey__toStateKey` 합성 키로 전환을 찾는다 (하위호환 경로).
     *
     * ★ 이 키는 유일하지 않다. V207 ① 이 `UNIQUE(workflow_id, from_state_id, to_state_id)` 를
     * 풀어 같은 구간에 전환을 여럿 둘 수 있게 됐기 때문이다. 2건 이상이면 **그 이름으로는 대상을
     * 특정할 수 없으므로** 404 로 거절한다 — 아무 쪽이나 골라 주면 규칙이 엉뚱한 전환에 붙고
     * 그 오배치는 화면에서 보이지 않는다. 호출자는 전환 id 로 다시 부르면 되고 화면은 이미 그렇게 한다.
     *
     * @throws PostActionNotFoundException 형식 오류 · 전환 미존재 · 키가 유일하지 않을 때.
     */
    private fun resolveByCompositeKey(
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
        val ids = transitionResolver.resolveTransitionIds(workflowKey, fromStateKey, toStateKey)
        return ids.singleOrNull()
            ?: throw PostActionNotFoundException(
                "전환을 특정할 수 없다(${ids.size}건) — workflowKey='$workflowKey' " +
                    "transitionKey='$transitionKey'. 2건 이상이면 전환 id 로 지목해야 한다",
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

/**
 * RFC 4122 표기(8-4-4-4-12 16진)만 전환 id 로 인정하는 패턴.
 *
 * `UUID.fromString` 을 그대로 쓰지 않는 이유는 그것이 `1-1-1-1-1` 같은 헐거운 표기도 받아들여
 * 갈래 판정이 예외 발생 여부에 매달리기 때문이다. 갈래는 예외가 아니라 형태로 가른다.
 */
private val TRANSITION_ID_PATTERN =
    Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

/**
 * 경로 세그먼트가 전환 id 면 그 UUID, 아니면 null (= 종전 합성 키로 읽으라는 뜻).
 *
 * 합성 키는 반드시 `__` 를 품으므로 두 갈래가 겹치지 않는다.
 */
private fun String.toTransitionIdOrNull(): UUID? {
    if (!TRANSITION_ID_PATTERN.matches(this)) {
        return null
    }
    return UUID.fromString(this)
}
