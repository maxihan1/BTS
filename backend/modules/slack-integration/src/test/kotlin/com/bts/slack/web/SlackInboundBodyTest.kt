// readBoundedSlackBody 단위 테스트 — Content-Length 사전 거절 + 위조·청크에도 유효한 상한 스트리밍 방어

package com.bts.slack.web

import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import java.io.ByteArrayInputStream

/**
 * [readBoundedSlackBody] 단위 테스트 — 인바운드 본문 상한의 **이중 방어**를 각각 독립으로 검증한다.
 *
 * ## 왜 컨트롤러 테스트로는 부족한가 (이 클래스가 따로 존재하는 이유)
 * MockMvc 의 [MockHttpServletRequest] 는 `getContentLengthLong()` 이 **실제 본문 길이를 그대로** 돌려주므로,
 * 컨트롤러 테스트에서는 항상 1차 방어(`Content-Length` 사전 검사)만 발동하고 2차 방어
 * ([java.io.InputStream.readNBytes] 상한 스트리밍)는 **한 번도 실행되지 않는다**. 즉 컨트롤러 테스트만으로는
 * 2차 방어가 죽어 있어도 초록이다(가짜 그린). 공격자는 `Content-Length` 를 위조하거나 청크 전송으로 아예
 * 생략할 수 있으므로 2차 방어야말로 진짜 방어선이다 — 그래서 여기서 요청 객체를 직접 만들어 검증한다.
 *
 * ## ★ 핵심 단언 — 상태가 아니라 "얼마나 읽었는가"
 * "초과 시 null" 만 단언하면 본문을 전부 읽어 들인 뒤 크기를 재는 구현도 통과한다(그게 바로 이 PR 이
 * 제거한 결함이다 — `rawBody.toByteArray().size > MAX`). 그래서 [CountingRequest] 로 **실제 소비된
 * 바이트 수**를 세어 상한 + 1 바이트를 넘겨 읽지 않았음을 단언한다.
 */
class SlackInboundBodyTest {
    // ── 상한 이내 — 원문 바이트 그대로 ────────────────────────────────────────────

    @Test
    fun `상한 이내 본문은 수신 바이트 그대로 돌려준다`() {
        val body = "payload=hello".toByteArray(Charsets.UTF_8)
        val request = CountingRequest(body, declaredContentLength = body.size.toLong())

        val result = readBoundedSlackBody(request, MAX_BYTES)

        assertThat(result).isEqualTo(body)
    }

    @Test
    fun `정확히 상한 크기인 본문은 통과한다`() {
        // 경계값(off-by-one) — 상한은 경계 포함이다.
        val body = ByteArray(MAX_BYTES) { 'a'.code.toByte() }
        val request = CountingRequest(body, declaredContentLength = body.size.toLong())

        val result = readBoundedSlackBody(request, MAX_BYTES)

        assertThat(result).hasSize(MAX_BYTES)
    }

    @Test
    fun `상한을 1바이트 초과하면 거부한다`() {
        val body = ByteArray(MAX_BYTES + 1) { 'a'.code.toByte() }
        val request = CountingRequest(body, declaredContentLength = body.size.toLong())

        val result = readBoundedSlackBody(request, MAX_BYTES)

        assertThat(result).isNull()
    }

    // ── 1차 방어 — Content-Length 사전 거절 (본문을 읽지 않는다) ────────────────────

    @Test
    fun `Content-Length 가 상한을 넘으면 본문을 한 바이트도 읽지 않고 거부한다`() {
        val body = ByteArray(MAX_BYTES * 2) { 'a'.code.toByte() }
        val request = CountingRequest(body, declaredContentLength = body.size.toLong())

        val result = readBoundedSlackBody(request, MAX_BYTES)

        assertThat(result).isNull()
        assertThat(request.bytesRead)
            .describedAs("Content-Length 선언만으로 거절되므로 본문 스트림은 열리지도 않아야 한다")
            .isZero()
    }

    // ── 2차 방어 — Content-Length 를 못 믿는 경우 (위조·청크 전송) ───────────────────

    @Test
    fun `Content-Length 미선언(청크 전송)이어도 상한 더하기 1 바이트까지만 읽고 거부한다`() {
        // 청크 전송은 Content-Length 가 없다(-1). 1차 방어가 통과되므로 스트리밍 상한이 유일한 방어선이다.
        val body = ByteArray(MAX_BYTES * 10) { 'a'.code.toByte() }
        val request = CountingRequest(body, declaredContentLength = -1L)

        val result = readBoundedSlackBody(request, MAX_BYTES)

        assertThat(result).isNull()
        assertThat(request.bytesRead)
            .describedAs("거대 본문이어도 힙에 올라오는 것은 상한 + 1 바이트뿐이어야 한다")
            .isEqualTo(MAX_BYTES + 1)
    }

    @Test
    fun `Content-Length 를 작게 위조해도 실제 바이트로 판정해 거부한다`() {
        // 공격자가 헤더를 0으로 위조해도 실제 수신 바이트가 상한을 넘으면 거부되어야 한다.
        val body = ByteArray(MAX_BYTES * 10) { 'a'.code.toByte() }
        val request = CountingRequest(body, declaredContentLength = 0L)

        val result = readBoundedSlackBody(request, MAX_BYTES)

        assertThat(result).isNull()
        assertThat(request.bytesRead).isEqualTo(MAX_BYTES + 1)
    }

    /**
     * 실제 소비된 바이트 수를 세는 [MockHttpServletRequest] — `Content-Length` 선언값을 본문과 **독립적으로**
     * 지정할 수 있다(위조·청크 시나리오 재현). [MockHttpServletRequest] 는 `getContentLengthLong()` 이 본문
     * 길이를 그대로 돌려주므로 그대로는 이 시나리오를 만들 수 없다.
     */
    private class CountingRequest(
        body: ByteArray,
        private val declaredContentLength: Long,
    ) : MockHttpServletRequest("POST", "/slack/events") {
        var bytesRead = 0
            private set

        private val stream =
            object : ServletInputStream() {
                private val delegate = ByteArrayInputStream(body)

                override fun read(): Int {
                    val b = delegate.read()
                    if (b != -1) bytesRead++
                    return b
                }

                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int {
                    val n = delegate.read(b, off, len)
                    if (n > 0) bytesRead += n
                    return n
                }

                override fun isFinished(): Boolean = delegate.available() == 0

                override fun isReady(): Boolean = true

                override fun setReadListener(listener: ReadListener?): Unit = throw UnsupportedOperationException()
            }

        override fun getContentLengthLong(): Long = declaredContentLength

        override fun getInputStream(): ServletInputStream = stream
    }

    private companion object {
        /** 테스트 전용 상한(실 컨트롤러 상한과 무관 — 이 함수는 상한을 인자로 받는다). */
        const val MAX_BYTES = 1024
    }
}
