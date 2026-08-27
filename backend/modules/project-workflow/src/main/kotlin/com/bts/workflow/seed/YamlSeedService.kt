// 표준 4 워크플로우 YAML 시드 — 해당 key 의 workflow 행이 없을 때만 삽입한다 (fail-fast 부팅 차단)

package com.bts.workflow.seed

import com.bts.workflow.domain.TransitionKind
import com.bts.workflow.engine.WorkflowPostActionFactory
import com.bts.workflow.engine.WorkflowValidatorFactory
import com.bts.workflow.jooq.tables.Statuses.Companion.STATUSES
import com.bts.workflow.jooq.tables.WorkflowPostActions.Companion.WORKFLOW_POST_ACTIONS
import com.bts.workflow.jooq.tables.WorkflowStates.Companion.WORKFLOW_STATES
import com.bts.workflow.jooq.tables.WorkflowStatuses.Companion.WORKFLOW_STATUSES
import com.bts.workflow.jooq.tables.WorkflowTransitions.Companion.WORKFLOW_TRANSITIONS
import com.bts.workflow.jooq.tables.WorkflowValidators.Companion.WORKFLOW_VALIDATORS
import com.bts.workflow.jooq.tables.Workflows.Companion.WORKFLOWS
import com.bts.workflow.repository.required
import com.bts.workflow.scheme.repository.SchemeIssueTypeMappingRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.konform.validation.Validation
import io.konform.validation.jsonschema.minItems
import io.konform.validation.jsonschema.minLength
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
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
 * @property transitions 전환 목록.
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
 * YAML 전환 항목 DTO.
 *
 * @property from 출발 상태 키. `kind` 가 `GLOBAL`·`INITIAL` 이면 **적지 않는다** — 출발 상태가
 *   없다는 것이 그 두 종류의 정의다 ([TransitionKind]). 미정의 시 null.
 * @property to 도착 상태 키
 * @property name 전환 이름
 * @property kind 전환 종류. `NORMAL`(기본)·`GLOBAL`·`INITIAL`. 모르는 값이면 부팅을 막는다.
 * @property validators 전환 전 검증 게이트 목록. 미정의 시 빈 리스트.
 * @property postActions 전환 후 자동 처리 목록. YAML 키는 postActions (camelCase). 미정의 시 빈 리스트.
 */
