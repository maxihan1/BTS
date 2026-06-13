// 이슈 링크 생성 요청 DTO — targetKey 와 linkType 을 받아 LinkApplicationService 로 위임한다.

package com.bts.issue.link.web.dto

import jakarta.validation.constraints.NotBlank

/**
 * `POST /api/v1/issues/{key}/links` 요청 바디.
 *
 * @property targetKey 링크 도착 이슈 키 (예: "BTS-2"). 공백 불가.
 * @property linkType 링크 유형 코드 (예: "blocks", "relates", "duplicates", "clones"). 공백 불가.
 *   알 수 없는 코드 전달 시 [com.bts.issue.link.domain.InvalidLinkTypeCodeException] → 400.
 */
data class CreateLinkRequest(
    @field:NotBlank(message = "targetKey 는 필수입니다.")
    val targetKey: String,
    @field:NotBlank(message = "linkType 은 필수입니다.")
    val linkType: String,
)
