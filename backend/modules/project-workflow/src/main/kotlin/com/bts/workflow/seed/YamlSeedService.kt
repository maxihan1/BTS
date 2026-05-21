// 표준 4 워크플로우 YAML 시드 — 부팅 시 dirty-diff 비교 후 변경된 경우만 재적재 (fail-fast 부팅 차단)

package com.bts.workflow.seed

import com.bts.workflow.domain.Workflow
import com.bts.workflow.jooq.tables.WorkflowStates.Companion.WORKFLOW_STATES
import com.bts.workflow.jooq.tables.WorkflowTransitions.Companion.WORKFLOW_TRANSITIONS
import com.bts.workflow.jooq.tables.Workflows.Companion.WORKFLOWS
import com.bts.workflow.repository.WorkflowRepository
import com.fasterxml.jackson.databind.ObjectMapper
import io.konform.validation.Validation
import io.konform.validation.jsonschema.minItems
import io.konform.validation.jsonschema.minLength
import jakarta.annotation.PostConstruct
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Service

// ── YAML DTO — Jackson 역직렬화 대상 ─────────────────────────────────────────

/**
 * YAML 파일 최상위 구조 DTO.
 *
 * @property key 워크플로우 식별 키 (예: software-default)
 * @property name 워크플로우 이름
 * @property states 상태 목록. 비어 있으면 Konform 검증 실패.
 * @property transitions 전이 목록.
 */
data class WorkflowYamlDto(
    val key: String = "",
    val name: String = "",
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
 * YAML 전이 항목 DTO.
 *
 * @property from 출발 상태 키
 * @property to 도착 상태 키
 * @property name 전이 이름
 */
data class TransitionYamlDto(
    val from: String = "",
    val to: String = "",
    val name: String = "",
)

// ── Konform 검증 규칙 ────────────────────────────────────────────────────────

/**
 * WorkflowYamlDto 에 대한 Konform 검증 규칙.
 *
 * Konform 은 Kotlin-native 선언형 검증 라이브러리다. Jakarta Validation 어노테이션 대신
 * 코드로 검증 규칙을 선언하며, 결과를 [ValidationResult] 타입으로 반환한다.
 */
val workflowYamlValidation: Validation<WorkflowYamlDto> = Validation {
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
 * 부팅 시 [@PostConstruct]를 통해 classpath:workflows/ 아래 4개 YAML 파일을 읽고
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

    /** 표준 4 워크플로우 YAML 키 목록 (classpath:workflows/<key>.yaml). */
    private val standardWorkflowKeys = listOf(
        "software-default",
        "bug-tracking",
        "simple",
        "kanban-basic",
    )

    /**
     * 부팅 시 1회 실행. 표준 4 YAML을 읽고 dirty-diff 비교 후 변경된 경우만 재적재.
     *
     * YAML 파일 부재 또는 Konform 검증 실패 시 [IllegalStateException] 으로 부팅 차단.
     */
    @PostConstruct
    fun seed() {
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
    private fun parseAndValidate(key: String, content: ByteArray): WorkflowYamlDto {
        val dto = try {
            yamlMapper.readValue(content, WorkflowYamlDto::class.java)
        } catch (ex: Exception) {
            throw IllegalStateException("워크플로우 YAML '$key' 파싱 실패: ${ex.message}", ex)
        }

        val result = workflowYamlValidation(dto)
        if (result is io.konform.validation.Invalid) {
            throw IllegalStateException("워크플로우 YAML '$key' 검증 실패: ${result.errors}")
        }

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

    /**
     * 기존 [Workflow] aggregate 와 [WorkflowYamlDto] 를 비교해 dirty 여부를 반환한다.
     *
     * @return 변경이 있으면 true, 없으면 false
     */
    private fun isDirty(existing: Workflow, dto: WorkflowYamlDto): Boolean {
        if (existing.name != dto.name) return true

        val existingStateKeys = existing.states.map { it.key }.toSet()
        val dtoStateKeys = dto.states.map { it.key }.toSet()
        if (existingStateKeys != dtoStateKeys) return true

        // states 상세 비교 (name, category, displayOrder)
        for (dtoState in dto.states) {
            val existingState = existing.states.firstOrNull { it.key == dtoState.key } ?: return true
            if (existingState.name != dtoState.name) return true
            if (existingState.category.name != dtoState.category) return true
            if (existingState.displayOrder != dtoState.displayOrder) return true
        }

        // transitions 비교 (from/to/name 집합 동일 여부)
        val existingTransitions = existing.transitions
            .map { Triple(it.fromStateKey, it.toStateKey, it.name) }
            .toSet()
        val dtoTransitions = dto.transitions
            .map { Triple(it.from, it.to, it.name) }
            .toSet()
        if (existingTransitions != dtoTransitions) return true

        return false
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
        val workflowId = dsl.insertInto(WORKFLOWS)
            .set(WORKFLOWS.KEY, dto.key)
            .set(WORKFLOWS.NAME, dto.name)
            .returningResult(WORKFLOWS.ID)
            .fetchOne()
            ?.value1()
            ?: error("workflows 삽입 실패: ${dto.key}")

        // 2. workflow_states 삽입 + key → UUID 매핑
        val stateKeyToId = mutableMapOf<String, java.util.UUID>()
        for (state in dto.states) {
            val stateId = dsl.insertInto(WORKFLOW_STATES)
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

        // 3. workflow_transitions 삽입
        for (transition in dto.transitions) {
            val fromStateId = stateKeyToId[transition.from]
                ?: error("전이 from 상태 키 '${transition.from}' 가 states 에 없음: ${dto.key}")
            val toStateId = stateKeyToId[transition.to]
                ?: error("전이 to 상태 키 '${transition.to}' 가 states 에 없음: ${dto.key}")

            dsl.insertInto(WORKFLOW_TRANSITIONS)
                .set(WORKFLOW_TRANSITIONS.WORKFLOW_ID, workflowId)
                .set(WORKFLOW_TRANSITIONS.FROM_STATE_ID, fromStateId)
                .set(WORKFLOW_TRANSITIONS.TO_STATE_ID, toStateId)
                .set(WORKFLOW_TRANSITIONS.NAME, transition.name)
                .execute()
        }

        log.info(
            "워크플로우 '{}' 적재 완료 — states: {}, transitions: {}",
            dto.key,
            dto.states.size,
            dto.transitions.size,
        )
    }
}

