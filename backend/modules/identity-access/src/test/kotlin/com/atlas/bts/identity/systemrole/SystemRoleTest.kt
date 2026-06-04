// 전역 시스템 역할(SystemRole) 파싱 규칙을 검증하는 단위 테스트 (FR-PM-08)

package com.atlas.bts.identity.systemrole

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SystemRoleTest {
    @Test
    fun `from은 정확한 enum name SYSTEM_ADMIN을 SYSTEM_ADMIN으로 변환한다`() {
        assertThat(SystemRole.from("SYSTEM_ADMIN")).isEqualTo(SystemRole.SYSTEM_ADMIN)
    }

    @Test
    fun `from은 미지정 문자열에 대해 IllegalArgumentException을 던진다`() {
        assertThatThrownBy { SystemRole.from("FOO") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `from은 소문자 입력을 거부한다`() {
        assertThatThrownBy { SystemRole.from("system_admin") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
