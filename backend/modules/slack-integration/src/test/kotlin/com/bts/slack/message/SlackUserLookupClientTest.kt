// SlackUserLookupClient 단위 테스트 — users.lookupByEmail 호출·결과 분류·봇토큰 미노출 검증 (FR-SL-02 D6 Task 1)

package com.bts.slack.message

import com.slack.api.RequestConfigurator
import com.slack.api.methods.MethodsClient
import com.slack.api.methods.SlackApiException
import com.slack.api.methods.request.users.UsersLookupByEmailRequest
import com.slack.api.methods.response.users.UsersLookupByEmailResponse
import com.slack.api.model.User
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.IOException

private typealias LookupConfigurator = RequestConfigurator<UsersLookupByEmailRequest.UsersLookupByEmailRequestBuilder>

class SlackUserLookupClientTest {
    private val methods = mockk<MethodsClient>()
    private val client = SlackUserLookupClient(methods)

    private val botToken = "xoxb-secret-token"

    private fun stubResponse(response: UsersLookupByEmailResponse) {
        every {
            methods.usersLookupByEmail(any<LookupConfigurator>())
        } returns response
    }

    @Test
    fun `ok 응답이면 Found 반환하고 token·email 을 요청에 싣는다`() {
        val slot = slot<LookupConfigurator>()
        val user =
            User().apply {
                id = "U123"
                teamId = "T1"
            }
        every { methods.usersLookupByEmail(capture(slot)) } returns
            UsersLookupByEmailResponse().apply {
                isOk = true
                this.user = user
            }

        val result = client.lookupByEmail(botToken, "person@example.com")

        assertThat(result).isEqualTo(SlackUserLookupResult.Found("U123", "T1"))
        val built = UsersLookupByEmailRequest.builder().also { slot.captured.configure(it) }.build()
        assertThat(built.token).isEqualTo(botToken)
        assertThat(built.email).isEqualTo("person@example.com")
    }

    @Test
    fun `ok false + users_not_found 는 NotFound`() {
        stubResponse(
            UsersLookupByEmailResponse().apply {
                isOk = false
                error = "users_not_found"
            },
        )

        val result = client.lookupByEmail(botToken, "person@example.com")

        assertThat(result).isEqualTo(SlackUserLookupResult.NotFound)
    }

    @Test
    fun `ok false + missing_scope 는 MissingScope`() {
        stubResponse(
            UsersLookupByEmailResponse().apply {
                isOk = false
                error = "missing_scope"
            },
        )

        val result = client.lookupByEmail(botToken, "person@example.com")

        assertThat(result).isEqualTo(SlackUserLookupResult.MissingScope)
    }

    @Test
    fun `ok false + 봇토큰_설치 무효 오류(invalid_auth·token_revoked·account_inactive·not_authed) 는 모두 MissingScope`() {
        listOf("invalid_auth", "token_revoked", "account_inactive", "not_authed").forEach { errorCode ->
            stubResponse(
                UsersLookupByEmailResponse().apply {
                    isOk = false
                    error = errorCode
                },
            )

            val result = client.lookupByEmail(botToken, "person@example.com")

            assertThat(result)
                .describedAs("error=%s", errorCode)
                .isEqualTo(SlackUserLookupResult.MissingScope)
        }
    }

    @Test
    fun `SlackApiException(429·rate_limited) 은 Transient 이고 토큰을 담지 않는다`() {
        every {
            methods.usersLookupByEmail(any<LookupConfigurator>())
        } throws mockk<SlackApiException>(relaxed = true)

        val result = client.lookupByEmail(botToken, "person@example.com")

        assertThat(result).isInstanceOf(SlackUserLookupResult.Transient::class.java)
        result as SlackUserLookupResult.Transient
        assertThat(result.reason).doesNotContain(botToken)
    }

    @Test
    fun `IOException(네트워크) 은 Transient 이고 토큰을 담지 않는다`() {
        every {
            methods.usersLookupByEmail(any<LookupConfigurator>())
        } throws IOException("network")

        val result = client.lookupByEmail(botToken, "person@example.com")

        assertThat(result).isInstanceOf(SlackUserLookupResult.Transient::class.java)
        result as SlackUserLookupResult.Transient
        assertThat(result.reason).doesNotContain(botToken)
    }

    @Test
    fun `결과 toString 어디에도 봇 토큰이 없다`() {
        val user =
            User().apply {
                id = "U123"
                teamId = "T1"
            }
        stubResponse(
            UsersLookupByEmailResponse().apply {
                isOk = true
                this.user = user
            },
        )

        val result = client.lookupByEmail(botToken, "person@example.com")

        assertThat(result.toString()).doesNotContain(botToken)
    }
}
