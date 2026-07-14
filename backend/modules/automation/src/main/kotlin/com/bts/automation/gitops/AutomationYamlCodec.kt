// 자동화 규칙 YAML 직렬화/역직렬화 codec — DB 무관 순수 변환 (FR-AT-06 GitOps Task 1)

package com.bts.automation.gitops

import com.bts.automation.domain.Action
import com.bts.automation.domain.ActionType
import com.bts.automation.domain.Condition
import com.bts.automation.domain.TriggerType
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.util.UUID

/**
 * export 시 [AutomationYamlCodec.toYaml] 에 전달하는 규칙 뷰(codec 전용 입력 타입).
 *
 * [com.bts.automation.domain.AutomationRule] 중 GitOps로 내보내지 않는 필드
 * (webhookTokenHash·nextFireAt·version·createdBy/createdAt/updatedAt)를 애초에 필드로 갖지 않는다 —
 * 타입 자체로 노출이 불가능하게 막는 방어([com.bts.automation.adapter.web.dto.AutomationRuleResponse]가
 * 토큰 필드를 두지 않는 선례와 동형, NFR3).
 *
 * @property id 규칙 식별자.
 * @property name 규칙 표시 이름.
 * @property enabled 활성화 여부.
 * @property actorUserId 액션 실행 주체(rule actor).
 * @property triggerType 트리거 타입.
 * @property triggerConfig 트리거별 설정 JSON 문자열(도메인 표현 그대로).
 * @property condition 조건 게이트. `null`이면 조건 없음.
 * @property actions 순차 실행 액션 목록(타입화된 도메인 표현).
 */
data class ExportRuleInput(
    val id: UUID,
    val name: String,
    val enabled: Boolean,
    val actorUserId: UUID,
    val triggerType: TriggerType,
    val triggerConfig: String,
    val condition: Condition?,
    val actions: List<Action>,
)

/**
 * [AutomationYamlCodec.fromYaml] 파싱 결과 — 프로젝트 키 + 규칙별 import 커맨드 목록.
 *
 * @property projectKey YAML 문서의 `projectKey` 값. 경로 `{projectKey}` 와의 일치 검증은 이 codec
 *   밖의 import 서비스 책임이다.
 * @property rules 입력 순서를 보존한 규칙별 import 커맨드 목록.
 */
data class ParsedImportDocument(
    val projectKey: String,
    val rules: List<ImportRuleCommand>,
)

/**
 * 규칙 1건의 import 커맨드 — 도메인 파서(`TriggerConfig.validate`/`Action.fromJson`/`Condition.fromJson`)가
 * 바로 소비할 수 있는 형태로 wire 비대칭을 이미 흡수한 상태다(`config`/`condition` 이 JSON 문자열).
 *
 * @property id 규칙 식별자. `null`이면 YAML에 id가 없던 경우(신규 UUID 생성은 import 서비스 책임).
 * @property name 규칙 표시 이름.
 * @property enabled 활성화 여부.
 * @property actorUserId 액션 실행 주체. `null`이면 YAML에 없던 경우(import 호출자로 기본은 import
 *   서비스 책임).
 * @property triggerType 트리거 타입.
 * @property triggerConfig 트리거별 설정 JSON 문자열(`TriggerConfig.validate` 입력 형식).
 * @property condition 조건 게이트 JSON 문자열(`Condition.fromJson` 입력 형식). `null`이면 YAML에 조건이
 *   없던 경우(EC6 — 조건 없음 또는 기존 조건 유지, 시맨틱 판단은 import 서비스 책임).
 * @property actions 순차 실행 액션 커맨드 목록.
 */
data class ImportRuleCommand(
    val id: UUID?,
    val name: String,
    val enabled: Boolean,
    val actorUserId: UUID?,
    val triggerType: TriggerType,
    val triggerConfig: String,
    val condition: String?,
    val actions: List<ImportActionCommand>,
)

/**
 * 액션 1건의 import 커맨드.
 *
 * @property type 액션 타입.
 * @property config 액션 설정 JSON 문자열(`Action.fromJson` 입력 형식).
 */
data class ImportActionCommand(
    val type: ActionType,
    val config: String,
)

/**
 * YAML 문서가 형식/스키마 버전을 위반할 때 던진다.
 *
 * `gitops` 패키지는 `domain` 패키지 밖이라 `sealed class AutomationDomainException`의 서브타입으로
 * 선언할 수 없다(Kotlin sealed 서브클래스는 동일 패키지 제약). 따라서 이 예외는 [RuntimeException]을
 * 직접 상속하며, HTTP 상태 코드 매핑은 이 codec을 소비하는 컨트롤러/서비스 계층(Task 5 scope) 책임이다.
 * 메시지에는 사용자가 보낸 원본 YAML 값을 echo하지 않는다(민감정보 누출 방지, [Condition] 예외 선례 동형).
 *
 * @param message 위반 내용을 설명하는 일반 메시지
 * @param cause 원인이 된 예외. 없으면 `null`(기본값)
 */
