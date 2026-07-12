// 자동화 조건 표현식 도메인 — JSONLogic 부분집합 sealed 트리, 코드 실행 경로 없음 (FR-AT-03)

package com.bts.automation.domain

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.TextNode

/**
 * 조건 leaf([Condition.Comparison])가 비교하는 리터럴 값 타입. [Action.SetFieldAction.value] 처럼
 * [JsonNode] 를 그대로 쓴다(모듈 내 일관성) — 단 `Action` 과 달리 [Condition.fromJson] 이 문자열/
 * 숫자/불리언/null/배열(문자열·숫자 원소만)로 제한 검증한다(중첩 객체 거부, [requireLiteral]).
 */
typealias ConditionValue = JsonNode

/**
 * 자동화 룰의 조건(if-else 분기 게이트)을 표현하는 sealed 트리. [Action](FR-AT-02)의 `sealed class`
 * + `fromJson` 패턴을 미러링한다. **평가 로직을 포함하지 않는 순수 데이터 트리다** — 관리자가 런타임
 * API 로 조건을 입력해도 코드 실행 경로가 구조적으로 존재하지 않아 RCE 표면이 0 이다. 실제 평가는
 * `ConditionEvaluator`(별도 태스크) 책임, 이 클래스는 파싱/직렬화/형식 검증만 한다. 연산자 상세는
 * [ComparisonOperator] 참고.
 *
 * 비교 연산자(`==`/`!=`/`>`/`>=`/`<`/`<=`)는 첫 피연산자가 `{"var": F}`, 두 번째가 리터럴이어야
 * 한다(고정 순서 — 뒤집으면 `<`/`>` 의미가 반전되므로 원천 차단, `3 < priority` = `priority > 3`).
 * `in` 만 예외적으로 양방향 허용(자연어 "urgent in labels" 지원).
 *
 * - [And] 모든 하위 조건 참 · [Or] 하나 이상 참 · [Not] 부정 · [Comparison] 필드-연산자-리터럴 leaf
 */
sealed class Condition {
    /** 모든 [conditions] 가 참이어야 참. 빈 리스트는 항등원(참)이다. */
    data class And(val conditions: List<Condition>) : Condition()

    /** [conditions] 중 하나 이상이 참이면 참. 빈 리스트는 항등원(거짓)이다. */
    data class Or(val conditions: List<Condition>) : Condition()

    /** [condition] 의 부정. */
    data class Not(val condition: Condition) : Condition()

    /**
     * 필드 값과 리터럴을 비교하는 leaf 조건. `EMPTY`/`EXISTS` 는 `var` 하나뿐이라 [value] 를 쓰지
     * 않는다(항상 [NullNode.instance]).
     *
     * @property field [FIELD_WHITELIST] 소속 필드 경로(`var` 참조 대상)
     * @property operator 비교 연산자
     * @property value 비교 대상 리터럴
     */
    data class Comparison(val field: String, val operator: ComparisonOperator, val value: ConditionValue) : Condition()

    /**
     * 이 조건 트리를 와이어 포맷(JSONLogic 부분집합) JSON 문자열로 직렬화한다. 원본과 바이트 단위로
     * 같음을 보장하지 않지만(`in` 피연산자를 `{"var": field}` 선두 정규형 고정) `fromJson(toJson())
     * == this` 는 보장한다(위치 무관 `var` 탐색이라 정규형 재파싱도 동일 객체 복원).
     */
    fun toJson(): String = objectMapper.writeValueAsString(toJsonNode())

    private fun toJsonNode(): JsonNode =
        when (this) {
            is And -> combinatorNode(KEY_AND, conditions)
            is Or -> combinatorNode(KEY_OR, conditions)
            is Not -> objectMapper.createObjectNode().apply { set<JsonNode>(KEY_NOT, condition.toJsonNode()) }
            is Comparison -> comparisonToJsonNode(this)
        }

    private fun combinatorNode(
        key: String,
        children: List<Condition>,
    ): JsonNode =
        objectMapper.createObjectNode().apply {
            val array = objectMapper.createArrayNode()
            children.forEach { array.add(it.toJsonNode()) }
            set<JsonNode>(key, array)
        }

    companion object {
        /** DoS 방지 — 조건 트리 최대 깊이(`and`/`or`/`not` 중첩). 런타임 API 입력이라 저장 시점에 차단. */
        const val MAX_DEPTH: Int = 10

        /** DoS 방지 — 조건 트리 최대 노드 수(얕지만 넓은 트리도 평가 비용이 크므로 함께 제한). */
        const val MAX_NODES: Int = 100

        /** `var` 참조 화이트리스트(FR-AT-03-2). `IssueSnapshot` 필드와 1:1 — 목록 밖은 [fromJson] 거부. */
        val FIELD_WHITELIST: Set<String> =
            setOf(
                "issue.key", "issue.type", "issue.status",
                "issue.priority", "issue.assignee", "issue.reporter",
                "issue.labels", "issue.summary", "issue.projectKey",
            )

        /**
         * JSONLogic 부분집합 `json` 을 파싱해 [Condition] 트리를 생성한다(파싱+검증 통합).
         * @throws InvalidConditionExpressionException 형식·화이트리스트·상한 위반 시
         */
        fun fromJson(json: String): Condition {
            val root = parseJsonObject(json)
            return parseCondition(root, depth = 1, budget = ParseBudget())
        }
    }
}

