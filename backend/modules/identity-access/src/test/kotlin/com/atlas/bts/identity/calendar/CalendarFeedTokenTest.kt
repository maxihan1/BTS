// 캘린더 피드 토큰 값 객체 단위 테스트 — rawToken 무작위성(CSPRNG) + SHA-256 결정성 (FR-CA-02 task-2)

package com.atlas.bts.identity.calendar

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [CalendarFeedToken] 생성/해시 값 객체 단위 테스트 (FR-CA-02 Task 2).
 *
 * 검증 대상.
 *  - generate() 가 매번 다른 rawToken(64 소문자 hex) 을 낸다(CSPRNG).
 *  - hash 는 64 소문자 hex 이며 rawToken 의 SHA-256 과 일치한다.
 *  - hash(raw) 는 동일 입력에 동일 출력을 내는 결정적 함수다.
 */
class CalendarFeedTokenTest {
    @Test
    fun `generate는 64자 소문자 hex rawToken과 64자 hash를 반환한다`() {
        val token = CalendarFeedToken.generate()

        assertThat(token.rawToken).matches("[0-9a-f]{64}")
        assertThat(token.hash).matches("[0-9a-f]{64}")
    }

    @Test
    fun `generate는 매번 다른 rawToken과 hash를 반환한다 (CSPRNG)`() {
        val first = CalendarFeedToken.generate()
        val second = CalendarFeedToken.generate()

        assertThat(first.rawToken).isNotEqualTo(second.rawToken)
        assertThat(first.hash).isNotEqualTo(second.hash)
    }

    @Test
    fun `generate의 hash는 rawToken의 SHA-256과 일치한다`() {
        val token = CalendarFeedToken.generate()

        assertThat(token.hash).isEqualTo(CalendarFeedToken.hash(token.rawToken))
    }

    @Test
    fun `hash는 동일 입력에 동일 출력을 낸다 (결정적 SHA-256)`() {
        val raw = CalendarFeedToken.generate().rawToken

        assertThat(CalendarFeedToken.hash(raw)).isEqualTo(CalendarFeedToken.hash(raw))
    }
}
