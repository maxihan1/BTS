// 아웃바운드 webhook 구독 Aggregate Root — name/url/eventFilter 불변식을 보장하는 순수 도메인 모델
package com.bts.search.webhook.domain

import java.time.Instant
import java.util.UUID

/**
 * 아웃바운드 webhook 구독 Aggregate Root.
 *
 * 외부 시스템이 등록해 두면 매칭 이벤트 발생 시 BTS가 HTTP로 통지하는 구독 단위를 나타낸다.
 * 실제 발송(서명·재시도·이력 기록)은 이 도메인 범위 밖(PR3)이며, 이 클래스는 구독 자체의
 * 불변식만 책임진다. 직접 생성자 대신 [OutboundWebhook.create] 팩토리 또는 [applyUpdate]를
 * 경유해 불변식을 검증하고 인스턴스를 얻는다.
 *
 * ## 불변식
 * - [name]은 trim 후 빈 문자열 불가, 최대 [MAX_NAME_LENGTH]자.
 * - [url]은 trim 후 빈 문자열 불가. (SSRF 검증은 인프라 의존이라 이 도메인 밖 — service 책임.)
 * - [eventFilter]는 비어 있을 수 없고, 각 항목이 [WebhookEventCatalog.PUBLISHABLE]에 속해야 하며
 *   중복은 정규화(제거)된다.
 *
 * ## secret 취급
 * [secretEncrypted]는 이미 암호화된 값(또는 미설정 시 `null`)만 보유한다. 평문 secret은
 * 이 도메인 경계 밖이며, 암호화는 service 계층이 shared-kernel `SecretEncryptor`로 수행한다.
 *
 * @property id 구독 식별자. 신규 생성 시 `null`이며 영속 계층이 채운다.
 * @property name 구독 이름. trim 후 비어 있지 않으며 최대 [MAX_NAME_LENGTH]자.
 * @property url 통지를 받을 대상 URL.
 * @property secretEncrypted 암호화된 서명용 secret. 미설정 시 `null`.
 * @property eventFilter 이 구독이 관심 두는 이벤트 wireValue 목록(중복 제거됨).
 * @property projectKey 필터링할 프로젝트 키. `null`이면 전체 프로젝트 대상.
 * @property enabled 구독 활성화 여부.
 * @property createdBy 구독을 생성한 SYSTEM_ADMIN actor UUID.
 * @property createdAt 최초 생성 시각. 신규 생성 시 `null`이며 영속 계층이 채운다.
 * @property updatedAt 마지막 변경 시각. 신규 생성 시 `null`이며 영속 계층이 채운다.
 * @property version 낙관적 동시성 제어(OCC) 버전. 신규 생성 시 0, 영속 계층이 갱신한다.
 */
