// AQL 지원 필드 화이트리스트 + 연산자 제약의 단일 진실출처 — shared-kernel 공유 객체

package com.bts.shared.search

/**
 * AQL 지원 필드 화이트리스트 — MVP 필드 집합과 연산자 제약의 단일 진실출처.
 *
 * 파서([com.bts.search.aql.AqlParser])와 이슈 검색 어댑터([com.bts.issue.adapter.outbound.search.IssueSearchAdapter])
 * 양쪽이 이 객체만 참조한다. drift를 구조적으로 차단한다.
 *
 * BC 격리 원칙상 issue-tracking은 search 모듈을 직접 import할 수 없으므로
 * 공유 정보는 반드시 shared-kernel에 위치해야 한다(AST-as-contract 선례).
 *
 * ### 분류
 *
 * - [MVP_FIELDS] — MVP 에서 지원하는 필드. 쿼리에 사용 가능.
 * - [PLANNED_FIELDS] — 후속 PR 에서 지원 예정인 필드. 현재 사용 시 SEARCH_FIELD_NOT_YET_SUPPORTED.
 * - 그 외 필드 — 오타나 존재하지 않는 필드. SEARCH_UNKNOWN_FIELD.
 *
 * ### 연산자 제약
 *
 * 특정 필드에 허용되지 않는 연산자가 있다([FIELD_OPERATOR_CONSTRAINTS] 참조).
 * - `priority` — `CONTAINS(~)` 불가. SMALLINT 컬럼이라 부분 문자열 비교가 의미 없다.
 * - `status` — `CONTAINS(~)` 불가. text 키 컬럼이라 부분 일치가 의미 없고 repository 오류를 유발.
 * - `text` — `=` `!=` `IN` `NOT IN` 불가. 가상 FTS 필드로 `~` 전문 검색만 허용 (FR-SR-04 ADR D4).
 *
 * ### 숫자 범위 제약
 *
 * - [SHORT_MIN] ~ [SHORT_MAX]: priority 는 SMALLINT 컬럼이므로 이 범위를 벗어난 숫자는 파서에서 거부한다.
 *   repository `asShort()` 에서 조용한 overflow wrap 이 발생하는 것을 사전 차단한다.
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
     * - `text` — 가상 FTS 필드 (summary + description 전문 검색 대상): `~` 만 허용 (FR-SR-04)
     *   실제 DB 컬럼이 아니며, issues.search_vector 를 경유한 tsvector + trigram 하이브리드 검색으로
     *   변환된다. `=` `!=` `IN` `NOT IN` 는 FTS 의미가 없으므로 금지한다.
     */
    val MVP_FIELDS: Set<String> = setOf("status", "label", "summary", "priority", "text")

    /**
     * ORDER BY 에서 정렬 가능한 필드 이름 집합 (소문자 정규화).
     *
     * [com.bts.issue.repository.IssueRepository]의 `buildOrderBy` 화이트리스트와
     * **정확히 일치해야 한다**. 두 곳 중 하나를 수정할 때는 반드시 이 집합도 함께 수정한다
     * (단일 진실출처 원칙 — drift 방지).
     *
     * - `status` — current_state_key (TEXT)
     * - `summary` — SUMMARY (TEXT)
     * - `priority` — PRIORITY (SMALLINT)
     * - `created_at` — CREATED_AT (TIMESTAMPTZ)
     * - `updated_at` — UPDATED_AT (TIMESTAMPTZ)
     *
     * `text`, `label` 등 MVP_FIELDS 에 있어도 정렬 불가인 필드는 여기에 포함하지 않는다.
     * [com.bts.search.aql.AqlParser]의 `parseSortItem` 이 이 집합으로 ORDER BY 필드를
     * 검증해 미포함 시 [AqlSyntaxException][com.bts.search.aql.AqlSyntaxException] 을 던진다.
     */
    val SORTABLE_FIELDS: Set<String> = setOf("status", "summary", "priority", "created_at", "updated_at")

    /**
     * 후속 PR 에서 지원 예정인 필드 이름 집합 (소문자 정규화).
     *
     * 사용자 식별자 해석(username→UUID, 이름→ID)이 필요하거나
     * `currentUser()` 함수와 함께 제공될 필드들이다.
     * MVP 에서 이 필드를 쿼리에 사용하면 SEARCH_FIELD_NOT_YET_SUPPORTED 를 반환한다.
     */
    val PLANNED_FIELDS: Set<String> = setOf("assignee", "reporter", "component", "project")

    /**
     * SMALLINT 최솟값 — PostgreSQL `smallint` 컬럼 하한.
     *
     * priority 필드에 입력되는 숫자가 이 범위를 벗어나면 파서에서 즉시 거부한다.
     */
    const val SHORT_MIN: Int = -32768

    /**
     * SMALLINT 최댓값 — PostgreSQL `smallint` 컬럼 상한.
     *
     * priority 필드에 입력되는 숫자가 이 범위를 벗어나면 파서에서 즉시 거부한다.
     */
    const val SHORT_MAX: Int = 32767

    /**
     * 필드별 허용되지 않는 연산자 맵.
     *
     * 키: 필드명(소문자), 값: 해당 필드에서 **사용할 수 없는** 연산자 집합.
     *
     * 현재 제약.
     * - `priority` — `CONTAINS(~)` 불가. SMALLINT 컬럼이라 부분 문자열 비교가 의미 없다.
     * - `status` — `CONTAINS(~)` 불가. text 키 컬럼으로 부분 일치 미지원, repository IllegalArgument 유발.
     * - `text` — `EQ(=)` `NEQ(!=)` `IN` `NOT_IN` 불가.
     *   가상 FTS 필드로 `CONTAINS(~)` 전문 검색만 의미가 있다. 등가/목록 비교는 FTS 맥락에서 정의되지
     *   않으므로 파서에서 사전 거부해 repository IllegalArgument 를 방지한다.
     */
    private val FIELD_OPERATOR_CONSTRAINTS: Map<String, Set<AqlOperator>> =
        mapOf(
            "priority" to setOf(AqlOperator.CONTAINS),
            "status" to setOf(AqlOperator.CONTAINS),
            "text" to setOf(AqlOperator.EQ, AqlOperator.NEQ, AqlOperator.IN, AqlOperator.NOT_IN),
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
     * 주어진 필드와 연산자 조합이 허용되지 않는지 확인한다.
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
