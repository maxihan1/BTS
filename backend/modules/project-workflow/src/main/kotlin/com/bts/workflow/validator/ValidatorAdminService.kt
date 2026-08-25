// 워크플로우 전환 validator 관리 서비스 — 전환해석 · 팩토리 dry-run 검증 · 편집 불가 타입 차단

package com.bts.workflow.validator

import com.bts.workflow.engine.WorkflowValidatorFactory
import com.bts.workflow.transition.TransitionKeyResolver
import com.bts.workflow.transition.requireContains
import com.bts.workflow.transition.resolveTransitionOrThrow
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 전환별 validator CRUD 관리 서비스.
 *
 * URL path 의 transitionKey 를 전환 UUID 로 해석하고, [WorkflowValidatorFactory] 로 검증한 뒤
 * [ValidatorRepository] 에 영속한다. 형제인 `PostActionAdminService` 와 같은 계약이다.
 *
 * ### transitionKey 는 id 이거나 종전 합성 키다
 * 경로 세그먼트가 UUID 로 파싱되면 `workflow_transitions.id` 로 읽는다 — 전환의 1급 식별자다
 * (ADR 2026-08-18 §D1). 아니면 종전 `fromStateKey__toStateKey` 합성 키로 읽는다.
 *
 * ### 지원 type 의 정본은 팩토리 하나다
 * 이 서비스는 지원 type 목록을 들지 않는다. 허용 여부는 [WorkflowValidatorFactory.create] dry-run 이
 * 정하고 여기서는 그것이 던진 [IllegalArgumentException] 을 [ValidatorValidationException] 으로 옮길
 * 뿐이다. 목록을 사본으로 들면 팩토리의 `when` 분기와 갈리는데 **두 목록은 서로를 검사하지 않으므로**
 * 갈린 사실이 드러나지 않는다.
 *
 * ### 편집 가능 타입은 허용 목록이고, 문자열이 아니라 타입으로 가른다
 * 이 API 로 만들거나 바꿀 수 있는 것은 [RequiredFieldValidator] · [PermissionValidator] ·
 * [NotStatusCategoryValidator] **셋뿐**이고 나머지는 전부 거절이다. 근거는 `validateConfig` KDoc 에 있다.
 * 그래서 `CustomExpression` 도 들어오지 못한다 — `expression/SpelEvaluator` 가
 * 「일반 사용자가 API 를 통해 임의 표현식을 전달하는 경로를 절대로 만들지 않는다」고 못 박았고,
 * Jira Cloud 내장 validator 9종에도 자유 표현식 입력칸이 없다.
 *
 * 판정은 팩토리가 만든 인스턴스의 **타입**으로 한다 — 여기에 type 문자열을 적으면 팩토리 분기와
 * 각 구현체의 `override val type` 에 이은 세 번째 사본이 되고, `is` 와 달리 컴파일러가 그 사본을
 * 지켜 주지 않는다. dry-run 은 인스턴스 생성까지라 `validate` 를 부르지 않는다 — 표현식 평가도
 * 권한 조회도 일어나지 않는다.
 *
 * 이미 들어간 `CustomExpression` 행은 **목록에 보이고 삭제도 된다** — 반쪽 목록은 관리자를 속인다.
 *
 * ### 캐시 무효화 불필요
 * validator 는 캐싱되지 않는다. `cache/WorkflowCache` 가 담는 것은 `Workflow` aggregate
 * (= `key · name · description · states · transitions`)뿐이고(`domain/Workflow.kt` ·
 * `repository/WorkflowRepository.kt` 의 「validator / post_action 컬렉션은 Workflow aggregate 책임 외」),
 * 전환 실행 시 `DefaultWorkflowDefinitionRepository.findValidators` 가 DB 를 직접 친다.
 *
 * ### 검증 순서 (create / update)
 * 1. transitionKey 해석 → 형식 오류·미존재·유일하지 않음 시 [ValidatorNotFoundException].
 * 2. (update / delete) id 가 그 전환 소속인지 확인 → 아니면 [ValidatorNotFoundException] (IDOR 차단).
 * 3. [WorkflowValidatorFactory.create] dry-run → [ValidatorValidationException].
 * 4. 생성된 인스턴스가 편집 허용 목록 밖이면 [ValidatorTypeNotEditableException].
 *
 * @param repository validator CRUD jOOQ 리포지토리.
 * @param factory validator type·config 검증용 팩토리. 지원 type 의 정본이다.
 * @param transitionResolver transitionKey → transition_id UUID 해석기.
 */
