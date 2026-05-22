// ActorId VO 단위 테스트 — 생성 정상, ZERO_UUID 거부, equality 3 케이스

package com.bts.issue.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [ActorId] VO 단위 테스트.
 *
 * Spring 컨텍스트 없이 순수 단위 테스트로 실행한다.
 * 테스트 케이스.
 * - create — 유효한 UUID 로 인스턴스 생성 시 value 필드가 입력 UUID 그대로임을 검증.
 * - reject_zero_uuid — nil UUID (모두 0) 로 생성 시 [IllegalArgumentException] 을 던진다.
 * - equality — 같은 UUID 값을 가진 두 인스턴스는 equals true, hashCode 동일.
 */
class ActorIdTest {
    @Test
    fun `create — 유효한 UUID 로 ActorId 를 생성하면 value 가 입력 UUID 그대로다`() {
        val uuid = UUID.randomUUID()

        val actorId = ActorId(uuid)

        assertThat(actorId.value).isEqualTo(uuid)
    }

    @Test
    fun `reject_zero_uuid — nil UUID 로 생성하면 IllegalArgumentException 을 던진다`() {
        val nilUuid = UUID.fromString("00000000-0000-0000-0000-000000000000")

        assertThatThrownBy { ActorId(nilUuid) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `equality — 같은 UUID 면 equals true 이고 hashCode 가 동일하다`() {
        val uuid = UUID.randomUUID()
        val a = ActorId(uuid)
        val b = ActorId(uuid)

        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }
}
