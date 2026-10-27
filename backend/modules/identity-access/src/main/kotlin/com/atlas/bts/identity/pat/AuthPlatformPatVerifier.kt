// BTS 가 모르는 pat_ 를 Auth_platform whoami 로 확인해 SSO 로 연결된 BTS 사용자 id 로 바꾸는 확인기 (FR-API-04)

package com.atlas.bts.identity.pat

import com.atlas.bts.identity.provider.ldap.ExternalAccountRepository
import com.atlas.bts.identity.provider.oidc.AuthPlatformRegistrationRunner.Companion.OIDC_PROVIDER_ID
import com.bts.shared.http.OutboundHttpClientConfig
import com.fasterxml.jackson.databind.JsonNode
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.time.Duration
import java.util.Optional
import java.util.UUID

/**
 * PAT 는 Auth_platform 하나가 발급한다 (S20). BTS 는 Auth 의 `GET /api/v1/users/me/whoami` 에 raw token 을 그대로
 * 보내 확인하고, 200 · `authMethod=pat` 이면 `userId`(= Auth OIDC `sub`)로 SSO 연결 행을 찾는다 — SSO 로그인이
 * 사용자를 찾는 것과 같은 행이라 새 신원 연결은 생기지 않는다. 연결 행이 없으면(SSO 로 한 번도 안 들어옴) null.
 *
 * - 캐시 key 는 `sha256Hex(raw)`. 확인 성공은 5분, 거절(Auth 4xx · 연결 없음)은 1분 기억한다 — Auth 가 whoami 마다
 *   감사 1줄을 남기므로 요청마다 묻지 않는다. 팜 identity-bts 와 같은 값.
 * - `bts.auth-platform.issuer-uri` 가 비면 확인하지 않는다(null).
 * - raw token 은 로그에 남기지 않는다(DEVELOPMENT.md §1.1).
 *
 * RestClient 는 조립 앱의 `outboundHttpRestClient` 빈을 받는다. identity 단독 컨텍스트는 shared-kernel 을 scan 하지
 * 않아서 같은 설정으로 직접 만든다(slack-integration `SlackResponseUrlClient` 선례).
 */
@Component
class AuthPlatformPatVerifier(
    private val externalAccountRepository: ExternalAccountRepository,
    @Value("\${bts.auth-platform.issuer-uri:}") issuerUri: String,
    private val restClient: RestClient = OutboundHttpClientConfig().outboundHttpRestClient(),
) {
    private val log = LoggerFactory.getLogger(AuthPlatformPatVerifier::class.java)
    private val whoamiUri = issuerUri.trimEnd('/').takeIf { it.isNotBlank() }?.let { "$it$WHOAMI_PATH" }
    private val accepted: Cache<String, UUID> = cache(ACCEPTED_TTL)
    private val rejected: Cache<String, Boolean> = cache(REJECTED_TTL)

    /**
     * Auth PAT 의 주인인 BTS 사용자 id 를 돌려준다.
     *
     * @param raw `pat_` 로 시작하는 raw token
     * @return 연결된 BTS 사용자 id. Auth 거절 · 연결 없음 · Auth 장애 · issuer 미설정이면 null
     */
    fun resolveUserId(raw: String): UUID? {
        val uri = whoamiUri ?: return null
        val key = sha256Hex(raw)
        return if (rejected.getIfPresent(key) != null) null else accepted.getIfPresent(key) ?: lookup(uri, raw, key)
    }

    private fun lookup(
        uri: String,
        raw: String,
        key: String,
    ): UUID? {
        val subject =
            try {
                fetchSubject(uri, raw)
            } catch (e: RestClientException) {
                // ponytail: Auth 장애는 기억하지 않고 401 — 클라이언트가 재시도와 거절을 구분해야 하면 503 으로 바꾼다
                log.warn("Auth PAT 확인 실패 — {}", e.javaClass.simpleName)
                return null
            }
        val userId =
            subject?.let { externalAccountRepository.findByProviderIdAndExternalSubject(OIDC_PROVIDER_ID, it)?.userId }
        if (userId == null) rejected.put(key, true) else accepted.put(key, userId)
        return userId
    }

    /** Auth 의 답 — 4xx 는 거절(null), 2xx 는 `authMethod=pat` 일 때 `userId`, 그 밖(5xx · 3xx)은 장애로 던진다. */
    private fun fetchSubject(
        uri: String,
        raw: String,
    ): String? =
        restClient.get()
            .uri(uri)
            .header(HttpHeaders.AUTHORIZATION, "Bearer $raw")
            .exchange { _, response ->
                val status = response.statusCode
                when {
                    status.is4xxClientError -> Optional.empty()
                    !status.is2xxSuccessful -> throw RestClientException("Auth whoami ${status.value()}")
                    else ->
                        Optional.ofNullable(
                            response.bodyTo(JsonNode::class.java)
                                ?.takeIf { it.path("authMethod").asText() == "pat" }
                                ?.path("userId")
                                ?.textValue(),
                        )
                }
            }.orElse(null)

    private companion object {
        const val WHOAMI_PATH = "/api/v1/users/me/whoami"
        val ACCEPTED_TTL: Duration = Duration.ofMinutes(5)
        val REJECTED_TTL: Duration = Duration.ofMinutes(1)
        const val CACHE_MAX_SIZE = 10_000L

        fun <V : Any> cache(ttl: Duration): Cache<String, V> =
            Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(CACHE_MAX_SIZE).build()
    }
}
