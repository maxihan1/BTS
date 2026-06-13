// IssueLink 도메인 객체 — 생성 규칙(자기참조 금지) 단위 테스트

package com.bts.issue.link.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class IssueLinkTest {

    @Test
    fun `sourceId와 targetId가 같으면 LinkSelfReferenceException을 던진다`() {
        val sameId = UUID.randomUUID()
        assertThatThrownBy { IssueLink.create(sameId, sameId, LinkType.BLOCKS) }
            .isInstanceOf(LinkSelfReferenceException::class.java)
    }

    @Test
    fun `서로 다른 sourceId와 targetId로 정상 생성된다`() {
        val sourceId = UUID.randomUUID()
        val targetId = UUID.randomUUID()

        val link = IssueLink.create(sourceId, targetId, LinkType.BLOCKS)

        assertThat(link.sourceId).isEqualTo(sourceId)
        assertThat(link.targetId).isEqualTo(targetId)
        assertThat(link.linkType).isEqualTo(LinkType.BLOCKS)
    }

    @Test
    fun `RELATES 타입으로도 정상 생성된다`() {
        val link = IssueLink.create(UUID.randomUUID(), UUID.randomUUID(), LinkType.RELATES)
        assertThat(link.linkType).isEqualTo(LinkType.RELATES)
    }
}
