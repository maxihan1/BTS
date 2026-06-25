// AQL 지원 필드 화이트리스트 — MVP 필드 집합과 연산자 제약의 단일 진실출처

package com.bts.search.aql

import com.bts.shared.search.AqlOperator

/**
 * AQL 지원 필드 화이트리스트 — MVP 필드 집합과 연산자 제약의 단일 진실출처.
 *
 * 파서([AqlParser])가 필드명을 검증할 때 이 객체만 참조한다.
 * 스펙 FR-2와 ADR D3의 필드 목록을 코드로 고정한다.
 *
 * ### 분류
 *
 * - [MVP_FIELDS] — MVP 에서 지원하는 필드. 쿼리에 사용 가능.
 * - [PLANNED_FIELDS] — 후속 PR 에서 지원 예정인 필드. 현재 사용 시 [AqlErrorCode.SEARCH_FIELD_NOT_YET_SUPPORTED].
 * - 그 외 필드 — 오타나 존재하지 않는 필드. [AqlErrorCode.SEARCH_UNKNOWN_FIELD].
 *
 * ### 연산자 제약
 *
 * 특정 필드에 허용되지 않는 연산자가 있다([FIELD_OPERATOR_CONSTRAINTS] 참조).
 * 예: `priority ~ 1` 은 오류(SMALLINT 컬럼에 부분 문자열 불일치).
 */
object AqlFields {

    /**
     * MVP 에서 지원하는 필드 이름 집합 (소문자 정규화).
     *
     * 스펙 FR-2 및 ADR D3 기준.
     * - `status` — current_state_key (text): `=` `!=` `IN` `NOT IN`
     * - `label` — 라벨 (TEXT[] 배열): `=` `!=` `IN` `NOT IN` `~`
     * - `summary` — 제목 (text): `~` `=`
     * - `priority` — 우선순위 (SMALLINT 1..5): `=` `!=` `IN` `NOT IN` (`~` 불가)
     */
    val MVP_FIELDS: Set<String> = setOf("status", "label", "summary", "priority")

    /**
     * 후속 PR 에서 지원 예정인 필드 이름 집합 (소문자 정규화).
     *
     * 사용자 식별자 해석(username→UUID, 이름→ID)이 필요하거나
     * `currentUser()` 함수와 함께 제공될 필드들이다.
     * MVP 에서 이 필드를 쿼리에 사용하면 [AqlErrorCode.SEARCH_FIELD_NOT_YET_SUPPORTED] 를 반환한다.
     */
    val PLANNED_FIELDS: Set<String> = setOf("assignee", "reporter", "component", "project")

    /**
     * 필드별 허용되지 않는 연산자 맵.
     *
     * 키: 필드명(소문자), 값: 해당 필드에서 **사용할 수 없는** 연산자 집합.
     *
     * 현재 제약.
     * - `priority` — `CONTAINS(~)` 불가. SMALLINT 컬럼이라 부분 문자열 비교가 의미 없다.
     */
    private val FIELD_OPERATOR_CONSTRAINTS: Map<String, Set<AqlOperator>> =
        mapOf(
            "priority" to setOf(AqlOperator.CONTAINS),
        )

    /**
     * 필드명을 검증하고 분류를 반환한다.
     *
     * @param fieldName 검증할 필드명 (원본 대소문자 그대로).
     * @return 필드 분류 결과 [FieldClassification].
     */
    fun classify(fieldName: String): FieldClassification {
        val lower = fieldName.lowercase()
        return when {
            lower in MVP_FIELDS -> FieldClassification.SUPPORTED
            lower in PLANNED_FIELDS -> FieldClassification.PLANNED
            else -> FieldClassification.UNKNOWN
        }
    }

    /**
     * 주어진 필드와 연산자 조합이 유효한지 확인한다.
     *
     * @param fieldName 필드명 (원본 대소문자 그대로).
     * @param op 검증할 연산자.
     * @return 해당 연산자가 금지된 경우 true.
     */
    fun isOperatorForbidden(
        fieldName: String,
        op: AqlOperator,
    ): Boolean {
        val lower = fieldName.lowercase()
        val forbidden = FIELD_OPERATOR_CONSTRAINTS[lower] ?: return false
        return op in forbidden
    }

    /** AQL 필드 분류 결과. */
    enum class FieldClassification {
        /** MVP 에서 지원하는 필드. */
        SUPPORTED,

        /** 후속 PR 에서 지원 예정인 필드. */
        PLANNED,

        /** 인식할 수 없는 필드 (오타 등). */
        UNKNOWN,
    }
}
