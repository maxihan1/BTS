// SlackUnfurlClient 단위 테스트 — chat.unfurl 호출·결과 분류 검증 (FR-SL-03 Task 7)

package com.bts.slack.message

import com.fasterxml.jackson.databind.ObjectMapper
import com.slack.api.RequestConfigurator
import com.slack.api.methods.MethodsClient
import com.slack.api.methods.SlackApiException
import com.slack.api.methods.request.chat.ChatUnfurlRequest
import com.slack.api.methods.response.chat.ChatUnfurlResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.IOException

class SlackUnfurlClientTest {
    private val objectMapper = ObjectMapper()
    private val methods = mockk<MethodsClient>()
    private val client = SlackUnfurlClient(objectMapper, methods)

    private val unfurls =
        mapOf(
            "https://atlas.example.com/issues/PROJ-123" to
                objectMapper.createObjectNode().apply { put("text", "PROJ-123 카드") },
        )

    private fun stubResponse(response: ChatUnfurlResponse) {
        every {
            methods.chatUnfurl(any<RequestConfigurator<ChatUnfurlRequest.ChatUnfurlRequestBuilder>>())
        } returns response
    }

    @Test
    fun `ok 응답이면 Sent 반환하고 token·channel·ts·unfurls 를 요청에 싣는다`() {
        val slot = slot<RequestConfigurator<ChatUnfurlRequest.ChatUnfurlRequestBuilder>>()
        every { methods.chatUnfurl(capture(slot)) } returns
            ChatUnfurlResponse().apply { isOk = true }

        val result = client.unfurl("xoxb-token", "C123", "1234567890.123456", unfurls)

        assertThat(result).isInstanceOf(SlackSendResult.Sent::class.java)
        val built = ChatUnfurlRequest.builder().also { slot.captured.configure(it) }.build()
        assertThat(built.token).isEqualTo("xoxb-token")
        assertThat(built.channel).isEqualTo("C123")
        assertThat(built.ts).isEqualTo("1234567890.123456")
        assertThat(built.rawUnfurls).isEqualTo(objectMapper.writeValueAsString(unfurls))
    }

    @Test
    fun `ok false + rate_limited 는 RetryableFailure`() {
        stubResponse(
            ChatUnfurlResponse().apply {
                isOk = false
                error = "rate_limited"
            },
        )

        val result = client.unfurl("t", "C1", "ts", unfurls)

        assertThat(result).isInstanceOf(SlackSendResult.RetryableFailure::class.java)
    }

    @Test
    fun `ok false + channel_not_found 는 PermanentFailure`() {
        stubResponse(
            ChatUnfurlResponse().apply {
                isOk = false
                error = "channel_not_found"
            },
        )

        val result = client.unfurl("t", "C1", "ts", unfurls)

        assertThat(result).isInstanceOf(SlackSendResult.PermanentFailure::class.java)
    }

    @Test
    fun `IOException(네트워크) 은 RetryableFailure`() {
        every {
            methods.chatUnfurl(any<RequestConfigurator<ChatUnfurlRequest.ChatUnfurlRequestBuilder>>())
        } throws IOException("network")

        val result = client.unfurl("t", "C1", "ts", unfurls)

        assertThat(result).isInstanceOf(SlackSendResult.RetryableFailure::class.java)
    }

    @Test
    fun `SlackApiException(429·5xx HTTP) 은 RetryableFailure`() {
        every {
            methods.chatUnfurl(any<RequestConfigurator<ChatUnfurlRequest.ChatUnfurlRequestBuilder>>())
        } throws mockk<SlackApiException>(relaxed = true)

        val result = client.unfurl("t", "C1", "ts", unfurls)

        assertThat(result).isInstanceOf(SlackSendResult.RetryableFailure::class.java)
    }

    @Test
    fun `botToken 은 실패 reason 에 노출되지 않는다`() {
        every {
            methods.chatUnfurl(any<RequestConfigurator<ChatUnfurlRequest.ChatUnfurlRequestBuilder>>())
        } throws IOException("network")

        val result = client.unfurl("xoxb-super-secret", "C1", "ts", unfurls) as SlackSendResult.RetryableFailure

        assertThat(result.reason).doesNotContain("xoxb-super-secret")
    }
}
