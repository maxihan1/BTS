// Slack slash 명령어 AQL 검색 cross-BC 포트 계약 (slack-integration → search-export-import 위임)
package com.bts.shared.search

/**
 * Slack slash 명령어(`/atlas search <aql>`) 검색 cross-BC 포트 계약.
 *
 * slack-integration 모듈이 Slack 사용자 입력의 raw AQL 문자열([SlashSearchQuery.rawAql])을
 * search-export-import BC 에 위임하기 위한 포트 인터페이스다.
 *
 * @see SlashSearchQuery
 * @see SlashSearchOutcome
 */
interface SlashIssueSearchPort {
    /**
     * Slack slash 커맨드 객체를 받아 AQL 파싱 및 검색 결과를 반환한다.
     *
     * default 구현은 어댑터 부재 환경에서 빈 페이지를 담은 [SlashSearchOutcome.Success] 를
     * 반환한다(fail-safe).
     *
     * @param query slash 커맨드 객체. rawAql, projectKey, viewerUserId, page, size 포함.
     * @return 파싱/검색 결과. 어댑터 미등록 시 빈 페이지 [SlashSearchOutcome.Success].
     */
    fun search(query: SlashSearchQuery): SlashSearchOutcome {
        return SlashSearchOutcome.Success(IssueSearchPage.empty(query.page, query.size))
    }
}
