// 부재중 설정(replace) 요청 DTO — 기간 필수(서비스가 검증), 대체담당자/메시지 nullable (FR-PR-03)

package com.atlas.bts.identity.dto

import java.time.Instant
import java.util.UUID

/**
 * 부재중(OOO) 설정 요청 DTO (FR-PR-03).
 *
 * [startsAt]/[endsAt] 이 null 이면 [com.atlas.bts.identity.ooo.OutOfOfficeService] 가 필수값 부재로
 * 검증 실패(400) 시킨다(FR-PR-02 `StatusPatchRequest` 와 마찬가지로 필드 자체는 nullable, 요구는
 * 서비스 책임). [delegateUserId]/[message] 는 없어도 OOO 가 성립하므로 nullable.
 */
data class OooPatchRequest(
    val startsAt: Instant?,
    val endsAt: Instant?,
    val delegateUserId: UUID?,
    val message: String?,
)
