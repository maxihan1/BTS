// 아웃바운드 webhook 구독 CRUD 서비스 — SYSTEM_ADMIN 게이트·SSRF 검증·secret 암호화 오케스트레이션 (FR-API-03 PR2)

package com.bts.search.webhook.application

import com.bts.search.webhook.domain.OutboundWebhook
import com.bts.shared.crypto.SecretEncryptor
import com.bts.shared.http.OutboundUrlValidator
import com.bts.shared.http.UrlCheck
import com.bts.shared.permission.SystemPermissionResolver
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional
class OutboundWebhookService(
    private val systemPermissionResolver: SystemPermissionResolver,
    private val urlValidator: OutboundUrlValidator,
    private val secretEncryptor: SecretEncryptor,
    private val repository: OutboundWebhookRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Suppress("LongParameterList", "ThrowsCount")
    fun create(
        actorId: UUID,
        name: String,
        url: String,
        eventFilter: List<String>,
        secret: String? = null,
        projectKey: String? = null,
        enabled: Boolean = true,
    ): OutboundWebhook {
        if (!systemPermissionResolver.isSystemAdmin(actorId)) throw WebhookForbiddenException()
        validateUrlNotSsrf(url)
        val secretEncrypted = secret?.takeIf { it.isNotBlank() }?.let { secretEncryptor.encrypt(it) }
        val webhook =
            try {
                OutboundWebhook.create(
                    createdBy = actorId,
                    name = name,
                    url = url,
                    eventFilter = eventFilter,
                    secretEncrypted = secretEncrypted,
                    projectKey = projectKey,
                    enabled = enabled,
                )
            } catch (e: IllegalArgumentException) {
                throw WebhookValidationException(e.message ?: DEFAULT_INVALID_MESSAGE, e)
            }
        log.info("OutboundWebhookService.create actor={} name={} hasSecret={}", actorId, name, secretEncrypted != null)
        return repository.save(webhook)
    }

    @Transactional(readOnly = true)
    fun list(
        actorId: UUID,
        page: Int,
        size: Int,
    ): List<OutboundWebhook> {
        if (!systemPermissionResolver.isSystemAdmin(actorId)) throw WebhookForbiddenException()
        return repository.listAll(page, size)
    }

    @Transactional(readOnly = true)
    fun get(
        actorId: UUID,
        id: UUID,
    ): OutboundWebhook {
        if (!systemPermissionResolver.isSystemAdmin(actorId)) throw WebhookForbiddenException()
        return repository.findById(id) ?: throw WebhookNotFoundException(id)
    }

    @Suppress("LongParameterList", "ThrowsCount")
    fun update(
        actorId: UUID,
        id: UUID,
        name: String,
        url: String,
        eventFilter: List<String>,
        version: Long,
        secret: String? = null,
        projectKey: String? = null,
        enabled: Boolean = true,
    ): OutboundWebhook {
        if (!systemPermissionResolver.isSystemAdmin(actorId)) throw WebhookForbiddenException()
        val existing = repository.findById(id) ?: throw WebhookNotFoundException(id)
        validateUrlNotSsrf(url)
        val secretEncrypted =
            if (secret.isNullOrBlank()) existing.secretEncrypted else secretEncryptor.encrypt(secret)
        val toUpdate =
            try {
                existing.applyUpdate(
                    name = name,
                    url = url,
                    eventFilter = eventFilter,
                    secretEncrypted = secretEncrypted,
                    projectKey = projectKey,
                    enabled = enabled,
                ).copy(version = version)
            } catch (e: IllegalArgumentException) {
                throw WebhookValidationException(e.message ?: DEFAULT_INVALID_MESSAGE, e)
            }
        log.info("OutboundWebhookService.update id={} actor={} hasSecret={}", id, actorId, secretEncrypted != null)
        return repository.update(toUpdate) ?: throw WebhookConflictException(id)
    }

    fun delete(
        actorId: UUID,
        id: UUID,
    ) {
        if (!systemPermissionResolver.isSystemAdmin(actorId)) throw WebhookForbiddenException()
        log.info("OutboundWebhookService.delete id={} actor={}", id, actorId)
        if (!repository.softDelete(id)) throw WebhookNotFoundException(id)
    }

    private fun validateUrlNotSsrf(url: String) {
        when (val check = urlValidator.check(url)) {
            is UrlCheck.Blocked -> {
                log.warn("webhook_url_rejected reason=blocked detail={}", check.reason)
                throw WebhookValidationException(SSRF_REJECT_MESSAGE)
            }
            is UrlCheck.Malformed -> {
                log.warn("webhook_url_rejected reason=malformed")
                throw WebhookValidationException(SSRF_REJECT_MESSAGE)
            }
            UrlCheck.Allowed -> Unit
        }
    }

    companion object {
        private const val DEFAULT_INVALID_MESSAGE = "아웃바운드 webhook 구독 입력이 유효하지 않습니다"
        private const val SSRF_REJECT_MESSAGE = "webhook URL 이 허용되지 않습니다 (형식 오류 또는 접근 불가 대상)"
    }
}
