// 이슈 키 prefix 예약어 차단 — ADR 2026-05-22-issue-key-prefix-policy §예약어 차단

package com.bts.issue.domain

/**
 * 이슈 키 prefix 예약어 차단.
 *
 * ADR `2026-05-22-issue-key-prefix-policy §예약어 차단` 기준.
 * 본 PR 의 dev seed (`ATLAS`) 는 SQL 직접 INSERT 라 이 검증을 우회한다.
 * Project Management 후속 PR 의 사용자 입력 API 에서 [isReserved] 를 호출해 차단한다.
 *
 * 예약어 추가/제거 시 ADR 갱신 + Maxi 승인 필요.
 */
object IssueKeyPrefixReservedWords {

    /**
     * 예약어 집합. 대문자 정규화 후 비교하므로 모든 원소는 대문자로 유지한다.
     *
     * 카테고리별 분류.
     * - 시스템. BTS/인프라 관련 단어
     * - 보안. 개발 도구 / 특수값 관련 단어
     * - HTTP. REST API 동사 (RFC 7231)
     * - SQL. DML/DDL 키워드
     */
    val WORDS: Set<String> = setOf(
        // 시스템
        "ADMIN", "API", "ATLAS", "BTS", "ROOT", "SYSTEM", "WWW",
        // 보안
        "DEBUG", "NULL", "TEST", "UNDEFINED",
        // HTTP
        "DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT",
        // SQL
        "DROP", "FROM", "INSERT", "SELECT", "TABLE", "UPDATE", "WHERE",
    )

    /**
     * [word] 가 예약어인지 대소문자 무관 검사한다.
     *
     * @param word 검사할 prefix 문자열
     * @return 예약어이면 `true`
     */
    fun isReserved(word: String): Boolean = WORDS.contains(word.uppercase())
}
