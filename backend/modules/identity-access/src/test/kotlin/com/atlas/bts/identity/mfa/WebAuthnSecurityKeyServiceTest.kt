// WebAuthnSecurityKeyService 단위 테스트 — 보안키 등록/인증/목록/삭제 오케스트레이션 + 감사 emit (FR-MF-03 Task 6)

package com.atlas.bts.identity.mfa

import com.atlas.bts.identity.audit.AuthAuditLog
import com.atlas.bts.identity.audit.AuthAuditLogService
import com.atlas.bts.identity.audit.AuthEventType
import com.webauthn4j.WebAuthnManager
import com.webauthn4j.converter.util.ObjectConverter
import com.webauthn4j.data.PublicKeyCredentialCreationOptions
import com.webauthn4j.data.PublicKeyCredentialRequestOptions
import com.webauthn4j.data.client.Origin
import com.webauthn4j.test.authenticator.webauthn.NoneAttestationAuthenticator
import com.webauthn4j.test.authenticator.webauthn.WebAuthnAuthenticatorAdaptor
import com.webauthn4j.test.client.ClientPlatform
import com.webauthn4j.util.Base64UrlUtil
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * [WebAuthnSecurityKeyService] 단위 테스트 (FR-MF-03 Task 6).
 *
 * ## 검증 전략 — 가상 ceremony + mock 영속/감사
 * 실제 [WebAuthnService] · [WebAuthnManager] · [ObjectConverter] 를 써서 webauthn4j-test 가상
 * 인증기([ClientPlatform])로 등록/인증 응답을 round-trip 생성한다(서명·challenge·origin 검증이
 * 실제로 동작). 영속([WebAuthnCredentialRepository])·challenge 저장소·감사([AuthAuditLogService])·
 * rate-limit([MfaAttemptLimiter]) 는 mockk 로 대역화해 오케스트레이션 분기를 단언한다.
 *
 * ## 가상 인증기 인스턴스 공유 (T5 인계 4번)
 * 한 보안키로 등록 후 인증을 모사하려면 같은 [ClientPlatform] 인스턴스를 등록·인증에 공유해야
 * 한다(매번 새 인증기면 등록 키를 몰라 NotAllowedException). [platform] 필드 1개를 공유한다.
 *
 * ## 감사 emit
 * [AuthAuditLogService] 는 relaxed mock 으로 두고 [slot] 캡처로 이벤트 유형/주체를 단언한다
 * (verifyLogin 성공=MFA_CHALLENGE_SUCCESS, 실패=MFA_CHALLENGE_FAILURE, 등록=MFA_WEBAUTHN_REGISTERED,
 * 삭제=MFA_WEBAUTHN_REMOVED).
 */
class WebAuthnSecurityKeyServiceTest {
    private val objectConverter = ObjectConverter()
    private val webAuthnManager: WebAuthnManager = WebAuthnManager.createNonStrictWebAuthnManager(objectConverter)
    private val properties =
        WebAuthnProperties(
            rpId = "bts.example.com",
            rpName = "BTS Workspace",
            origin = "https://bts.example.com",
        )
    private val webAuthnService = WebAuthnService(webAuthnManager, properties, objectConverter)

    private val fixedNow: Instant = Instant.parse("2024-06-10T07:33:20Z")
    private val clock: Clock = Clock.fixed(fixedNow, ZoneOffset.UTC)

    private lateinit var repo: WebAuthnCredentialRepository
    private lateinit var challengeStore: WebAuthnChallengeStore
    private lateinit var auditLog: AuthAuditLogService
    private lateinit var limiter: MfaAttemptLimiter
    private lateinit var service: WebAuthnSecurityKeyService

    /** 한 보안키로 등록 후 인증을 모사하기 위한 공유 가상 인증기. */
    private val platform: ClientPlatform = client()

    private val userId: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")

