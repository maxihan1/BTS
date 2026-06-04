// DbRelyingPartyRegistrationRepository 단위 테스트 — SamlIdpConfig → RelyingPartyRegistration 변환

package com.atlas.bts.identity.provider.saml

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * DbRelyingPartyRegistrationRepository 단위 테스트 (FR-AU-03).
 *
 * SamlIdpConfigRepository 를 MockK 로 대체해 config → RelyingPartyRegistration 변환 로직만 검증한다.
 * - enabled config → assertingParty(IdP) 메타가 올바르게 매핑된다.
 * - 비활성/없는 registrationId → null (RelyingPartyRegistrationRepository 계약).
 * - PEM x509 인증서가 verification credential 로 파싱된다.
 */
class DbRelyingPartyRegistrationRepositoryTest {
    private val configRepo = mockk<SamlIdpConfigRepository>()
    private val sut = DbRelyingPartyRegistrationRepository(configRepo)

    private val testCertPem =
        """
        -----BEGIN CERTIFICATE-----
        MIIDBzCCAe+gAwIBAgIUI4yv17NnJ0u2j4PKWMm2cvVjLCcwDQYJKoZIhvcNAQEL
        BQAwEzERMA8GA1UEAwwIdGVzdC1pZHAwHhcNMjYwNjA0MDA0ODM5WhcNMzYwNjAx
        MDA0ODM5WjATMREwDwYDVQQDDAh0ZXN0LWlkcDCCASIwDQYJKoZIhvcNAQEBBQAD
        ggEPADCCAQoCggEBALSprleUH2lVcARLIBrsj5hpcHtKB1dtq7b/aAyCHuRyMGgE
        ntbfQWsV9vnY7eho8Ca0b3LALKnv/ThiJg7R0WD9jeevW0IRkHAGO7sLlO0a4er
        M3ngLZIKPJGq0dw3I29QwOeLnTukyDx7C+BXNFeBGiloALuMTXXeUnjtPN2xgg9h
        fjFXVqj9OFHMiSyKvJ4jowbmnhL78vmzetmKZEDbo4nBUmS/XEtmLLpbvHXPo5qN
        1c6dYg3kXN8A8/gQzUCpcO0SkasfXwBuMJs4UavjCDkG07rFwN4V3l8GSJNBHXcx
        A1kuruYsbu5dkO+lGSnhNkXBi+PRjbOMfP1xJQ8CAwEAAaNTMFEwHQYDVR0OBBYE
        FFAws2b5nhMkC83ZBl8g5e7J8mc6MB8GA1UdIwQYMBaAFFAws2b5nhMkC83ZBl8g
        5e7J8mc6MA8GA1UdEwEB/wQFMAMBAf8wDQYJKoZIhvcNAQELBQADggEBAJcjAA2t
        8ZCAKaJ36Lw12VnOvKp9XVbuB2GJTAe8o/yjMv8LWMfRSNFpVzAnwmrf62koxiUy
        scGihyaIo1SPjaP9LulfQCbTbAIS0ukwp/8EM6iUq4/eqbimJ1Pwk/36KMVHmonC
        yWkUL0Zn9X3an/IoP4JmW0A+CCOJeqCTUO9S4Pcku43yOgCGlvtKwTqMzR4av2nJ
        yUkoCIJIpntFyeKR53wcKXvSoOrRbQv4KsBkJRiUgDHsaNEuKc7I2sXyzIN6VRgJ
        CyBTeGRKICMQqNaXWgJSzbpYm2DWsd1qe9ldRmAADeZ6WZhA1pOPR4IsUB7ASnkE
        B4GPDShOZ2wzEe0=
        -----END CERTIFICATE-----
        """.trimIndent()

    private fun config(registrationId: String) =
        SamlIdpConfig(
            id = UUID.randomUUID(),
            registrationId = registrationId,
            displayName = "Okta",
            idpEntityId = "https://idp.example.com/entity",
            idpSsoUrl = "https://idp.example.com/sso",
            idpX509Cert = testCertPem,
            authnProviderId = UUID.fromString("00000000-0000-4a03-8000-000000000003"),
            enabled = true,
        )

    @Test
    fun `findByRegistrationId — enabled config 를 RelyingPartyRegistration 으로 변환`() {
        every { configRepo.findEnabledByRegistrationId("okta") } returns config("okta")

        val reg = sut.findByRegistrationId("okta")

        assertThat(reg).isNotNull()
        assertThat(reg!!.registrationId).isEqualTo("okta")
        val idp = reg.assertingPartyDetails
        assertThat(idp.entityId).isEqualTo("https://idp.example.com/entity")
        assertThat(idp.singleSignOnServiceLocation).isEqualTo("https://idp.example.com/sso")
        assertThat(idp.verificationX509Credentials).hasSize(1)
        assertThat(idp.verificationX509Credentials.first().isVerificationCredential).isTrue()
    }

    @Test
    fun `findByRegistrationId — config 없으면 null (EC5)`() {
        every { configRepo.findEnabledByRegistrationId("missing") } returns null

        assertThat(sut.findByRegistrationId("missing")).isNull()
    }
}
