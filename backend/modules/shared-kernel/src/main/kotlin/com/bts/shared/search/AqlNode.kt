// AQL(Atlas Query Language) AST 노드 — BC 비의존 순수 데이터 트리

package com.bts.shared.search

/**
 * AQL(Atlas Query Language) 추상 구문 트리(AST) 노드 sealed 계층.
 *
 * AQL 쿼리를 파싱한 결과를 BC 비의존 순수 데이터 트리로 표현한다.
 * [BoardCardFilter] 와 동일한 "shared-kernel 순수 VO" 원칙을 따르며,
 * 어떤 BC 내부 타입도 참조하지 않는다.
 *
 * ### 노드 종류
 *
 * - [And] — 두 하위 노드를 AND 결합한다. 우선순위는 OR 보다 높다.
 * - [Or] — 두 하위 노드를 OR 결합한다.
 * - [Not] — 단일 하위 노드를 NOT(부정)한다.
 * - [Comparison] — 단일 필드에 대한 비교 표현식. 쿼리의 최소 단위.
 *
 * ### 예시
 *
 * `summary ~ "버그" AND (label = bug OR priority = 1)` 은 아래와 같이 조립된다.
 * ```
 * And(
 *   Comparison(AqlField("summary"), CONTAINS, [Str("버그")]),
 *   Or(
 *     Comparison(AqlField("label"), EQ, [Str("bug")]),
 *     Comparison(AqlField("priority"), EQ, [Num(1)]),
 *   )
 * )
 * ```
 *
 * ### BC 격리
 *
 * 이 sealed 계층은 search 모듈(파서)과 issue-tracking 모듈(jOOQ 어댑터) 양쪽이
 * shared-kernel 을 통해 공유한다(AST-as-contract 패턴).
 * issue-tracking 내부를 직접 import 하지 않으므로 ArchUnit 경계를 위반하지 않는다.
 *
 * @see AqlField
 * @see AqlOperator
 * @see AqlValue
 */
sealed interface AqlNode {
    /**
     * 두 하위 AST 노드를 AND 결합하는 이진 노드.
     *
     * `left AND right` 의미로 해석된다.
     * AND 는 OR 보다 우선순위가 높으므로 파서가 트리를 올바르게 구성해야 한다.
     *
     * @property left AND 의 좌항 노드.
     * @property right AND 의 우항 노드.
     */
    data class And(
        val left: AqlNode,
        val right: AqlNode,
    ) : AqlNode

    /**
     * 두 하위 AST 노드를 OR 결합하는 이진 노드.
     *
     * `left OR right` 의미로 해석된다.
     *
     * @property left OR 의 좌항 노드.
     * @property right OR 의 우항 노드.
     */
    data class Or(
        val left: AqlNode,
        val right: AqlNode,
    ) : AqlNode

    /**
     * 단일 하위 노드를 부정하는 단항 노드.
     *
     * `NOT child` 의미로 해석된다.
     *
     * @property child 부정할 하위 AST 노드.
     */
    data class Not(
        val child: AqlNode,
    ) : AqlNode

    /**
     * 단일 필드에 대한 비교 표현식 — AST 의 리프 단위.
     *
     * `field op values` 또는 `field IN (values)` 형태로 표현된다.
     * [AqlOperator.IN] 과 [AqlOperator.NOT_IN] 연산자는 [values] 에 여러 값을 가진다.
     * 그 외 연산자는 [values] 에 단일 값을 가진다.
     *
     * @property field 비교 대상 필드. 예: [AqlField]("status").
     * @property op 비교 연산자. [AqlOperator] 참조.
     * @property values 비교 값 목록. IN/NOT_IN 은 복수, 그 외는 단수.
     */
    data class Comparison(
        val field: AqlField,
        val op: AqlOperator,
        val values: List<AqlValue>,
    ) : AqlNode
}

/**
 * AQL 필드 식별자 값 객체.
 *
 * AQL 쿼리에서 필드명(예: `status`, `label`, `summary`, `priority`)을 래핑한다.
 * 파서가 파싱한 원시 문자열을 타입 안전하게 전달하는 역할을 한다.
 *
 * @property value 필드명 원시 문자열. 예: `"status"`.
 */
@JvmInline
value class AqlField(val value: String)

/**
 * AQL 비교 연산자.
 *
 * AQL 문법에서 지원하는 비교 연산자를 열거한다.
 * 연산자별 적용 가능 필드 제약은 파서(AqlFields)가 검증한다.
 */
enum class AqlOperator {
    /** 정확 일치. `field = value` */
    EQ,

    /** 불일치. `field != value` */
    NEQ,

    /** 부분 문자열 포함. `field ~ value` (MVP: ILIKE %v%) */
    CONTAINS,

    /** 목록 중 하나와 일치. `field IN (v1, v2, ...)` */
    IN,

    /** 목록 중 어느 것과도 불일치. `field NOT IN (v1, v2, ...)` */
    NOT_IN,
}

/**
 * AQL 비교 값 sealed 계층.
 *
 * 파서가 파싱한 리터럴 값을 문자열([Str])과 정수([Num]) 로 구분해 타입 안전하게 전달한다.
 * 부동소수점·날짜·함수 값은 후속 PR 영역이므로 MVP 에서 제외한다.
 */
sealed interface AqlValue {
    /**
     * 문자열 리터럴 값.
     *
     * 큰따옴표로 감싼 문자열 또는 공백 없는 bare word 를 표현한다.
     * 예: `"open"`, `bug`, `"로그인 버그"`.
     *
     * @property value 파싱된 문자열 원시 값.
     */
    data class Str(val value: String) : AqlValue

    /**
     * 정수 리터럴 값.
     *
     * AQL 숫자 리터럴을 표현한다. 주로 priority 필드에 사용한다.
     * 예: `priority = 1`.
     *
     * @property value 파싱된 정수 원시 값.
     */
    data class Num(val value: Int) : AqlValue
}
