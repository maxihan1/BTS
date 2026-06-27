// AqlFields 단위 테스트 — text 가상 FTS 필드 포함 MVP 필드 분류와 연산자 제약 전체 검증

package com.bts.shared.search

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [AqlFields] 단위 테스트.
 *
 * ### 검증 항목
 *
 * - `text` 가상 FTS 필드 분류 (FR-SR-04 신규)
 * - `text` 연산자 제약 (`~`만 허용, `=`/`!=`/`IN`/`NOT IN` 금지)
 * - 기존 MVP 필드(status/label/summary/priority) 분류 및 제약 회귀 가드
 * - PLANNED/UNKNOWN 필드 분류
 * - 필드명 대소문자 정규화
 */
class AqlFieldsTest {
    // ── text 가상 FTS 필드 (신규, FR-SR-04) ──────────────────────────────────────

    @Test
    fun `text 필드는 SUPPORTED로 분류된다`() {
        assertThat(AqlFields.classify("text")).isEqualTo(AqlFields.FieldClassification.SUPPORTED)
    }

    @Test
    fun `text 필드에 EQ 연산자는 금지된다`() {
        assertThat(AqlFields.isOperatorForbidden("text", AqlOperator.EQ)).isTrue()
    }

    @Test
    fun `text 필드에 NEQ 연산자는 금지된다`() {
        assertThat(AqlFields.isOperatorForbidden("text", AqlOperator.NEQ)).isTrue()
    }

    @Test
    fun `text 필드에 IN 연산자는 금지된다`() {
        assertThat(AqlFields.isOperatorForbidden("text", AqlOperator.IN)).isTrue()
    }

    @Test
    fun `text 필드에 NOT_IN 연산자는 금지된다`() {
        assertThat(AqlFields.isOperatorForbidden("text", AqlOperator.NOT_IN)).isTrue()
    }

    @Test
    fun `text 필드에 CONTAINS 연산자는 허용된다`() {
        assertThat(AqlFields.isOperatorForbidden("text", AqlOperator.CONTAINS)).isFalse()
    }

    // ── 기존 MVP 필드 분류 회귀 가드 ─────────────────────────────────────────────

    @Test
    fun `status 필드는 SUPPORTED로 분류된다`() {
        assertThat(AqlFields.classify("status")).isEqualTo(AqlFields.FieldClassification.SUPPORTED)
    }

    @Test
    fun `label 필드는 SUPPORTED로 분류된다`() {
        assertThat(AqlFields.classify("label")).isEqualTo(AqlFields.FieldClassification.SUPPORTED)
    }

    @Test
    fun `summary 필드는 SUPPORTED로 분류된다`() {
        assertThat(AqlFields.classify("summary")).isEqualTo(AqlFields.FieldClassification.SUPPORTED)
    }

    @Test
    fun `priority 필드는 SUPPORTED로 분류된다`() {
        assertThat(AqlFields.classify("priority")).isEqualTo(AqlFields.FieldClassification.SUPPORTED)
    }

    // ── 기존 연산자 제약 회귀 가드 ───────────────────────────────────────────────

    @Test
    fun `priority 필드에 CONTAINS 연산자는 금지된다`() {
        assertThat(AqlFields.isOperatorForbidden("priority", AqlOperator.CONTAINS)).isTrue()
    }

    @Test
    fun `status 필드에 CONTAINS 연산자는 금지된다`() {
        assertThat(AqlFields.isOperatorForbidden("status", AqlOperator.CONTAINS)).isTrue()
    }

    @Test
    fun `priority 필드에 EQ 연산자는 허용된다`() {
        assertThat(AqlFields.isOperatorForbidden("priority", AqlOperator.EQ)).isFalse()
    }

    @Test
    fun `label 필드에 CONTAINS 연산자는 허용된다`() {
        assertThat(AqlFields.isOperatorForbidden("label", AqlOperator.CONTAINS)).isFalse()
    }

    @Test
    fun `summary 필드에 CONTAINS 연산자는 허용된다`() {
        assertThat(AqlFields.isOperatorForbidden("summary", AqlOperator.CONTAINS)).isFalse()
    }

    // ── PLANNED / UNKNOWN 필드 분류 ──────────────────────────────────────────────

    @Test
    fun `assignee 필드는 PLANNED로 분류된다`() {
        assertThat(AqlFields.classify("assignee")).isEqualTo(AqlFields.FieldClassification.PLANNED)
    }

    @Test
    fun `reporter 필드는 PLANNED로 분류된다`() {
        assertThat(AqlFields.classify("reporter")).isEqualTo(AqlFields.FieldClassification.PLANNED)
    }

    @Test
    fun `component 필드는 PLANNED로 분류된다`() {
        assertThat(AqlFields.classify("component")).isEqualTo(AqlFields.FieldClassification.PLANNED)
    }

    @Test
    fun `project 필드는 PLANNED로 분류된다`() {
        assertThat(AqlFields.classify("project")).isEqualTo(AqlFields.FieldClassification.PLANNED)
    }

    @Test
    fun `인식할 수 없는 필드는 UNKNOWN으로 분류된다`() {
        assertThat(AqlFields.classify("foobar")).isEqualTo(AqlFields.FieldClassification.UNKNOWN)
        assertThat(AqlFields.classify("created")).isEqualTo(AqlFields.FieldClassification.UNKNOWN)
        assertThat(AqlFields.classify("unknown_field")).isEqualTo(AqlFields.FieldClassification.UNKNOWN)
    }

    // ── 대소문자 정규화 ────────────────────────────────────────────────────────────

    @Test
    fun `필드명 대소문자는 정규화되어 분류된다`() {
        assertThat(AqlFields.classify("TEXT")).isEqualTo(AqlFields.FieldClassification.SUPPORTED)
        assertThat(AqlFields.classify("Text")).isEqualTo(AqlFields.FieldClassification.SUPPORTED)
        assertThat(AqlFields.classify("STATUS")).isEqualTo(AqlFields.FieldClassification.SUPPORTED)
    }

    @Test
    fun `연산자 금지 검사 시 필드명 대소문자는 정규화된다`() {
        assertThat(AqlFields.isOperatorForbidden("TEXT", AqlOperator.EQ)).isTrue()
        assertThat(AqlFields.isOperatorForbidden("TEXT", AqlOperator.CONTAINS)).isFalse()
        assertThat(AqlFields.isOperatorForbidden("PRIORITY", AqlOperator.CONTAINS)).isTrue()
    }
}