data class TransitionYamlDto(
    val from: String? = null,
    val to: String = "",
    val name: String = "",
    val kind: String = TransitionKind.NORMAL.name,
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
 * 부팅 완료 후 [ApplicationReadyEvent] 로 classpath:workflows/ 아래 4개 YAML 을 읽고,
 * **해당 key 의 workflow 행이 없을 때만** 삽입한다. 이미 있으면 손대지 않는다.
 *
 * YAML 파싱/검증 실패, 또는 미지원 validator/postAction type 감지 시 [IllegalStateException] 을 던져
 * 부팅을 차단한다 (fail-fast). validator/postAction type 검증은 DB INSERT 전에 factory dry-run 으로 한다.
 *
 * ### 정본은 DB 다 (ADR 2026-08-18-workflow-db-as-source-of-truth)
 * 종전에는 YAML 과 DB 를 dirty-diff 비교해 다르면 삭제 후 재삽입했다. 그 경로가 운영자의 DB 수정을
 * 재기동마다 되돌렸고, 그래서 워크플로우를 화면에서 편집할 수 없었다. 이제 YAML 은 **빈 DB 를 채우는
 * 최초 1회 부트스트랩**이고, 되돌림은 「기본값으로 복원」(로드맵 PR 6)이 명시적으로 한다.
 * 삽입한 워크플로우에는 `origin='SEED'` 를 표기해 복원 대상을 식별한다 (ADR D3).
 *
 * 부작용 — 시드 YAML 을 고쳐 배포해도 기존 DB 에는 반영되지 않는다. 의도된 동작이다.
 *
 * ### 전역 상태 카탈로그 이중 기록 (FR-WF-04)
 * 삽입 시 `workflow_states` 와 `statuses`/`workflow_statuses` 를 같은 트랜잭션에서 함께 기록한다.
 * 전자는 `workflow_transitions` 의 FK 대상이라 아직 필요하고, 후자가 없으면 빈 DB(시드가 채움)와
 * 기존 DB(V204 백필이 채움)의 카탈로그 상태가 갈라진다.
 *
 * ### post-action 공존 정책 (FR-NT-05 D6)
 * post-action 은 런타임 API(PostActionAdminService)로만 관리된다. 시드가 기존 워크플로우를 지우지
 * 않게 되면서 「YAML structural 변경 → CASCADE 로 런타임 post-action 소실」 경로도 함께 사라졌다.
 *
 * ### default 매핑 백필 (R6)
 * 빈 DB 최초 부팅용 `workflow_scheme_issue_type_mappings` 기본 매핑 보강은 [seedAll] 말미의
 * [SchemeIssueTypeMappingRepository.repairDefaultMappings] 가 계속 담당한다.
 *
 * ### `TooManyFunctions` 억제 사유
 * FR-WF-07 이 「기본값으로 복원」의 읽기 창구([loadStandardYaml])를 얹어 함수 수가 detekt 한도(11)를
 * 넘었다. 근본 해결은 이 클래스를 「부팅 시딩」과 「YAML 읽기」로 쪼개는 것이고, 그것은 시딩 로직
 * 전체를 재배치하는 별도 작업이다 — 이 PR 의 범위(초안·발행)와 섞으면 두 변경의 회귀 원인이
 * 구분되지 않는다. 전역 임계값은 건드리지 않는다(`WorkflowWriteRepository` 와 같은 판단).
 *
 * @param dsl jOOQ DSLContext. 전환/상태/validator/postAction 직접 INSERT 에 사용한다.
 * @param resourceLoader classpath YAML 파일 접근용 Spring ResourceLoader.
 * @param yamlMapper YAML 파일 역직렬화용 Jackson ObjectMapper (YAMLFactory 기반).
 * @param validatorFactory validator type dry-run 검증용 팩토리. 미지원 type 에 [IllegalArgumentException] 을 던진다.
 * @param postActionFactory postAction type dry-run 검증용 팩토리. 미지원 type 에 [IllegalArgumentException] 을 던진다.
 * @param mappingRepository 스킴-이슈타입 매핑 기록→재연결 및 R6 default 매핑 백필용 리포지토리.
 *   Spring 컨텍스트에서는 등록된 Bean 이 주입된다. 손수 생성한 인스턴스는 `@Repository` 프록시 밖이라
 *   향후 `@Transactional` 우회를 잠복시킬 수 있어 기본값을 두지 않고 필수 주입으로 강제한다.
 */

@Suppress("TooManyFunctions")
@Service
class YamlSeedService(
    private val dsl: DSLContext,
    private val resourceLoader: ResourceLoader,
    private val validatorFactory: WorkflowValidatorFactory,
    private val postActionFactory: WorkflowPostActionFactory,
    private val mappingRepository: SchemeIssueTypeMappingRepository,
) : StandardWorkflowDefaults {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 시드가 적재한 워크플로우의 출처 표기. `workflows.origin` CHECK(SEED|CUSTOM) 과 V205 의 UPDATE 대상이 같은 값이다. */
    private val seedOrigin = "SEED"

    /**
     * YAML 워크플로우 파일 역직렬화 전용 매퍼 — YAMLFactory 기반, 내부 생성.
     *
     * Spring `@Bean ObjectMapper`(YAMLFactory)로 노출하면 Spring Boot 의 기본 JSON ObjectMapper 가
     * `@ConditionalOnMissingBean(ObjectMapper)` 로 backoff 되어, HTTP 메시지 컨버터가 이 YAML 매퍼를
     * 사용해 **모든 REST 응답이 YAML 로 직렬화되는 회귀**(조립 앱 전 API 파손)를 유발한다. 따라서
     * Bean 으로 노출하지 않고 이 서비스 내부에서만 보유한다.
     */
    private val yamlMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

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
            insertIfAbsent(dto)
        }

        // R6 백필 — 빈 DB 최초 부팅 시 표준 4 스킴 default 매핑을 보강한다. 위치가 load-bearing이다:
        // insertIfAbsent 는 이미 있는 워크플로우를 건너뛰므로 루프 안에 두면 기존 DB(EC-9)에서 안 돈다.
        // 루프 밖(4 워크플로우 처리 완료 후) 1회 호출해 매 seedAll() 마다 dangling/누락을 보정한다.
        mappingRepository.repairDefaultMappings()

        // 상태 카탈로그 보정 — 같은 자리에서 같은 이유(유실 보정)로 돈다. 아래 KDoc 참조.
        repairStatusCatalog()

        log.info("YamlSeedService 완료 — 표준 4 워크플로우 시드 점검 끝")
    }

    /**
     * `workflow_states` 를 기준으로 전역 상태 카탈로그의 **빠진 행만** 채운다.
     *
     * ### 왜 필요한가
     * 이 서비스는 「workflow 행이 없을 때만 삽입」한다. 그래서 workflow 행은 있는데 카탈로그만 비어 있는
     * 상태에 빠지면 재기동으로는 영영 복구되지 않는다. 그런 상태는 실제로 만들어질 수 있다 —
     * 이 PR 배포 후 앱만 이전 버전으로 롤백하면 구 코드의 `deleteWorkflow` → 재삽입이 돌고,
     * `workflow_statuses` 가 `workflows` FK CASCADE 로 함께 사라진다. 다시 롤포워드해도 workflow 행은
     * 이미 있으므로 삽입 경로를 타지 않는다.
     *
     * CI 판별식은 운영 DB 를 보지 않으므로 이 상태는 **조용하다.** 그래서 부팅마다 스스로 보정한다.
     *
     * 선례는 바로 위의 [SchemeIssueTypeMappingRepository.repairDefaultMappings] 다 — 같은 자리에서
     * 같은 이유(빈 DB·유실 보정)로 루프 밖 1회 돈다. 새 패턴이 아니다.
     *
     * INSERT 만 한다. `ON CONFLICT DO NOTHING` 이라 멀쩡한 DB 에서는 아무 행도 늘지 않는다.
     * 로직은 V204 백필과 같다 — 마이그레이션이 한 번 하는 일을 런타임이 반복 가능하게 한다.
     */
    private fun repairStatusCatalog() {
        val insertedStatuses =
            dsl.insertInto(STATUSES)
                .columns(STATUSES.KEY, STATUSES.NAME, STATUSES.CATEGORY)
                .select(
                    dsl.selectDistinct(WORKFLOW_STATES.KEY, WORKFLOW_STATES.NAME, WORKFLOW_STATES.CATEGORY)
                        .on(WORKFLOW_STATES.KEY)
                        .from(WORKFLOW_STATES)
                        .orderBy(WORKFLOW_STATES.KEY),
                )
                // V206 이 statuses.key 를 부분 유니크 인덱스(WHERE deleted_at IS NULL)로 바꿨다.
                // 술어를 함께 적지 않으면 PostgreSQL 이 추론할 제약을 못 찾아
                // "there is no unique or exclusion constraint matching the ON CONFLICT specification" 로 죽는다.
                .onConflict(STATUSES.KEY)
                .where(STATUSES.DELETED_AT.isNull)
                .doNothing()
                .execute()

        val insertedLinks =
            dsl.insertInto(WORKFLOW_STATUSES)
                .columns(
                    WORKFLOW_STATUSES.WORKFLOW_ID,
                    WORKFLOW_STATUSES.STATUS_ID,
                    WORKFLOW_STATUSES.DISPLAY_ORDER,
                )
                .select(
                    dsl.select(WORKFLOW_STATES.WORKFLOW_ID, STATUSES.ID, WORKFLOW_STATES.DISPLAY_ORDER)
                        .from(WORKFLOW_STATES)
                        .join(STATUSES)
                        .on(STATUSES.KEY.eq(WORKFLOW_STATES.KEY)),
                )
                .onConflict(WORKFLOW_STATUSES.WORKFLOW_ID, WORKFLOW_STATUSES.STATUS_ID)
                .doNothing()
                .execute()
        if (insertedStatuses > 0 || insertedLinks > 0) {
            log.warn(
                "상태 카탈로그 보정 — statuses {}행 · workflow_statuses {}행을 되채웠다. " +
                    "정상 상태에서는 0행이다 (앱만 롤백한 이력이 있는지 확인할 것).",
                insertedStatuses,
                insertedLinks,
            )
        }
    }

    /**
     * YAML 바이트를 파싱하고 Konform 검증 및 validator/postAction type dry-run 검증을 수행한다.
     *
     * 파싱 실패, Konform 검증 실패, 또는 미지원 validator/postAction type 감지 시
     * [IllegalStateException] 을 던져 부팅을 차단한다 (fail-fast).
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

        dryRunValidatorAndPostActionTypes(dto)

        return dto
    }

    /**
     * 워크플로우 YAML 의 모든 validator/postAction type 에 대해 factory dry-run 을 수행한다.
     *
     * 미지원 type 이나 필수 config 키 누락 시 [IllegalStateException] 을 던진다.
     * 생성된 인스턴스는 버린다 — DB INSERT 는 기존 로직에서 별도 수행한다.
     *
     * @param dto 파싱된 워크플로우 YAML DTO
     * @throws IllegalStateException 미지원 type 또는 필수 config 키 누락 시
     */
    private fun dryRunValidatorAndPostActionTypes(dto: WorkflowYamlDto) {
        for (transition in dto.transitions) {
            for (validator in transition.validators) {
                try {
                    validatorFactory.create(validator.type, validator.config)
                } catch (ex: IllegalArgumentException) {
                    throw IllegalStateException(
                        "워크플로우 '${dto.key}' 시드 실패: validator type '${validator.type}' — ${ex.message}",
                        ex,
                    )
                }
            }
            for (postAction in transition.postActions) {
                try {
                    postActionFactory.create(postAction.type, postAction.config)
                } catch (ex: IllegalArgumentException) {
                    throw IllegalStateException(
                        "워크플로우 '${dto.key}' 시드 실패: postAction type '${postAction.type}' — ${ex.message}",
                        ex,
                    )
                }
            }
        }
    }

    /**
     * 해당 key 의 workflow 행이 **없을 때만** 삽입한다. 이미 있으면 아무것도 하지 않는다.
     *
     * ### 왜 비교하지 않는가
     * 종전에는 YAML 과 DB 를 dirty-diff 비교해 다르면 `deleteWorkflow` → 재삽입했다. 그 경로가
     * **운영자의 DB 수정을 재기동마다 되돌렸다.** DB 가 워크플로우 정의의 정본이 되면서
     * (ADR 2026-08-18-workflow-db-as-source-of-truth D1·D2) YAML 은 빈 DB 를 채우는 최초 1회
     * 부트스트랩 전용이 됐다. 되돌림이 필요하면 「기본값으로 복원」(로드맵 PR 6)이 명시적으로 한다.
     *
     * 이 결정의 부작용 — 시드 YAML 을 고쳐 배포해도 **기존 DB 에는 반영되지 않는다.** 의도된 동작이다.
     */
    private fun insertIfAbsent(dto: WorkflowYamlDto) {
        if (workflowRowExists(dto.key)) {
            log.debug("워크플로우 '{}' — 이미 있음, 건너뜀", dto.key)
            return
        }
        log.info("워크플로우 '{}' — 신규 적재", dto.key)
        insertWorkflow(dto)
    }

    /**
     * `workflows` 행의 **존재만** 확인한다. aggregate 를 복원하지 않는다.
     *
     * ### 왜 findByKey 를 쓰지 않는가
     * `findByKey` 는 [com.bts.workflow.domain.Workflow] 를 만들고 그 invariant 가 「상태가 하나 이상」을
     * 요구한다. 읽기 경로가 전역 카탈로그 2단으로 옮겨진 뒤에는 **`workflow_statuses` 가 비어 있는
     * 중간 상태**(구 코드로 롤백했다가 롤포워드한 이력 등)에서 그 복원이 `IllegalArgumentException` 으로
     * 죽는다. 그러면 바로 그 중간 상태를 되채우려던 보정 경로에 **닿기도 전에** 시드가 실패한다.
     *
     * 존재 판정에는 행 하나만 있으면 된다. 복원 비용도 없다.
     */
    private fun workflowRowExists(key: String): Boolean =
        dsl.fetchExists(
            dsl.selectOne().from(WORKFLOWS).where(WORKFLOWS.KEY.eq(key)),
        )

    /**
     * [WorkflowYamlDto] 를 workflows / workflow_states / workflow_transitions /
     * workflow_validators / workflow_post_actions 에 삽입한다.
     *
     * 삽입 순서.
     * 1. workflows 행 삽입 → workflow UUID 획득
     * 2. workflow_states 행 삽입 → state key → UUID 매핑 구성
     * 3. workflow_transitions 행 삽입 → transition UUID 획득 후 validators/post_actions 삽입
     *
     * @return 새로 발급된 workflows.id (UUID).
     */
    private fun insertWorkflow(dto: WorkflowYamlDto): java.util.UUID {
        // 1. workflows 삽입
        val workflowId =
            dsl.insertInto(WORKFLOWS)
                .set(WORKFLOWS.KEY, dto.key)
                .set(WORKFLOWS.NAME, dto.name)
                .set(WORKFLOWS.DESCRIPTION, dto.description)
                .set(WORKFLOWS.ORIGIN, seedOrigin)
                .returningResult(WORKFLOWS.ID)
                .fetchOne()
                ?.value1()
                ?: error("workflows 삽입 실패: ${dto.key}")

        // 2. 상태 적재 — workflow_states 와 전역 카탈로그를 **같은 루프에서** 기록한다.
        //    두 기록이 갈라지면 StatusCatalogParityTest 가 red 를 낸다.
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
            insertStatusForWorkflow(workflowId, state)
        }

        // 3. workflow_transitions 삽입 + validators/post_actions 삽입
        insertTransitions(dto, workflowId, stateKeyToId)

        logSeeded(dto, workflowId)

        return workflowId
    }

    /**
     * 전환과 그에 딸린 validator / post_action 을 심는다.
     *
     * ### 신 컬럼이 정본이다 (V207 · 3단 분할 2단계)
     * V207 이 전환의 출발·도착을 워크플로우 종속 `workflow_states` 에서 전역 카탈로그 편성
     * `workflow_statuses` 로 재지정했다. 그 재지정 백필은 **마이그레이션 시점에 있던 행**만 손댄다 —
     * 그 뒤에 시드가 심는 행은 시드가 직접 신 컬럼을 채워야 한다. 채우지 않으면 빈 DB 로 부팅한
     * 사이트의 표준 워크플로우 전환이 전부 신 컬럼 NULL 로 남아, 3단계에서 NOT NULL 을 걸 수 없다.
     *
     * [TransitionKind.NORMAL] 은 구 컬럼도 함께 채운다 — 시드는 `workflow_states` 행을 직접 만드는
     * 두 경로 중 하나라 가리킬 대상이 실재하고, 3단계(`workflow_states` DROP)까지 롤백 자리를 지킨다.
     * 신·구가 **같은 상태**를 가리키므로 읽기 폴백이 어느 쪽을 보든 결과가 같다.
     *
     * ### GLOBAL·INITIAL 은 구 컬럼을 비워 둔다
     * 출발지가 없으니 `from_state_id` 는 애초에 채울 값이 없고, `to_state_id` 도 **일부러 비운다** —
     * V207 ⑨ 가 기존 DB 에 심은 INITIAL 행이 정확히 그 모양(구 컬럼 둘 다 NULL)이기 때문이다.
     * 여기서만 채우면 빈 DB 로 올린 사이트와 기존 사이트의 같은 전환이 서로 다른 모양이 된다.
     * V207 ⑧ 이 두 구 컬럼의 NOT NULL 을 풀어 NULL 이 적법하고, 읽기는 신 컬럼이 차 있으면
     * 구 컬럼을 보지 않는다(`WorkflowRepository` 폴백 순서).
     *
     * ### `display_order` 를 여기서 지정하지 않는 이유
     * INITIAL 이 받아야 할 값은 0 이고
     * (V207 ⑨ · [com.bts.workflow.repository.WorkflowWriteRepository.insertTransition]),
     * 컬럼 DEFAULT 가 이미 0 이라 그대로 두면 맞는다. 시드가 심는 NORMAL 전환이 전부 0 으로 남는
     * 것은 별개의 선재 결함(장부 후보)이며 이 함수의 변경이 그것을 늘리지 않는다.
     *
     * @param dto 파싱·검증이 끝난 워크플로우 정의
     * @param workflowId 방금 삽입한 `workflows.id`
     * @param stateKeyToId 상태 키 → 구형 `workflow_states.id`
     */
    private fun insertTransitions(
        dto: WorkflowYamlDto,
        workflowId: java.util.UUID,
        stateKeyToId: Map<String, java.util.UUID>,
    ) {
        val stateKeyToCompositionId = fetchStatusCompositionIds(workflowId)
        for (transition in dto.transitions) {
            val transitionId =
                insertTransitionRow(dto, workflowId, transition, stateKeyToId, stateKeyToCompositionId)
            insertValidators(transitionId, transition.validators)
            insertPostActions(transitionId, transition.postActions)
        }
    }

    /**
     * 전환 1행을 심고 새 `workflow_transitions.id` 를 돌려준다. 컬럼 정책은 [insertTransitions] KDoc 참조.
     *
     * @param dto 파싱·검증이 끝난 워크플로우 정의
     * @param workflowId 방금 삽입한 `workflows.id`
     * @param transition 심을 전환 1건
     * @param stateKeyToId 상태 키 → 구형 `workflow_states.id`
     * @param compositionIds 상태 키 → 전역 카탈로그 편성 `workflow_statuses.id`
     */
    private fun insertTransitionRow(
        dto: WorkflowYamlDto,
        workflowId: java.util.UUID,
        transition: TransitionYamlDto,
        stateKeyToId: Map<String, java.util.UUID>,
        compositionIds: Map<String, java.util.UUID>,
    ): java.util.UUID {
        val kind = transition.kindOrFail(dto.key)
        val fromKey = transition.fromStateKeyOrFail(dto.key, kind)
        val originless = kind != TransitionKind.NORMAL
        return dsl.insertInto(WORKFLOW_TRANSITIONS)
            .set(WORKFLOW_TRANSITIONS.WORKFLOW_ID, workflowId)
            .set(WORKFLOW_TRANSITIONS.FROM_STATE_ID, fromKey?.let { stateKeyToId.stateIdOf(dto.key, it) })
            .set(
                WORKFLOW_TRANSITIONS.TO_STATE_ID,
                if (originless) null else stateKeyToId.stateIdOf(dto.key, transition.to),
            )
            .set(WORKFLOW_TRANSITIONS.FROM_STATUS_ID, fromKey?.let { compositionIds.stateIdOf(dto.key, it) })
            .set(WORKFLOW_TRANSITIONS.TO_STATUS_ID, compositionIds.stateIdOf(dto.key, transition.to))
            .set(WORKFLOW_TRANSITIONS.KIND, kind.name)
            .set(WORKFLOW_TRANSITIONS.NAME, transition.name)
            .returningResult(WORKFLOW_TRANSITIONS.ID)
            .fetchOne()
            ?.value1()
            ?: error("workflow_transitions 삽입 실패: ${dto.key}/${fromKey ?: kind.name}->${transition.to}")
    }

    /**
     * 그 워크플로우의 상태 키 → `workflow_statuses.id` 매핑. 전환의 신 FK 가 가리키는 것이 이 id 다.
     *
     * [insertStatusForWorkflow] 의 INSERT 는 `ON CONFLICT DO NOTHING` 이라 편성 행의 id 를 돌려주지
     * 않는다(이미 있던 행이면 삽입 자체가 없다). 상태 루프가 끝난 뒤 한 번 되읽는 편이 조회 1회로
     * 끝나고, 「기존 행 재사용」 경로에서도 같은 값을 준다.
     *
     * @param workflowId 대상 `workflows.id`
     */
    private fun fetchStatusCompositionIds(workflowId: java.util.UUID): Map<String, java.util.UUID> =
        dsl.select(STATUSES.KEY, WORKFLOW_STATUSES.ID)
            .from(WORKFLOW_STATUSES)
            .join(STATUSES).on(STATUSES.ID.eq(WORKFLOW_STATUSES.STATUS_ID))
            .where(WORKFLOW_STATUSES.WORKFLOW_ID.eq(workflowId))
            .and(STATUSES.DELETED_AT.isNull)
            .fetch()
            .associate { record -> record.required(STATUSES.KEY) to record.required(WORKFLOW_STATUSES.ID) }

    /**
     * 적재 결과를 한 줄로 남긴다. 카탈로그 건수를 포함해 **부팅 로그만 보고** `statuses`/`workflow_statuses`
     * 가 채워졌는지 알 수 있게 한다 (게이트 1 리뷰 R8).
     */
    private fun logSeeded(
        dto: WorkflowYamlDto,
        workflowId: java.util.UUID,
    ) {
        val catalogRows = dsl.fetchCount(WORKFLOW_STATUSES, WORKFLOW_STATUSES.WORKFLOW_ID.eq(workflowId))

        log.info(
            "워크플로우 '{}' 적재 완료 — states: {}, transitions: {}, validators: {}, postActions: {}, " +
                "workflow_statuses: {}, statuses(전역 누적): {}",
            dto.key,
            dto.states.size,
            dto.transitions.size,
            dto.transitions.sumOf { it.validators.size },
            dto.transitions.sumOf { it.postActions.size },
            catalogRows,
            dsl.fetchCount(STATUSES),
        )
    }

    /**
     * 상태 1건을 전역 카탈로그(`statuses`)와 워크플로우 연결(`workflow_statuses`)에 기록한다.
     *
     * ### 왜 ON CONFLICT DO NOTHING 인가
     * 상태 키는 전역 유일이다. 이미 있는 키면 **기존 행을 그대로 쓴다** — 운영자가 바꾼 이름을
     * 시드가 덮지 않는다(ADR 2026-08-18-workflow-global-status-catalog D3 「이름은 자유, 키는 불변」).
     */
    private fun insertStatusForWorkflow(
        workflowId: java.util.UUID,
        state: StateYamlDto,
    ) {
        dsl.insertInto(STATUSES)
            .set(STATUSES.KEY, state.key)
            .set(STATUSES.NAME, state.name)
            .set(STATUSES.CATEGORY, state.category)
            // V206 부분 유니크 인덱스 술어. 위 주석과 같은 이유다.
            .onConflict(STATUSES.KEY)
            .where(STATUSES.DELETED_AT.isNull)
            .doNothing()
            .execute()

        dsl.insertInto(WORKFLOW_STATUSES)
            .columns(WORKFLOW_STATUSES.WORKFLOW_ID, WORKFLOW_STATUSES.STATUS_ID, WORKFLOW_STATUSES.DISPLAY_ORDER)
            .select(
                dsl.select(DSL.value(workflowId), STATUSES.ID, DSL.value(state.displayOrder))
                    .from(STATUSES)
                    .where(STATUSES.KEY.eq(state.key)),
            )
            .onConflict(WORKFLOW_STATUSES.WORKFLOW_ID, WORKFLOW_STATUSES.STATUS_ID)
            .doNothing()
            .execute()
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
     * Konform 검증은 [parseAndValidate] 에서 수행하므로 호출 전 검증이 완료된 DTO 를 전달해야 한다.
     *
     * @param dto 파싱 및 검증이 완료된 [WorkflowYamlDto]
     */
    @Transactional
    fun seedSingle(dto: WorkflowYamlDto) {
        dryRunValidatorAndPostActionTypes(dto)
        insertIfAbsent(dto)
    }

    /**
     * 표준 워크플로우의 YAML 원본을 읽어 **검증된** DTO 로 돌려준다. DB 에는 아무것도 쓰지 않는다.
     *
     * 「기본값으로 복원」(FR-WF-07 · ADR `2026-08-18-workflow-db-as-source-of-truth` §D4)이 쓴다.
     * ADR 이 YAML 을 지우지 않고 남긴 이유가 둘인데 — 새 환경의 부트스트랩 소스와 **복원 기준** —
     * 이 메서드가 뒤쪽이다.
     *
     * 파싱을 여기서 내주는 것은 복원 경로가 YAML 파서를 한 벌 더 갖지 않게 하기 위해서다.
     * 두 벌이 되면 Konform 검증과 validator/post-action type dry-run 이 한쪽에만 붙는 날이 온다.
     *
     * @param key 워크플로우 키. 표준 4종이 아니면 null 을 돌려준다 — 복원할 기본값이 없다는 뜻이다.
     * @throws IllegalStateException YAML 이 있는데 파싱·검증에 실패했을 때. 조용히 null 로 접지 않는다
     */
    override fun loadStandardYaml(key: String): WorkflowYamlDto? {
        val resource =
            if (key in standardWorkflowKeys) {
                resourceLoader.getResource("classpath:workflows/$key.yaml").takeIf { it.exists() }
            } else {
                null
            }

        return resource?.let { parseAndValidate(key, it.inputStream.use { stream -> stream.readBytes() }) }
    }
}

