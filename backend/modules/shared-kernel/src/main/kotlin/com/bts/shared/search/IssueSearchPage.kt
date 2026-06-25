// AQL 검색 결과 페이지 + 이슈 결과 단건 View VO

package com.bts.shared.search

import java.time.Instant
import java.util.UUID

/**
 * AQL 이슈 검색 결과 페이지 VO.
 *
 * [IssueSearchPort.search] 가 반환하는 읽기 전용 값 객체.
 * Spring Data 의 [Page] 와 동형이지만 shared-kernel 은 Spring 에 의존하지 않으므로
 * BC 비의존 커스텀 페이지 타입을 사용한다.
 *
 * @property items 현재 페이지의 이슈 결과 목록. 빈 목록이면 조건에 맞는 이슈가 없는 것이다.
 * @property total 필터 조건 전체의 총 이슈 수. 페이지네이션 UI 에 사용한다.
 * @property page 현재 페이지 번호(0-base).
 * @property size 요청한 페이지 크기.
 * @see IssueSearchHit
 * @see IssueSearchPort
 */
data class IssueSearchPage(
    val items: List<IssueSearchHit>,
    val total: Long,
    val page: Int,
    val size: Int,
) {
    companion object {
        /**
         * 결과 없음을 나타내는 빈 페이지를 생성한다.
         *
         * [IssueSearchPort] 의 default fail-safe 구현과 어댑터 부재 환경에서 사용한다.
         * 데이터 누출 없이 안전한 빈 응답을 반환한다.
         *
         * @param page 요청 페이지 번호(0-base). 응답에 그대로 반영된다.
         * @param size 요청 페이지 크기. 응답에 그대로 반영된다.
         * @return items 가 빈 목록이고 total 이 0 인 [IssueSearchPage].
         */
        fun empty(page: Int, size: Int): IssueSearchPage =
            IssueSearchPage(items = emptyList(), total = 0L, page = page, size = size)
    }
}

/**
 * AQL 이슈 검색 결과 단건 View VO.
 *
 * [IssueSearchPage.items] 의 원소로, 검색 결과 목록 행 UI 에 필요한 최소 필드를 포함한다.
 * issue-tracking 어댑터가 이 VO 를 조립해 반환하며, search 모듈 컨트롤러가
 * 응답 DTO([AqlSearchHit])로 직렬화한다.
 *
 * ### 필드 선택 기준
 *
 * 목록 행 UI 에 즉시 표시하는 필드만 포함한다([IssueResponse] 선례 참조).
 * labels 는 후속 PR 에서 추가한다.
 *
 * @property key 이슈 키. 예: `"PROJ-1"`.
 * @property summary 이슈 제목. 목록 행 주 텍스트.
 * @property typeKey 이슈 유형 키. 예: `"bug"`, `"task"`.
 * @property currentStateKey 현재 워크플로우 상태 키. 예: `"open"`.
 * @property assigneeId 담당자 UUID. 미배정이면 null.
 * @property priority 우선순위 숫자 값(1..5). 숫자 작을수록 높은 우선순위.
 * @property priorityName 우선순위 표시명. 예: `"Critical"`, `"Medium"`.
 * @property projectKey 이슈가 속한 프로젝트 키. 예: `"PROJ"`.
 * @property updatedAt 마지막 수정 시각(UTC). ISO-8601 로 직렬화된다.
 */
data class IssueSearchHit(
    val key: String,
    val summary: String,
    val typeKey: String,
    val currentStateKey: String,
    val assigneeId: UUID?,
    val priority: Int,
    val priorityName: String,
    val projectKey: String,
    val updatedAt: Instant,
)
