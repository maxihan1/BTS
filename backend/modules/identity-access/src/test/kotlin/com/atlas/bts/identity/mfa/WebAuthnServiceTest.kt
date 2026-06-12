// WebAuthnService 등록/인증 옵션 생성·검증을 가상 authenticator 로 round-trip 검증 (FR-MF-03 Task 5)

package com.atlas.bts.identity.mfa

import com.webauthn4j.WebAuthnManager
import com.webauthn4j.converter.AttestedCredentialDataConverter
import com.webauthn4j.converter.util.ObjectConverter
import com.webauthn4j.credential.CredentialRecord
import com.webauthn4j.credential.CredentialRecordImpl
import com.webauthn4j.data.PublicKeyCredentialCreationOptions
import com.webauthn4j.data.PublicKeyCredentialRequestOptions
import com.webauthn4j.data.client.Origin
import com.webauthn4j.data.client.challenge.Challenge
import com.webauthn4j.data.client.challenge.DefaultChallenge
import com.webauthn4j.test.authenticator.webauthn.NoneAttestationAuthenticator
import com.webauthn4j.test.authenticator.webauthn.WebAuthnAuthenticatorAdaptor
import com.webauthn4j.test.client.ClientPlatform
import com.webauthn4j.verifier.exception.VerificationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * [WebAuthnService] 의 등록/인증 옵션 생성과 응답 검증을 webauthn4j-test 가상 인증기로 round-trip 검증 (FR-MF-03 Task 5).
 *
 * ## 검증 전략 — 가상 ceremony round-trip
 * webauthn4j-test 의 [ClientPlatform] 은 실제 브라우저/보안키 없이 WebAuthn 등록(attestation)/
 * 인증(assertion) 응답을 가상 생성한다. 서버가 만든 옵션을 가상 인증기에 넘겨 응답을 받고, 그
 * 응답을 서버 검증에 다시 넣어 round-trip 이 성공하면 옵션 구성·검증 파라미터가 표준에 맞는다는
 * 강한 증거가 된다.
 *
 * ## origin 정합
 * 서버 검증은 응답의 origin 이 [WebAuthnProperties.origin] 과 일치하는지 본다. 따라서 가상
 * [ClientPlatform] 도 같은 [Origin] 으로 생성해 정상 경로를 재현하고, (c) 테스트는 일부러 다른
 * origin 으로 만들어 검증 거부를 확인한다.
 */
class WebAuthnServiceTest {
    private val objectConverter = ObjectConverter()
    private val webAuthnManager: WebAuthnManager = WebAuthnManager.createNonStrictWebAuthnManager(objectConverter)
    private val properties =
        WebAuthnProperties(
            rpId = "bts.example.com",
            rpName = "BTS Workspace",
            origin = "https://bts.example.com",
        )
    private val service = WebAuthnService(webAuthnManager, properties, objectConverter)

    /** properties.origin 과 일치하는 가상 클라이언트(정상 경로 재현). */
    private fun client(origin: String = properties.origin): ClientPlatform {
        val authenticator = NoneAttestationAuthenticator()
        val adaptor = WebAuthnAuthenticatorAdaptor(authenticator, objectConverter)
        return ClientPlatform(Origin(origin), adaptor)
    }

    /** 16바이트 user handle(WebAuthn 권장 user.id 길이). */
    private fun userHandle(): ByteArray = ByteArray(USER_HANDLE_BYTES) { it.toByte() }

    /** 가상 등록 → 서버 검증 → CredentialRecord 로 환원하는 공통 헬퍼. */
    private fun registerCredential(challenge: Challenge): CredentialRecord {
        val optionsJson = service.registrationOptionsJson(challenge, userHandle(), "alice", "Alice", emptyList())
        val options = objectConverter.jsonConverter.readValue(optionsJson, PublicKeyCredentialCreationOptions::class.java)!!
        val credential = client().create(options)
        val responseJson = objectConverter.jsonConverter.writeValueAsString(credential)
        val registrationData = service.verifyRegistration(responseJson, challenge)
        return CredentialRecordImpl(
            registrationData.attestationObject!!,
            registrationData.collectedClientData,
            registrationData.clientExtensions,
            registrationData.transports,
        )
    }

