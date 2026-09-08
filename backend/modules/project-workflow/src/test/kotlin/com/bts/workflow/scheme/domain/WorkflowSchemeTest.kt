// WorkflowScheme Aggregate Root factory invariant 검증 — create(key, name, description, isDefault)

package com.bts.workflow.scheme.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * WorkflowScheme.create(...) companion factory 의 invariant 검증 테스트.
 *
 * 검증 대상.
 * 1. 정상 생성 — name 비어 있지 않음, description null 허용, isDefault 기본 false
 * 2. name 빈 문자열 → IllegalArgumentException
 * 3. name 공백만 → IllegalArgumentException
 * 4. description null 허용 — 예외 없음
 * 5. isDefault 기본값 false
 * 6. equals / hashCode — key 기반 동일성
 * 7. Clock 주입 — createdAt / updatedAt 고정 가능
 */
class WorkflowSchemeTest {
    // ── 픽스처 ────────────────────────────────────────────────────────────────

    private val fixedClock: Clock =
        Clock.fixed(Instant.parse("2024-01-15T09:00:00Z"), ZoneOffset.UTC)

    private val validKey = WorkflowSchemeKey("software-scheme")
    private val validName = "Software Development Scheme"

    // ── 정상 생성 ─────────────────────────────────────────────────────────────

    @Test
    fun `정상 파라미터로 WorkflowScheme 생성 성공`() {
        val scheme =
            WorkflowScheme.create(
                key = validKey,
                name = validName,
                description = "소프트웨어 개발 기본 스킴",
                isDefault = true,
                clock = fixedClock,
                projectId = null,
            )

        assertThat(scheme.key).isEqualTo(validKey)
        assertThat(scheme.name).isEqualTo(validName)
        assertThat(scheme.description).isEqualTo("소프트웨어 개발 기본 스킴")
        assertThat(scheme.isDefault).isTrue()
        assertThat(scheme.id).isNull()
        assertThat(scheme.deletedAt).isNull()
    }

    // ── isDefault 기본값 ──────────────────────────────────────────────────────

    @Test
    fun `isDefault 생략 시 기본값은 false`() {
        val scheme =
            WorkflowScheme.create(
                key = validKey,
                name = validName,
                description = null,
                clock = fixedClock,
                projectId = null,
            )

        assertThat(scheme.isDefault).isFalse()
    }

    // ── description null 허용 ─────────────────────────────────────────────────

    @Test
    fun `description null 이어도 예외 없이 생성 성공`() {
        val scheme =
            WorkflowScheme.create(
                key = validKey,
                name = validName,
                description = null,
                clock = fixedClock,
                projectId = null,
            )

        assertThat(scheme.description).isNull()
    }

    // ── name 빈 문자열 거부 ───────────────────────────────────────────────────

    @Test
    fun `name 이 빈 문자열이면 IllegalArgumentException`() {
        assertThatThrownBy {
            WorkflowScheme.create(
                key = validKey,
                name = "",
                description = null,
                clock = fixedClock,
                projectId = null,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("name")
    }

    // ── name 공백만 거부 ──────────────────────────────────────────────────────

    @Test
    fun `name 이 공백만 있으면 IllegalArgumentException`() {
        assertThatThrownBy {
            WorkflowScheme.create(
                key = validKey,
                name = "   ",
                description = null,
                clock = fixedClock,
                projectId = null,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("name")
    }

    // ── Clock 주입 ────────────────────────────────────────────────────────────

    @Test
    fun `Clock 주입 시 createdAt 과 updatedAt 이 고정 시각으로 설정`() {
        val scheme =
            WorkflowScheme.create(
                key = validKey,
                name = validName,
                description = null,
                clock = fixedClock,
                projectId = null,
            )

        val expected = Instant.parse("2024-01-15T09:00:00Z")
        assertThat(scheme.createdAt).isEqualTo(expected)
        assertThat(scheme.updatedAt).isEqualTo(expected)
    }

    // ── equals / hashCode (key 기반) ──────────────────────────────────────────

    @Test
    fun `같은 key 를 가진 두 WorkflowScheme 은 equals true`() {
        val scheme1 =
            WorkflowScheme.create(
                key = validKey,
                name = "이름 A",
                description = null,
                clock = fixedClock,
                projectId = null,
            )
        val scheme2 =
            WorkflowScheme.create(
                key = validKey,
                name = "이름 B",
                description = "설명",
                isDefault = true,
                clock = fixedClock,
                projectId = null,
            )

        assertThat(scheme1).isEqualTo(scheme2)
        assertThat(scheme1.hashCode()).isEqualTo(scheme2.hashCode())
    }

    @Test
    fun `다른 key 를 가진 두 WorkflowScheme 은 equals false`() {
        val scheme1 =
            WorkflowScheme.create(
                key = WorkflowSchemeKey("software-scheme"),
                name = validName,
                description = null,
                clock = fixedClock,
                projectId = null,
            )
        val scheme2 =
            WorkflowScheme.create(
                key = WorkflowSchemeKey("agile-scheme"),
                name = validName,
                description = null,
                clock = fixedClock,
                projectId = null,
            )

        assertThat(scheme1).isNotEqualTo(scheme2)
    }
}
