// Slack slash search 서브커맨드 결과 타입 — 문법 오류를 예외 대신 값으로 전달
package com.bts.shared.search

/**
 * [SlashIssueSearchPort.search] 의 결과를 표현하는 sealed 계층.
 *
 * AQL 문법 오류를 예외로 던지지 않고 [SyntaxError] 값으로 전달하는 이유는,
 * cross-BC 경계를 넘는 예외 클래스명 문자열 매칭을 금지하는 원칙 때문이다
 * (search-export-import 의 내부 예외 타입을 slack-integration 이 gradle 의존할 수 없다).
 * 결과 타입으로 명시하면 호출부가 컴파일 타임에 두 케이스를 모두 처리하도록 강제된다.
 *
 * @see SlashIssueSearchPort
 */
sealed interface SlashSearchOutcome {
    /**
     * AQL 파싱과 검색이 모두 성공한 경우.
     *
     * @property page 검색 결과 페이지. 조건에 맞는 이슈가 없으면 빈 페이지([IssueSearchPage.empty]).
     */
    data class Success(
        val page: IssueSearchPage,
    ) : SlashSearchOutcome

    /**
     * AQL 원문 파싱에 실패한 경우.
     *
     * @property reason 사용자에게 노출 가능한 오류 사유 요약. 내부 예외 스택트레이스나
     *   구현 세부사항을 담지 않는다.
     */
    data class SyntaxError(
        val reason: String,
    ) : SlashSearchOutcome
}
