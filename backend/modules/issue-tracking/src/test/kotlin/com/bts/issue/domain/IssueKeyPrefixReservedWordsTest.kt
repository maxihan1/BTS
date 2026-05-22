// IssueKeyPrefixReservedWords 예약어 차단 단위 테스트

package com.bts.issue.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [IssueKeyPrefixReservedWords.isReserved] 단위 테스트.
 *
 * ADR `2026-05-22-issue-key-prefix-policy §예약어 차단` 의 4 카테고리 전체를 검증한다.
 * - 시스템 예약어 (`API`) → true
 * - 대소문자 무관 (`api`, `Api`) → true
 * - BTS 자체명 (`ATLAS`) → true
 * - 보안 예약어 (`NULL`) → true
 * - HTTP 동사 (`GET`) → true
 * - SQL 키워드 (`SELECT`) → true
 * - 일반 단어 (`BUG`, `PROJ`, `ISS`) → false
 */
class IssueKeyPrefixReservedWordsTest {
    @Test
    fun `isReserved — API 는 시스템 예약어이므로 true 를 반환한다`() {
        assertThat(IssueKeyPrefixReservedWords.isReserved("API")).isTrue()
    }

    @Test
    fun `isReserved — 소문자 api 도 대소문자 무관 비교로 true 를 반환한다`() {
        assertThat(IssueKeyPrefixReservedWords.isReserved("api")).isTrue()
    }

    @Test
    fun `isReserved — 혼합 대소문자 Api 도 true 를 반환한다`() {
        assertThat(IssueKeyPrefixReservedWords.isReserved("Api")).isTrue()
    }

    @Test
    fun `isReserved — ATLAS 는 BTS 자체명이므로 true 를 반환한다`() {
        assertThat(IssueKeyPrefixReservedWords.isReserved("ATLAS")).isTrue()
    }

    @Test
    fun `isReserved — NULL 은 보안 예약어이므로 true 를 반환한다`() {
        assertThat(IssueKeyPrefixReservedWords.isReserved("NULL")).isTrue()
    }

    @Test
    fun `isReserved — GET 은 HTTP 동사이므로 true 를 반환한다`() {
        assertThat(IssueKeyPrefixReservedWords.isReserved("GET")).isTrue()
    }

    @Test
    fun `isReserved — SELECT 는 SQL 키워드이므로 true 를 반환한다`() {
        assertThat(IssueKeyPrefixReservedWords.isReserved("SELECT")).isTrue()
    }

    @Test
    fun `isReserved — BUG 는 일반 단어이므로 false 를 반환한다`() {
        assertThat(IssueKeyPrefixReservedWords.isReserved("BUG")).isFalse()
    }

    @Test
    fun `isReserved — PROJ 는 일반 단어이므로 false 를 반환한다`() {
        assertThat(IssueKeyPrefixReservedWords.isReserved("PROJ")).isFalse()
    }

    @Test
    fun `isReserved — ISS 는 일반 단어이므로 false 를 반환한다`() {
        assertThat(IssueKeyPrefixReservedWords.isReserved("ISS")).isFalse()
    }
}
