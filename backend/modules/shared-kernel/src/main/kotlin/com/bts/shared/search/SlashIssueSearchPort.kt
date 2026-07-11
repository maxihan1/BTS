// Slack slash 명령어 AQL 검색 cross-BC 포트 계약 (slack-integration → search-export-import 위임)
package com.bts.shared.search

/**
 * Slack slash 명령어(`/atlas search <aql>`) 검색 cross-BC 포트 계약.
 *
 * slack-integration 모듈이 Slack 사용자 입력의 raw AQL 문자열([SlashSearchQuery.rawAql])을
 * search-export-import BC 에 위임하기 위한 포트 인터페이스다. 의존 방향은 아래와 같다.
 *
 * ```
 * slack ──(port)──▶ shared-kernel ◀──(impl)── search-export-import
 * ```
 *
 * slack-integration 모듈은 search-export-import 내부(AQL 렉서/파서)를 직접 gradle
 * 의존하지 않는다. 이 포트를 shared-kernel 에 배치함으로써 BC 경계를 ArchUnit 이 강제한다.
 *
 * ### 파싱 소유는 search-export-import BC
 *
 * AQL 렉서/파서는 search-export-import BC 의 내부 구현이다. slack-integration 은 이
 * 파서를 gradle 의존할 수 없으므로, [SlashSearchQuery.rawAql] 원문 문자열을 그대로
 * 이 포트에 전달하고 실제 파싱은 구현체(어댑터)가 수행한다.
 *
 * ### 문법 오류는 예외가 아닌 결과 타입으로 전달
 *
 * AQL 파싱 실패는 예외를 던지지 않고 [SlashSearchOutcome.SyntaxError] 값으로 반환한다.
 * cross-BC 경계를 넘는 예외 클래스명 문자열 매칭은 금지 원칙이다(search-export-import
 * 내부 예외 타입을 slack-integration 이 gradle 의존할 수 없어 타입으로 구분 불가능하기
 * 때문). 결과 타입 [SlashSearchOutcome] 은 컴파일 타임에 호출부가 성공/실패 두 케이스를
 * 모두 처리하도록 강제한다.
 *
 * ### fail-safe default 구현 — 읽기는 빈 결과 방향
 *
 * 어댑터(search-export-import 구현체)가 등록되지 않은 환경(단위 테스트, 단계적 배포)에서
 * default 구현이 빈 페이지를 담은 [SlashSearchOutcome.Success] 를 반환한다.
 * 데이터 조회 fail-safe — 보안 판정이 아니므로 빈 결과(데이터 누출 0)가 적절하다.
 * 권한 resolver 의 fail-closed 방향(권한 없음으로 거부)과 반대인 것은 의도적이다.
 *
 * ### visibility 필터 책임
 *
 * [SlashSearchQuery.viewerUserId] 가 볼 수 없는 보안 등급 이슈는 최종적으로
 * issue-tracking BC 의 [IssueSearchPort] 구현체가 SQL 수준에서 필터한다
 * (search-export-import 어댑터가 파싱 후 [IssueSearchPort] 에 위임하는 구조).
 * 이 포트를 소비하는 slack-integration 모듈은 필터 여부를 알지 못한다.
 *
 * @see SlashSearchQuery
 * @see SlashSearchOutcome
 * @see IssueSearchPort
 */
interface SlashIssueSearchPort {
    /**
     * Slack slash 커맨드 객체를 받아 AQL 파싱 및 검색 결과를 반환한다.
     *
     * 구현체(search-export-import 어댑터)는 [SlashSearchQuery.rawAql] 을 렉싱/파싱한 뒤
     * [IssueSearchPort] 에 위임한다. 파싱 실패는 [SlashSearchOutcome.SyntaxError] 로 반환한다.
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
