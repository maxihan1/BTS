// 부재중 조회/설정 응답 DTO — 기간+대체담당자+메시지+활성여부 (FR-PR-03)

package com.atlas.bts.identity.dto

import java.time.Instant
import java.util.UUID

/**
 * 부재중(OOO) 응답 DTO (FR-PR-03).
 *
 * 미설정/종료/해제 상태는 [startsAt]/[endsAt]/[delegateUserId]/[delegateName]/[message] 가 모두 null 이고
 * [active] 는 false 다. [startsAt]/[endsAt] 은 ISO-8601 Instant 로 직렬화된다.
 */
data class OooResponse(
    val startsAt: Instant?,
    val endsAt: Instant?,
    val delegateUserId: UUID?,
    val delegateName: String?,
    val message: String?,
    val active: Boolean,
)
