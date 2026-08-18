// 이슈 작업의 행위자를 식별하는 VO — identity-access UserId 와 같은 UUID 값. BC 격리 룰에 따라 별도 VO

package com.bts.issue.domain

import java.util.UUID

/**
 * 이슈 작업(생성·전환·코멘트 등)을 수행하는 행위자의 식별자 VO.
 *
 * identity-access BC 의 UserId 와 동일 UUID 값을 공유하지만,
 * BC 격리 원칙(CLAUDE.md §핵심 패턴)에 따라 다른 BC 의 내부 클래스를 직접 import 하지 않고
 * 이 issue-tracking BC 전용 VO 로 별도 정의한다.
 * ADR 2026-05-22-issue-permission-resolver-port 참조.
 *
 * @property value 행위자를 식별하는 UUID. nil UUID(모두 0)는 허용하지 않는다.
 */
data class ActorId(val value: UUID) {
    init {
        require(value != ZERO_UUID) { "ActorId cannot be nil UUID" }
    }

    companion object {
        /** nil UUID — 유효하지 않은 ActorId 판별 기준. 인스턴스 생성에 사용 불가. */
        val ZERO_UUID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000000")
    }
}
