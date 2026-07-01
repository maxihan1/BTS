// 아웃바운드 webhook 구독 REST 요청/응답 DTO — 응답에 secret 원문 필드 없음(hasSecret 만) (FR-API-03 PR2)

package com.bts.search.webhook.web.dto

import com.bts.search.webhook.domain.OutboundWebhook
import java.time.Instant
import java.util.UUID

/**
 * 아웃바운드 webhook 구독 생성 요청 바디.
 *
 * 필수 필드([name]/[url])는 nullable 로 받아 컨트롤러 `required` 헬퍼가 null/blank 를 400 으로 거부한다.
 * [eventFilter] 의 빈/미지 이벤트 검증은 도메인 팩토리([OutboundWebhook.create])가 수행하고
 * [com.bts.search.webhook.web.OutboundWebhookExceptionHandler] 가 400 으로 매핑한다.
 * [secret] 평문은 서비스가 암호화 후에만 영속하며 응답에는 절대 포함하지 않는다(DEVELOPMENT §1.1.2).
 *
 * @param name 구독 이름(필수).
 * @param url 통지 대상 URL(필수, SSRF 검증 대상).
 * @param eventFilter 관심 이벤트 wireValue 목록(비어있지 않고 발행가능 allowlist 소속).
 * @param secret 서명용 평문 secret. null/blank 이면 미설정.
 * @param projectKey 필터링할 프로젝트 키. null 이면 전체 프로젝트 대상.
 * @param enabled 활성화 여부. null 이면 기본 `true`.
 */
data class CreateWebhookRequest(
    val name: String?,
    val url: String?,
    val eventFilter: List<String>?,
    val secret: String? = null,
    val projectKey: String? = null,
    val enabled: Boolean? = null,
)

/**
 * 아웃바운드 webhook 구독 수정 요청 바디(PUT — 전체 교체).
 *
 * name/url/eventFilter/projectKey/enabled 는 전체 교체 대상이며, [secret] 만 3-state(생략/null/blank=기존
 * 암호문 유지, 비어있지 않은 값=재암호화 교체)로 처리된다. [version] 은 OCC(낙관적 동시성 제어) 키로 필수다.
 *
 * @param name 새 구독 이름(필수).
 * @param url 새 통지 대상 URL(필수, SSRF 검증 대상).
 * @param eventFilter 새 관심 이벤트 wireValue 목록.
 * @param version 클라이언트가 보유한 현재 version(OCC 키, 필수).
 * @param secret 새 평문 secret. null/blank 이면 기존 암호문 유지.
 * @param projectKey 새 프로젝트 키. null 이면 전체 프로젝트 대상.
 * @param enabled 새 활성화 여부. null 이면 기본 `true`.
 */
data class UpdateWebhookRequest(
    val name: String?,
    val url: String?,
    val eventFilter: List<String>?,
    val version: Long?,
    val secret: String? = null,
    val projectKey: String? = null,
    val enabled: Boolean? = null,
)

/**
 * 아웃바운드 webhook 구독 응답 바디.
 *
 * **secret 원문 필드는 존재하지 않는다.** 서명 secret 설정 여부는 [hasSecret] boolean 으로만 노출하며,
 * 암호문([OutboundWebhook.secretEncrypted])도 응답에 포함하지 않는다(secret 위생, DEVELOPMENT §1.1.2).
 *
 * @param id 구독 식별자.
 * @param name 구독 이름.
 * @param url 통지 대상 URL.
 * @param eventFilter 관심 이벤트 wireValue 목록.
 * @param projectKey 필터링할 프로젝트 키. null 이면 전체 프로젝트 대상.
 * @param enabled 활성화 여부.
 * @param hasSecret 서명용 secret 설정 여부(암호문 존재 여부). 원문/암호문 자체는 노출하지 않는다.
 * @param createdAt 최초 생성 시각.
 * @param updatedAt 마지막 변경 시각.
 * @param version 낙관적 동시성 제어(OCC) 버전.
 */
data class WebhookResponse(
    val id: UUID,
    val name: String,
    val url: String,
    val eventFilter: List<String>,
    val projectKey: String?,
    val enabled: Boolean,
    val hasSecret: Boolean,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val version: Long,
) {
    companion object {
        /**
         * 영속된 도메인 [OutboundWebhook] 을 응답 DTO 로 변환한다.
         *
         * secret 은 [hasSecret](= [OutboundWebhook.secretEncrypted] != null)으로만 표현하고
         * 암호문/평문 어느 것도 응답에 담지 않는다.
         *
         * @param webhook 변환할 도메인 객체([OutboundWebhook.id] 가 채워진 영속 결과).
         * @return secret 원문/암호문이 제거된 [WebhookResponse].
         */
        fun from(webhook: OutboundWebhook): WebhookResponse =
            WebhookResponse(
                id = requireNotNull(webhook.id) { "영속된 webhook 구독은 id 가 있어야 한다." },
                name = webhook.name,
                url = webhook.url,
                eventFilter = webhook.eventFilter,
                projectKey = webhook.projectKey,
                enabled = webhook.enabled,
                hasSecret = webhook.secretEncrypted != null,
                createdAt = webhook.createdAt,
                updatedAt = webhook.updatedAt,
                version = webhook.version,
            )
    }
}
