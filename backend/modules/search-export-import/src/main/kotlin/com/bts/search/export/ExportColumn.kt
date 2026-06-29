// 이슈 export 컬럼 정의와 IssueSearchHit 추출 로직 — 9필드 1:1 매핑

package com.bts.search.export

import com.bts.search.web.SearchValidationException
import com.bts.shared.search.IssueSearchHit

/**
 * 이슈 Export 컬럼 정의.
 *
 * [IssueSearchHit] 9필드와 1:1 대응한다.
 * 영문 헤더 라벨과 각 필드 추출 로직을 캡슐화한다.
 * CSV/XLSX 두 형식 모두에서 헤더 행 조립과 셀 값 추출에 사용한다.
 *
 * @property headerLabel CSV/XLSX 헤더 행에 표시되는 영문 라벨.
 * @see ExportCellSanitizer formula injection 방어 — extract 결과에 적용
 */
enum class ExportColumn(val headerLabel: String) {
    KEY("Key"),
    SUMMARY("Summary"),
    TYPE("Type"),
    STATUS("Status"),
    ASSIGNEE_ID("Assignee ID"),
    PRIORITY("Priority"),
    PRIORITY_NAME("Priority Name"),
    PROJECT("Project"),
    UPDATED_AT("Updated At"),
    ;

    /**
     * [IssueSearchHit]에서 이 컬럼에 해당하는 값을 문자열로 추출한다.
     *
     * 변환 규칙.
     * - [ASSIGNEE_ID]: `null` → `""` (빈 문자열)
     * - [PRIORITY]: `Int` → `String` (예: `2` → `"2"`)
     * - [UPDATED_AT]: `Instant` → ISO-8601 UTC 문자열 (예: `"2024-03-15T10:30:00Z"`)
     *   — 검색 응답([AqlSearchResponse])과 동일한 직렬화 방식.
     *
     * formula injection 방어는 호출자([ExportCellSanitizer.sanitize])가 담당한다.
     *
     * @param hit 검색 결과 단건 VO.
     * @return 셀 값 문자열. nullable 필드는 빈 문자열로 처리한다.
     */
    fun extract(hit: IssueSearchHit): String =
        when (this) {
            KEY -> hit.key
            SUMMARY -> hit.summary
            TYPE -> hit.typeKey
            STATUS -> hit.currentStateKey
            ASSIGNEE_ID -> hit.assigneeId?.toString().orEmpty()
            PRIORITY -> hit.priority.toString()
            PRIORITY_NAME -> hit.priorityName
            PROJECT -> hit.projectKey
            // Instant.toString()은 ISO-8601 UTC 문자열을 반환한다 (예: "2024-03-15T10:30:00Z")
            // Jackson JavaTimeModule + WRITE_DATES_AS_TIMESTAMPS=false 와 동일한 직렬화 결과
            UPDATED_AT -> hit.updatedAt.toString()
        }

    companion object {
        /**
         * 컬럼 이름 → [ExportColumn] 역인덱스 맵. 클래스 로드 시 1회 생성된다.
         *
         * `parse` 호출마다 맵을 재생성하지 않고 여기서 O(1) 조회한다.
         */
        private val BY_NAME: Map<String, ExportColumn> = entries.associateBy { it.name }

        /**
         * 컬럼 이름 목록을 파싱해 [ExportColumn] 목록으로 변환한다.
         *
         * null 또는 빈 목록을 전달하면 전체 9컬럼을 enum 선언 순서(표준 순서)로 반환한다.
         * 부분집합을 전달해도 요청 순서 무관하게 enum 선언 순서로 정렬해 반환한다(결정성 보장).
         * 컬럼 이름은 대소문자를 구분하며 enum 상수 이름(대문자)과 정확히 일치해야 한다.
         *
         * @param names 요청한 컬럼 이름 목록. null 또는 빈 목록이면 전체 9컬럼을 반환한다.
         * @return 표준 순서(enum 선언 순서)로 정렬된 [ExportColumn] 목록.
         * @throws SearchValidationException [names]에 지원하지 않는 컬럼 이름이 포함된 경우.
         *   IAE 대신 [SearchValidationException]을 사용하여 catch-all 핸들러의 500 변질을 방지한다.
         */
        fun parse(names: List<String>?): List<ExportColumn> {
            if (names.isNullOrEmpty()) return entries.toList()
            val resolved =
                names.map { name ->
                    BY_NAME[name] ?: throw SearchValidationException("지원하지 않는 컬럼: '$name'")
                }
            return resolved.sortedBy { it.ordinal }
        }
    }
}