    @BeforeEach
    fun setUp() {
        repo = mockk(relaxed = true)
        challengeStore = mockk(relaxed = true)
        auditLog = mockk(relaxed = true)
        limiter = mockk(relaxed = true)
        every { limiter.isBlocked(any()) } returns false
        every { repo.findByUser(any()) } returns emptyList()
        // relaxed mock 은 nullable 반환에도 더미를 줄 수 있어 "미등록" 기본값을 명시한다(전역 중복 오판 방지).
        every { repo.findByCredentialId(any()) } returns null
        service =
            WebAuthnSecurityKeyService(
                webAuthnService = webAuthnService,
                webAuthnManager = webAuthnManager,
                repo = repo,
                challengeStore = challengeStore,
                auditLog = auditLog,
                limiter = limiter,
                clock = clock,
            )
    }

    /** properties.origin 과 일치하는 가상 클라이언트(정상 경로 재현). */
    private fun client(origin: String = properties.origin): ClientPlatform {
        val authenticator = NoneAttestationAuthenticator()
        val adaptor = WebAuthnAuthenticatorAdaptor(authenticator, objectConverter)
        return ClientPlatform(Origin(origin), adaptor)
    }

    private fun readCreationOptions(json: String): PublicKeyCredentialCreationOptions =
        objectConverter.jsonConverter.readValue(json, PublicKeyCredentialCreationOptions::class.java)!!

    private fun readRequestOptions(json: String): PublicKeyCredentialRequestOptions =
        objectConverter.jsonConverter.readValue(json, PublicKeyCredentialRequestOptions::class.java)!!

    /** 가상 등록 응답 JSON 을 생성하고, 등록 시작 시 발급된 challenge 를 challengeStore.consume 에 stub 한다. */
    private fun registerResponseJson(plat: ClientPlatform = platform): String {
        val startJson = service.registerStart(userId)
        val options = readCreationOptions(startJson)
        // 옵션에 담긴 challenge 로 finish 의 consume stub 을 맞춘다(가상 인증기가 이 challenge 로 서명).
        every { challengeStore.consume(userId) } returns
            com.webauthn4j.data.client.challenge.DefaultChallenge(options.challenge.value)
        val credential = plat.create(options)
        return objectConverter.jsonConverter.writeValueAsString(credential)
    }

    /** 등록까지 완료해 저장된 credential 을 mock repo 에 심고 그 credentialId(base64url)를 돌려준다. */
    private fun registerAndStore(): String {
        val storedSlot = slot<WebAuthnCredential>()
        justRun { repo.insert(capture(storedSlot)) }
        val responseJson = registerResponseJson()
        val result = service.registerFinish(userId, responseJson, "회사 노트북")
        assertThat(result).isInstanceOf(WebAuthnSecurityKeyService.RegisterResult.Success::class.java)
        val stored = storedSlot.captured
        every { repo.findByCredentialId(stored.credentialId) } returns stored
        every { repo.findByUser(userId) } returns listOf(stored)
        return stored.credentialId
    }

    private fun authResponseJson(): String {
        val startJson = service.authenticateStart(userId)
        val options = readRequestOptions(startJson)
        every { challengeStore.consume(userId) } returns
            com.webauthn4j.data.client.challenge.DefaultChallenge(options.challenge.value)
        val credential = platform.get(options)
        return objectConverter.jsonConverter.writeValueAsString(credential)
    }

    // ── registerStart ──────────────────────────────────────────────────────────

    @Test
    fun `registerStart — challenge 발급 후 등록 옵션 JSON 을 반환한다`() {
        every { challengeStore.issue(userId) } returns com.webauthn4j.data.client.challenge.DefaultChallenge()

        val json = service.registerStart(userId)

        verify(exactly = 1) { challengeStore.issue(userId) }
        val options = readCreationOptions(json)
        assertThat(options.rp.id).isEqualTo("bts.example.com")
    }

    // ── registerFinish ─────────────────────────────────────────────────────────

