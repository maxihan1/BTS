// LinkType enum — 코드↔라벨 매핑 및 DDL CHECK 정합 가드 테스트

package com.bts.issue.link.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class LinkTypeTest {

    @Test
    fun `fromCode는 'blocks' 코드를 BLOCKS 로 변환한다`() {
        assertThat(LinkType.fromCode("blocks")).isEqualTo(LinkType.BLOCKS)
    }

    @Test
    fun `fromCode는 알 수 없는 코드에 대해 예외를 던진다`() {
        assertThatThrownBy { LinkType.fromCode("unknown-code") }
            .isInstanceOf(Exception::class.java)
    }

    @Test
    fun `BLOCKS는 outwardLabel이 'blocks', inwardLabel이 'is blocked by'다`() {
        assertThat(LinkType.BLOCKS.outwardLabel).isEqualTo("blocks")
        assertThat(LinkType.BLOCKS.inwardLabel).isEqualTo("is blocked by")
    }

    @Test
    fun `RELATES는 대칭이다 — outwardLabel과 inwardLabel이 동일하고 isSymmetric이 true다`() {
        assertThat(LinkType.RELATES.outwardLabel).isEqualTo("relates to")
        assertThat(LinkType.RELATES.inwardLabel).isEqualTo("relates to")
        assertThat(LinkType.RELATES.isSymmetric).isTrue()
    }

    @Test
    fun `DUPLICATES는 outward가 'duplicates', inward가 'is duplicated by'이고 비대칭이다`() {
        assertThat(LinkType.DUPLICATES.outwardLabel).isEqualTo("duplicates")
        assertThat(LinkType.DUPLICATES.inwardLabel).isEqualTo("is duplicated by")
        assertThat(LinkType.DUPLICATES.isSymmetric).isFalse()
    }

    @Test
    fun `CLONES는 outward가 'clones', inward가 'is cloned by'이고 비대칭이다`() {
        assertThat(LinkType.CLONES.outwardLabel).isEqualTo("clones")
        assertThat(LinkType.CLONES.inwardLabel).isEqualTo("is cloned by")
        assertThat(LinkType.CLONES.isSymmetric).isFalse()
    }

    @Test
    fun `BLOCKS는 isSymmetric이 false다`() {
        assertThat(LinkType.BLOCKS.isSymmetric).isFalse()
    }

    /**
     * DDL CHECK 정합 가드 — issue_links 테이블의 link_type CHECK 제약과 enum 코드 집합이
     * 동기화되어 있는지 컴파일 타임에 고정한다. enum 값을 추가·삭제하면 이 테스트가 깨진다.
     */
    @Test
    fun `enum 코드 집합이 DDL CHECK와 정합한다`() {
        val expectedCodes = setOf("blocks", "relates", "duplicates", "clones")
        val actualCodes = LinkType.entries.map { it.code }.toSet()
        assertThat(actualCodes).isEqualTo(expectedCodes)
    }
}
