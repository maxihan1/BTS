// Slack slash 명령어 search 서브커맨드 커맨드 객체 — 파서 미거침 raw AQL 문자열 그대로 전달
package com.bts.shared.search

import java.util.UUID

/**
 * Slack slash 명령어(`/atlas search <aql>`) 검색 커맨드 객체.
 *
 * slack-integration 모듈이 Slack 사용자 입력에서 파싱한 raw AQL 문자열을
 * [SlashIssueSearchPort.search] 에 전달하기 위한 커맨드 DTO 다.
 * [IssueSearchQuery] 선례와 동일하게, 위치 인자 다중 파라미터 대신 커맨드 객체를 채택한 이유는
 * 후속 확장(정렬 옵션, 페이지네이션 UI)에서 포트 시그니처를 변경하지 않고 필드를 추가할 수
 * 있기 때문이다.
 *
 * ### 포트 계약상 불변 보장
 *
 * 이 클래스의 모든 필드는 val 로 선언된 불변 값이다.
 * 커맨드 객체 자체는 생성 후 수정되지 않는다.
 *
 * ### AST 가 아닌 raw 문자열을 담는 이유
 *
 * [IssueSearchQuery] 와 달리 이미 파싱된 [AqlNode] 대신 [rawAql] 원문 문자열을 담는다.
 * AQL 렉서/파서는 search-export-import BC 의 내부 구현이며, slack-integration BC 는
 * 이를 gradle 의존할 수 없다(BC 경계). 파싱은 [SlashIssueSearchPort] 구현체(어댑터)가
 * 수행하고, 문법 오류는 [SlashSearchOutcome.SyntaxError] 로 전달한다.
 *
 * ### projectKey 단일 스코프
 *
 * Slack slash 명령어는 인라인 인자로 프로젝트 키를 필수로 받는다(채널-프로젝트 매핑 미의존).
 * [IssueSearchQuery.projectKey] 와 동일하게 단일 스코프만 지원한다.
 *
 * @property rawAql 사용자가 입력한 AQL 원문 문자열. 파싱은 어댑터 책임.
 * @property projectKey 검색 대상 프로젝트 키. 예: `"PROJ"`.
 * @property viewerUserId 검색을 요청한 Slack 연동 BTS 사용자 UUID. visibility 필터 및 권한 검증 기준.
 * @property page 요청 페이지 번호(0-base).
 * @property size 요청 페이지 크기(1..100).
 * @see SlashIssueSearchPort
 * @see SlashSearchOutcome
 */
data class SlashSearchQuery(
    val rawAql: String,
    val projectKey: String,
    val viewerUserId: UUID,
    val page: Int,
    val size: Int,
)
