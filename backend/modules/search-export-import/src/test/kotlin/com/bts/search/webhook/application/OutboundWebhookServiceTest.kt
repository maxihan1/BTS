// 아웃바운드 webhook 구독 서비스 단위 테스트 — SYSTEM_ADMIN 게이트·SSRF·secret 암호화 (mockk 격리, FR-API-03 PR2)

package com.bts.search.webhook.application

import com.bts.search.webhook.domain.OutboundWebhook
import com.bts.search.webhook.domain.WebhookEventCatalog
import com.bts.shared.crypto.SecretEncryptor
import com.bts.shared.http.OutboundUrlValidator
import com.bts.shared.http.UrlCheck
import com.bts.shared.permission.SystemPermissionResolver
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID

class OutboundWebhookServiceTest {
    private val systemPermissionResolver: SystemPermissionResolver = mockk()
    private val urlValidator: OutboundUrlValidator = mockk()
    private val secretEncryptor: SecretEncryptor = mockk()
    private val repository: OutboundWebhookRepository = mockk()
    private val service =
        OutboundWebhookService(systemPermissionResolver, urlValidator, secretEncryptor, repository)

    private val adminId: UUID = UUID.randomUUID()
    private val nonAdminId: UUID = UUID.randomUUID()

    private val validUrl = "https://hooks.example.com/abc"
    private val validEventFilter = listOf(WebhookEventCatalog.ISSUE_CREATED)

    @BeforeEach
    fun setUp() {
        // fail-closed 기본: 명시 admin(adminId)이 아니면 전부 false — 미stub actor 도 non-admin 취급.
        every { systemPermissionResolver.isSystemAdmin(any()) } returns false
        every { systemPermissionResolver.isSystemAdmin(adminId) } returns true
        // URL 검증 기본 통과 — SSRF 테스트에서 개별 override.
        every { urlValidator.check(any()) } returns UrlCheck.Allowed
    }

    private fun aWebhook(
        id: UUID = UUID.randomUUID(),
        secretEncrypted: String? = null,
        version: Long = 0L,
    ): OutboundWebhook =
        OutboundWebhook(
            id = id,
            name = "배포 알림",
            url = validUrl,
            secretEncrypted = secretEncrypted,
            eventFilter = validEventFilter,
            projectKey = null,
            enabled = true,
            createdBy = adminId,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
            version = version,
        )

    // ── admin 게이트: 비admin → Forbidden, 리소스 조회 이전(존재 probe 차단) ─────────

