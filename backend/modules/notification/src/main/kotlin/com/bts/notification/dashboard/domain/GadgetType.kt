// 표준 가젯 카탈로그 12종 — 카테고리·활성 여부·config 필드 디스크립터를 단일 출처로 관리

package com.bts.notification.dashboard.domain

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.UUID

/** 가젯 대분류 카테고리. */
enum class GadgetCategory { ISSUE, STATIC, CHART, ACTIVITY }

/** config 필드 기본 타입. URL 은 http/https 스킴 검증을 포함한다. */
enum class FieldType { STRING, INT, UUID, ENUM, ARRAY, URL }

/**
 * config 필드 하나의 형식 디스크립터.
 *
 * 타입(type)에 따라 사용하는 제약 필드가 달라진다.
 * - STRING: minLength, maxLength
 * - INT: min, max
 * - UUID: (없음 — 형식만 검증)
 * - ENUM: enumValues
 * - ARRAY: minItems, maxItems, itemSchema
 * - URL: maxLength, (http/https 스킴 강제)
 */
data class ConfigFieldDescriptor(
    val key: String,
    val type: FieldType,
    val required: Boolean,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val min: Int? = null,
    val max: Int? = null,
    val enumValues: Set<String>? = null,
    val itemSchema: List<ConfigFieldDescriptor>? = null,
    val minItems: Int? = null,
    val maxItems: Int? = null,
)

/**
 * 카탈로그 엔트리 — GET /gadgets/catalog 응답 DTO 파생에 사용한다.
 *
 * configFields 는 GadgetType.configFields 와 동일한 인스턴스 목록이므로
 * 검증 로직과 카탈로그 응답이 항상 단일 출처에서 파생된다.
 * requireAtLeastOne 은 additionalRules 의 RequireAtLeastOne 규칙 그룹을 노출한다.
 */
data class GadgetCatalogEntry(
    val type: String,
    val category: GadgetCategory,
    val label: String,
    val enabled: Boolean,
    val configFields: List<ConfigFieldDescriptor>,
    /** 교차필드 "적어도 하나 필수" 규칙 그룹 목록. 각 원소는 그룹 내 필드 키 목록이다. */
    val requireAtLeastOne: List<List<String>> = emptyList(),
)

/** 단순 per-field required 로 표현할 수 없는 추가 검증 규칙. */
sealed interface AdditionalRule {
    /** 위반 시 에러 메시지를 반환하고, 통과 시 null 을 반환한다. */
    fun validate(config: JsonNode): String?
}

/** keys 목록 중 적어도 하나가 config 에 존재해야 통과하는 규칙. */
class RequireAtLeastOne(val keys: List<String>) : AdditionalRule {
    override fun validate(config: JsonNode): String? {
        val present = keys.any { key -> config.has(key) && !config.get(key).isNull }
        return if (!present) "필드 [${keys.joinToString(", ")}] 중 적어도 하나는 필요합니다." else null
    }
}

/**
 * 표준 가젯 카탈로그 12종.
 *
 * 각 상수가 자신의 config 필드 디스크립터를 선언적으로 보유한다.
 * validateConfig 와 catalog() 는 같은 디스크립터(configFields + additionalRules)에서 파생되어 drift 를 차단한다.
 *
 * @property key 직렬화 키 (소문자 snake_case)
 * @property category 가젯 대분류
 * @property enabled MVP 활성 여부 — false 는 미래 기능
 * @property configFields config 필드 디스크립터 목록
 * @property additionalRules per-field 로 표현 불가한 추가 검증 규칙
 */