/**
 * [Condition.Comparison.operator] 값. JSON 와이어 키([jsonKey])와 1:1 대응, 연산자 집합 고정(확장
 * 시 스펙 개정 필요, FR-AT-03-2).
 * | 값 | 키 | 의미 |
 * |---|---|---|
 * | [EQUALS] | `==` | 같음(스칼라 deep equal, 타입 다르면 false, null==null=true) |
 * | [NOT_EQUALS] | `!=` | 다름 |
 * | [GREATER_THAN] 등 4종 | `>`/`>=`/`<`/`<=` | 서수 비교(양쪽 숫자일 때만, 아니면 false) |
 * | [IN] | `in` | 배열이면 필드 값이 원소인지, 문자열이면 부분문자열인지 |
 * | [EMPTY] | `!` | 비어있음(falsy) |
 * | [EXISTS] | `!!` | 존재(truthy, [EMPTY] 의 부정) |
 */
enum class ComparisonOperator(val jsonKey: String) {
    EQUALS("=="),
    NOT_EQUALS("!="),
    GREATER_THAN(">"),
    GREATER_THAN_OR_EQUAL(">="),
    LESS_THAN("<"),
    LESS_THAN_OR_EQUAL("<="),
    IN("in"),
    EMPTY("!"),
    EXISTS("!!"),
    ;

    companion object {
        private val byKey: Map<String, ComparisonOperator> = entries.associateBy { it.jsonKey }

        /** `key` 에 대응하는 [ComparisonOperator] 를 찾는다. 없으면 `null`(미지원 연산자). */
        fun fromKey(key: String): ComparisonOperator? = byKey[key]
    }
}

/**
 * 조건 표현식이 형식/화이트리스트/상한을 위반할 때. 형식만 검증한다([ActionConfigInvalidException]
 * 선례 동형). HTTP 400 매핑. 메시지에는 사용자가 보낸 원본 값을 echo 하지 않는다(민감정보 누출 방지).
 *
 * @param message 위반 내용을 설명하는 일반 메시지
 * @param cause 원인이 된 예외. 없으면 `null`(기본값)
 */
class InvalidConditionExpressionException(message: String, cause: Throwable? = null) :
    AutomationDomainException(message, cause)

private const val KEY_AND = "and"
private const val KEY_OR = "or"
private const val KEY_NOT = "not"
private const val KEY_VAR = "var"
private const val BINARY_OPERAND_COUNT = 2
private const val MSG_INVALID_JSON = "조건 표현식은 유효한 JSON 객체여야 합니다."
private const val MSG_NODE_SHAPE_INVALID = "조건 노드는 정확히 하나의 연산자 키를 가진 JSON 객체여야 합니다."
private const val MSG_UNSUPPORTED_OPERATOR = "지원하지 않는 연산자입니다."
private const val MSG_COMBINATOR_SHAPE_INVALID = "and/or 연산자의 값은 조건 배열이어야 합니다."
private const val MSG_FIELD_NOT_WHITELISTED = "필드가 허용 목록에 없습니다."
private const val MSG_VAR_FIELD_INVALID = "var 참조는 비어있지 않은 문자열 필드명이어야 합니다."
private const val MSG_LIMIT_EXCEEDED = "조건 표현식이 너무 깊거나 노드 수가 많습니다."
private const val MSG_COMPARISON_SHAPE_INVALID = "비교 연산자는 정확히 하나의 var 참조와 하나의 리터럴 값으로 이루어진 2개 원소 배열이어야 합니다."
private const val MSG_EXISTENCE_SHAPE_INVALID = "!/!! 연산자는 var 참조 하나만 피연산자로 가져야 합니다."
private const val MSG_LITERAL_INVALID = "리터럴 값은 문자열/숫자/불리언/null 또는 문자열·숫자로만 이루어진 배열이어야 합니다."

private val objectMapper = ObjectMapper()

private class ParseBudget {
    var nodeCount: Int = 0
}

private fun parseJsonObject(json: String): JsonNode {
    val node =
        if (json.isBlank()) {
            null
        } else {
            try {
                objectMapper.readTree(json)
            } catch (e: JsonParseException) {
                throw InvalidConditionExpressionException(MSG_INVALID_JSON, e)
            }
        }
    if (node == null || !node.isObject) {
        throw InvalidConditionExpressionException(MSG_INVALID_JSON)
    }
    return node
}

