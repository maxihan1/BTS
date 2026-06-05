// 전역 사용자 그룹 도메인 모델의 name 정규화/검증 불변식 테스트 (FR-PM-09)

package com.atlas.bts.identity.group

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class UserGroupTest {
    @Test
    fun `create는 name 앞뒤 공백을 trim한다`() {
        val group = UserGroup.create(name = "  backend-team  ", description = null)

        assertThat(group.name).isEqualTo("backend-team")
    }

    @Test
    fun `create는 description 앞뒤 공백을 trim한다`() {
        val group = UserGroup.create(name = "team", description = "  핵심 팀  ")

        assertThat(group.description).isEqualTo("핵심 팀")
    }

    @Test
    fun `create는 빈 문자열 name에 예외를 던진다`() {
        assertThatThrownBy { UserGroup.create(name = "", description = null) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `create는 공백만 있는 name에 예외를 던진다`() {
        assertThatThrownBy { UserGroup.create(name = "   ", description = null) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `create는 name 255자 초과 시 예외를 던진다`() {
        val tooLong = "a".repeat(256)

        assertThatThrownBy { UserGroup.create(name = tooLong, description = null) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `create는 name 255자는 허용한다`() {
        val maxName = "a".repeat(255)

        val group = UserGroup.create(name = maxName, description = null)

        assertThat(group.name).hasSize(255)
    }

    @Test
    fun `create는 description 500자 초과 시 예외를 던진다`() {
        val tooLong = "a".repeat(501)

        assertThatThrownBy { UserGroup.create(name = "team", description = tooLong) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `create는 description 500자는 허용한다`() {
        val maxDesc = "a".repeat(500)

        val group = UserGroup.create(name = "team", description = maxDesc)

        assertThat(group.description).hasSize(500)
    }

    @Test
    fun `create는 정상값으로 그룹을 생성한다`() {
        val group = UserGroup.create(name = "backend-team", description = "백엔드 팀")

        assertThat(group.name).isEqualTo("backend-team")
        assertThat(group.description).isEqualTo("백엔드 팀")
        assertThat(group.id).isNull()
    }

    @Test
    fun `create는 빈 description을 null로 정규화한다`() {
        val group = UserGroup.create(name = "team", description = "   ")

        assertThat(group.description).isNull()
    }
}
