// 계약 스냅샷의 leaf 값을 타입별 표준값으로 치환하는 정규화기 — 조립 부팅 없이 단위 검증이 가능하도록 분리

package com.bts.app.contract

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.BooleanNode
import com.fasterxml.jackson.databind.node.DoubleNode
import com.fasterxml.jackson.databind.node.IntNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.TextNode

/**
 * 계약 스냅샷 정규화기 — **값 변동은 지우고 타입·필드명·nullability 는 보존**한다.
 *
 * [WorkflowSchemeContractSnapshotTest] 가 조립 응답을 파일로 굳히기 전에 통과시키는 변환이다.
 * 자동증가 id·실행 시각·환경별 행 수를 표준값으로 눌러 바이트 동일성을 만들되, 계약이 실제로
 * 지켜야 하는 축(필드명·타입·null 여부)은 남긴다.
 *
 * ## 왜 별도 파일인가
 * 정규화 규칙 자체는 DB·Tomcat·prod 프로파일이 필요 없는 순수 함수다. 조립 테스트 안에 사인
 * private 메서드로 두면 규칙 하나를 확인하는 데 9-BC 컨텍스트 부팅(약 2분)이 필요하고,
 * **실 응답에 나타나지 않는 타입 분기**(예: 실수)는 프로덕션 DTO 를 고치지 않고서는 아예
 * 검증할 수 없다. 분리하면 [ContractSnapshotCanonicalizerTest] 가 각 분기를 직접 못박는다.
 *
 * ## 숫자 — 정수와 실수를 다른 표준값으로 가른다
 * 예전 규칙은 모든 숫자를 `1` 로 치환해, DTO 필드가 `Long` 에서 `Double` 로 바뀌어도 스냅샷이
 * 바이트 동일이었다(부채 실측 — 뮤테이션 주입 시 BUILD SUCCESSFUL). JSON 수준에서 `Long` 은
 * `1`, `Double` 은 `1.0` 으로 직렬화되므로 **정수/실수 구분은 실제로 계약에 드러나는 차이**다.
 * 따라서 정수는 [CANONICAL_INTEGRAL], 실수는 [CANONICAL_FRACTIONAL] 로 나눠 찍는다.
 * 프론트 Zod 가 이 필드들을 `z.number().int()` 로 못박고 있어, 실수로 바뀌면 프론트 계약
 * 테스트도 같은 변경에서 함께 실패한다(봉인 2축).
 *
 * ## 왜 INT/LONG 까지는 가르지 않는가
 * `numberType()` 으로 INT 와 LONG 을 나누면 스냅샷이 **런타임 값의 크기에 의존**하게 된다 —
 * 같은 `Long` 필드라도 id 가 2^31 을 넘는 순간 스냅샷이 흔들려, 정규화가 없애려던 환경 의존성이
 * 되돌아온다. 게다가 `Int` 와 `Long` 은 JSON 바이트가 똑같아(`1`) 계약상 구분이 불가능하다.
 * 그래서 **wire 에서 관측 가능한 경계**인 정수/실수에서만 가른다.
 */
object ContractSnapshotCanonicalizer {
    /**
     * 정수 표준값 — **0 이 아니라 1** 이다.
     *
     * id·schemeId 는 BIGSERIAL 이라 실값이 1 부터 시작하고, 프론트 Zod 가 그 불변식을
     * `z.number().int().positive()` 로 못박고 있다. 0 을 쓰면 계약 테스트가 어휘 불일치가 아니라
     * **값 제약**으로 실패해, 형태 검증에 값 검증이 섞인다. 1 은 positive·nonnegative·nullable
     * 제약을 모두 만족하는 중립값이면서 실제 데이터에 더 가깝다.
     */
    const val CANONICAL_INTEGRAL = 1

    /**
     * 실수 표준값 — 소수부가 **반드시 0 이 아니어야** 한다.
     *
     * `1.0` 을 쓰면 Jackson 이 `1.0` 으로 찍어 정수 `1` 과 파일상 구분은 되지만, 값이 정수와
     * 같아 사람이 diff 를 오독하기 쉽다. `1.5` 는 정수로 반올림되지 않아 실수임이 한눈에 보인다.
     */
    const val CANONICAL_FRACTIONAL = 1.5

    const val CANONICAL_STRING = "string"
    const val CANONICAL_UUID = "00000000-0000-4000-8000-000000000000"
    const val CANONICAL_INSTANT = "2026-01-01T00:00:00Z"

    val UUID_REGEX = Regex("^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$")
    val INSTANT_REGEX = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?Z$""")

    /** leaf 를 타입별 표준값으로 치환한다 — 값 변동은 지우고 **타입·필드명·nullability 는 보존**한다. */
    fun canonicalNode(node: JsonNode): JsonNode =
        when {
            node.isObject -> canonicalObject(node)
            node.isArray -> canonicalArray(node)
            node.isNull -> NullNode.instance
            node.isBoolean -> BooleanNode.TRUE
            node.isNumber -> canonicalNumber(node)
            node.isTextual -> canonicalText(node.textValue())
            else -> TextNode(CANONICAL_STRING)
        }

    /**
     * 정수/실수를 서로 다른 표준값으로 가른다.
     *
     * `isIntegralNumber` 는 INT·LONG·BIG_INTEGER 를, 그 여집합은 FLOAT·DOUBLE·BIG_DECIMAL 을 받는다.
     */
    private fun canonicalNumber(node: JsonNode): JsonNode =
        if (node.isIntegralNumber) IntNode(CANONICAL_INTEGRAL) else DoubleNode(CANONICAL_FRACTIONAL)

    private fun canonicalObject(node: JsonNode): JsonNode {
        val out = JsonNodeFactory.instance.objectNode()
        node.fieldNames().asSequence().sorted().forEach { out.set<JsonNode>(it, canonicalNode(node.get(it))) }
        return out
    }

    /** 원소를 정규화한 뒤 중복 제거 + 정렬 — 행 수·정렬 순서가 환경마다 달라도 스냅샷이 흔들리지 않는다. */
    private fun canonicalArray(node: JsonNode): JsonNode {
        val out = JsonNodeFactory.instance.arrayNode()
        node
            .map { canonicalNode(it) }
            .distinctBy { it.toString() }
            .sortedBy { it.toString() }
            .forEach { out.add(it) }
        return out
    }

    /**
     * 문자열을 표준값으로 치환한다.
     *
     * 빈 문자열도 [CANONICAL_STRING] 이 되므로 `null` 과는 파일상 다른 값으로 남는다 —
     * 백엔드가 `null` 을 `''` 로 바꾸면 스냅샷 diff 가 난다([ContractSnapshotCanonicalizerTest]
     * 가 이 축을 직접 못박는다).
     */
    private fun canonicalText(value: String): TextNode =
        when {
            UUID_REGEX.matches(value) -> TextNode(CANONICAL_UUID)
            INSTANT_REGEX.matches(value) -> TextNode(CANONICAL_INSTANT)
            else -> TextNode(CANONICAL_STRING)
        }
}
