// 이슈 부모 설정/해제 응답 DTO — 이슈 키와 부모 요약 정보를 포함한다.

package com.bts.issue.link.web.dto

import com.bts.issue.domain.Issue
import com.fasterxml.jackson.annotation.JsonInclude

/**
 * `PATCH /api/v1/issues/{key}/parent` 응답 바디.
 *
 * `parent` 가 null 이면 [JsonInclude.Include.NON_NULL] 로 응답 키 자체가 제거된다
 * (형제 DTO 컨벤션 + 프론트 Zod nullish 정합 — prod ObjectMapper 기본값에 의존하지 않음).
 *
 * @property key 이슈 키 (예: "BTS-1").
 * @property parent 설정된 부모 이슈 요약. 부모 없으면 null (JSON 필드 미포함).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
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