    @Test
    fun `create - 비admin 이면 WebhookForbiddenException 이고 repo 미호출`() {
        assertThrows<WebhookForbiddenException> {
            service.create(nonAdminId, "배포 알림", validUrl, validEventFilter)
        }
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `list - 비admin 이면 WebhookForbiddenException 이고 repo 미호출`() {
        assertThrows<WebhookForbiddenException> {
            service.list(nonAdminId, 0, 20)
        }
        verify(exactly = 0) { repository.listAll(any(), any()) }
    }

    @Test
    fun `get - 비admin 이면 findById 이전에 WebhookForbiddenException (존재하지 않는 id 라도 403)`() {
        assertThrows<WebhookForbiddenException> {
            service.get(nonAdminId, UUID.randomUUID())
        }
        verify(exactly = 0) { repository.findById(any()) }
    }

    @Test
    fun `update - 비admin 이면 findById 이전에 WebhookForbiddenException`() {
        assertThrows<WebhookForbiddenException> {
            service.update(nonAdminId, UUID.randomUUID(), "배포 알림", validUrl, validEventFilter, 0L)
        }
        verify(exactly = 0) { repository.findById(any()) }
    }

    @Test
    fun `delete - 비admin 이면 softDelete 이전에 WebhookForbiddenException`() {
        assertThrows<WebhookForbiddenException> {
            service.delete(nonAdminId, UUID.randomUUID())
        }
        verify(exactly = 0) { repository.softDelete(any()) }
    }

    // ── create: SSRF·eventFilter·secret ───────────────────────────────────────

    @Test
    fun `create - SSRF Blocked url 이면 WebhookValidationException 이고 저장 안 함`() {
        every { urlValidator.check("http://169.254.169.254/") } returns UrlCheck.Blocked("내부망 주소 차단: 169.254.169.254")

        assertThrows<WebhookValidationException> {
            service.create(adminId, "배포 알림", "http://169.254.169.254/", validEventFilter)
        }
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `create - Malformed url 이면 WebhookValidationException 이고 저장 안 함`() {
        every { urlValidator.check("::::not-a-url") } returns UrlCheck.Malformed("URL 파싱 실패")

        assertThrows<WebhookValidationException> {
            service.create(adminId, "배포 알림", "::::not-a-url", validEventFilter)
        }
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `create - 미지 eventFilter 이면 WebhookValidationException 이고 저장 안 함`() {
        assertThrows<WebhookValidationException> {
            service.create(adminId, "배포 알림", validUrl, listOf("unknown.event"))
        }
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `create - secret 제공 시 encrypt 호출 후 암호문만 저장 (원문 미저장)`() {
        every { secretEncryptor.encrypt("raw-secret") } returns "ENC-CIPHERTEXT"
        every { repository.save(any()) } returns aWebhook(secretEncrypted = "ENC-CIPHERTEXT")

        service.create(adminId, "배포 알림", validUrl, validEventFilter, secret = "raw-secret")

        verify { secretEncryptor.encrypt("raw-secret") }
        verify {
            repository.save(
                match { it.secretEncrypted == "ENC-CIPHERTEXT" && it.secretEncrypted != "raw-secret" },
            )
        }
    }

    @Test
    fun `create - secret 없으면 encrypt 미호출 secretEncrypted null 저장`() {
        every { repository.save(any()) } returns aWebhook(secretEncrypted = null)

        service.create(adminId, "배포 알림", validUrl, validEventFilter)

        verify(exactly = 0) { secretEncryptor.encrypt(any()) }
        verify { repository.save(match { it.secretEncrypted == null }) }
    }

    @Test
    fun `create - blank secret 은 encrypt 미호출 (원문 없음 취급)`() {
        every { repository.save(any()) } returns aWebhook(secretEncrypted = null)

        service.create(adminId, "배포 알림", validUrl, validEventFilter, secret = "   ")

        verify(exactly = 0) { secretEncryptor.encrypt(any()) }
        verify { repository.save(match { it.secretEncrypted == null }) }
    }

    @Test
    fun `create - 정상 요청 시 저장 결과 그대로 반환`() {
        val saved = aWebhook()
        every { repository.save(any()) } returns saved

        val result = service.create(adminId, "배포 알림", validUrl, validEventFilter)

        assertEquals(saved, result)
    }

    // ── list / get ────────────────────────────────────────────────────────────

    @Test
    fun `list - admin 이면 repo listAll(page,size) 결과 반환`() {
        val page = listOf(aWebhook(), aWebhook())
        every { repository.listAll(0, 20) } returns page

        val result = service.list(adminId, 0, 20)

        assertEquals(page, result)
    }

    @Test
    fun `get - 존재하지 않으면 WebhookNotFoundException`() {
        every { repository.findById(any()) } returns null

        assertThrows<WebhookNotFoundException> {
            service.get(adminId, UUID.randomUUID())
        }
    }

    @Test
    fun `get - 존재하면 도메인 반환`() {
        val webhook = aWebhook()
        every { repository.findById(webhook.id!!) } returns webhook

        val result = service.get(adminId, webhook.id!!)

        assertEquals(webhook, result)
    }

    // ── update: 404·SSRF·secret 3-state·OCC ───────────────────────────────────

    @Test
    fun `update - 존재하지 않으면 WebhookNotFoundException`() {
        every { repository.findById(any()) } returns null

        assertThrows<WebhookNotFoundException> {
            service.update(adminId, UUID.randomUUID(), "새 이름", validUrl, validEventFilter, 0L)
        }
    }

    @Test
    fun `update - SSRF Blocked url 이면 WebhookValidationException 이고 update 안 함`() {
        val existing = aWebhook()
        every { repository.findById(existing.id!!) } returns existing
        every { urlValidator.check("http://10.0.0.1/") } returns UrlCheck.Blocked("내부망 주소 차단: 10.0.0.1")

        assertThrows<WebhookValidationException> {
            service.update(adminId, existing.id!!, "새 이름", "http://10.0.0.1/", validEventFilter, existing.version)
        }
        verify(exactly = 0) { repository.update(any()) }
    }

    @Test
    fun `update - secret 생략 시 기존 암호문 유지 encrypt 미호출`() {
        val existing = aWebhook(secretEncrypted = "OLD-ENC")
        every { repository.findById(existing.id!!) } returns existing
        every { repository.update(any()) } returns existing.copy(version = 1L)

        service.update(adminId, existing.id!!, "새 이름", validUrl, validEventFilter, existing.version)

        verify(exactly = 0) { secretEncryptor.encrypt(any()) }
        verify { repository.update(match { it.secretEncrypted == "OLD-ENC" }) }
    }

    @Test
    fun `update - secret 새 값 제공 시 재암호화 교체`() {
        val existing = aWebhook(secretEncrypted = "OLD-ENC")
        every { repository.findById(existing.id!!) } returns existing
        every { secretEncryptor.encrypt("new-secret") } returns "NEW-ENC"
        every { repository.update(any()) } returns existing.copy(secretEncrypted = "NEW-ENC", version = 1L)

        service.update(
            adminId,
            existing.id!!,
            "새 이름",
            validUrl,
            validEventFilter,
            existing.version,
            secret = "new-secret",
        )

        verify { secretEncryptor.encrypt("new-secret") }
        verify { repository.update(match { it.secretEncrypted == "NEW-ENC" }) }
    }

    @Test
    fun `update - repo update 가 null(OCC 충돌) 이면 WebhookConflictException`() {
        val existing = aWebhook()
        every { repository.findById(existing.id!!) } returns existing
        every { repository.update(any()) } returns null

        assertThrows<WebhookConflictException> {
            service.update(adminId, existing.id!!, "새 이름", validUrl, validEventFilter, existing.version)
        }
    }

    @Test
    fun `update - 정상 요청 시 갱신된 도메인 반환`() {
        val existing = aWebhook()
        val updated = existing.copy(name = "새 이름", version = 1L)
        every { repository.findById(existing.id!!) } returns existing
        every { repository.update(any()) } returns updated

        val result = service.update(adminId, existing.id!!, "새 이름", validUrl, validEventFilter, existing.version)

        assertEquals(updated, result)
    }

    @Test
    fun `update - version 은 클라이언트 제공 값을 OCC 키로 사용`() {
        val existing = aWebhook(version = 3L)
        every { repository.findById(existing.id!!) } returns existing
        every { repository.update(any()) } returns existing.copy(version = 8L)

        service.update(adminId, existing.id!!, "새 이름", validUrl, validEventFilter, version = 7L)

        verify { repository.update(match { it.version == 7L }) }
    }

    // ── delete ─────────────────────────────────────────────────────────────────

    @Test
    fun `delete - 존재하지 않으면(softDelete false) WebhookNotFoundException`() {
        every { repository.softDelete(any()) } returns false

        assertThrows<WebhookNotFoundException> {
            service.delete(adminId, UUID.randomUUID())
        }
    }

    @Test
    fun `delete - admin 이면 softDelete 호출`() {
        val id = UUID.randomUUID()
        every { repository.softDelete(id) } returns true

        service.delete(adminId, id)

        verify { repository.softDelete(id) }
    }
}
