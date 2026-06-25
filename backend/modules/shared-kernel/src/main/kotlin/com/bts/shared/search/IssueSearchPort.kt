// AQL 이슈 검색 cross-BC 포트 계약 (search → issue-tracking 위임)

package com.bts.shared.search

/**
 * AQL 이슈 검색 cross-BC 포트 계약.
 *
 * search 모듈이 AQL 파싱 결과([IssueSearchQuery])를 issue-tracking BC 에 위임하기 위한
 * 포트 인터페이스. 의존 방향은 아래와 같다.
 *
 * ```
 * search-export-import ──(port)──▶ shared-kernel ◀──(impl)── issue-tracking
 * ```
 *
 * search 모듈은 issue-tracking 내부를 직접 gradle 의존하지 않는다.
 * 이 포트를 shared-kernel 에 배치함으로써 BC 경계를 ArchUnit 이 강제한다.
 *
 * ### fail-safe default 구현
 *
 * 어댑터(issue-tracking 구현체)가 등록되지 않은 환경(단위 테스트, 단계적 배포)에서
 * default 구현이 빈 [IssueSearchPage] 를 반환한다.
 * 데이터 조회 fail-safe — 보안 판정이 아니므로 빈 결과(데이터 누출 0)가 적절하다.
 * 권한 resolver 의 fail-closed 방향과 다른 것은 의도적이다.
 *
 * ### visibility 필터 책임
 *
 * [IssueSearchQuery.viewerUserId] 가 볼 수 없는 보안 등급 이슈는
 * 구현체가 SQL 수준에서 필터해야 한다.
 * visibility 보안 술어는 최상위 AND 로 항상 결합되며 우회할 수 없다(FR-3).
 * 이 포트를 소비하는 search 모듈은 필터 여부를 알지 못한다.
 *
 * @see IssueSearchQuery
 * @see IssueSearchPage
 * @see BoardIssueLookupPort
 */
interface IssueSearchPort {

    /**
     * AQL 커맨드 객체를 받아 가시 이슈 검색 결과를 반환한다.
     *
     * 구현체는 [IssueSearchQuery.ast] 를 jOOQ Condition 으로 재귀 변환하고
     * visibility 보안 술어를 최상위 AND 로 결합한 뒤 실행한다.
     *
     * default 구현은 어댑터 부재 환경에서 빈 페이지를 반환한다(fail-safe).
     *
     * @param query AQL 커맨드 객체. projectKey, AST, sort, viewerUserId, page, size 포함.
     * @return 검색 결과 페이지. 어댑터 미등록 시 빈 페이지([IssueSearchPage.empty]).
     */
    fun search(query: IssueSearchQuery): IssueSearchPage =
        IssueSearchPage.empty(query.page, query.size)
}
