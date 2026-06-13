// 이슈 링크 단건 응답 DTO — linkId, 방향, 라벨, 상대 이슈 정보를 포함한다.

package com.bts.issue.link.web.dto

import com.bts.issue.link.application.LinkEntry
import com.bts.issue.link.application.LinkResult

/**
 * 링크 단건 항목 응답. `POST /links` 성공 응답 및 `GET /links` 리스트 항목으로 사용된다.
 *
 * @property id 링크 BIGINT 식별자.
 * @property linkType 링크 유형 코드 대문자 (예: "BLOCKS").
 * @property direction 방향 — "OUTWARD" 또는 "INWARD".
 * @property label 이 이슈 관점의 관계 라벨 (예: "blocks", "is blocked by").
 * @property otherIssue 상대 이슈 요약 정보.
 */
data class IssueLinkResponse(
    val id: Long,
    val linkType: String,
    val direction: String,
    val label: String,
    val otherIssue: OtherIssueDto,
) {
    companion object {
        /**
         * 링크 생성 결과 [LinkResult] 와 상대 이슈 정보를 받아 응답 DTO 를 생성한다.
         *
         * POST 성공 시 outward 방향으로 고정 (source=요청 이슈).
         */
        fun fromResult(
            result: LinkResult,
            otherIssueKey: String,
            otherIssueSummary: String,
            otherCurrentStateKey: String,
        ): IssueLinkResponse =
            IssueLinkResponse(
                id = result.linkId,
                linkType = result.linkType.name,
                direction = "OUTWARD",
                label = result.linkType.outwardLabel,
                otherIssue =
                    OtherIssueDto(
                        key = otherIssueKey,
                        summary = otherIssueSummary,
                        statusKey = otherCurrentStateKey,
                    ),
            )

        /**
         * [LinkEntry] 를 응답 DTO 로 변환한다.
         *
         * @param entry 링크 항목.
         * @param outward true = 이 이슈가 source (OUTWARD), false = target (INWARD).
         */
        fun from(
            entry: LinkEntry,
            outward: Boolean,
        ): IssueLinkResponse =
            IssueLinkResponse(
                id = entry.linkId,
                linkType = entry.linkType.name,
                direction = if (outward) "OUTWARD" else "INWARD",
                label = entry.relationLabel,
                otherIssue =
                    OtherIssueDto(
                        key = entry.otherIssueKey,
                        summary = entry.otherIssueSummary,
                        statusKey = entry.otherCurrentStateKey,
                    ),
            )
    }
}

/**
 * 링크 응답에 포함되는 상대 이슈 요약 DTO.
 *
 * @property key 이슈 키 (예: "BTS-2").
 * @property summary 이슈 제목.
 * @property statusKey 현재 워크플로우 상태 키 (예: "open").
 */
data class OtherIssueDto(
    val key: String,
    val summary: String,
    val statusKey: String,
)