@Service
class ValidatorAdminService(
    private val repository: ValidatorRepository,
    private val factory: WorkflowValidatorFactory,
    private val transitionResolver: TransitionKeyResolver,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 전환에 속한 validator 목록을 반환한다.
     *
     * 편집할 수 없는 type 의 행도 숨기지 않고 그대로 담는다.
     *
     * @param workflowKey 워크플로우 식별 키.
     * @param transitionKey 전환 지목값 — 전환 id(UUID) 또는 종전 `fromStateKey__toStateKey` 합성 키.
     * @return [ValidatorRow] 목록 (displayOrder ASC).
     * @throws ValidatorNotFoundException 전환 미존재 · transitionKey 형식 오류 · 키가 유일하지 않을 때.
     */
    @Transactional(readOnly = true)
    fun listForTransition(
        workflowKey: String,
        transitionKey: String,
    ): List<ValidatorRow> {
        val transitionId = resolveOrThrow(workflowKey, transitionKey)
        return repository.findByTransitionId(transitionId)
    }

    /**
     * validator 를 생성한다.
     *
     * @param workflowKey 워크플로우 식별 키.
     * @param transitionKey 전환 지목값 — 전환 id(UUID) 또는 종전 `fromStateKey__toStateKey` 합성 키.
     * @param type validator 타입 식별자.
     * @param config 타입별 설정 Map.
     * @param displayOrder UI 표시 순서.
     * @return 삽입된 [ValidatorRow].
     * @throws ValidatorNotFoundException 전환 미존재 또는 transitionKey 형식 오류 시.
     * @throws ValidatorValidationException 미지원 type · 필수 config 키 누락 · config 타입 불일치 시.
     * @throws ValidatorTypeNotEditableException 화면에서 편집할 수 없는 type 일 때.
     */
    @Transactional
    fun create(
        workflowKey: String,
        transitionKey: String,
        type: String,
        config: Map<String, Any?>,
        displayOrder: Int,
    ): ValidatorRow {
        val transitionId = resolveOrThrow(workflowKey, transitionKey)
        validateConfig(type, config)
        val row = repository.insert(transitionId, type, config, displayOrder)
        log.info(
            "ValidatorAdminService.create id={} workflowKey={} transitionKey={} type={}",
            row.id,
            workflowKey,
            transitionKey,
            type,
        )
        return row
    }

    /**
     * validator 를 수정한다.
     *
     * type 교체도 생성과 **같은** 검증을 탄다. 한쪽만 태우면 기존 행의 type 을 편집 불가 값으로
     * 바꿔 넣는 문이 열린다.
     *
     * @param workflowKey 워크플로우 식별 키.
     * @param transitionKey 전환 지목값 — 전환 id(UUID) 또는 종전 `fromStateKey__toStateKey` 합성 키.
     * @param id 수정할 validator UUID.
     * @param type 변경할 타입.
     * @param config 변경할 config Map.
     * @param displayOrder 변경할 displayOrder.
     * @return 수정된 [ValidatorRow].
     * @throws ValidatorNotFoundException 전환 미존재 · id 미존재 · id 가 그 전환 소속이 아닐 때.
     * @throws ValidatorValidationException 미지원 type · 필수 config 키 누락 · config 타입 불일치 시.
     * @throws ValidatorTypeNotEditableException 화면에서 편집할 수 없는 type 으로 바꾸려 할 때.
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
    ): ValidatorRow {
        val transitionId = resolveOrThrow(workflowKey, transitionKey)
        ensureValidatorBelongsToTransition(id, transitionId)
        validateConfig(type, config)
        val row = repository.update(id, type, config, displayOrder)
        log.info(
            "ValidatorAdminService.update id={} workflowKey={} transitionKey={} type={}",
            id,
            workflowKey,
            transitionKey,
            type,
        )
        return row
    }

    /**
     * validator 를 삭제한다.
     *
     * 편집할 수 없는 type 의 행도 삭제된다 — 지울 수 없으면 잘못 들어간 규칙을 풀 방법이 없다.
     *
     * @param workflowKey 워크플로우 식별 키.
     * @param transitionKey 전환 지목값 — 전환 id(UUID) 또는 종전 `fromStateKey__toStateKey` 합성 키.
     * @param id 삭제할 validator UUID.
     * @throws ValidatorNotFoundException 전환 미존재 · id 미존재 · id 가 그 전환 소속이 아닐 때.
     */
    @Transactional
    fun delete(
        workflowKey: String,
        transitionKey: String,
        id: UUID,
    ) {
        val transitionId = resolveOrThrow(workflowKey, transitionKey)
        ensureValidatorBelongsToTransition(id, transitionId)
        repository.deleteById(id)
        log.info(
            "ValidatorAdminService.delete id={} workflowKey={} transitionKey={}",
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
     * 이상이면 특정 불가 — 여기서는 그 실패를 validator 의 404 예외로 옮기기만 한다. 형제인
     * `PostActionAdminService` 가 **같은 구현**을 부르므로 모호성 정책이 두 벌로 갈리지 않는다.
     *
     * @throws ValidatorNotFoundException 형식 오류 · 전환 미존재 · 키가 유일하지 않을 때.
     */
    private fun resolveOrThrow(
        workflowKey: String,
        transitionKey: String,
    ): UUID =
        transitionResolver.resolveTransitionOrThrow(workflowKey, transitionKey) {
            throw ValidatorNotFoundException(it)
        }

    /**
     * validator id 가 해당 transitionId 에 속하는지 확인한다 (IDOR 차단).
     *
     * @throws ValidatorNotFoundException id 가 속하지 않거나 미존재 시.
     */
    private fun ensureValidatorBelongsToTransition(
        id: UUID,
        transitionId: UUID,
    ) {
        repository.findByTransitionId(transitionId).requireContains(id, transitionId, "validator") {
            throw ValidatorNotFoundException(it)
        }
    }

    /**
     * type + config 검증. create 와 update 가 **같은** 함수를 탄다.
     *
     * 1. [WorkflowValidatorFactory.create] dry-run — 미지원 type · 필수 config 키 누락 ·
     *    config 타입 불일치 · 알 수 없는 enum 을 전부 [IllegalArgumentException] 으로 받아
     *    [ValidatorValidationException] 으로 옮긴다. 지원 목록을 여기서 다시 세지 않는다.
     * 2. 만들어진 인스턴스가 **허용 목록**에 없으면 편집 불가다. 허용은 [RequiredFieldValidator] ·
     *    [PermissionValidator] · [NotStatusCategoryValidator] 셋뿐이다. 새 validator 를 편집 가능하게
     *    하려면 아래 `editable` 조건에 **명시 등재**해야 한다 — 빠뜨리면 400 이지 조용한 허용이 아니다.
     *    거절 목록이었다면 팩토리에 표현식·스크립트·템플릿·URL 을 받는 분기가 하나 늘어나는 것만으로
     *    그 타입이 **코드 변경 0 으로** 이 API 를 통해 쓰기 가능해진다(fail-open). type 문자열 비교가
     *    아니라 **타입 검사**이므로 클래스가 사라지거나 이름이 바뀌면 컴파일이 먼저 깨진다.
     *
     * 인스턴스를 만들 뿐 `validate` 를 부르지 않는다 — SpEL 평가도 권한 조회도 일어나지 않는다.
     *
     * @throws ValidatorValidationException type·config 검증 실패 시.
     * @throws ValidatorTypeNotEditableException 편집할 수 없는 type 일 때.
     */
    private fun validateConfig(
        type: String,
        config: Map<String, Any?>,
    ) {
        val instance =
            try {
                factory.create(type, config)
            } catch (ex: IllegalArgumentException) {
                throw ValidatorValidationException(ex.message ?: "검증 실패", ex)
            }
        val editable =
            instance is RequiredFieldValidator ||
                instance is PermissionValidator ||
                instance is NotStatusCategoryValidator
        if (!editable) {
            log.info("ValidatorAdminService: 편집 불가 validator type 거절 type={}", type)
            throw ValidatorTypeNotEditableException(type)
        }
    }
}
