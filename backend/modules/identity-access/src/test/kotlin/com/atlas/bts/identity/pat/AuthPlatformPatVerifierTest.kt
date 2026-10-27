// AuthPlatformPatVerifier 단위 테스트 — loopback stub Auth whoami 로 연결 · 거절 · 캐시 · 장애 경로 확인 (FR-API-04)

package com.atlas.bts.identity.pat

import com.atlas.bts.identity.provider.ldap.ExternalAccount
import com.atlas.bts.identity.provider.ldap.ExternalAccountRepository
import com.atlas.bts.identity.provider.oidc.AuthPlatformRegistrationRunner.Companion.OIDC_PROVIDER_ID
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * JDK [HttpServer] 를 Auth_platform whoami stub 으로 띄워 실제 RestClient 로 확인한다 — 신규 의존성 0.
 * 테스트마다 확인기를 새로 만들어 캐시가 섞이지 않는다.
 */
class AuthPlatformPatVerifierTest {
    private val hits = AtomicInteger()
    private val lastAuthorization = AtomicReference<String?>()
    private var stubStatus = 200
    private var stubBody = """{"userId":"$AUTH_SUB","username":"alice","authMethod":"pat"}"""

    private lateinit var server: HttpServer
    private val externalAccounts: ExternalAccountRepository = mockk()

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/users/me/whoami") { exchange ->
            hits.incrementAndGet()
            lastAuthorization.set(exchange.requestHeaders.getFirst("Authorization"))
            val bytes = stubBody.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(stubStatus, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        every { externalAccounts.findByProviderIdAndExternalSubject(any(), any()) } returns null
        every { externalAccounts.findByProviderIdAndExternalSubject(OIDC_PROVIDER_ID, AUTH_SUB) } returns linked()
    }

    @AfterEach
    fun stopServer() {
        server.stop(0)
    }

    private fun verifier(issuer: String = "http://127.0.0.1:${server.address.port}/") =
        AuthPlatformPatVerifier(externalAccounts, issuer)

    @Test
    fun `Auth 가 pat 로 확인하면 SSO 로 연결된 BTS 사용자 id 를 돌려준다`() {
        assertThat(verifier().resolveUserId(RAW)).isEqualTo(BTS_USER_ID)
        assertThat(lastAuthorization.get()).isEqualTo("Bearer $RAW")
    }

    @Test
    fun `SSO 연결 행이 없으면 null`() {
        stubBody = """{"userId":"${UUID.randomUUID()}","authMethod":"pat"}"""

        assertThat(verifier().resolveUserId(RAW)).isNull()
    }

    @Test
    fun `Auth 가 401 이면 null`() {
        stubStatus = 401
        stubBody = """{"error":"invalid_token"}"""

        assertThat(verifier().resolveUserId(RAW)).isNull()
    }

    @Test
    fun `authMethod 가 pat 이 아니면 null`() {
        stubBody = """{"userId":"$AUTH_SUB","authMethod":"session"}"""

        assertThat(verifier().resolveUserId(RAW)).isNull()
    }

    @Test
    fun `확인 성공은 캐시한다 — 두 번 불러도 HTTP 는 1번`() {
        val verifier = verifier()

        verifier.resolveUserId(RAW)
        assertThat(verifier.resolveUserId(RAW)).isEqualTo(BTS_USER_ID)
        assertThat(hits.get()).isEqualTo(1)
    }

    @Test
    fun `거절도 캐시한다 — 두 번 불러도 HTTP 는 1번`() {
        stubStatus = 401
        val verifier = verifier()

        verifier.resolveUserId(RAW)
        assertThat(verifier.resolveUserId(RAW)).isNull()
        assertThat(hits.get()).isEqualTo(1)
    }

    @Test
    fun `Auth 장애(5xx)는 기억하지 않는다 — 두 번 부르면 HTTP 도 2번`() {
        stubStatus = 503
        val verifier = verifier()

        verifier.resolveUserId(RAW)
        assertThat(verifier.resolveUserId(RAW)).isNull()
        assertThat(hits.get()).isEqualTo(2)
    }

    @Test
    fun `issuer 설정이 없으면 HTTP 없이 null`() {
        assertThat(verifier(issuer = "").resolveUserId(RAW)).isNull()
        assertThat(hits.get()).isZero()
    }

    private fun linked() =
        ExternalAccount(
            id = UUID.randomUUID(),
            providerId = OIDC_PROVIDER_ID,
            externalSubject = AUTH_SUB,
            userId = BTS_USER_ID,
            groups = emptyList(),
            failedAttempts = 0,
            lockedUntil = null,
            lastLoginAt = null,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )

    private companion object {
        const val RAW = "pat_authPlatformToken000000000000000000000000000"
        const val AUTH_SUB = "11111111-1111-4111-8111-111111111111"
        val BTS_USER_ID: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
    }
}
