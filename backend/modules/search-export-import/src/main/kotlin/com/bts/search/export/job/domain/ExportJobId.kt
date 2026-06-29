// ExportJob 식별자 — UUID를 감싸는 value class (type-safe ID)

package com.bts.search.export.job.domain

import java.util.UUID

/**
 * [ExportJob] 도메인 식별자.
 *
 * UUID 를 직접 노출하지 않고 타입 안전 value class 로 감싼다.
 * 서비스 레이어에서 UUID 인자와 [ExportJobId] 인자를 혼동하는 컴파일 타임 오류를 방지한다.
 *
 * @property value 내부 UUID 값.
 */
@JvmInline
value class ExportJobId(val value: UUID) {
    override fun toString(): String = value.toString()
}
