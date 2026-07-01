// OutboundWebhook 도메인 모델 불변식 단위 테스트
package com.bts.search.webhook.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class OutboundWebhookTest {
    private val validCreatedBy: UUID = UUID.randomUUID()
    private val validName = "Slack 알림 구독"
    private val validUrl = "https://example.com/webhook"
    private val validEventFilter = listOf(WebhookEventCatalog.ISSUE_CREATED)

    // ── name 불변식 ──────────────────────────────────────────────────────────

    @Test
    fun `create - name이 blank이면 IllegalArgumentException을 던진다`() {
        assertThrows<IllegalArgumentException> {
            OutboundWebhook.create(
                createdBy = validCreatedBy,
                name = "   ",
                url = validUrl,
                eventFilter = validEventFilter,
            )
        }
    }

    @Test
    fun `create - name이 빈 문자열이면 IllegalArgumentException을 던진다`() {
        assertThrows<IllegalArgumentException> {
            OutboundWebhook.create(
                createdBy = validCreatedBy,
                name = "",
                url = validUrl,
                eventFilter = validEventFilter,
            )
        }
    }

    @Test
    fun `create - name이 최대 길이(100자)를 초과하면 IllegalArgumentException을 던진다`() {
        val tooLongName = "a".repeat(OutboundWebhook.MAX_NAME_LENGTH + 1)
        assertThrows<IllegalArgumentException> {
            OutboundWebhook.create(
                createdBy = validCreatedBy,
                name = tooLongName,
                url = validUrl,
                eventFilter = validEventFilter,
            )
        }
    }

    @Test
    fun `create - name이 정확히 최대 길이(100자)이면 성공한다`() {
        val maxName = "a".repeat(OutboundWebhook.MAX_NAME_LENGTH)
        val webhook =
            OutboundWebhook.create(
                createdBy = validCreatedBy,
                name = maxName,
                url = validUrl,
                eventFilter = validEventFilter,
            )
        assertEquals(maxName, webhook.name)
    }

    @Test
    fun `create - name 앞뒤 공백은 trim된다`() {
        val webhook =
            OutboundWebhook.create(
                createdBy = validCreatedBy,
                name = "  Slack 알림 구독  ",
                url = validUrl,
                eventFilter = validEventFilter,
            )
        assertEquals("Slack 알림 구독", webhook.name)
    }

    // ── url 불변식 ───────────────────────────────────────────────────────────

    @Test
    fun `create - url이 blank이면 IllegalArgumentException을 던진다`() {
        assertThrows<IllegalArgumentException> {
            OutboundWebhook.create(
                createdBy = validCreatedBy,
                name = validName,
                url = "   ",
                eventFilter = validEventFilter,
            )
        }
    }

    @Test
    fun `create - url이 빈 문자열이면 IllegalArgumentException을 던진다`() {
        assertThrows<IllegalArgumentException> {
            OutboundWebhook.create(
                createdBy = validCreatedBy,
                name = validName,
                url = "",
                eventFilter = validEventFilter,
            )
        }
    }

    // ── eventFilter 불변식 ───────────────────────────────────────────────────

    @Test
    fun `create - eventFilter가 빈 목록이면 IllegalArgumentException을 던진다`() {
        assertThrows<IllegalArgumentException> {
            OutboundWebhook.create(
                createdBy = validCreatedBy,
                name = validName,
                url = validUrl,
                eventFilter = emptyList(),
            )
        }
    }

    @Test
    fun `create - eventFilter에 allowlist에 없는 미지 이벤트가 포함되면 IllegalArgumentException을 던진다`() {
        assertThrows<IllegalArgumentException> {
            OutboundWebhook.create(
                createdBy = validCreatedBy,
                name = validName,
                url = validUrl,
                eventFilter = listOf("issue.unknown.event"),
            )
        }
    }

    @Test
    fun `create - eventFilter의 일부만 미지 이벤트여도 IllegalArgumentException을 던진다`() {
        assertThrows<IllegalArgumentException> {
            OutboundWebhook.create(
                createdBy = validCreatedBy,
                name = validName,
                url = validUrl,
                eventFilter = listOf(WebhookEventCatalog.ISSUE_CREATED, "not.a.real.event"),
            )
        }
    }

    @Test
    fun `create - eventFilter에 중복 이벤트가 있으면 정규화되어 중복이 제거된다`() {
        val webhook =
            OutboundWebhook.create(
                createdBy = validCreatedBy,
                name = validName,
                url = validUrl,
                eventFilter =
                    listOf(
                        WebhookEventCatalog.ISSUE_CREATED,
                        WebhookEventCatalog.ISSUE_CREATED,
                        WebhookEventCatalog.ISSUE_TRANSITIONED,
                    ),
            )
        assertEquals(
            listOf(WebhookEventCatalog.ISSUE_CREATED, WebhookEventCatalog.ISSUE_TRANSITIONED),
            webhook.eventFilter,
        )
    }

    // ── 정상 생성 ─────────────────────────────────────────────────────────────

    @Test
    fun `create - 유효한 값으로 생성하면 OutboundWebhook을 반환하고 id와 타임스탬프는 null, version은 0이다`() {
        val webhook =
            OutboundWebhook.create(
                createdBy = validCreatedBy,
                name = validName,
                url = validUrl,
                eventFilter = validEventFilter,
            )

        assertEquals(null, webhook.id)
        assertEquals(validCreatedBy, webhook.createdBy)
        assertEquals(validName, webhook.name)
        assertEquals(validUrl, webhook.url)
        assertEquals(validEventFilter, webhook.eventFilter)
        assertEquals(null, webhook.projectKey)
        assertEquals(null, webhook.secretEncrypted)
        assertTrue(webhook.enabled)
        assertEquals(null, webhook.createdAt)
        assertEquals(null, webhook.updatedAt)
        assertEquals(0L, webhook.version)
    }

    @Test
    fun `create - secret과 projectKey를 함께 전달하면 반영된다`() {
        val webhook =
            OutboundWebhook.create(
                createdBy = validCreatedBy,
                name = validName,
                url = validUrl,
                eventFilter = validEventFilter,
                secretEncrypted = "encrypted-cipher-text",
                projectKey = "ATLAS",
                enabled = false,
            )

        assertEquals("encrypted-cipher-text", webhook.secretEncrypted)
        assertEquals("ATLAS", webhook.projectKey)
        assertEquals(false, webhook.enabled)
    }

    // ── applyUpdate 불변식 (수정 시에도 create와 동일 검증 재적용) ───────────────

    @Test
    fun `applyUpdate - 유효한 값으로 갱신하면 필드가 교체된 새 인스턴스를 반환한다`() {
        val original =
            OutboundWebhook.create(
                createdBy = validCreatedBy,
                name = validName,
                url = validUrl,
                eventFilter = validEventFilter,
            )

        val updated =
            original.applyUpdate(
                name = "새 이름",
                url = "https://example.com/new-webhook",
                eventFilter = listOf(WebhookEventCatalog.ISSUE_TRANSITIONED),
                projectKey = "ATLAS",
                enabled = false,
            )

        assertEquals("새 이름", updated.name)
        assertEquals("https://example.com/new-webhook", updated.url)
        assertEquals(listOf(WebhookEventCatalog.ISSUE_TRANSITIONED), updated.eventFilter)
        assertEquals("ATLAS", updated.projectKey)
        assertEquals(false, updated.enabled)
        // 원본 인스턴스는 변경되지 않는다 (불변).
        assertEquals(validName, original.name)
    }

    @Test
    fun `applyUpdate - secretEncrypted를 생략하면 기존 값이 유지된다`() {
        val original =
            OutboundWebhook.create(
                createdBy = validCreatedBy,
                name = validName,
                url = validUrl,
                eventFilter = validEventFilter,
                secretEncrypted = "original-cipher-text",
            )

        val updated =
            original.applyUpdate(
                name = validName,
                url = validUrl,
                eventFilter = validEventFilter,
            )

        assertEquals("original-cipher-text", updated.secretEncrypted)
    }

    @Test
    fun `applyUpdate - name이 blank이면 IllegalArgumentException을 던진다`() {
        val original =
            OutboundWebhook.create(
                createdBy = validCreatedBy,
                name = validName,
                url = validUrl,
                eventFilter = validEventFilter,
            )

        assertThrows<IllegalArgumentException> {
            original.applyUpdate(name = "   ", url = validUrl, eventFilter = validEventFilter)
        }
    }

    @Test
    fun `applyUpdate - eventFilter에 미지 이벤트가 있으면 IllegalArgumentException을 던진다`() {
        val original =
            OutboundWebhook.create(
                createdBy = validCreatedBy,
                name = validName,
                url = validUrl,
                eventFilter = validEventFilter,
            )

        assertThrows<IllegalArgumentException> {
            original.applyUpdate(name = validName, url = validUrl, eventFilter = listOf("not.a.real.event"))
        }
    }
}
