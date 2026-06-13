// 이슈 링크 목록 응답 DTO — outward/inward 방향별 링크 리스트를 포함한다.

package com.bts.issue.link.web.dto

import com.bts.issue.link.application.LinkListResult

/**
 * `GET /api/v1/issues/{key}/links` 응답 바디.
 *
 * @property outward 이 이슈가 source 인 링크 목록 (outward 방향).
 * @property inward 이 이슈가 target 인 링크 목록 (inward 방향).
 */
data class LinkListResponse(
    val outward: List<IssueLinkResponse>,
    val inward: List<IssueLinkResponse>,
) {
    companion object {
        /** [LinkListResult] 를 응답 DTO 로 변환한다. */
        fun from(result: LinkListResult): LinkListResponse =
            LinkListResponse(
                outward = result.outward.map { IssueLinkResponse.from(it, outward = true) },
                inward = result.inward.map { IssueLinkResponse.from(it, outward = false) },
            )
    }
}
