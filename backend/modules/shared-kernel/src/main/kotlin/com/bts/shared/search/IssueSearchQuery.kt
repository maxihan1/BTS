// AQL 이슈 검색 커맨드 객체 — 파서→포트 계약 DTO

package com.bts.shared.search

import java.util.UUID

/**
 * AQL 이슈 검색 커맨드 객체.
 *
 * search 모듈이 AQL 쿼리를 파싱한 뒤 [IssueSearchPort.search] 에 전달하는
 * 단일 커맨드 DTO 다. 위치 인자 다중 파라미터 대신 커맨드 객체를 채택한 이유는
 * 후속 확장(projectKeys 복수화, currentUser()/now() 함수 평가 컨텍스트)에서
 * 포트 시그니처를 변경하지 않고 필드를 추가할 수 있기 때문이다.
 *
 * ### 포트 계약상 불변 보장
 *
 * 이 클래스의 모든 필드는 val 로 선언된 불변 값이다.
 * 커맨드 객체 자체는 생성 후 수정되지 않는다.
 *
 * ### projectKey 단일 스코프 (MVP)
 *
 * MVP 에서는 단일 프로젝트 스코프만 지원한다.
 * visibility 보안 술어([IssueSearchPort] 구현체 책임)가 프로젝트 단위이므로
 * 단일 스코프가 가장 단순하고 안전하다.
 * 후속 PR 에서 cross-project 검색 지원 시 `projectKey` → `projectKeys: List<String>` 로
 * 확장할 수 있다.
 *
 * @property projectKey 검색 대상 프로젝트 키. MVP 는 단일 스코프 필수. 예: `"PROJ"`.
 * @property ast AQL 파서가 생성한 추상 구문 트리. [AqlNode] 루트 노드.
 * @property sort ORDER BY 절 정렬 기준 목록. 빈 목록이면 기본 정렬(created_at DESC) 적용.
 * @property viewerUserId 검색을 요청한 사용자 UUID. visibility 필터 및 권한 검증 기준.
 * @property page 요청 페이지 번호(0-base).
 * @property size 요청 페이지 크기(1..100).
 * @see IssueSearchPort
 * @see IssueSearchPage
 */
data class IssueSearchQuery(
    val projectKey: String,
    val ast: AqlNode,
    val sort: List<AqlSort>,
    val viewerUserId: UUID,
    val page: Int,
    val size: Int,
)
