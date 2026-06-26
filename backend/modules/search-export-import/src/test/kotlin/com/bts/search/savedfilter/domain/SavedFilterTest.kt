// SavedFilter 도메인 모델 불변식 단위 테스트

package com.bts.search.savedfilter.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class SavedFilterTest {
    private val validOwnerId: UUID = UUID.randomUUID()
    private val validName = "내 버그 필터"
    private val validAqlQuery = "status = OPEN AND assignee = currentUser()"
    private val validProjectKey = "ATLAS"

    // ── name 불변식 ──────────────────────────────────────────────────────────

    @Test
    fun `create - name이 blank이면 IllegalArgumentException을 던진다`() {
        assertThrows<IllegalArgumentException> {
            SavedFilter.create(
                ownerId = validOwnerId,
                name = "   ",
                aqlQuery = validAqlQuery,
                projectKey = validProjectKey,
            )
        }
    }

    @Test
    fun `create - name이 빈 문자열이면 IllegalArgumentException을 던진다`() {
        assertThrows<IllegalArgumentException> {
            SavedFilter.create(
                ownerId = validOwnerId,
                name = "",
                aqlQuery = validAqlQuery,
                projectKey = validProjectKey,
            )
        }
    }

    @Test
    fun `create - name이 최대 길이(100자)를 초과하면 IllegalArgumentException을 던진다`() {
        val tooLongName = "a".repeat(SavedFilter.MAX_NAME_LENGTH + 1)
        assertThrows<IllegalArgumentException> {
            SavedFilter.create(
                ownerId = validOwnerId,
                name = tooLongName,
                aqlQuery = validAqlQuery,
                projectKey = validProjectKey,
            )
        }
    }

    @Test
    fun `create - name이 정확히 최대 길이(100자)이면 성공한다`() {
        val maxName = "a".repeat(SavedFilter.MAX_NAME_LENGTH)
        val filter =
            SavedFilter.create(
                ownerId = validOwnerId,
                name = maxName,
                aqlQuery = validAqlQuery,
                projectKey = validProjectKey,
            )
        assertEquals(maxName, filter.name)
    }

    // ── aqlQuery 불변식 ───────────────────────────────────────────────────────

    @Test
    fun `create - aqlQuery가 blank이면 IllegalArgumentException을 던진다`() {
        assertThrows<IllegalArgumentException> {
            SavedFilter.create(
                ownerId = validOwnerId,
                name = validName,
                aqlQuery = "   ",
                projectKey = validProjectKey,
            )
        }
    }

    @Test
    fun `create - aqlQuery가 빈 문자열이면 IllegalArgumentException을 던진다`() {
        assertThrows<IllegalArgumentException> {
            SavedFilter.create(
                ownerId = validOwnerId,
                name = validName,
                aqlQuery = "",
                projectKey = validProjectKey,
            )
        }
    }

    @Test
    fun `create - aqlQuery가 최대 길이(2000자)를 초과하면 IllegalArgumentException을 던진다`() {
        val tooLongQuery = "a".repeat(SavedFilter.MAX_AQL_LENGTH + 1)
        assertThrows<IllegalArgumentException> {
            SavedFilter.create(
                ownerId = validOwnerId,
                name = validName,
                aqlQuery = tooLongQuery,
                projectKey = validProjectKey,
            )
        }
    }

    @Test
    fun `create - aqlQuery가 정확히 최대 길이(2000자)이면 성공한다`() {
        val maxQuery = "a".repeat(SavedFilter.MAX_AQL_LENGTH)
        val filter =
            SavedFilter.create(
                ownerId = validOwnerId,
                name = validName,
                aqlQuery = maxQuery,
                projectKey = validProjectKey,
            )
        assertEquals(maxQuery, filter.aqlQuery)
    }

    // ── projectKey 불변식 ─────────────────────────────────────────────────────

    @Test
    fun `create - projectKey가 blank이면 IllegalArgumentException을 던진다`() {
        assertThrows<IllegalArgumentException> {
            SavedFilter.create(
                ownerId = validOwnerId,
                name = validName,
                aqlQuery = validAqlQuery,
                projectKey = "   ",
            )
        }
    }

    @Test
    fun `create - projectKey가 빈 문자열이면 IllegalArgumentException을 던진다`() {
        assertThrows<IllegalArgumentException> {
            SavedFilter.create(
                ownerId = validOwnerId,
                name = validName,
                aqlQuery = validAqlQuery,
                projectKey = "",
            )
        }
    }

    // ── 정상 생성 ─────────────────────────────────────────────────────────────

    @Test
    fun `create - 유효한 값으로 생성하면 SavedFilter를 반환하고 id와 타임스탬프는 null이다`() {
        val filter =
            SavedFilter.create(
                ownerId = validOwnerId,
                name = validName,
                aqlQuery = validAqlQuery,
                projectKey = validProjectKey,
            )

        assertEquals(validOwnerId, filter.ownerId)
        assertEquals(validName, filter.name)
        assertEquals(validAqlQuery, filter.aqlQuery)
        assertEquals(validProjectKey, filter.projectKey)
        assertEquals(null, filter.id)
        assertEquals(null, filter.createdAt)
        assertEquals(null, filter.updatedAt)
        assertEquals(0L, filter.version)
    }

    @Test
    fun `create - name 앞뒤 공백은 trim된다`() {
        val filter =
            SavedFilter.create(
                ownerId = validOwnerId,
                name = "  내 버그 필터  ",
                aqlQuery = validAqlQuery,
                projectKey = validProjectKey,
            )
        assertEquals("내 버그 필터", filter.name)
    }

    @Test
    fun `create - 반환된 객체는 모든 필드가 val(불변)이어야 한다`() {
        val filter =
            SavedFilter.create(
                ownerId = validOwnerId,
                name = validName,
                aqlQuery = validAqlQuery,
                projectKey = validProjectKey,
            )
        // data class 동등성 검증 — 같은 값으로 재생성한 객체와 equals
        val same =
            SavedFilter.create(
                ownerId = validOwnerId,
                name = validName,
                aqlQuery = validAqlQuery,
                projectKey = validProjectKey,
            )
        assertEquals(filter, same)
    }
}
