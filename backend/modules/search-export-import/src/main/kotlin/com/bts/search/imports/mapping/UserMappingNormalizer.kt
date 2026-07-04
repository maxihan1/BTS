// FR-IM-02 Import 사용자 매핑 정규화 + 작성자 식별자 수집 순수 헬퍼 (Task 3)

package com.bts.search.imports.mapping

import com.bts.search.imports.parse.ParsedImportRow

/**
 * Import 사용자 매핑에서 소스 식별자(이메일)를 정규화하고, 한 행([ParsedImportRow])에 등장하는
 * 전 작성자 식별자를 수집하는 순수 헬퍼.
 *
 * F2 정규화 삼자 일치의 유일 진실원천이다 — distinct 식별자 수집(Task 5 `ImportMappingService`)·
 * `import_user_mappings` 저장 키·프로세서 행별 해석(Task 6 `ImportJobProcessor`)이 반드시 이
 * [normalize] 를 거쳐야 하며, 별도로 대소문자/공백을 정규화하지 않는다. 어긋나면 같은 사용자를
 * 가리키는 식별자가 서로 다른 키로 취급되어 조용한 오배정(엉뚱한 사용자에게 배정)이 발생한다.
 */
object UserMappingNormalizer {

    /**
     * 소스 식별자(이메일) 원본 문자열을 정규화한다 — 앞뒤 공백 제거 후 소문자 변환.
     *
     * @param raw 원본 식별자 문자열
     * @return 정규화된 식별자 문자열
     */
    fun normalize(raw: String): String = raw.trim().lowercase()

    /**
     * 한 행에 등장하는 전 작성자 식별자를 정규화해 수집한다.
     *
     * 수집 대상은 다음 6종 필드다.
     * - [ParsedImportRow.reporterEmail]
     * - [ParsedImportRow.assigneeEmail]
     * - [ParsedImportRow.comments] 의 각 `authorEmail`
     * - [ParsedImportRow.worklogs] 의 각 `authorEmail`
     * - [ParsedImportRow.attachments] 의 각 `authorEmail`
     * - [ParsedImportRow.changelog] 의 각 `authorEmail`
     *
     * null 이거나 공백만으로 이뤄진 식별자는 제외한다. 대소문자/공백만 다른 식별자는 [normalize] 를
     * 거쳐 하나로 합쳐진다(dedup).
     *
     * @param row 파싱된 import 행 1건
     * @return 정규화된 작성자 식별자 집합(dedup 완료)
     */
    fun collectIdentifiers(row: ParsedImportRow): Set<String> {
        val rawIdentifiers =
            listOf(row.reporterEmail, row.assigneeEmail) +
                row.comments.map { it.authorEmail } +
                row.worklogs.map { it.authorEmail } +
                row.attachments.map { it.authorEmail } +
                row.changelog.map { it.authorEmail }

        return rawIdentifiers
            .mapNotNull { it }
            .filter { it.isNotBlank() }
            .map { normalize(it) }
            .toSet()
    }
}
