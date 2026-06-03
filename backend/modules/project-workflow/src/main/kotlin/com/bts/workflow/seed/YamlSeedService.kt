// 표준 4 워크플로우 YAML 시드 — 부팅 시 dirty-diff 비교 후 변경된 경우만 재적재 (fail-fast 부팅 차단)

package com.bts.workflow.seed

import com.bts.workflow.domain.Workflow
import com.bts.workflow.jooq.tables.WorkflowPostActions.Companion.WORKFLOW_POST_ACTIONS
import com.bts.workflow.jooq.tables.WorkflowStates.Companion.WORKFLOW_STATES
import com.bts.workflow.jooq.tables.WorkflowTransitions.Companion.WORKFLOW_TRANSITIONS
import com.bts.workflow.jooq.tables.WorkflowValidators.Companion.WORKFLOW_VALIDATORS
import com.bts.workflow.jooq.tables.Workflows.Companion.WORKFLOWS
import com.bts.workflow.repository.WorkflowRepository
import com.fasterxml.jackson.databind.ObjectMapper
import io.konform.validation.Validation
import io.konform.validation.jsonschema.minItems
import io.konform.validation.jsonschema.minLength
import org.jooq.DSLContext
import org.jooq.JSONB
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

// ── YAML DTO — Jackson 역직렬화 대상 ─────────────────────────────────────────

/**
 * YAML 파일 최상위 구조 DTO.
 *
 * @property key 워크플로우 식별 키 (예: software-default)
 * @property name 워크플로우 이름
 * @property description 워크플로우 설명. null 허용 (YAML에 명시 안 된 경우 null).
 * @property states 상태 목록. 비어 있으면 Konform 검증 실패.
 * @property transitions 전이 목록.
 */
data class WorkflowYamlDto(
    val key: String = "",
    val name: String = "",
    val description: String? = null,
    val states: List<StateYamlDto> = emptyList(),
    val transitions: List<TransitionYamlDto> = emptyList(),
)

/**
 * YAML 상태 항목 DTO.
 *
 * @property key 상태 키 (워크플로우 내 유일)
 * @property name 상태 이름
 * @property category 카테고리 문자열 (TODO / IN_PROGRESS / DONE)
 * @property displayOrder UI 정렬 순서
 */
data class StateYamlDto(
    val key: String = "",
    val name: String = "",
    val category: String = "",
    val displayOrder: Int = 0,
)

/**
 * YAML validator 항목 DTO.
 *
 * @property type validator 구현 타입 식별자 (예: RequiredField, Permission, CustomExpression)
 * @property config 타입별 파라미터 맵 (예: mapOf("field" to "resolution"))
 */
data class ValidatorYamlDto(
    val type: String = "",
    val config: Map<String, Any?> = emptyMap(),
)

/**
 * YAML post_action 항목 DTO.
 *
 * @property type post_action 구현 타입 식별자 (예: SetField, AddWatcher, Notify)
 * @property config 타입별 파라미터 맵 (예: mapOf("field" to "assignee", "value" to "actor"))
 */
data class PostActionYamlDto(
    val type: String = "",
    val config: Map<String, Any?> = emptyMap(),
)

/**
 * YAML 전이 항목 DTO.
 *
 * @property from 출발 상태 키
 * @property to 도착 상태 키
 * @property name 전이 이름
 * @property validators 전이 전 검증 게이트 목록. 미정의 시 빈 리스트.
 * @property postActions 전이 후 자동 처리 목록. YAML 키는 post_actions (snake_case). 미정의 시 빈 리스트.
 */
data class TransitionYamlDto(
    val from: String = "",
    val to: String = "",
    val name: String = "",
    val validators: List<ValidatorYamlDto> = emptyList(),
    val postActions: List<PostActionYamlDto> = emptyList(),
)

// ── Konform 검증 규칙 ────────────────────────────────────────────────────────

/**
 * WorkflowYamlDto 에 대한 Konform 검증 규칙.
 *
 * Konform 은 Kotlin-native 선언형 검증 라이브러리다. Jakarta Validation 어노테이션 대신
 * 코드로 검증 규칙을 선언하며, 결과를 [ValidationResult] 타입으로 반환한다.
 */
