// SlackMessageClient 단위 테스트 — chat.postMessage/views.open/chat.update 호출·결과 분류 검증
// (FR-SL-02 Task 6, FR-SL-05 Task 6)

package com.bts.slack.message

import com.slack.api.RequestConfigurator
import com.slack.api.methods.MethodsClient
import com.slack.api.methods.SlackApiException
import com.slack.api.methods.request.chat.ChatPostMessageRequest
import com.slack.api.methods.request.chat.ChatUpdateRequest
import com.slack.api.methods.request.views.ViewsOpenRequest
import com.slack.api.methods.response.chat.ChatPostMessageResponse
import com.slack.api.methods.response.chat.ChatUpdateResponse
import com.slack.api.methods.response.views.ViewsOpenResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.IOException

class SlackMessageClientTest {
    private val methods = mockk<MethodsClient>()
    private val client = SlackMessageClient(methods)

    private val message = RenderedSlackMessage(text = "PROJ-123 멘션", blocks = "[]")

    private fun stubResponse(response: ChatPostMessageResponse) {
        every {
            methods.chatPostMessage(any<RequestConfigurator<ChatPostMessageRequest.ChatPostMessageRequestBuilder>>())
        } returns response
    }

    @Test
    fun `ok 응답이면 Sent 반환하고 token·channel·blocks 를 요청에 싣는다`() {
        val slot = slot<RequestConfigurator<ChatPostMessageRequest.ChatPostMessageRequestBuilder>>()
        every { methods.chatPostMessage(capture(slot)) } returns
            ChatPostMessageResponse().apply { isOk = true }

        val result = client.postDirectMessage("xoxb-token", "U123", message)

        assertThat(result).isInstanceOf(SlackSendResult.Sent::class.java)
        val built = ChatPostMessageRequest.builder().also { slot.captured.configure(it) }.build()
        assertThat(built.token).isEqualTo("xoxb-token")
        assertThat(built.channel).isEqualTo("U123")
        assertThat(built.blocksAsString).isEqualTo("[]")
        assertThat(built.text).isEqualTo("PROJ-123 멘션")
    }

    @Test
    fun `ok false + rate_limited 는 RetryableFailure`() {
        stubResponse(
            ChatPostMessageResponse().apply {
                isOk = false
                error = "rate_limited"
            },
        )

        val result = client.postDirectMessage("t", "U1", message)

        assertThat(result).isInstanceOf(SlackSendResult.RetryableFailure::class.java)
    }

    @Test
    fun `ok false + channel_not_found 는 PermanentFailure`() {
        stubResponse(
            ChatPostMessageResponse().apply {
                isOk = false
                error = "channel_not_found"
            },
        )

        val result = client.postDirectMessage("t", "U1", message)

        assertThat(result).isInstanceOf(SlackSendResult.PermanentFailure::class.java)
    }

    @Test
    fun `IOException(네트워크) 은 RetryableFailure`() {
        every {
            methods.chatPostMessage(any<RequestConfigurator<ChatPostMessageRequest.ChatPostMessageRequestBuilder>>())
        } throws IOException("network")

        val result = client.postDirectMessage("t", "U1", message)

        assertThat(result).isInstanceOf(SlackSendResult.RetryableFailure::class.java)
    }

    @Test
    fun `SlackApiException(429·5xx HTTP) 은 RetryableFailure`() {
        every {
            methods.chatPostMessage(any<RequestConfigurator<ChatPostMessageRequest.ChatPostMessageRequestBuilder>>())
        } throws mockk<SlackApiException>(relaxed = true)

        val result = client.postDirectMessage("t", "U1", message)

        assertThat(result).isInstanceOf(SlackSendResult.RetryableFailure::class.java)
    }

