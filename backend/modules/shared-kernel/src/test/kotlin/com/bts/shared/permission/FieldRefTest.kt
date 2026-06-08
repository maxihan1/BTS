// 필드 수준 권한 계약(FieldRef 동등성/FieldKind 2종)이 shared-kernel에 존재함을 단언

package com.bts.shared.permission

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [FieldRef] · [FieldKind] 의 계약 단위 테스트.
 *
 * FR-PM-07 PR-A Task 1 — 필드 수준 권한 판정 cross-BC 포트를 shared-kernel
 * (`com.bts.shared.permission`)에 고정한다. Spring 컨텍스트 없이 순수 단위 테스트로 실행한다.
 *
 * 검증 항목.
 * - field_kind_entries — [FieldKind] 항목 2종(CORE, CUSTOM).
 * - field_ref_equality — 같은 kind·key 의 [FieldRef] 는 동등(`==`)하고 해시코드가 같다.
 * - field_ref_inequality — kind 또는 key 가 다르면 동등하지 않다.
 */
class FieldRefTest {
    @Test
    fun `field_kind_entries — FieldKind 항목은 CORE, CUSTOM 2종이다`() {
        assertThat(FieldKind.entries)
            .containsExactlyInAnyOrder(FieldKind.CORE, FieldKind.CUSTOM)
    }

    @Test
    fun `field_ref_equality — 같은 kind·key 는 동등하고 해시코드가 같다`() {
        val a = FieldRef(FieldKind.CORE, "summary")
        val b = FieldRef(FieldKind.CORE, "summary")

        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
    }

    @Test
    fun `field_ref_inequality — kind 또는 key 가 다르면 동등하지 않다`() {
        val core = FieldRef(FieldKind.CORE, "summary")
        val custom = FieldRef(FieldKind.CUSTOM, "summary")
        val otherKey = FieldRef(FieldKind.CORE, "priority")

        assertThat(core).isNotEqualTo(custom)
        assertThat(core).isNotEqualTo(otherKey)
    }
}
