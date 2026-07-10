// DEFAULT_SLACK_SCOPES — users:read.email 스코프 추가 및 기존 6개 스코프 보존을 검증하는 테스트

package com.bts.slack.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [DEFAULT_SLACK_SCOPES] 검증 (FR-SL-02 D6 Task 2).
 *
 * D6 사용자 연결(Slack `users.lookupByEmail` 호출)에 필요한 `users:read.email` 스코프가
 * 기본 봇 스코프 CSV(comma-separated values — 쉼표로 구분한 문자열 목록)에 포함되는지,
 * 그리고 기존 6개 스코프가 이번 변경으로 유실되지 않았는지를 확인한다.
 */
class SlackScopesTest {
    @Test
    fun `DEFAULT_SLACK_SCOPES는 users_read_email 스코프를 포함한다`() {
        val scopes = DEFAULT_SLACK_SCOPES.split(",")

        assertThat(scopes).contains("users:read.email")
    }

    @Test
    fun `DEFAULT_SLACK_SCOPES는 기존 6개 스코프를 모두 보존한다`() {
        val scopes = DEFAULT_SLACK_SCOPES.split(",")

        assertThat(scopes).contains(
            "chat:write",
            "chat:write.public",
            "links:read",
            "links:write",
            "commands",
            "app_mentions:read",
        )
    }
}