class AutomationYamlInvalidException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

private const val MSG_BLANK_YAML = "YAML 본문이 비어 있습니다."
private const val MSG_PARSE_FAILED = "YAML 문서를 파싱할 수 없습니다."
private const val MSG_UNSUPPORTED_VERSION = "지원하지 않는 YAML 스키마 버전입니다."

// actionConfigMap 이 방출하는 YAML 설정 맵의 키 이름 — Action 서브타입별 config 필드명과 1:1 대응
// ([com.bts.automation.domain.Action.fromJson] 의 파싱 대상 키와 동일해야 wire 비대칭 흡수가 성립한다).
private const val FIELD_FIELD = "field"
private const val FIELD_VALUE = "value"
private const val FIELD_ASSIGNEE_ID = "assigneeId"
private const val FIELD_BODY = "body"
private const val FIELD_URL = "url"
private const val FIELD_METHOD = "method"
private const val FIELD_HEADERS = "headers"

/**
 * 자동화 규칙을 YAML로 직렬화/역직렬화하는 순수 codec(FR-AT-06 GitOps).
 *
 * DB/Spring 컨텍스트에 의존하지 않는다 — export 서비스가 하이드레이션한 [ExportRuleInput] 목록을
 * 받아 YAML 텍스트를 만들고([toYaml]), import 서비스가 받은 YAML 텍스트를 [ParsedImportDocument] 로
 * 파싱한다([fromYaml]). 실제 upsert/저장/권한 검증은 이 codec 밖(Task 3/4/5 scope) 책임이다.
 *
 * Spring `@Bean` 으로 노출하지 않는다 — 이 클래스가 보유한 [yamlMapper] 를 Bean 으로 노출하면 Spring
 * Boot 의 기본 JSON `ObjectMapper` 가 `@ConditionalOnMissingBean` 으로 backoff 되어 **모든 REST 응답이
 * YAML 로 직렬화되는 회귀**를 유발한다(`YamlSeedService` 선례, [[custom-objectmapper-bean-yaml-response-regression]]).
 * 이 클래스를 Kotlin `object` 싱글턴으로 선언해 애초에 DI 대상이 아니게 만든다 — 소비자는
 * `AutomationYamlCodec.toYaml(...)` 처럼 정적으로 호출한다.
 */
object AutomationYamlCodec {
    /** 현재 지원하는 YAML 스키마 버전. [fromYaml] 은 이 값과 다른 `version` 을 거부한다. */
    const val SCHEMA_VERSION: Int = 1

    /**
     * YAML 파일 (역)직렬화 전용 매퍼 — [YAMLFactory] 기반, 이 object 내부에서만 보유한다.
     * 클래스 KDoc §Bean 노출 금지 참고.
     */
    private val yamlMapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

    /**
     * `triggerConfig`/action config/condition JSON 문자열 ↔ YAML 객체(Map) 변환 전용 매퍼.
     * [yamlMapper] 는 YAMLFactory 기반이라 순수 JSON 문자열 파싱/생성에는 별도 매퍼가 필요하다.
     */
    private val jsonMapper = ObjectMapper()

    /**
     * export용 규칙 목록을 GitOps YAML 스키마(v1) 텍스트로 직렬화한다.
     *
     * `rules` 순서를 그대로 보존한다(결정적 순서는 호출자 책임 — export 서비스가 createdAt→id로
     * 정렬한 뒤 전달한다). `triggerConfig`/`condition`/액션 `config` JSON 문자열은 각각 YAML 객체로
     * 방출된다(wire 비대칭 흡수, 클래스 KDoc 참고).
     *
     * @param projectKey 문서의 `projectKey` 값.
     * @param rules export할 규칙 목록(webhook 토큰/OCC version/nextFireAt 없는 뷰).
     * @return GitOps YAML 스키마(v1) 텍스트.
     * @throws AutomationYamlInvalidException `triggerConfig` 가 유효한 JSON 객체가 아닌 경우(도메인
     *   불변식상 발생하지 않아야 하는 방어적 케이스).
     */
    fun toYaml(
        projectKey: String,
        rules: List<ExportRuleInput>,
    ): String {
        val document =
            AutomationRulesYaml(
                version = SCHEMA_VERSION,
                projectKey = projectKey,
                rules = rules.map { it.toYamlRule() },
            )
        return yamlMapper.writeValueAsString(document)
    }

