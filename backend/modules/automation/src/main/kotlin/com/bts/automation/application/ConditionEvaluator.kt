// 조건 트리를 순회하며 이슈 컨텍스트에 대해 참/거짓을 판정하는 순수 평가기 — 예외를 던지지 않는 fail-safe 계약 (FR-AT-03 Task 2)

package com.bts.automation.application

import com.bts.automation.domain.ComparisonOperator
import com.bts.automation.domain.Condition
import com.fasterxml.jackson.databind.JsonNode

/**
 * [Condition] 트리를 재귀적으로 순회해 [ConditionContext] 에 대한 참/거짓을 판정하는 순수(상태 없음,
 * IO 없음) 평가기. [TemplateRenderer]/[TriggerMatcher] 와 동일하게 의존성이 없는 상태 없는 싱글턴이라
 * `object` 로 선언한다(Spring 빈 등록이 필요 없음, automation 모듈 관례).
 *
 * ## fail-safe 계약
 * [evaluate] 는 **예외를 던지지 않는다.** 예상치 못한 타입/구조(누락 필드, 타입 불일치, 비교 불가능한
 * 값)는 모두 `false` 로 흡수한다 — 상위 룰 평가 게이트가 이를 "조건 불일치"로 취급해 액션을
 * SKIPPED 처리하도록 위임한다(예외로 전파해 워커 전체를 죽이지 않기 위함). 무한 재귀/과도한 트리
 * 크기 방지는 이 계층의 책임이 아니다 — [Condition.fromJson] 이 파싱 시점에 깊이/노드 수
 * 상한([Condition.MAX_DEPTH]/[Condition.MAX_NODES])을 이미 강제한다.
 *
 * ## 연산자별 평가 의미
 * | 연산자 | 의미 | fail-safe 판정 |
 * |---|---|---|
 * | [ComparisonOperator.EQUALS]/[ComparisonOperator.NOT_EQUALS] | 스칼라 deep equal | 타입이 다르면 다름으로 판정(문자열 `"3"` ≠ 숫자 `3`). `null == null` 은 참, 누락 필드는 `null` 취급 |
 * | [ComparisonOperator.GREATER_THAN] 등 4종 | 서수 비교 | 양쪽 모두 숫자일 때만 비교, 아니면 `false`(예외 없음) |
 * | [ComparisonOperator.IN] | 멤버십/부분문자열 | 리터럴이 배열이면 필드 값이 원소인지, 필드 값이 리스트(라벨 등)면 리터럴이 원소인지, 필드 값이 문자열이면 리터럴이 부분문자열인지. 그 외 조합(예: 스칼라 필드 × 스칼라 haystack)은 `false` |
 * | [ComparisonOperator.EMPTY]/[ComparisonOperator.EXISTS] | falsy/truthy | `null`/빈 문자열/빈 배열/`false` 만 falsy, 그 외(빈 배열이 아닌 값·숫자 전반 포함)는 truthy |
 */
object ConditionEvaluator {

    /**
     * [condition] 트리를 [ctx] 에 대해 평가한다.
     *
     * @param condition 평가할 조건 트리(파싱 시점에 이미 형식/화이트리스트/상한 검증을 통과했다).
     * @param ctx `var` 참조를 값으로 해석하는 읽기 전용 컨텍스트.
     * @return 조건이 참이면 `true`. 예외를 던지지 않는다(클래스 KDoc "fail-safe 계약" 참고).
     */
    fun evaluate(
        condition: Condition,
        ctx: ConditionContext,
    ): Boolean =
        when (condition) {
            is Condition.And -> condition.conditions.all { evaluate(it, ctx) }
            is Condition.Or -> condition.conditions.any { evaluate(it, ctx) }
            is Condition.Not -> !evaluate(condition.condition, ctx)
            is Condition.Comparison -> evaluateComparison(condition, ctx)
        }