/**
 * 상태 키에 대응하는 id 를 돌려준다. 구형 `workflow_states.id` 맵과 전역 카탈로그 편성
 * `workflow_statuses.id` 맵이 **같은 키 집합**을 갖는다는 전제를 여기 한 곳에서 확인한다.
 *
 * 없으면 조용히 NULL 을 넣지 않고 멈춘다 — NULL 이 들어가면 그 전환이 새 읽기 경로에서 사라진다.
 *
 * @param workflowKey 오류 메시지에 실을 워크플로우 키
 * @param stateKey 찾을 상태 키
 */
private fun Map<String, java.util.UUID>.stateIdOf(
    workflowKey: String,
    stateKey: String,
): java.util.UUID =
    this[stateKey]
        ?: error("전환 상태 키 '$stateKey' 가 states 에 없음: $workflowKey")

/**
 * YAML 의 `kind` 문자열을 [TransitionKind] 로 바꾼다. 모르는 값이면 부팅을 멈춘다 (fail-fast).
 *
 * 오타를 조용히 `NORMAL` 로 떨어뜨리면 최초 전환이 보통 전환이 되어 이슈 생성 진입 상태가 사라진다.
 * 그 결함은 이슈를 만들어 봐야 드러나므로, 부팅을 실패시키는 편이 훨씬 싸다.
 *
 * @param workflowKey 오류 메시지에 실을 워크플로우 키
 */