    @Test
    fun `registerFinish — 가상 등록 검증 성공 시 insert + MFA_WEBAUTHN_REGISTERED emit`() {
        val storedSlot = slot<WebAuthnCredential>()
        justRun { repo.insert(capture(storedSlot)) }
        val eventSlot = slot<AuthAuditLog>()
        justRun { auditLog.record(capture(eventSlot)) }

        val responseJson = registerResponseJson()
        val result = service.registerFinish(userId, responseJson, "회사 노트북")

        assertThat(result).isInstanceOf(WebAuthnSecurityKeyService.RegisterResult.Success::class.java)
        verify(exactly = 1) { repo.insert(any()) }
        assertThat(storedSlot.captured.userId).isEqualTo(userId)
        assertThat(storedSlot.captured.name).isEqualTo("회사 노트북")
        assertThat(storedSlot.captured.credentialId).isNotBlank()
        assertThat(eventSlot.captured.eventType).isEqualTo(AuthEventType.MFA_WEBAUTHN_REGISTERED)
        assertThat(eventSlot.captured.userId).isEqualTo(userId)
    }

    @Test
    fun `registerFinish — challenge 만료(consume null)면 Expired 로 거부하고 insert 안 함`() {
        every { challengeStore.consume(userId) } returns null

        val result = service.registerFinish(userId, "{}", "x")

        assertThat(result).isEqualTo(WebAuthnSecurityKeyService.RegisterResult.Expired)
        verify(exactly = 0) { repo.insert(any()) }
    }

    @Test
    fun `registerFinish — 검증 실패(잘못된 응답)면 InvalidRegistration 로 거부`() {
        every { challengeStore.consume(userId) } returns
            com.webauthn4j.data.client.challenge.DefaultChallenge()

        val result = service.registerFinish(userId, "not-a-valid-credential-json", "x")

        assertThat(result).isEqualTo(WebAuthnSecurityKeyService.RegisterResult.InvalidRegistration)
        verify(exactly = 0) { repo.insert(any()) }
    }

    @Test
    fun `registerFinish — credentialId 전역 중복이면 AlreadyRegistered 로 거부(409)`() {
        val existing =
            WebAuthnCredential(
                id = UUID.randomUUID(),
                userId = UUID.randomUUID(),
                credentialId = "DUMMY",
                attestedCredentialData = "DUMMY",
                signCount = 0,
                name = null,
                aaguid = null,
                lastUsedAt = null,
                createdAt = fixedNow,
                updatedAt = fixedNow,
            )
        // 어떤 credentialId 로 조회하든 이미 존재하는 것으로 본다(전역 UNIQUE 충돌).
        every { repo.findByCredentialId(any()) } returns existing

        val responseJson = registerResponseJson()
        val result = service.registerFinish(userId, responseJson, "회사 노트북")

        assertThat(result).isEqualTo(WebAuthnSecurityKeyService.RegisterResult.AlreadyRegistered)
        verify(exactly = 0) { repo.insert(any()) }
    }

    // ── authenticateStart ──────────────────────────────────────────────────────

    @Test
    fun `authenticateStart — 등록 키를 allowCredentials 로 한 인증 옵션 JSON 을 반환한다`() {
        val credentialId = registerAndStore()

        val json = service.authenticateStart(userId)

        val options = readRequestOptions(json)
        assertThat(options.allowCredentials).isNotEmpty
        val allowed = options.allowCredentials!!.map { Base64UrlUtil.encodeToString(it.id) }
        assertThat(allowed).contains(credentialId)
    }

    // ── verifyLogin ────────────────────────────────────────────────────────────