data class OutboundWebhook(
    val id: UUID?,
    val name: String,
    val url: String,
    val secretEncrypted: String?,
    val eventFilter: List<String>,
    val projectKey: String?,
    val enabled: Boolean,
    val createdBy: UUID,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val version: Long,
) {
    companion object {
        /** [name]의 최대 허용 글자 수. */
        const val MAX_NAME_LENGTH = 100

        /**
         * 검증을 거쳐 신규 [OutboundWebhook]을 생성한다.
         *
         * @param createdBy 구독을 생성하는 SYSTEM_ADMIN actor UUID.
         * @param name 구독 이름 (정규화 전 원본).
         * @param url 통지를 받을 대상 URL.
         * @param eventFilter 관심 이벤트 wireValue 목록.
         * @param secretEncrypted 이미 암호화된 secret. 기본값 `null`(미설정).
         * @param projectKey 필터링할 프로젝트 키. 기본값 `null`(전체 프로젝트).
         * @param enabled 구독 활성화 여부. 기본값 `true`.
         * @return 불변식을 만족하는 신규 [OutboundWebhook] ([id]/타임스탬프는 `null`, version=0).
         * @throws IllegalArgumentException name·url·eventFilter가 불변식을 위반한 경우.
         */
        fun create(
            createdBy: UUID,
            name: String,
            url: String,
            eventFilter: List<String>,
            secretEncrypted: String? = null,
            projectKey: String? = null,
            enabled: Boolean = true,
        ): OutboundWebhook {
            val normalizedName = validateAndTrimName(name)
            validateUrl(url)
            val normalizedEventFilter = validateAndNormalizeEventFilter(eventFilter)

            return OutboundWebhook(
                id = null,
                name = normalizedName,
                url = url,
                secretEncrypted = secretEncrypted,
                eventFilter = normalizedEventFilter,
                projectKey = projectKey,
                enabled = enabled,
                createdBy = createdBy,
                createdAt = null,
                updatedAt = null,
                version = 0L,
            )
        }
    }

    /**
     * 변경 가능한 필드를 교체하여 새 [OutboundWebhook] 인스턴스를 반환한다.
     *
     * `copy()`를 직접 사용하지 않고 이 메서드를 경유함으로써 [create]와 동일한 불변식 검증이
     * 항상 적용된다(메모리: patch-merge-domain-bypass). [secretEncrypted]를 생략하면 기존 값을
     * 유지한다 — secret 3-state(생략=유지 / 값=교체) 판단은 service 계층 책임이며, 이 메서드는
     * 단순히 "명시적으로 전달된 값으로 교체"만 수행한다.
     *
     * @param name 새 이름.
     * @param url 새 URL.
     * @param eventFilter 새 관심 이벤트 wireValue 목록.
     * @param secretEncrypted 새 암호화된 secret. 생략 시 기존 값 유지.
     * @param projectKey 새 프로젝트 키. 생략 시 기존 값 유지.
     * @param enabled 새 활성화 여부. 생략 시 기존 값 유지.
     * @return 변경된 [OutboundWebhook] 인스턴스.
     * @throws IllegalArgumentException name·url·eventFilter가 불변식을 위반한 경우.
     */
    fun applyUpdate(
        name: String,
        url: String,
        eventFilter: List<String>,
        secretEncrypted: String? = this.secretEncrypted,
        projectKey: String? = this.projectKey,
        enabled: Boolean = this.enabled,
    ): OutboundWebhook {
        val normalizedName = validateAndTrimName(name)
        validateUrl(url)
        val normalizedEventFilter = validateAndNormalizeEventFilter(eventFilter)

        return copy(
            name = normalizedName,
            url = url,
            secretEncrypted = secretEncrypted,
            eventFilter = normalizedEventFilter,
            projectKey = projectKey,
            enabled = enabled,
        )
    }
}

/**
 * [OutboundWebhook.name] 불변식 검증 및 trim 정규화.
 *
 * @param name 정규화 전 이름.
 * @return trim된 이름.
 * @throws IllegalArgumentException trim 후 빈 문자열이거나 [OutboundWebhook.MAX_NAME_LENGTH]자를 초과한 경우.
 */
private fun validateAndTrimName(name: String): String {
    val trimmed = name.trim()
    require(trimmed.isNotEmpty()) { "Webhook name must not be blank" }
    require(trimmed.length <= OutboundWebhook.MAX_NAME_LENGTH) {
        "Webhook name must be ${OutboundWebhook.MAX_NAME_LENGTH} characters or fewer, but was ${trimmed.length}"
    }
    return trimmed
}

/**
 * [OutboundWebhook.url] 불변식 검증.
 *
 * SSRF(내부망 접근 등) 검증은 인프라 의존(shared-kernel `OutboundUrlValidator`)이라
 * service 계층 책임이며, 이 도메인은 비어 있는지만 확인한다.
 *
 * @param url 검증할 URL 문자열.
 * @throws IllegalArgumentException trim 후 빈 문자열인 경우.
 */
private fun validateUrl(url: String) {
    require(url.isNotBlank()) { "Webhook url must not be blank" }
}

/**
 * [OutboundWebhook.eventFilter] 불변식 검증 및 중복 제거 정규화.
 *
 * - 비어 있으면 [IllegalArgumentException]을 던진다.
 * - [WebhookEventCatalog.PUBLISHABLE]에 속하지 않는 항목이 있으면 [IllegalArgumentException]을 던진다.
 * - 순서를 보존하며 중복 항목을 제거한다.
 *
 * @param eventFilter 검증할 이벤트 wireValue 목록.
 * @return 중복이 제거된 이벤트 wireValue 목록.
 * @throws IllegalArgumentException 비어 있거나 미지 이벤트가 포함된 경우.
 */
private fun validateAndNormalizeEventFilter(eventFilter: List<String>): List<String> {
    require(eventFilter.isNotEmpty()) { "Webhook eventFilter must not be empty" }
    val unknown = eventFilter.filterNot { WebhookEventCatalog.isPublishable(it) }
    require(unknown.isEmpty()) {
        "Webhook eventFilter contains unpublishable event(s): ${unknown.distinct().joinToString()}"
    }
    return eventFilter.distinct()
}