@Suppress("LargeClass")
enum class GadgetType(
    val key: String,
    val category: GadgetCategory,
    val enabled: Boolean,
    val configFields: List<ConfigFieldDescriptor>,
    val additionalRules: List<AdditionalRule> = emptyList(),
) {
    ASSIGNED_TO_ME(
        key = "assigned_to_me",
        category = GadgetCategory.ISSUE,
        enabled = true,
        configFields =
            listOf(
                ConfigFieldDescriptor("maxItems", FieldType.INT, required = false, min = 1, max = 50),
            ),
    ),
    RECENTLY_CREATED(
        key = "recently_created",
        category = GadgetCategory.ISSUE,
        enabled = true,
        configFields =
            listOf(
                ConfigFieldDescriptor("projectKey", FieldType.STRING, required = false, maxLength = 100),
                ConfigFieldDescriptor("maxItems", FieldType.INT, required = false, min = 1, max = 50),
            ),
    ),
    FILTER_RESULT(
        key = "filter_result",
        category = GadgetCategory.ISSUE,
        enabled = true,
        configFields =
            listOf(
                ConfigFieldDescriptor("filterId", FieldType.UUID, required = false),
                ConfigFieldDescriptor("aql", FieldType.STRING, required = false, maxLength = 2000),
                ConfigFieldDescriptor("maxItems", FieldType.INT, required = false, min = 1, max = 50),
            ),
        additionalRules = listOf(RequireAtLeastOne(listOf("filterId", "aql"))),
    ),
    ISSUE_COUNT(
        key = "issue_count",
        category = GadgetCategory.ISSUE,
        enabled = true,
        configFields =
            listOf(
                ConfigFieldDescriptor("filterId", FieldType.UUID, required = false),
                ConfigFieldDescriptor("aql", FieldType.STRING, required = false, maxLength = 2000),
            ),
        additionalRules = listOf(RequireAtLeastOne(listOf("filterId", "aql"))),
    ),
    TEXT_WIDGET(
        key = "text_widget",
        category = GadgetCategory.STATIC,
        enabled = true,
        configFields =
            listOf(
                ConfigFieldDescriptor("markdown", FieldType.STRING, required = true, minLength = 1, maxLength = 10000),
            ),
    ),
    LINK_LIST(
        key = "link_list",
        category = GadgetCategory.STATIC,
        enabled = true,
        configFields =
            listOf(
                ConfigFieldDescriptor(
                    key = "links",
                    type = FieldType.ARRAY,
                    required = true,
                    minItems = 1,
                    maxItems = 20,
                    itemSchema =
                        listOf(
                            ConfigFieldDescriptor("label", FieldType.STRING, required = true, maxLength = 100),
                            ConfigFieldDescriptor("url", FieldType.URL, required = true, maxLength = 2000),
                        ),
                ),
            ),
    ),
    PIE_CHART(
        key = "pie_chart",
        category = GadgetCategory.CHART,
        enabled = false,
        configFields =
            listOf(
                ConfigFieldDescriptor(
                    key = "field",
                    type = FieldType.ENUM,
                    required = true,
                    enumValues = setOf("status", "assignee", "priority", "issueType"),
                ),
                ConfigFieldDescriptor("filterId", FieldType.UUID, required = false),
                ConfigFieldDescriptor("aql", FieldType.STRING, required = false, maxLength = 2000),
            ),
    ),
    BAR_CHART(
        key = "bar_chart",
        category = GadgetCategory.CHART,
        enabled = false,
        configFields =
            listOf(
                ConfigFieldDescriptor(
                    key = "field",
                    type = FieldType.ENUM,
                    required = true,
                    enumValues = setOf("status", "assignee", "priority", "issueType"),
                ),
                ConfigFieldDescriptor("filterId", FieldType.UUID, required = false),
                ConfigFieldDescriptor("aql", FieldType.STRING, required = false, maxLength = 2000),
            ),
    ),
    CREATED_VS_RESOLVED(
        key = "created_vs_resolved",
        category = GadgetCategory.CHART,
        enabled = false,
        configFields =
            listOf(
                ConfigFieldDescriptor("projectKey", FieldType.STRING, required = false, maxLength = 100),
                ConfigFieldDescriptor("days", FieldType.INT, required = false, min = 7, max = 90),
            ),
    ),
    SPRINT_BURNDOWN(
        key = "sprint_burndown",
        category = GadgetCategory.CHART,
        enabled = false,
        configFields =
            listOf(
                ConfigFieldDescriptor("sprintId", FieldType.UUID, required = false),
            ),
    ),
    ACTIVITY_STREAM(
        key = "activity_stream",
        category = GadgetCategory.ACTIVITY,
        enabled = false,
        configFields =
            listOf(
                ConfigFieldDescriptor("projectKey", FieldType.STRING, required = false, maxLength = 100),
                ConfigFieldDescriptor("maxItems", FieldType.INT, required = false, min = 1, max = 50),
            ),
    ),
    COMMENTS_RECENT(
        key = "comments_recent",
        category = GadgetCategory.ACTIVITY,
        enabled = false,
        configFields =
            listOf(
                ConfigFieldDescriptor("projectKey", FieldType.STRING, required = false, maxLength = 100),
                ConfigFieldDescriptor("maxItems", FieldType.INT, required = false, min = 1, max = 50),
            ),
    ),
    ;

    /**
     * config JsonNode 의 형식을 검증한다.
     *
     * null 과 빈 ObjectNode 는 동등하게 처리한다(C1 — null=빈객체 동등).
     * 알 수 없는 config 키는 무시한다(EC5).
     * 위반 시 DashboardDomainException 을 throw 한다.
     *
     * @param config 검증할 JSON 노드 (null 허용 — 빈 객체와 동등)
     * @throws DashboardDomainException 형식 위반 시
     */
    fun validateConfig(config: JsonNode?) {
        val effectiveConfig: JsonNode =
            if (config == null || !config.isObject) OBJECT_MAPPER.createObjectNode() else config

        for (field in configFields) {
            val node = effectiveConfig.get(field.key)
            if (node == null || node.isNull) {
                if (field.required) {
                    throw DashboardDomainException("필수 필드 '${field.key}'가 누락되었습니다.")
                }
            } else {
                validateField(field, node)
            }
        }

        for (rule in additionalRules) {
            rule.validate(effectiveConfig)?.let { throw DashboardDomainException(it) }
        }
    }

    private fun validateField(
        descriptor: ConfigFieldDescriptor,
        node: JsonNode,
    ) {
        when (descriptor.type) {
            FieldType.STRING -> validateStringField(descriptor, node)
            FieldType.INT -> validateIntField(descriptor, node)
            FieldType.UUID -> validateUuidField(descriptor, node)
            FieldType.ENUM -> validateEnumField(descriptor, node)
            FieldType.ARRAY -> validateArrayField(descriptor, node)
            FieldType.URL -> validateUrlField(descriptor, node)
        }
    }

    private fun validateStringField(
        descriptor: ConfigFieldDescriptor,
        node: JsonNode,
    ) {
        if (!node.isTextual) throw DashboardDomainException("필드 '${descriptor.key}'는 문자열이어야 합니다.")
        val text = node.asText()
        val minLen = descriptor.minLength
        val maxLen = descriptor.maxLength
        val violation =
            when {
                minLen != null && text.length < minLen -> "필드 '${descriptor.key}'는 최소 ${minLen}자 이상이어야 합니다."
                maxLen != null && text.length > maxLen -> "필드 '${descriptor.key}'는 최대 ${maxLen}자 이하여야 합니다."
                else -> null
            }
        if (violation != null) throw DashboardDomainException(violation)
    }

    private fun validateIntField(
        descriptor: ConfigFieldDescriptor,
        node: JsonNode,
    ) {
        if (!node.isIntegralNumber) throw DashboardDomainException("필드 '${descriptor.key}'는 정수여야 합니다.")
        val value = node.intValue()
        val min = descriptor.min
        val max = descriptor.max
        val violation =
            when {
                min != null && value < min -> "필드 '${descriptor.key}'는 $min 이상이어야 합니다."
                max != null && value > max -> "필드 '${descriptor.key}'는 $max 이하여야 합니다."
                else -> null
            }
        if (violation != null) throw DashboardDomainException(violation)
    }

    private fun validateUuidField(
        descriptor: ConfigFieldDescriptor,
        node: JsonNode,
    ) {
        if (!node.isTextual) throw DashboardDomainException("필드 '${descriptor.key}'는 문자열(UUID)이어야 합니다.")
        val text = node.asText()
        val isValid = runCatching { UUID.fromString(text) }.isSuccess
        if (!isValid) throw DashboardDomainException("필드 '${descriptor.key}'는 유효한 UUID 형식이어야 합니다: $text")
    }

    private fun validateEnumField(
        descriptor: ConfigFieldDescriptor,
        node: JsonNode,
    ) {
        if (!node.isTextual) throw DashboardDomainException("필드 '${descriptor.key}'는 문자열이어야 합니다.")
        val value = node.asText()
        descriptor.enumValues?.let {
            if (value !in it) {
                throw DashboardDomainException(
                    "필드 '${descriptor.key}'의 값 '$value'은 허용되지 않습니다. 허용 값: ${it.joinToString(", ")}",
                )
            }
        }
    }

    private fun validateArrayField(
        descriptor: ConfigFieldDescriptor,
        node: JsonNode,
    ) {
        if (!node.isArray) throw DashboardDomainException("필드 '${descriptor.key}'는 배열이어야 합니다.")
        val minIt = descriptor.minItems
        val maxIt = descriptor.maxItems
        val sizeError =
            when {
                minIt != null && node.size() < minIt -> "필드 '${descriptor.key}'는 최소 ${minIt}개 이상의 항목이 필요합니다."
                maxIt != null && node.size() > maxIt -> "필드 '${descriptor.key}'는 최대 ${maxIt}개 이하의 항목을 가져야 합니다."
                else -> null
            }
        if (sizeError != null) throw DashboardDomainException(sizeError)
        descriptor.itemSchema?.let { schema ->
            node.forEach { item -> validateItemSchemaEntry(schema, item) }
        }
    }

    private fun validateItemSchemaEntry(
        schema: List<ConfigFieldDescriptor>,
        item: JsonNode,
    ) {
        for (fieldDesc in schema) {
            val fieldNode = item.get(fieldDesc.key)
            if (fieldNode == null || fieldNode.isNull) {
                if (fieldDesc.required) {
                    throw DashboardDomainException("배열 항목의 필수 필드 '${fieldDesc.key}'가 누락되었습니다.")
                }
            } else {
                validateField(fieldDesc, fieldNode)
            }
        }
    }

    private fun validateUrlField(
        descriptor: ConfigFieldDescriptor,
        node: JsonNode,
    ) {
        if (!node.isTextual) throw DashboardDomainException("필드 '${descriptor.key}'는 문자열(URL)이어야 합니다.")
        val text = node.asText()
        val maxLen = descriptor.maxLength
        val lower = text.lowercase()
        val error =
            when {
                maxLen != null && text.length > maxLen -> "필드 '${descriptor.key}'는 최대 ${maxLen}자 이하여야 합니다."
                !lower.startsWith("http://") && !lower.startsWith("https://") ->
                    "URL 은 http 또는 https 스킴이어야 합니다. 현재 값: $text"
                else -> null
            }
        if (error != null) throw DashboardDomainException(error)
    }

    companion object {
        private val OBJECT_MAPPER = ObjectMapper()

        /**
         * 소문자 snake_case 키로 GadgetType 을 찾는다. 대소문자 엄격 일치(EC4).
         *
         * @param key 소문자 snake_case 키 (예: "assigned_to_me")
         * @return 일치하는 GadgetType, 없으면 null
         */
        fun fromKey(key: String): GadgetType? = entries.find { it.key == key }

        /**
         * 모든 가젯 카탈로그 엔트리를 반환한다.
         *
         * configFields 는 검증 디스크립터와 동일한 단일 출처에서 파생된다.
         *
         * @return 12종 GadgetCatalogEntry 목록
         */
        fun catalog(): List<GadgetCatalogEntry> =
            entries.map { type ->
                GadgetCatalogEntry(
                    type = type.key,
                    category = type.category,
                    label =
                        type.key.split("_").joinToString(" ") { word ->
                            word.replaceFirstChar { it.uppercaseChar() }
                        },
                    enabled = type.enabled,
                    configFields = type.configFields,
                    requireAtLeastOne =
                        type.additionalRules
                            .filterIsInstance<RequireAtLeastOne>()
                            .map { it.keys },
                )
            }
    }
}
