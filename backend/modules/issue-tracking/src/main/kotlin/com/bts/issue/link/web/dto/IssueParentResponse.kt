// 이슈 부모 설정/해제 응답 DTO — 이슈 키와 부모 요약 정보를 포함한다.

package com.bts.issue.link.web.dto

import com.bts.issue.domain.Issue

/**
 * `PATCH /api/v1/issues/{key}/parent` 응답 바디.
 *
 * @property key 이슈 키 (예: "BTS-1").
 * @property parent 설정된 부모 이슈 요약. 부모 없으면 null (JSON 필드 미포함).
 */
data class IssueParentResponse(
    val key: String,
    val parent: ParentDto?,
) {
    companion object {
        /**
         * 자식 이슈와 선택적 부모 이슈로 응답 DTO 를 생성한다.
         *
         * @param child 부모가 설정/해제된 자식 이슈.
         * @param parentIssue 부모 이슈. null 이면 부모 없음(해제).
         */
        fun from(
            child: Issue,
            parentIssue: Issue?,
        ): IssueParentResponse =
            IssueParentResponse(
                key = child.key.value,
                parent =
                    parentIssue?.let {
                        ParentDto(
                            key = it.key.value,
                            summary = it.summary,
                        )
                    },
            )
    }
}

/**
 * 부모 이슈 요약 DTO.
 *
 * @property key 부모 이슈 키.
 * @property summary 부모 이슈 제목.
 */
data class ParentDto(
    val key: String,
    val summary: String,
)