    /** (a) 등록 옵션 → 가상 등록 → 서버 검증 round-trip 이 성공한다. */
    @Test
    fun `registrationOptionsJson 으로 가상 등록 후 verifyRegistration 이 성공한다`() {
        val challenge = DefaultChallenge()

        val registrationData = run {
            val optionsJson = service.registrationOptionsJson(challenge, userHandle(), "alice", "Alice", emptyList())
            val options = objectConverter.jsonConverter.readValue(optionsJson, PublicKeyCredentialCreationOptions::class.java)!!
            val credential = client().create(options)
            val responseJson = objectConverter.jsonConverter.writeValueAsString(credential)
            service.verifyRegistration(responseJson, challenge)
        }

        assertThat(registrationData.attestationObject).isNotNull
        assertThat(registrationData.attestationObject!!.authenticatorData.attestedCredentialData).isNotNull
    }

    /** (b) 등록된 credential 로 인증 옵션 → 가상 인증 → 서버 검증 round-trip 이 성공한다. */
    @Test
    fun `authenticationOptionsJson 으로 가상 인증 후 verifyAuthentication 이 성공한다`() {
        val regChallenge = DefaultChallenge()
        val credentialRecord = registerCredential(regChallenge)
        val credentialId = credentialRecord.attestedCredentialData.credentialId

        val authChallenge = DefaultChallenge()
        val optionsJson = service.authenticationOptionsJson(authChallenge, listOf(credentialId))
        val options = objectConverter.jsonConverter.readValue(optionsJson, PublicKeyCredentialRequestOptions::class.java)!!
        val credential = client().get(options)
        val responseJson = objectConverter.jsonConverter.writeValueAsString(credential)

        val authenticationData = service.verifyAuthentication(responseJson, credentialRecord, authChallenge)

        assertThat(authenticationData.credentialId).isEqualTo(credentialId)
    }

    /** (c) 가상 클라이언트가 다른 origin 으로 응답하면 등록 검증이 거부된다(VerificationException). */
    @Test
    fun `다른 origin 응답이면 verifyRegistration 이 거부된다`() {
        val challenge = DefaultChallenge()
        val optionsJson = service.registrationOptionsJson(challenge, userHandle(), "alice", "Alice", emptyList())
        val options = objectConverter.jsonConverter.readValue(optionsJson, PublicKeyCredentialCreationOptions::class.java)!!
        val credential = client(origin = "https://evil.example.com").create(options)
        val responseJson = objectConverter.jsonConverter.writeValueAsString(credential)

        assertThatThrownBy { service.verifyRegistration(responseJson, challenge) }
            .isInstanceOf(VerificationException::class.java)
    }

    /** credential 직렬화 헬퍼 round-trip(AttestedCredentialDataConverter 위임)이 동일 credentialId 를 보존한다. */
    @Test
    fun `serializeCredential 과 deserializeCredential 이 round-trip 된다`() {
        val challenge = DefaultChallenge()
        val credentialRecord = registerCredential(challenge)
        val attestedCredentialData = credentialRecord.attestedCredentialData

        val serialized = service.serializeAttestedCredentialData(attestedCredentialData)
        val deserialized = service.deserializeAttestedCredentialData(serialized)

        val expected = AttestedCredentialDataConverter(objectConverter).convert(attestedCredentialData)
        assertThat(serialized).isEqualTo(expected)
        assertThat(deserialized.credentialId).isEqualTo(attestedCredentialData.credentialId)
    }

    private companion object {
        const val USER_HANDLE_BYTES = 16
    }
}
