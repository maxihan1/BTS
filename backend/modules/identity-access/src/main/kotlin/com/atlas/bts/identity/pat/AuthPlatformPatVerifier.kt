// BTS 가 모르는 pat_ 를 Auth_platform whoami 로 확인해 SSO 로 연결된 BTS 사용자 id 로 바꾸는 확인기 (FR-API-04)

package com.atlas.bts.identity.pat

import com.atlas.bts.identity.provider.ldap.ExternalAccountRepository
import com.atlas.bts.identity.provider.oidc.AuthPlatformRegistrationRunner.Companion.OIDC_PROVIDER_ID
import com.atlas.bts.identity.provider.oidc.AuthPlatformRegistrationRunner.Companion.REGISTRATION_ID
import com.atlas.bts.identity.provider.oidc.OidcProviderConfigReader
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
import java.util.concurrent.Semaphore

/**
 * PAT 는 Auth_platform 하나가 발급한다 (S20). BTS 는 Auth 의 `GET /api/v1/users/me/whoami` 에 raw token 을 그대로
 * 보내 확인하고, 200 · `authMethod=pat` 이면 `userId`(= Auth OIDC `sub`)로 SSO 연결 행을 찾는다 — SSO 로그인이
 * 사용자를 찾는 것과 같은 행이라 새 신원 연결은 생기지 않는다. 연결 행이 없으면(SSO 로 한 번도 안 들어옴) null.
 *
 * - 캐시 key 는 `sha256Hex(raw)`. 확인 성공은 5분, 거절(Auth 401·403 · 연결 없음)은 1분 기억한다 — Auth 가 whoami 마다
 *   감사 1줄을 남기므로 요청마다 묻지 않는다. 팜 identity-bts 와 같은 값. 그 밖의 응답 · 망 오류는 기억하지 않는다.
 * - `bts.auth-platform.issuer-uri` 가 비면 확인하지 않는다(null).
 * - SSO kill switch — 관리자가 `oidc_provider_configs(registration_id=auth).enabled=false` 로 SSO 를 끄면 Auth PAT 도
 *   막힌다. 캐시보다 먼저 봐서 끄는 즉시 반영된다.
 * - 인증 없는 요청도 이 길을 타므로 형식(`pat_` + base62 48자)이 맞을 때만, 동시 [MAX_INFLIGHT] 개까지만 Auth 를 부른다.
 * - raw token 은 로그에 남기지 않는다(DEVELOPMENT.md §1.1).
 *
 * RestClient 는 조립 앱의 `outboundHttpRestClient` 빈을 받는다. identity 단독 컨텍스트는 shared-kernel 을 scan 하지
 * 않아서 같은 설정으로 직접 만든다(slack-integration `SlackResponseUrlClient` 선례).
 */
@Component
class AuthPlatformPatVerifier(
    private val externalAccountRepository: ExternalAccountRepository,
    private val oidcProviderConfigs: OidcProviderConfigReader,
    @Value("\${bts.auth-platform.issuer-uri:}") issuerUri: String,
    private val restClient: RestClient = OutboundHttpClientConfig().outboundHttpRestClient(),
) {
    private val log = LoggerFactory.getLogger(AuthPlatformPatVerifier::class.java)
    private val whoamiUri = issuerUri.trimEnd('/').takeIf { it.isNotBlank() }?.let { "$it$WHOAMI_PATH" }
    private val accepted: Cache<String, UUID> = cache(ACCEPTED_TTL)
    private val rejected: Cache<String, Boolean> = cache(REJECTED_TTL)
    private val inflight = Semaphore(MAX_INFLIGHT)

    /**
     * Auth PAT 의 주인인 BTS 사용자 id 를 돌려준다.
     *
     * @param raw `pat_` 로 시작하는 raw token
     * @return 연결된 BTS 사용자 id. Auth 거절 · 연결 없음 · Auth 장애 · issuer 미설정 · SSO 꺼짐 · 형식 불일치면 null
     */
    fun resolveUserId(raw: String): UUID? {
        val uri = whoamiUri?.takeIf { PAT_FORMAT.matches(raw) && ssoEnabled() } ?: return null
        val key = sha256Hex(raw)
        val known = accepted.getIfPresent(key)
        return when {
            known != null -> known
            rejected.getIfPresent(key) != null -> null
            else -> lookupWithinLimit(uri, raw, key)
        }
    }

    private fun ssoEnabled(): Boolean = oidcProviderConfigs.findEnabledByRegistrationId(REGISTRATION_ID) != null

    // ponytail: 동시 MAX_INFLIGHT 개를 넘으면 기다리지 않고 401(기억하지 않음) — Auth 가 느려도 BTS request thread 를 그 이상
    //   잡지 않는다. 정상 부하에서 401 이 보이면 single-flight · 대기열로 바꾼다
    private fun lookupWithinLimit(
        uri: String,
        raw: String,
        key: String,
    ): UUID? {
        if (!inflight.tryAcquire()) return null
        return try {
            lookup(uri, raw, key)
        } finally {
            inflight.release()
        }
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

    /** Auth 의 답 — 401·403 은 거절(null), 2xx 는 `authMethod=pat` 일 때 `userId`, 그 밖(3xx · 404 · 429 · 5xx)은 장애로 던진다. */
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
                    status.value() in REJECTED_STATUSES -> Optional.empty()
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
        const val MAX_INFLIGHT = 8
        val REJECTED_STATUSES = setOf(401, 403)

        /** Auth · BTS PAT 공통 형식 — `pat_` + base62 48자 */
        val PAT_FORMAT = Regex("pat_[0-9A-Za-z]{48}")

        fun <V : Any> cache(ttl: Duration): Cache<String, V> =
            Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(CACHE_MAX_SIZE).build()
    }
}