private fun TransitionYamlDto.kindOrFail(workflowKey: String): TransitionKind =
    TransitionKind.entries.firstOrNull { it.name == kind }
        ?: error("전환 '$name' 의 kind '$kind' 를 모른다: $workflowKey — NORMAL·GLOBAL·INITIAL 중 하나여야 한다")

/**
 * 출발 상태 키를 준다. [TransitionKind.NORMAL] 은 반드시 있고 `GLOBAL`·`INITIAL` 은 없어야 한다.
 *
 * [com.bts.workflow.domain.Workflow.of] invariant 5 를 **시드 시점으로 당겨** 검사한다. 어긋난 행을
 * 심으면 그 워크플로우는 조회할 때마다 aggregate 복원이 통째로 실패한다 — 심는 쪽에서 막는 편이 싸다.
 *
 * @param workflowKey 오류 메시지에 실을 워크플로우 키
 * @param kind 이 전환의 종류
 */
private fun TransitionYamlDto.fromStateKeyOrFail(
    workflowKey: String,
    kind: TransitionKind,
): String? {
    val declared = from?.takeIf { it.isNotBlank() }
    if (kind != TransitionKind.NORMAL) {
        check(declared == null) { "${kind.name} 전환 '$name' 은 출발 상태를 가질 수 없다: $workflowKey" }
        return null
    }
    return declared ?: error("NORMAL 전환 '$name' 에 from 이 없다: $workflowKey")
}