/** `node` 를 [Condition] 으로 파싱한다. `depth`/`budget` 로 깊이/노드 상한(DoS 방지)을 강제한다. */
private fun parseCondition(
    node: JsonNode,
    depth: Int,
    budget: ParseBudget,
): Condition {
    budget.nodeCount++
    if (depth > Condition.MAX_DEPTH || budget.nodeCount > Condition.MAX_NODES) {
        throw InvalidConditionExpressionException(MSG_LIMIT_EXCEEDED)
    }
    if (!node.isObject || node.size() != 1) {
        throw InvalidConditionExpressionException(MSG_NODE_SHAPE_INVALID)
    }
    val entry = node.fields().next()
    return when (entry.key) {
        KEY_AND -> Condition.And(parseConditionArray(entry.value, depth, budget))
        KEY_OR -> Condition.Or(parseConditionArray(entry.value, depth, budget))
        KEY_NOT -> Condition.Not(parseCondition(entry.value, depth + 1, budget))
        else -> parseComparisonNode(entry.key, entry.value)
    }
}

private fun parseConditionArray(
    node: JsonNode,
    depth: Int,
    budget: ParseBudget,
): List<Condition> {
    if (!node.isArray) {
        throw InvalidConditionExpressionException(MSG_COMBINATOR_SHAPE_INVALID)
    }
    return node.map { parseCondition(it, depth + 1, budget) }
}

private fun parseComparisonNode(
    key: String,
    operand: JsonNode,
): Condition.Comparison {
    val operator =
        ComparisonOperator.fromKey(key) ?: throw InvalidConditionExpressionException(MSG_UNSUPPORTED_OPERATOR)
    return when (operator) {
        ComparisonOperator.EMPTY, ComparisonOperator.EXISTS -> parseExistence(operator, operand)
        else -> parseBinaryComparison(operator, operand)
    }
}

private fun parseExistence(
    operator: ComparisonOperator,
    operand: JsonNode,
): Condition.Comparison {
    if (!isVarNode(operand)) {
        throw InvalidConditionExpressionException(MSG_EXISTENCE_SHAPE_INVALID)
    }
    return Condition.Comparison(field = extractVarField(operand), operator = operator, value = NullNode.instance)
}

/** 2개 원소 배열 피연산자를 파싱한다. `in` 만 양방향 허용, 나머지는 첫 원소가 `var` 여야 한다. */
private fun parseBinaryComparison(
    operator: ComparisonOperator,
    operands: JsonNode,
): Condition.Comparison {
    if (!operands.isArray || operands.size() != BINARY_OPERAND_COUNT) {
        throw InvalidConditionExpressionException(MSG_COMPARISON_SHAPE_INVALID)
    }
    val left = operands[0]
    val right = operands[1]
    val allowReversed = operator == ComparisonOperator.IN
    val (field, literal) =
        when {
            isVarNode(left) && !isVarNode(right) -> extractVarField(left) to right
            allowReversed && isVarNode(right) && !isVarNode(left) -> extractVarField(right) to left
            else -> throw InvalidConditionExpressionException(MSG_COMPARISON_SHAPE_INVALID)
        }
    return Condition.Comparison(field = field, operator = operator, value = requireLiteral(literal))
}

private fun isVarNode(node: JsonNode): Boolean = node.isObject && node.size() == 1 && node.has(KEY_VAR)

/** `{"var": F}` 에서 `F` 를 추출한다. 문자열이 아니거나 화이트리스트 밖이면 거부. */
private fun extractVarField(node: JsonNode): String {
    val fieldNode = node.get(KEY_VAR)
    if (fieldNode == null || !fieldNode.isTextual || fieldNode.asText().isBlank()) {
        throw InvalidConditionExpressionException(MSG_VAR_FIELD_INVALID)
    }
    val field = fieldNode.asText()
    if (field !in Condition.FIELD_WHITELIST) {
        throw InvalidConditionExpressionException(MSG_FIELD_NOT_WHITELISTED)
    }
    return field
}

private fun requireLiteral(node: JsonNode): JsonNode {
    val isScalar = node.isTextual || node.isNumber || node.isBoolean || node.isNull
    val isScalarArray = node.isArray && node.all { it.isTextual || it.isNumber }
    if (!isScalar && !isScalarArray) {
        throw InvalidConditionExpressionException(MSG_LITERAL_INVALID)
    }
    return node
}

private fun comparisonToJsonNode(comparison: Condition.Comparison): JsonNode {
    val varNode = objectMapper.createObjectNode().apply { set<JsonNode>(KEY_VAR, TextNode(comparison.field)) }
    return objectMapper.createObjectNode().apply {
        when (comparison.operator) {
            ComparisonOperator.EMPTY, ComparisonOperator.EXISTS -> set<JsonNode>(comparison.operator.jsonKey, varNode)
            else ->
                set<JsonNode>(
                    comparison.operator.jsonKey,
                    objectMapper.createArrayNode().add(varNode).add(comparison.value),
                )
        }
    }
}
