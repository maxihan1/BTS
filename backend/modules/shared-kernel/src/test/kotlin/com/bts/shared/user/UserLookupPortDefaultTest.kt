// UserLookupPort default 메서드 fail-safe 단위 테스트

package com.bts.shared.user

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * [UserLookupPort] default 구현 단위 테스트.
 *
 * 기존 exists 만 구현한 anonymous fake 로 새 default 메서드를 호출해
 * fail-safe 반환값을 검증한다.
 * Spring 컨텍스트 없이 순수 단위 테스트로 실행한다.
 */
class UserLookupPortDefaultTest {
    @Test
    fun `findDisplayNamesByIds default 는 빈 맵을 반환한다`() {
        val port =
            object : UserLookupPort {
                override fun exists(userId: UUID) = false
            }
        assertThat(port.findDisplayNamesByIds(setOf(UUID.randomUUID()))).isEmpty()
    }

    @Test
    fun `findEmailById default 는 null 을 반환한다`() {
        val port =
            object : UserLookupPort {
                override fun exists(userId: UUID) = false
            }
        assertThat(port.findEmailById(UUID.randomUUID())).isNull()
    }
}