    @Test
    fun `verifyLogin — 가상 인증 검증 성공 시 advanceSignCount + touchLastUsed + MFA_CHALLENGE_SUCCESS emit`() {
        registerAndStore()
        every { repo.advanceSignCount(any(), any()) } returns true
        val eventSlot = slot<AuthAuditLog>()
        justRun { auditLog.record(capture(eventSlot)) }

        val responseJson = authResponseJson()
        val result = service.verifyLogin(userId, responseJson)

        assertThat(result).isEqualTo(WebAuthnSecurityKeyService.VerifyResult.Success)
        verify(exactly = 1) { repo.advanceSignCount(any(), any()) }
        verify(exactly = 1) { repo.touchLastUsed(any(), fixedNow) }
        verify(exactly = 1) { limiter.reset(userId) }
        assertThat(eventSlot.captured.eventType).isEqualTo(AuthEventType.MFA_CHALLENGE_SUCCESS)
        assertThat(eventSlot.captured.userId).isEqualTo(userId)
    }

    @Test
    fun `verifyLogin — signCount 역행(clone 의심)이면 InvalidAssertion + MFA_CHALLENGE_FAILURE emit`() {
        registerAndStore()
        // advanceSignCount 가 false = clone/replay 의심 → 거부.
        every { repo.advanceSignCount(any(), any()) } returns false
        val eventSlot = slot<AuthAuditLog>()
        justRun { auditLog.record(capture(eventSlot)) }

        val responseJson = authResponseJson()
        val result = service.verifyLogin(userId, responseJson)

        assertThat(result).isEqualTo(WebAuthnSecurityKeyService.VerifyResult.InvalidAssertion)
        verify(exactly = 1) { limiter.recordFailure(userId) }
        verify(exactly = 0) { repo.touchLastUsed(any(), any()) }
        assertThat(eventSlot.captured.eventType).isEqualTo(AuthEventType.MFA_CHALLENGE_FAILURE)
    }

    @Test
    fun `verifyLogin — signCount 0 인 정상 기기(advanceSignCount 0to0 true)는 허용한다`() {
        registerAndStore()
        // 항상 0 을 보고하는 정상 인증기: advanceSignCount(0→0)가 true.
        every { repo.advanceSignCount(any(), any()) } returns true

        val responseJson = authResponseJson()
        val result = service.verifyLogin(userId, responseJson)

        assertThat(result).isEqualTo(WebAuthnSecurityKeyService.VerifyResult.Success)
    }

    @Test
    fun `verifyLogin — challenge 만료(consume null)면 InvalidAssertion + MFA_CHALLENGE_FAILURE emit`() {
        registerAndStore()
        every { challengeStore.consume(userId) } returns null
        val eventSlot = slot<AuthAuditLog>()
        justRun { auditLog.record(capture(eventSlot)) }

        val result = service.verifyLogin(userId, "{}")

        assertThat(result).isEqualTo(WebAuthnSecurityKeyService.VerifyResult.InvalidAssertion)
        verify(exactly = 1) { limiter.recordFailure(userId) }
        assertThat(eventSlot.captured.eventType).isEqualTo(AuthEventType.MFA_CHALLENGE_FAILURE)
    }

    @Test
    fun `verifyLogin — 응답 credentialId 가 등록되지 않았으면 InvalidAssertion`() {
        every { challengeStore.consume(userId) } returns
            com.webauthn4j.data.client.challenge.DefaultChallenge()
        every { repo.findByCredentialId(any()) } returns null
        val responseJson = authResponseJsonForUnknown()

        val result = service.verifyLogin(userId, responseJson)

        assertThat(result).isEqualTo(WebAuthnSecurityKeyService.VerifyResult.InvalidAssertion)
        verify(exactly = 1) { limiter.recordFailure(userId) }
    }

    @Test
    fun `verifyLogin — 다른 사용자 소유 credential 이면 InvalidAssertion(소유 검증)`() {
        val credentialId = registerAndStore()
        val stored = repoStoredFor(credentialId).copy(userId = UUID.randomUUID())
        every { repo.findByCredentialId(credentialId) } returns stored

        val responseJson = authResponseJson()
        val result = service.verifyLogin(userId, responseJson)

        assertThat(result).isEqualTo(WebAuthnSecurityKeyService.VerifyResult.InvalidAssertion)
        verify(exactly = 0) { repo.advanceSignCount(any(), any()) }
    }

