// SavedFilterShare 도메인 모델 + ShareType enum 불변식 단위 테스트 (FR-SR-03)

package com.bts.search.savedfilter.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SavedFilterShareTest {

    // ── ShareType.from 불변식 ─────────────────────────────────────────────────

    @Test
    fun `ShareType from - 알려진 값 PROJECT는 PROJECT를 반환한다`() {
        assertEquals(ShareType.PROJECT, ShareType.from("PROJECT"))
    }

    @Test
    fun `ShareType from - 알려진 값 GROUP는 GROUP를 반환한다`() {
        assertEquals(ShareType.GROUP, ShareType.from("GROUP"))
    }

    @Test
    fun `ShareType from - 알려진 값 AUTHENTICATED는 AUTHENTICATED를 반환한다`() {
        assertEquals(ShareType.AUTHENTICATED, ShareType.from("AUTHENTICATED"))
    }

    @Test
    fun `ShareType from - 알 수 없는 문자열은 IllegalArgumentException을 던진다`() {
        assertThrows<IllegalArgumentException> {
            ShareType.from("UNKNOWN_TYPE")
        }
    }

    @Test
    fun `ShareType from - 빈 문자열은 IllegalArgumentException을 던진다`() {
        assertThrows<IllegalArgumentException> {
            ShareType.from("")
        }
    }

    // ── PROJECT targetId 불변식 ───────────────────────────────────────────────

    @Test
    fun `create - PROJECT에 targetId null이면 IllegalArgumentException을 던진다`() {
        assertThrows<IllegalArgumentException> {
            SavedFilterShare.create(
                shareType = ShareType.PROJECT,
                targetId = null,
            )
        }
    }

    @Test
    fun `create - PROJECT에 targetId blank이면 IllegalArgumentException을 던진다`() {
        assertThrows<IllegalArgumentException> {
            SavedFilterShare.create(
                shareType = ShareType.PROJECT,
                targetId = "   ",
            )
        }
    }

    @Test
    fun `create - PROJECT에 targetId 빈 문자열이면 IllegalArgumentException을 던진다`() {
        assertThrows<IllegalArgumentException> {
            SavedFilterShare.create(
                shareType = ShareType.PROJECT,
                targetId = "",
            )
        }
    }

    @Test
    fun `create - PROJECT에 유효한 targetId이면 SavedFilterShare를 반환한다`() {
        val share = SavedFilterShare.create(
            shareType = ShareType.PROJECT,
            targetId = "ATLAS",
        )
        assertEquals(ShareType.PROJECT, share.shareType)
        assertEquals("ATLAS", share.targetId)
    }

    // ── GROUP targetId 불변식 ─────────────────────────────────────────────────

    @Test
    fun `create - GROUP에 targetId null이면 IllegalArgumentException을 던진다`() {
        assertThrows<IllegalArgumentException> {
            SavedFilterShare.create(
                shareType = ShareType.GROUP,
                targetId = null,
            )
        }
    }

    @Test
    fun `create - GROUP에 targetId blank이면 IllegalArgumentException을 던진다`() {
        assertThrows<IllegalArgumentException> {
            SavedFilterShare.create(
                shareType = ShareType.GROUP,
                targetId = "   ",
            )
        }
    }

    @Test
    fun `create - GROUP에 유효한 targetId이면 SavedFilterShare를 반환한다`() {
        val share = SavedFilterShare.create(
            shareType = ShareType.GROUP,
            targetId = "group-uuid-1234",
        )
        assertEquals(ShareType.GROUP, share.shareType)
        assertEquals("group-uuid-1234", share.targetId)
    }

    // ── AUTHENTICATED targetId 불변식 ─────────────────────────────────────────

    @Test
    fun `create - AUTHENTICATED에 targetId null이면 성공한다`() {
        val share = SavedFilterShare.create(
            shareType = ShareType.AUTHENTICATED,
            targetId = null,
        )
        assertEquals(ShareType.AUTHENTICATED, share.shareType)
        assertEquals(null, share.targetId)
    }

    @Test
    fun `create - AUTHENTICATED에 targetId 값이 있으면 IllegalArgumentException을 던진다`() {
        assertThrows<IllegalArgumentException> {
            SavedFilterShare.create(
                shareType = ShareType.AUTHENTICATED,
                targetId = "some-id",
            )
        }
    }

    // ── targetId trim 불변식 ──────────────────────────────────────────────────

    @Test
    fun `create - PROJECT의 targetId 앞뒤 공백은 trim된다`() {
        val share = SavedFilterShare.create(
            shareType = ShareType.PROJECT,
            targetId = "  ATLAS  ",
        )
        assertEquals("ATLAS", share.targetId)
    }

    // ── 컬렉션 정규화 — dedupe ────────────────────────────────────────────────

    @Test
    fun `normalize - 동일한 shareType과 targetId 쌍은 중복 제거된다`() {
        val shares = listOf(
            SavedFilterShare.create(ShareType.PROJECT, "ATLAS"),
            SavedFilterShare.create(ShareType.PROJECT, "ATLAS"),
            SavedFilterShare.create(ShareType.PROJECT, "ATLAS"),
        )
        val result = SavedFilterShare.normalize(shares)
        assertThat(result).hasSize(1)
    }

    @Test
    fun `normalize - shareType이 다르면 중복으로 보지 않는다`() {
        val shares = listOf(
            SavedFilterShare.create(ShareType.PROJECT, "ATLAS"),
            SavedFilterShare.create(ShareType.GROUP, "ATLAS"),
        )
        val result = SavedFilterShare.normalize(shares)
        assertThat(result).hasSize(2)
    }

    @Test
    fun `normalize - targetId가 다르면 중복으로 보지 않는다`() {
        val shares = listOf(
            SavedFilterShare.create(ShareType.PROJECT, "ATLAS"),
            SavedFilterShare.create(ShareType.PROJECT, "BETA"),
        )
        val result = SavedFilterShare.normalize(shares)
        assertThat(result).hasSize(2)
    }

    // ── 컬렉션 정규화 — 상한 검사 ─────────────────────────────────────────────

    @Test
    fun `normalize - dedupe 후 MAX_SHARES_PER_FILTER 이하이면 통과한다`() {
        val shares = (1..SavedFilterShare.MAX_SHARES_PER_FILTER).map { i ->
            SavedFilterShare.create(ShareType.PROJECT, "PROJ-$i")
        }
        val result = SavedFilterShare.normalize(shares)
        assertThat(result).hasSize(SavedFilterShare.MAX_SHARES_PER_FILTER)
    }

    @Test
    fun `normalize - dedupe 후 MAX_SHARES_PER_FILTER 초과 시 IllegalArgumentException을 던진다`() {
        val shares = (1..(SavedFilterShare.MAX_SHARES_PER_FILTER + 1)).map { i ->
            SavedFilterShare.create(ShareType.PROJECT, "PROJ-$i")
        }
        assertThrows<IllegalArgumentException> {
            SavedFilterShare.normalize(shares)
        }
    }

    @Test
    fun `normalize - 동일 공유 60개는 dedupe 후 1개가 되어 상한 검사를 통과한다`() {
        // MAX_SHARES_PER_FILTER = 50 이지만 dedupe 먼저 → 1개 → 통과
        val shares = (1..60).map {
            SavedFilterShare.create(ShareType.PROJECT, "ATLAS")
        }
        val result = SavedFilterShare.normalize(shares)
        assertThat(result).hasSize(1)
    }

    // ── data class 불변성 ─────────────────────────────────────────────────────

    @Test
    fun `create - 동일한 인수로 생성된 두 SavedFilterShare는 equals가 참이다`() {
        val a = SavedFilterShare.create(ShareType.PROJECT, "ATLAS")
        val b = SavedFilterShare.create(ShareType.PROJECT, "ATLAS")
        assertEquals(a, b)
    }
}
