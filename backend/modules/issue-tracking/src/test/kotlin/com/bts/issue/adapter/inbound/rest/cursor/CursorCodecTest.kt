// cursor 토큰 인코딩/디코딩 유틸 단위 테스트

package com.bts.issue.adapter.inbound.rest.cursor

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * [CursorCodec] 단위 테스트.
 *
 * 검증 케이스.
 * - C-1. encode → decode round-trip: OffsetDateTime + UUID 동일값 복원
 * - C-2. 토큰이 `v1:` prefix 로 시작한다
 * - C-3. 빈 문자열 decode → null (첫 페이지, 예외 아님)
 * - C-4. 위변조/형식오류 토큰 → CursorDecodeException
 * - C-5. 다른 버전 prefix (`v2:`) → CursorDecodeException
 * - C-6. 나노초 정밀도 보존: .123456789 round-trip 후 동일값
 */
class CursorCodecTest {

    private val sampleId = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val sampleCreatedAt = OffsetDateTime.of(2024, 1, 15, 10, 30, 0, 0, ZoneOffset.UTC)

    // ── C-1. round-trip ────────────────────────────────────────────────────────

    @Test
    fun `encode 후 decode 하면 동일한 OffsetDateTime과 UUID를 반환한다`() {
        val token = CursorCodec.encode(sampleCreatedAt, sampleId)
        val position = CursorCodec.decode(token)

        assertThat(position).isNotNull
        assertThat(position!!.createdAt).isEqualTo(sampleCreatedAt)
        assertThat(position.id).isEqualTo(sampleId)
    }

    // ── C-2. v1: prefix ────────────────────────────────────────────────────────

    @Test
    fun `encode 결과 토큰은 v1_ prefix 로 시작한다`() {
        val token = CursorCodec.encode(sampleCreatedAt, sampleId)

        assertThat(token).startsWith("v1:")
    }

    // ── C-3. 빈 문자열 → null (첫 페이지) ──────────────────────────────────────

    @Test
    fun `빈 문자열 decode 는 null 을 반환한다 (첫 페이지)`() {
        val position = CursorCodec.decode("")

        assertThat(position).isNull()
    }

    // ── C-4. 위변조/형식오류 → CursorDecodeException ───────────────────────────

    @Test
    fun `위변조된 Base64 문자열이면 CursorDecodeException 을 던진다`() {
        assertThatThrownBy { CursorCodec.decode("v1:!!!INVALID!!!") }
            .isInstanceOf(CursorDecodeException::class.java)
    }

    @Test
    fun `v1_ prefix 뒤 내용이 구분자 없는 형식이면 CursorDecodeException 을 던진다`() {
        // Base64URL 이지만 | 구분자가 없는 경우
        val noDelimiter = "v1:" + java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("nodatetime".toByteArray())
        assertThatThrownBy { CursorCodec.decode(noDelimiter) }
            .isInstanceOf(CursorDecodeException::class.java)
    }

    @Test
    fun `v1_ prefix 뒤 날짜 파싱 실패 시 CursorDecodeException 을 던진다`() {
        val badDate = "v1:" + java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("NOT_A_DATE|11111111-1111-4111-8111-111111111111".toByteArray())
        assertThatThrownBy { CursorCodec.decode(badDate) }
            .isInstanceOf(CursorDecodeException::class.java)
    }

    @Test
    fun `v1_ prefix 뒤 UUID 파싱 실패 시 CursorDecodeException 을 던진다`() {
        val badUuid = "v1:" + java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("2024-01-15T10:30:00Z|NOT_A_UUID".toByteArray())
        assertThatThrownBy { CursorCodec.decode(badUuid) }
            .isInstanceOf(CursorDecodeException::class.java)
    }

    // ── C-5. 다른 버전 prefix → CursorDecodeException ─────────────────────────

    @Test
    fun `v2_ prefix 이면 CursorDecodeException 을 던진다`() {
        val v2Token = "v2:" + java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("2024-01-15T10:30:00Z|11111111-1111-4111-8111-111111111111".toByteArray())
        assertThatThrownBy { CursorCodec.decode(v2Token) }
            .isInstanceOf(CursorDecodeException::class.java)
    }

    @Test
    fun `prefix 없는 토큰이면 CursorDecodeException 을 던진다`() {
        assertThatThrownBy { CursorCodec.decode("GARBAGE") }
            .isInstanceOf(CursorDecodeException::class.java)
    }

    // ── C-6. 나노초 정밀도 보존 ────────────────────────────────────────────────

    @Test
    fun `나노초 정밀도 123456789 가 round-trip 후에도 동일하게 보존된다`() {
        val nanoDateTime = OffsetDateTime.of(2024, 3, 20, 15, 45, 30, 123_456_789, ZoneOffset.UTC)

        val token = CursorCodec.encode(nanoDateTime, sampleId)
        val position = CursorCodec.decode(token)

        assertThat(position).isNotNull
        assertThat(position!!.createdAt).isEqualTo(nanoDateTime)
        assertThat(position.createdAt.nano).isEqualTo(123_456_789)
    }

    @Test
    fun `나노초 없는 경우도 round-trip 이 정상 동작한다`() {
        val noNano = OffsetDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)

        val token = CursorCodec.encode(noNano, sampleId)
        val position = CursorCodec.decode(token)

        assertThat(position).isNotNull
        assertThat(position!!.createdAt).isEqualTo(noNano)
        assertThat(position.createdAt.nano).isEqualTo(0)
    }

    @Test
    fun `UTC 외 오프셋(+09_00) round-trip 이 정상 동작한다`() {
        val kst = ZoneOffset.ofHours(9)
        val kstDateTime = OffsetDateTime.of(2024, 6, 15, 12, 0, 0, 0, kst)

        val token = CursorCodec.encode(kstDateTime, sampleId)
        val position = CursorCodec.decode(token)

        assertThat(position).isNotNull
        assertThat(position!!.createdAt).isEqualTo(kstDateTime)
    }
}
