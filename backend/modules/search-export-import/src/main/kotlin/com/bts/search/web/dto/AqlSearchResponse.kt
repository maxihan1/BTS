// AQL 검색 응답 DTO — IssueSearchHit을 HTTP 응답 형식으로 직렬화 (FR-SR-02 FR-4)

package com.bts.search.web.dto

import com.bts.shared.search.IssueSearchHit
import java.time.Instant
import java.util.UUID

/**
 * AQL 이슈 검색 결과 단건 응답 DTO.
 *
 * [IssueSearchHit](shared-kernel 중립 View VO)을 HTTP 응답 직렬화 형식으로 변환한다.
 * 컨트롤러가 `Page<AqlSearchResponse>`를 raw Page로 반환하므로
 * `content[].{필드}` 형태로 직렬화된다.
 *
 * ### 필드 선택 기준
 *
 * 목록 행 UI에 즉시 표시하는 최소 필드만 포함한다([IssueSearchHit] 동일).
 * labels는 후속 PR에서 추가한다.
 *
 * @property key 이슈 키. 예: `"PROJ-1"`.
 * @property summary 이슈 제목.
 * @property typeKey 이슈 유형 키. 예: `"bug"`, `"task"`.
 * @property currentStateKey 현재 워크플로우 상태 키. 예: `"open"`.
 * @property assigneeId 담당자 UUID. 미배정이면 null.
 * @property priority 우선순위 숫자 값(1..5). 숫자 작을수록 높은 우선순위.
 * @property priorityName 우선순위 표시명. 예: `"Critical"`, `"Medium"`.
 * @property projectKey 이슈가 속한 프로젝트 키.
 * @property updatedAt 마지막 수정 시각(UTC). ISO-8601 직렬화.
 */
data class AqlSearchResponse(
    val key: String,
    val summary: String,
    val typeKey: String,
    val currentStateKey: String,
    val assigneeId: UUID?,
    val priority: Int,
    val priorityName: String,
    val projectKey: String,
    val updatedAt: Instant,
) {
    companion object {
        /**
         * [IssueSearchHit]에서 [AqlSearchResponse]로 변환한다.
         *
         * shared-kernel VO를 HTTP 응답 DTO로 변환하는 유일한 진입점이다.
         * 필드를 1:1 매핑하며 변환 중 손실되는 정보는 없다.
         *
         * @param hit shared-kernel 검색 결과 VO.
         * @return HTTP 응답 직렬화용 DTO.
         */
        fun from(hit: IssueSearchHit): AqlSearchResponse =
            AqlSearchResponse(
                key = hit.key,
                summary = hit.summary,
                typeKey = hit.typeKey,
                currentStateKey = hit.currentStateKey,
                assigneeId = hit.assigneeId,
                priority = hit.priority,
                priorityName = hit.priorityName,
                projectKey = hit.projectKey,
                updatedAt = hit.updatedAt,
            )
    }
}

/** [AqlSearchResponse]의 별칭. 스펙 명세에서 'AqlSearchHit'로 언급되는 타입과 동일하다. */
typealias AqlSearchHit = AqlSearchResponse
