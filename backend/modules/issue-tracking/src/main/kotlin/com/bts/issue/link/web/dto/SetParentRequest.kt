// 이슈 부모 설정/해제 요청 DTO — parentKey null 전달 시 부모 해제.

package com.bts.issue.link.web.dto

/**
 * `PATCH /api/v1/issues/{key}/parent` 요청 바디.
 *
 * @property parentKey 부모로 지정할 이슈 키. null 전달 시 부모 해제.
 */
data class SetParentRequest(
    val parentKey: String?,
)
