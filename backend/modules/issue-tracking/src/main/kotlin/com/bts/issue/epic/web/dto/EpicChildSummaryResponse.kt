// 에픽에 연결된 자식 이슈 단건 요약 응답 DTO

package com.bts.issue.epic.web.dto

import com.bts.issue.domain.Issue
import com.bts.issue.type.domain.IssueType

/**
 * 에픽 자식 이슈 단건 요약 응답 DTO (FR-EP-01 Task 6).
 *
 * POST `/api/v1/issues/{epicKey}/epic-children` 201 응답과
 * GET `/api/v1/issues/{epicKey}/epic-children` 목록 원소 타입으로 사용된다.
 *
 * @property key 이슈 키. 예: `"ATLAS-42"`.
 * @property summary 이슈 제목.
 * @property typeKey 이슈 유형 키. 예: `"task"`. 타입 미조회 시 null.
 * @property currentStateKey 현재 워크플로우 상태 키. 예: `"open"`.
 */
data class EpicChildSummaryResponse(
    val key: String,
    val summary: String,
    val typeKey: String?,
    val currentStateKey: String,
) {
    companion object {
        /**
         * [Issue] + [IssueType] 에서 [EpicChildSummaryResponse] 를 생성한다.
         *
         * @param issue 자식 이슈 도메인 엔티티.
         * @param issueType 이슈 타입 정보. 타입 조회 실패 시 null.
         * @return [EpicChildSummaryResponse] 인스턴스.
         */
        fun from(
            issue: Issue,
            issueType: IssueType?,
        ): EpicChildSummaryResponse =
            EpicChildSummaryResponse(
                key = issue.key.value,
                summary = issue.summary,
                typeKey = issueType?.key?.value,
                currentStateKey = issue.currentStateKey,
            )
    }
}