    @Test
    fun `verifyLogin — rate-limit 차단 시 TooManyAttempts 로 즉시 거부`() {
        every { limiter.isBlocked(userId) } returns true

        val result = service.verifyLogin(userId, "{}")

        assertThat(result).isEqualTo(WebAuthnSecurityKeyService.VerifyResult.TooManyAttempts)
        verify(exactly = 0) { challengeStore.consume(any()) }
    }

    // ── listKeys / deleteKey / hasActiveKey ──────────────────────────────────────

    @Test
    fun `listKeys — 사용자의 등록 키 요약(id·name·createdAt·lastUsedAt)을 반환한다`() {
        registerAndStore()

        val keys = service.listKeys(userId)

        assertThat(keys).hasSize(1)
        assertThat(keys.first().name).isEqualTo("회사 노트북")
    }

    @Test
    fun `deleteKey — 소유 일치 삭제 성공 시 true + MFA_WEBAUTHN_REMOVED emit`() {
        val keyId = UUID.randomUUID()
        every { repo.deleteByIdAndUser(keyId, userId) } returns true
        val eventSlot = slot<AuthAuditLog>()
        justRun { auditLog.record(capture(eventSlot)) }

        val deleted = service.deleteKey(userId, keyId)

        assertThat(deleted).isTrue()
        assertThat(eventSlot.captured.eventType).isEqualTo(AuthEventType.MFA_WEBAUTHN_REMOVED)
        assertThat(eventSlot.captured.userId).isEqualTo(userId)
    }

    @Test
    fun `deleteKey — 타인 소유(삭제 0행)면 false 이고 감사 emit 안 함`() {
        val keyId = UUID.randomUUID()
        every { repo.deleteByIdAndUser(keyId, userId) } returns false

        val deleted = service.deleteKey(userId, keyId)

        assertThat(deleted).isFalse()
        verify(exactly = 0) { auditLog.record(any()) }
    }

    @Test
    fun `hasActiveKey — 등록 키가 있으면 true, 없으면 false`() {
        every { repo.findByUser(userId) } returns emptyList()
        assertThat(service.hasActiveKey(userId)).isFalse()

        registerAndStore()
        assertThat(service.hasActiveKey(userId)).isTrue()
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────────

    /** registerAndStore 로 mock repo 에 심긴 credential 을 credentialId 로 되찾는다(stub 으로 non-null 보장). */
    private fun repoStoredFor(credentialId: String): WebAuthnCredential =
        requireNotNull(repo.findByCredentialId(credentialId)) { "stored credential not stubbed for $credentialId" }

    /** 등록 안 된 별도 인증기로 인증 응답을 만들어 "미등록 credentialId" 케이스를 모사한다. */
    private fun authResponseJsonForUnknown(): String {
        val other = client()
        val regStart = service.registerStart(userId)
        val regOptions = readCreationOptions(regStart)
        val regCredential = other.create(regOptions)
        // 위 등록을 저장하지 않은 채(=미등록) 그 키로 인증 응답만 만든다.
        val authStart = service.authenticateStart(userId)
        val authOptions = readRequestOptions(authStart)
        // allowCredentials 가 비면 인증기가 거부하므로, 해당 키 id 를 허용 목록으로 강제한 옵션을 직접 만든다.
        val forced =
            objectConverter.jsonConverter.writeValueAsString(
                PublicKeyCredentialRequestOptions(
                    authOptions.challenge,
                    null,
                    properties.rpId,
                    listOf(
                        com.webauthn4j.data.PublicKeyCredentialDescriptor(
                            com.webauthn4j.data.PublicKeyCredentialType.PUBLIC_KEY,
                            regCredential.rawId,
                            emptySet(),
                        ),
                    ),
                    com.webauthn4j.data.UserVerificationRequirement.PREFERRED,
                    null,
                ),
            )
        val authCredential = other.get(readRequestOptions(forced))
        return objectConverter.jsonConverter.writeValueAsString(authCredential)
    }
}