    /**
     * GitOps YAML 스키마(v1) 텍스트를 파싱해 [ParsedImportDocument] 로 변환한다.
     *
     * `trigger.config`/`condition`/액션 `config` YAML 객체를 각각 JSON 문자열로 직렬화해, 기존 도메인
     * 파서(`TriggerConfig.validate`/`Condition.fromJson`/`Action.fromJson`)가 바로 소비할 수 있는 형태로
     * wire 비대칭을 흡수한다. 실제 도메인 검증(화이트리스트·상한·타입별 형식)은 이 codec이 아니라 그
     * 파서들의 책임이다 — 여기서는 YAML 구조 자체(파싱 가능 여부·스키마 버전)만 검증한다.
     *
     * @param raw YAML 텍스트.
     * @return 프로젝트 키 + 규칙별 import 커맨드 목록(입력 순서 보존).
     * @throws AutomationYamlInvalidException 본문이 비어있거나 파싱에 실패하거나 `version` 이
     *   [SCHEMA_VERSION] 과 다른 경우. 메시지에 원본 YAML 값을 echo하지 않는다.
     */
    fun fromYaml(raw: String): ParsedImportDocument {
        val document = parseDocument(raw)
        if (document.version != SCHEMA_VERSION) {
            throw AutomationYamlInvalidException(
                "$MSG_UNSUPPORTED_VERSION (지원 버전: $SCHEMA_VERSION, 입력 버전: ${document.version})",
            )
        }
        return ParsedImportDocument(
            projectKey = document.projectKey,
            rules = document.rules.map { it.toImportCommand() },
        )
    }

    private fun parseDocument(raw: String): AutomationRulesYaml {
        if (raw.isBlank()) {
            throw AutomationYamlInvalidException(MSG_BLANK_YAML)
        }
        return try {
            yamlMapper.readValue(raw, AutomationRulesYaml::class.java)
        } catch (e: JsonProcessingException) {
            throw AutomationYamlInvalidException(MSG_PARSE_FAILED, e)
        }
    }

    private fun ExportRuleInput.toYamlRule(): YamlRule =
        YamlRule(
            id = id,
            name = name,
            enabled = enabled,
            actorUserId = actorUserId,
            trigger = YamlTrigger(type = triggerType, config = triggerConfig.jsonToConfigMap()),
            condition = condition?.toJson()?.jsonToConfigMap(),
            actions = actions.map { it.toYamlAction() },
        )

    private fun YamlRule.toImportCommand(): ImportRuleCommand =
        ImportRuleCommand(
            id = id,
            name = name,
            enabled = enabled,
            actorUserId = actorUserId,
            triggerType = trigger.type,
            triggerConfig = jsonMapper.writeValueAsString(trigger.config),
            condition = condition?.let { jsonMapper.writeValueAsString(it) },
            actions = actions.map { it.toImportActionCommand() },
        )

    private fun YamlAction.toImportActionCommand(): ImportActionCommand =
        ImportActionCommand(type = type, config = jsonMapper.writeValueAsString(config))

    private fun Action.toYamlAction(): YamlAction {
        return YamlAction(type = actionTypeOf(this), config = actionConfigMap(this))
    }

    /** [Action] 서브타입 → [ActionType] 매핑(YAML 방출 전용). [Action.fromJson] 과 1:1 역함수 관계. */
    private fun actionTypeOf(action: Action): ActionType =
        when (action) {
            is Action.SetFieldAction -> ActionType.SET_FIELD
            is Action.AssignAction -> ActionType.ASSIGN
            is Action.AddCommentAction -> ActionType.ADD_COMMENT
            is Action.CallWebhookAction -> ActionType.CALL_WEBHOOK
        }

    /**
     * [Action] 서브타입 → YAML 설정 맵 매핑(YAML 방출 전용). 응답 DTO 조립 시 쓰이는
     * `AutomationRuleResponses.kt` 의 `actionConfigOf` 와 매핑 형태가 같지만 목적(HTTP 응답 vs
     * YAML 방출)이 달라 별도로 둔다(같은 모듈 내 유사 매핑 중복은 그 파일의 기존 선례와 동형).
     */
    private fun actionConfigMap(action: Action): Map<String, Any?> =
        when (action) {
            is Action.SetFieldAction ->
                mapOf(
                    FIELD_FIELD to action.field,
                    FIELD_VALUE to jsonMapper.convertValue(action.value, Any::class.java),
                )
            is Action.AssignAction -> mapOf(FIELD_ASSIGNEE_ID to action.assigneeId)
            is Action.AddCommentAction -> mapOf(FIELD_BODY to action.body)
            is Action.CallWebhookAction ->
                mapOf(
                    FIELD_URL to action.url,
                    FIELD_METHOD to action.method,
                    FIELD_HEADERS to action.headers,
                    FIELD_BODY to action.body,
                )
        }

    /** JSON 문자열을 YAML 객체로 방출하기 위한 제네릭 맵으로 변환한다. */
    private fun String.jsonToConfigMap(): Map<String, Any?> =
        try {
            jsonMapper.readValue(this, object : TypeReference<Map<String, Any?>>() {})
        } catch (e: JsonProcessingException) {
            throw AutomationYamlInvalidException(MSG_PARSE_FAILED, e)
        }
}