    /** [Condition.Comparison] leaf 하나를 연산자별로 분기 평가한다(exhaustive when, [ComparisonOperator] 전 항목). */
    private fun evaluateComparison(
        comparison: Condition.Comparison,
        ctx: ConditionContext,
    ): Boolean {
        val fieldValue = ctx.valueOf(comparison.field)
        return when (comparison.operator) {
            ComparisonOperator.EQUALS -> scalarEquals(fieldValue, comparison.value)
            ComparisonOperator.NOT_EQUALS -> !scalarEquals(fieldValue, comparison.value)
            ComparisonOperator.GREATER_THAN,
            ComparisonOperator.GREATER_THAN_OR_EQUAL,
            ComparisonOperator.LESS_THAN,
            ComparisonOperator.LESS_THAN_OR_EQUAL,
            -> compareOrdinal(fieldValue, comparison.value, comparison.operator)
            ComparisonOperator.IN -> evaluateIn(fieldValue, comparison.value)
            ComparisonOperator.EMPTY -> isFalsy(fieldValue)
            ComparisonOperator.EXISTS -> !isFalsy(fieldValue)
        }
    }

    /**
     * [fieldValue] 와 [literal] 의 스칼라 deep equal.
     *
     * 타입이 다르면 값이 같아 보여도 다름으로 판정한다(문자열 `"3"` 은 숫자 `3` 과 다르다 — 암묵적
     * 타입 변환을 하지 않는다). 양쪽 모두 `null` 이면(누락 필드 포함) 참.
     */
    private fun scalarEquals(
        fieldValue: Any?,
        literal: JsonNode,
    ): Boolean =
        when {
            fieldValue == null -> literal.isNull
            literal.isNull -> false
            fieldValue is String -> literal.isTextual && fieldValue == literal.asText()
            fieldValue is Boolean -> literal.isBoolean && fieldValue == literal.asBoolean()
            fieldValue is Int -> literal.isIntegralNumber && fieldValue == literal.asInt()
            fieldValue is List<*> -> literal.isArray && fieldValue == literal.map { literalToText(it) }
            else -> false
        }

    /** 양쪽 모두 숫자일 때만 [operator] 로 서수 비교한다. 하나라도 숫자가 아니면(필드 누락 포함) `false`. */
    private fun compareOrdinal(
        fieldValue: Any?,
        literal: JsonNode,
        operator: ComparisonOperator,
    ): Boolean {
        val left = (fieldValue as? Number)?.toDouble() ?: return false
        val right = literal.takeIf { it.isNumber }?.asDouble() ?: return false
        return when (operator) {
            ComparisonOperator.GREATER_THAN -> left > right
            ComparisonOperator.GREATER_THAN_OR_EQUAL -> left >= right
            ComparisonOperator.LESS_THAN -> left < right
            ComparisonOperator.LESS_THAN_OR_EQUAL -> left <= right
            else -> false
        }
    }

    /**
     * `in` 연산자 — 클래스 KDoc 표 참고. [literal] 이 배열이면 [fieldValue] 가 원소인지, [fieldValue]
     * 가 리스트(라벨 등)면 [literal] 이 원소인지, [fieldValue] 가 문자열이면 [literal] 이 부분문자열인지
     * 판정한다. 세 조합 모두 아니면(예: 스칼라 필드 × 스칼라 haystack) `false`.
     */
    private fun evaluateIn(
        fieldValue: Any?,
        literal: JsonNode,
    ): Boolean =
        when {
            literal.isArray -> literal.any { scalarEquals(fieldValue, it) }
            fieldValue is List<*> -> fieldValue.any { it == literalToText(literal) }
            fieldValue is String -> fieldValue.contains(literalToText(literal))
            else -> false
        }

    /** falsy 판정 — `null`/빈 문자열/빈 배열/`false` 만 falsy. 그 외(숫자 전체 포함)는 truthy. */
    private fun isFalsy(fieldValue: Any?): Boolean =
        when (fieldValue) {
            null -> true
            is String -> fieldValue.isEmpty()
            is List<*> -> fieldValue.isEmpty()
            is Boolean -> !fieldValue
            else -> false
        }

    /** 스칼라 [node] 를 문자열로 변환한다(멤버십/부분문자열 비교용). 스칼라가 아니면 빈 문자열(fail-safe). */
    private fun literalToText(node: JsonNode): String =
        when {
            node.isTextual -> node.asText()
            node.isNumber -> node.asText()
            node.isBoolean -> node.asText()
            else -> ""
        }
}
