// JwtIssuer.issue 시그니처 회귀 가드 — mfaVerified 미전달 호출처(SSO 핸들러 등)의 mfa_verified=false 유지 단언 (FR-MF-01 C6)

package com.atlas.bts.identity.jwt

import com.nimbusds.jwt.SignedJWT
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * `JwtIssuer.issue` 시그니처 변경(FR-MF-01 — `mfaVerified` 파라미터 추가)에 대한 회귀 가드.
 *
 * ## 배경 (C6)
 * `mfaVerified` 파라미터는 기본값 `false` 로 추가되어 기존 호출처 4곳
 * (`AuthController`, `RefreshTokenService`, `OidcAuthenticationSuccessHandler`,
 * `Saml2AuthenticationSuccessHandler`)은 컴파일이 깨지지 않는다.
 *
 * 본 PR 범위는 로컬 로그인 2단계 MFA 이며, SSO(OIDC/SAML) 로그인의 `mfa_verified` 실체화는
 * **범위 밖**이다. 따라서 SSO 성공 핸들러는 `mfaVerified` 를 전달하지 않아 `mfa_verified=false`
 * 가 **유지되는 것이 의도**다. 이 테스트는 SSO 핸들러 파일을 직접 건드리지 않고(수정 금지)
 * `JwtIssuer` 를 SSO 핸들러와 **동일한 방식**(`mfaVerified` 인자 생략)으로 직접 호출하여
 * 발급 토큰의 `mfa_verified` 가 `false` 임을 고정한다.
 *
 * 누군가 `issue` 의 `mfaVerified` 기본값을 `true` 로 바꾸거나 하드코딩을 되돌리면 이 테스트가
 * 깨져 SSO 토큰에 `mfa_verified=true` 가 잘못 새어 나가는 회귀를 차단한다.
 *
 * ## 참조
 * - FR-MF-01 Task 5 (C6 — JwtIssuer 시그니처 파급 / SSO 핸들러 false 유지)
 * - plan-files-constructor-injection 메모리 (시그니처 변경 시 호출처 회귀 테스트 포함)
 */
class JwtIssuerSignatureRegressionTest {
    private val keyProvider = DevMemoryKeyProvider()
    private val issuerUri = "https://bts.example.com"
    private lateinit var jwtIssuer: JwtIssuer

    private val userId = UUID.fromString("33333333-0000-0000-0000-000000000003")
    private val sessionId = UUID.fromString("44444444-0000-0000-0000-000000000004")

    @BeforeEach
    fun setUp() {
        jwtIssuer = JwtIssuer(keyProvider, issuerUri)
    }

    @Test
    fun `mfaVerified 인자를 생략한 발급(SSO 핸들러 호출 방식)은 mfa_verified=false 를 유지한다`() {
        // SSO 성공 핸들러(OIDC/SAML)는 mfaVerified 를 전달하지 않는다 — 동일 호출 방식 재현.
        val token = jwtIssuer.issue(userId, sessionId, "oidc", listOf("issues:read"))
        val claims = SignedJWT.parse(token).jwtClaimsSet

        assertThat(claims.getBooleanClaim("mfa_verified")).isFalse()
    }

    @Test
    fun `mfaVerified 인자를 생략한 발급은 roles 포함 호출에서도 mfa_verified=false 를 유지한다`() {
        // SAML 핸들러처럼 roles 를 함께 전달하는 경로도 mfaVerified 생략 시 false 유지.
        val token = jwtIssuer.issue(userId, sessionId, "saml", listOf("issues:read"), roles = listOf("SYSTEM_ADMIN"))
        val claims = SignedJWT.parse(token).jwtClaimsSet

        assertThat(claims.getBooleanClaim("mfa_verified")).isFalse()
    }
}