    @Test
    fun `openModal 은 viewsOpen 을 triggerId·view 로 호출하고 ok 응답이면 Sent 반환한다`() {
        val slot = slot<RequestConfigurator<ViewsOpenRequest.ViewsOpenRequestBuilder>>()
        every { methods.viewsOpen(capture(slot)) } returns
            ViewsOpenResponse().apply { isOk = true }

        val result = client.openModal("xoxb-token", "trigger-1", "{\"type\":\"modal\"}")

        assertThat(result).isInstanceOf(SlackSendResult.Sent::class.java)
        val built = ViewsOpenRequest.builder().also { slot.captured.configure(it) }.build()
        assertThat(built.token).isEqualTo("xoxb-token")
        assertThat(built.triggerId).isEqualTo("trigger-1")
        assertThat(built.viewAsString).isEqualTo("{\"type\":\"modal\"}")
    }

    @Test
    fun `openModal ok false 는 PermanentFailure 이며 reason 에 botToken 이 노출되지 않는다`() {
        every {
            methods.viewsOpen(any<RequestConfigurator<ViewsOpenRequest.ViewsOpenRequestBuilder>>())
        } returns
            ViewsOpenResponse().apply {
                isOk = false
                error = "expired_trigger_id"
            }

        val result = client.openModal("xoxb-secret-token", "trigger-1", "{}")

        assertThat(result).isInstanceOf(SlackSendResult.PermanentFailure::class.java)
        val reason = (result as SlackSendResult.PermanentFailure).reason
        assertThat(reason).doesNotContain("xoxb-secret-token")
    }

    @Test
    fun `openModal IOException(네트워크) 은 RetryableFailure 이며 botToken 이 노출되지 않는다`() {
        every {
            methods.viewsOpen(any<RequestConfigurator<ViewsOpenRequest.ViewsOpenRequestBuilder>>())
        } throws IOException("network")

        val result = client.openModal("xoxb-secret-token", "trigger-1", "{}")

        assertThat(result).isInstanceOf(SlackSendResult.RetryableFailure::class.java)
        val reason = (result as SlackSendResult.RetryableFailure).reason
        assertThat(reason).doesNotContain("xoxb-secret-token")
    }

    @Test
    fun `updateMessage 는 chatUpdate 를 channel·ts·blocks 로 호출하고 ok 응답이면 Sent 반환한다`() {
        val slot = slot<RequestConfigurator<ChatUpdateRequest.ChatUpdateRequestBuilder>>()
        every { methods.chatUpdate(capture(slot)) } returns
            ChatUpdateResponse().apply { isOk = true }

        val result = client.updateMessage("xoxb-token", "C123", "1700000000.000100", "[]")

        assertThat(result).isInstanceOf(SlackSendResult.Sent::class.java)
        val built = ChatUpdateRequest.builder().also { slot.captured.configure(it) }.build()
        assertThat(built.token).isEqualTo("xoxb-token")
        assertThat(built.channel).isEqualTo("C123")
        assertThat(built.ts).isEqualTo("1700000000.000100")
        assertThat(built.blocksAsString).isEqualTo("[]")
    }

    @Test
    fun `updateMessage ok false 는 PermanentFailure 이며 reason 에 botToken 이 노출되지 않는다`() {
        every {
            methods.chatUpdate(any<RequestConfigurator<ChatUpdateRequest.ChatUpdateRequestBuilder>>())
        } returns
            ChatUpdateResponse().apply {
                isOk = false
                error = "message_not_found"
            }

        val result = client.updateMessage("xoxb-secret-token", "C123", "1700000000.000100", "[]")

        assertThat(result).isInstanceOf(SlackSendResult.PermanentFailure::class.java)
        val reason = (result as SlackSendResult.PermanentFailure).reason
        assertThat(reason).doesNotContain("xoxb-secret-token")
    }

    @Test
    fun `updateMessage SlackApiException(429·5xx HTTP) 은 RetryableFailure 이며 botToken 이 노출되지 않는다`() {
        every {
            methods.chatUpdate(any<RequestConfigurator<ChatUpdateRequest.ChatUpdateRequestBuilder>>())
        } throws mockk<SlackApiException>(relaxed = true)

        val result = client.updateMessage("xoxb-secret-token", "C123", "1700000000.000100", "[]")

        assertThat(result).isInstanceOf(SlackSendResult.RetryableFailure::class.java)
        val reason = (result as SlackSendResult.RetryableFailure).reason
        assertThat(reason).doesNotContain("xoxb-secret-token")
    }
}