val workflowYamlValidation: Validation<WorkflowYamlDto> =
    Validation {
        WorkflowYamlDto::key {
            minLength(1)
        }
        WorkflowYamlDto::name {
            minLength(1)
        }
        WorkflowYamlDto::states {
            minItems(1)
        }
    }

// ── YamlSeedService ──────────────────────────────────────────────────────────

/**
 * 표준 4 워크플로우 YAML 시드 서비스.
 *
 * 부팅 완료 후 [ApplicationReadyEvent] 를 통해 classpath:workflows/ 아래 4개 YAML 파일을 읽고
 * 현재 DB 상태와 dirty-diff 비교해 변경이 있는 경우만 재적재한다.
 *
 * YAML 파싱/검증 실패 시 [IllegalStateException] 을 던져 부팅을 차단한다 (fail-fast).
 *
 * 해시 저장 테이블 없이 dirty-diff (DB row vs YAML 내용 비교) 를 사용하므로
 * 별도 마이그레이션 추가가 필요 없다. 표준 4 워크플로우 한정이므로 성능 영향 미미.
 */
@Service
class YamlSeedService(
    private val workflowRepository: WorkflowRepository,
    private val dsl: DSLContext,
    private val resourceLoader: ResourceLoader,
    private val yamlMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** config Map → JSON 직렬화 전용. yamlMapper 는 YAMLFactory 기반이므로 별도 JSON ObjectMapper 필요. */
    private val jsonMapper = ObjectMapper()

    /** 표준 4 워크플로우 YAML 키 목록 (classpath:workflows/<key>.yaml). */
    private val standardWorkflowKeys =
        listOf(
            "software-default",
            "bug-tracking",
            "simple",
            "kanban-basic",
        )

    /**
     * 표준 4 YAML을 읽고 dirty-diff 비교 후 변경된 경우만 재적재.
     *
     * `@PostConstruct` 대신 [ApplicationReadyEvent] 를 사용한다. `@PostConstruct` 는 Bean 초기화 단계
     * 이므로 TransactionManager 가 미준비 상태일 수 있어 `@Transactional` AOP 가 작동하지 않을 수 있다.
     * [ApplicationReadyEvent] 는 모든 Bean 초기화 완료 후 발행되므로 트랜잭션 매니저를 보장한다.
     *
     * YAML 파일 부재 또는 Konform 검증 실패 시 [IllegalStateException] 으로 부팅 차단.
     * 테스트에서 직접 호출할 수 있다.
     */
    @EventListener(ApplicationReadyEvent::class)
    @Transactional
    fun seedAll() {
        log.info("YamlSeedService 시작 — 표준 4 워크플로우 시드 점검")

        for (key in standardWorkflowKeys) {
            val resourcePath = "classpath:workflows/$key.yaml"
            val resource = resourceLoader.getResource(resourcePath)
            check(resource.exists()) { "표준 워크플로우 YAML 파일 없음: $key.yaml" }

            val content = resource.inputStream.use { it.readBytes() }
            val dto = parseAndValidate(key, content)
            applyIfChanged(dto)
        }

        log.info("YamlSeedService 완료 — 표준 4 워크플로우 시드 점검 끝")
    }

    /**
     * YAML 바이트를 파싱하고 Konform 검증을 수행한다.
     *
     * 파싱 실패 또는 검증 실패 시 [IllegalStateException] 을 던진다.
     */
    private fun parseAndValidate(
        key: String,
        content: ByteArray,
    ): WorkflowYamlDto {
        val dto =
            try {
                yamlMapper.readValue(content, WorkflowYamlDto::class.java)
            } catch (ex: com.fasterxml.jackson.core.JsonProcessingException) {
                throw IllegalStateException("워크플로우 YAML '$key' 파싱 실패: ${ex.message}", ex)
            } catch (ex: java.io.IOException) {
                throw IllegalStateException("워크플로우 YAML '$key' 읽기 실패: ${ex.message}", ex)
            }

        val result = workflowYamlValidation(dto)
        if (result is io.konform.validation.Invalid) {
            error("워크플로우 YAML '$key' 검증 실패: ${result.errors}")
        }

        validateTransitionUniqueness(dto.key, dto.transitions)

        return dto
    }

    /**
     * 현재 DB 상태와 dto 를 dirty-diff 비교해 변경이 있으면 재적재한다.
     *
     * 비교 기준.
     * - workflows.name 변경 여부
     * - states 키/이름/카테고리/displayOrder 변경 여부
     * - transitions from/to/name 변경 여부
     *
     * 변경 감지 시 CASCADE DELETE 후 전체 재삽입 한다.
     */
    private fun applyIfChanged(dto: WorkflowYamlDto) {
        val existing = workflowRepository.findByKey(dto.key)

        if (existing != null && !isDirty(existing, dto)) {
            log.debug("워크플로우 '{}' — 변경 없음, skip", dto.key)
            return
        }

        if (existing != null) {
            log.info("워크플로우 '{}' — 변경 감지, 재적재 시작", dto.key)
            deleteWorkflow(dto.key)
        } else {
            log.info("워크플로우 '{}' — 신규 적재", dto.key)
        }

        insertWorkflow(dto)
    }

    /** 같은 워크플로우 안에 (from, to) 쌍이 중복 정의된 전이가 있으면 [IllegalStateException] 을 던진다. */
    private fun validateTransitionUniqueness(
        workflowKey: String,
        transitions: List<TransitionYamlDto>,
    ) {
        val duplicates =
            transitions
                .groupBy { it.from to it.to }
                .filter { it.value.size > 1 }
        if (duplicates.isNotEmpty()) {
            val dup = duplicates.keys.first().let { (f, t) -> "($f,$t)" }
            error("Workflow '$workflowKey' has duplicate (from, to)=$dup")
        }
    }

    /**
     * 기존 [Workflow] aggregate 와 [WorkflowYamlDto] 를 비교해 dirty 여부를 반환한다.
     *
     * validator/post_action 변경도 dirty 판정에 포함된다.
     * 판정 기준은 DB 에 저장된 validator/post_action 총 수와 YAML 정의 총 수 비교다.
     *
     * @return 변경이 있으면 true, 없으면 false
     */
    private fun isDirty(
        existing: Workflow,
        dto: WorkflowYamlDto,
    ): Boolean =
        differsInName(existing, dto) ||
            existing.description != dto.description ||
            differsInStateSet(existing, dto) ||
            differsInStateDetails(existing, dto) ||
            differsInTransitions(existing, dto) ||
            differsInValidators(existing, dto) ||
            differsInPostActions(existing, dto)

    private fun differsInName(
        existing: Workflow,
        dto: WorkflowYamlDto,
    ): Boolean = existing.name != dto.name

    private fun differsInStateSet(
        existing: Workflow,
        dto: WorkflowYamlDto,
    ): Boolean = existing.states.map { it.key }.toSet() != dto.states.map { it.key }.toSet()

    private fun differsInStateDetails(
        existing: Workflow,
        dto: WorkflowYamlDto,
    ): Boolean =
        dto.states.any { dtoState ->
            val existingState = existing.states.firstOrNull { it.key == dtoState.key }
            existingState == null ||
                existingState.name != dtoState.name ||
                existingState.category.name != dtoState.category ||
                existingState.displayOrder != dtoState.displayOrder
        }

    private fun differsInTransitions(
        existing: Workflow,
        dto: WorkflowYamlDto,
    ): Boolean {
        val existingTransitions =
            existing.transitions
                .map { Triple(it.fromStateKey, it.toStateKey, it.name) }
                .toSet()
        val dtoTransitions =
            dto.transitions
                .map { Triple(it.from, it.to, it.name) }
                .toSet()
        return existingTransitions != dtoTransitions
    }

    /**
     * DB 에 저장된 workflow_validators 와 YAML 정의를 전이별로 비교해 변경 여부를 반환한다.
     *
     * 전이별 (fromStateKey, toStateKey) 를 키로 DB validators type 목록과 YAML validators type 목록을
     * 비교한다. 총 count 비교만으로는 전이별 분포 변경을 감지하지 못하므로 전이별 비교를 사용한다.
     */
    private fun differsInValidators(
        existing: Workflow,
        dto: WorkflowYamlDto,
    ): Boolean {
        val dbValidatorsByTransition = fetchValidatorTypesByTransition(existing.key)
        return dto.transitions.any { transition ->
            val key = transition.from to transition.to
            val dbTypes = dbValidatorsByTransition[key] ?: emptyList()
            val dtoTypes = transition.validators.map { it.type }
            dbTypes != dtoTypes
        }
    }

    /**
     * DB 에 저장된 workflow_post_actions 와 YAML 정의를 전이별로 비교해 변경 여부를 반환한다.
     */
    private fun differsInPostActions(
        existing: Workflow,
        dto: WorkflowYamlDto,
    ): Boolean {
        val dbPostActionsByTransition = fetchPostActionTypesByTransition(existing.key)
        return dto.transitions.any { transition ->
            val key = transition.from to transition.to
            val dbTypes = dbPostActionsByTransition[key] ?: emptyList()
            val dtoTypes = transition.postActions.map { it.type }
            dbTypes != dtoTypes
        }
    }

    /**
     * 워크플로우 키에 속한 모든 전이의 validator type 목록을 (fromStateKey, toStateKey) 기준으로
     * 그루핑해 반환한다. display_order ASC 정렬.
     *
     * FROM / TO state 는 fetchTransitionStateKeys 로 별도 조회해 cartesian product 를 피한다.
     */
    private fun fetchValidatorTypesByTransition(
        workflowKey: String,
    ): Map<Pair<String, String>, List<String>> {
        val rows =
            dsl.select(
                WORKFLOW_TRANSITIONS.ID,
                WORKFLOW_VALIDATORS.TYPE,
                WORKFLOW_VALIDATORS.DISPLAY_ORDER,
            )
                .from(WORKFLOW_VALIDATORS)
                .join(WORKFLOW_TRANSITIONS)
                .on(WORKFLOW_VALIDATORS.TRANSITION_ID.eq(WORKFLOW_TRANSITIONS.ID))
                .join(WORKFLOWS)
                .on(WORKFLOW_TRANSITIONS.WORKFLOW_ID.eq(WORKFLOWS.ID))
                .where(WORKFLOWS.KEY.eq(workflowKey))
                .orderBy(WORKFLOW_VALIDATORS.DISPLAY_ORDER.asc())
                .fetch()

        val transitionKeyMap = fetchTransitionStateKeys(workflowKey)
        return rows
            .groupBy { it.get(WORKFLOW_TRANSITIONS.ID) }
            .mapNotNull { (transitionId, records) ->
                val stateKeys = transitionKeyMap[transitionId] ?: return@mapNotNull null
                stateKeys to records.map { it.get(WORKFLOW_VALIDATORS.TYPE) ?: "" }
            }
            .toMap()
    }

    /**
     * 워크플로우 키에 속한 모든 전이의 post_action type 목록을 (fromStateKey, toStateKey) 기준으로
     * 그루핑해 반환한다. display_order ASC 정렬.
     */
    private fun fetchPostActionTypesByTransition(
        workflowKey: String,
    ): Map<Pair<String, String>, List<String>> {
        val rows =
            dsl.select(
                WORKFLOW_TRANSITIONS.ID,
                WORKFLOW_POST_ACTIONS.TYPE,
                WORKFLOW_POST_ACTIONS.DISPLAY_ORDER,
            )
                .from(WORKFLOW_POST_ACTIONS)
                .join(WORKFLOW_TRANSITIONS)
                .on(WORKFLOW_POST_ACTIONS.TRANSITION_ID.eq(WORKFLOW_TRANSITIONS.ID))
                .join(WORKFLOWS)
                .on(WORKFLOW_TRANSITIONS.WORKFLOW_ID.eq(WORKFLOWS.ID))
                .where(WORKFLOWS.KEY.eq(workflowKey))
                .orderBy(WORKFLOW_POST_ACTIONS.DISPLAY_ORDER.asc())
                .fetch()

        val transitionKeyMap = fetchTransitionStateKeys(workflowKey)
        return rows
            .groupBy { it.get(WORKFLOW_TRANSITIONS.ID) }
            .mapNotNull { (transitionId, records) ->
                val stateKeys = transitionKeyMap[transitionId] ?: return@mapNotNull null
                stateKeys to records.map { it.get(WORKFLOW_POST_ACTIONS.TYPE) ?: "" }
            }
            .toMap()
    }

    /**
     * 워크플로우 키에 속한 모든 전이의 (transition_id to (fromStateKey, toStateKey)) 매핑을 반환한다.
     *
     * FROM / TO state 를 별칭 JOIN 으로 조회해 cartesian product 없이 확보한다.
     */
    private fun fetchTransitionStateKeys(
        workflowKey: String,
    ): Map<java.util.UUID?, Pair<String, String>> {
        val fromState = WORKFLOW_STATES.`as`("from_state")
        val toState = WORKFLOW_STATES.`as`("to_state")
        return dsl.select(
            WORKFLOW_TRANSITIONS.ID,
            fromState.KEY,
            toState.KEY,
        )
            .from(WORKFLOW_TRANSITIONS)
            .join(WORKFLOWS)
            .on(WORKFLOW_TRANSITIONS.WORKFLOW_ID.eq(WORKFLOWS.ID))
            .join(fromState)
            .on(WORKFLOW_TRANSITIONS.FROM_STATE_ID.eq(fromState.ID))
            .join(toState)
            .on(WORKFLOW_TRANSITIONS.TO_STATE_ID.eq(toState.ID))
            .where(WORKFLOWS.KEY.eq(workflowKey))
            .fetch()
            .associate { record ->
                record.get(WORKFLOW_TRANSITIONS.ID) to
                    (
                        (record.get(fromState.KEY) ?: "") to
                            (record.get(toState.KEY) ?: "")
                    )
            }
    }

    /**
     * workflows 테이블에서 key 로 워크플로우를 삭제한다.
     *
     * workflow_states / workflow_transitions 는 ON DELETE CASCADE 이므로 자동 삭제된다.
     */
    private fun deleteWorkflow(key: String) {
        dsl.deleteFrom(WORKFLOWS)
            .where(WORKFLOWS.KEY.eq(key))
            .execute()
        log.debug("워크플로우 '{}' 삭제 완료 (CASCADE)", key)
    }

    /**
     * [WorkflowYamlDto] 를 workflows / workflow_states / workflow_transitions 에 삽입한다.
     *
     * 삽입 순서.
     * 1. workflows 행 삽입 → workflow UUID 획득
     * 2. workflow_states 행 삽입 → state key → UUID 매핑 구성
     * 3. workflow_transitions 행 삽입 (from/to UUID FK 사용)
     */
    private fun insertWorkflow(dto: WorkflowYamlDto) {
        // 1. workflows 삽입
        val workflowId =
            dsl.insertInto(WORKFLOWS)
                .set(WORKFLOWS.KEY, dto.key)
                .set(WORKFLOWS.NAME, dto.name)
                .set(WORKFLOWS.DESCRIPTION, dto.description)
                .returningResult(WORKFLOWS.ID)
                .fetchOne()
                ?.value1()
                ?: error("workflows 삽입 실패: ${dto.key}")

        // 2. workflow_states 삽입 + key → UUID 매핑
        val stateKeyToId = mutableMapOf<String, java.util.UUID>()
        for (state in dto.states) {
            val stateId =
                dsl.insertInto(WORKFLOW_STATES)
                    .set(WORKFLOW_STATES.WORKFLOW_ID, workflowId)
                    .set(WORKFLOW_STATES.KEY, state.key)
                    .set(WORKFLOW_STATES.NAME, state.name)
                    .set(WORKFLOW_STATES.CATEGORY, state.category)
                    .set(WORKFLOW_STATES.DISPLAY_ORDER, state.displayOrder)
                    .returningResult(WORKFLOW_STATES.ID)
                    .fetchOne()
                    ?.value1()
                    ?: error("workflow_states 삽입 실패: ${dto.key}/${state.key}")
            stateKeyToId[state.key] = stateId
        }

        // 3. workflow_transitions 삽입 + validators/post_actions 삽입
        for (transition in dto.transitions) {
            val fromStateId =
                stateKeyToId[transition.from]
                    ?: error("전이 from 상태 키 '${transition.from}' 가 states 에 없음: ${dto.key}")
            val toStateId =
                stateKeyToId[transition.to]
                    ?: error("전이 to 상태 키 '${transition.to}' 가 states 에 없음: ${dto.key}")

            val transitionId =
                dsl.insertInto(WORKFLOW_TRANSITIONS)
                    .set(WORKFLOW_TRANSITIONS.WORKFLOW_ID, workflowId)
                    .set(WORKFLOW_TRANSITIONS.FROM_STATE_ID, fromStateId)
                    .set(WORKFLOW_TRANSITIONS.TO_STATE_ID, toStateId)
                    .set(WORKFLOW_TRANSITIONS.NAME, transition.name)
                    .returningResult(WORKFLOW_TRANSITIONS.ID)
                    .fetchOne()
                    ?.value1()
                    ?: error("workflow_transitions 삽입 실패: ${dto.key}/${transition.from}->${transition.to}")

            insertValidators(transitionId, transition.validators)
            insertPostActions(transitionId, transition.postActions)
        }

        log.info(
            "워크플로우 '{}' 적재 완료 — states: {}, transitions: {}, validators: {}, postActions: {}",
            dto.key,
            dto.states.size,
            dto.transitions.size,
            dto.transitions.sumOf { it.validators.size },
            dto.transitions.sumOf { it.postActions.size },
        )
    }

    /**
     * workflow_validators 에 validator 목록을 삽입한다.
     *
     * config Map 은 Jackson ObjectMapper 로 JSON 직렬화 후 JSONB.valueOf 로 변환한다.
     *
     * @param transitionId workflow_transitions.id
     * @param validators YAML 에서 파싱된 validator DTO 목록
     */
    private fun insertValidators(
        transitionId: java.util.UUID,
        validators: List<ValidatorYamlDto>,
    ) {
        validators.forEachIndexed { index, validator ->
            val configJson = jsonMapper.writeValueAsString(validator.config)
            dsl.insertInto(WORKFLOW_VALIDATORS)
                .set(WORKFLOW_VALIDATORS.TRANSITION_ID, transitionId)
                .set(WORKFLOW_VALIDATORS.TYPE, validator.type)
                .set(WORKFLOW_VALIDATORS.CONFIG, JSONB.valueOf(configJson))
                .set(WORKFLOW_VALIDATORS.DISPLAY_ORDER, index)
                .execute()
        }
    }

    /**
     * workflow_post_actions 에 post_action 목록을 삽입한다.
     *
     * config Map 은 Jackson ObjectMapper 로 JSON 직렬화 후 JSONB.valueOf 로 변환한다.
     *
     * @param transitionId workflow_transitions.id
     * @param postActions YAML 에서 파싱된 post_action DTO 목록
     */
    private fun insertPostActions(
        transitionId: java.util.UUID,
        postActions: List<PostActionYamlDto>,
    ) {
        postActions.forEachIndexed { index, postAction ->
            val configJson = jsonMapper.writeValueAsString(postAction.config)
            dsl.insertInto(WORKFLOW_POST_ACTIONS)
                .set(WORKFLOW_POST_ACTIONS.TRANSITION_ID, transitionId)
                .set(WORKFLOW_POST_ACTIONS.TYPE, postAction.type)
                .set(WORKFLOW_POST_ACTIONS.CONFIG, JSONB.valueOf(configJson))
                .set(WORKFLOW_POST_ACTIONS.DISPLAY_ORDER, index)
                .execute()
        }
    }

    /**
     * 단건 [WorkflowYamlDto] 를 파싱 없이 직접 시드한다.
     *
     * 테스트에서 표준 4 YAML 목록 밖의 테스트 픽스처를 시드할 때 사용한다.
     * Konform 검증 및 transition 중복 검증은 [parseAndValidate] 에서 수행하므로 호출 전 검증이
     * 완료된 DTO 를 전달해야 한다.
     *
     * @param dto 파싱 및 검증이 완료된 [WorkflowYamlDto]
     */
    @Transactional
    fun seedSingle(dto: WorkflowYamlDto) {
        validateTransitionUniqueness(dto.key, dto.transitions)
        applyIfChanged(dto)
    }
}
