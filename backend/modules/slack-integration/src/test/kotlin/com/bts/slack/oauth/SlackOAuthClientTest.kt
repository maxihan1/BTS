// SlackOAuthClient 단위 테스트 — oauth.v2.access 토큰 교환 + authorize URL 생성 (FR-SL-01 Task 6)

package com.bts.slack.oauth

import com.bts.slack.config.SlackProperties
import com.slack.api.RequestConfigurator
import com.slack.api.methods.MethodsClient
import com.slack.api.methods.request.oauth.OAuthV2AccessRequest
import com.slack.api.methods.response.oauth.OAuthV2AccessResponse
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * [SlackOAuthClient] 단위 테스트.
 *
 * 검증 범위.
 * - `oauth.v2.access` 성공 응답을 [SlackOAuthTokenResponse]로 매핑하고, 교환 요청에
 *   client_id·client_secret·code·redirect_uri가 정확히 실린다.
 * - `ok:false` 실패 응답의 error 코드가 보존된 채 반환된다(예외로 승격하지 않음 — 판정은 Task 8 서비스 책임).
 * - ok:true지만 필수 필드(access_token)가 없어도 예외 없이 null로 매핑한다.
 * - authorize URL이 고정 엔드포인트에 client_id·scope·state·redirect_uri를 URL 인코딩해 붙인다.
 *
 * ## stub 방식
 * MockWebServer가 테스트 클래스패스에 없으므로 slack-api-client의 [MethodsClient]를 mockk로 stub한다.
 * 교환 요청 검증은 [RequestConfigurator]를 캡처해 실제 [OAuthV2AccessRequest] 빌더에 적용하는 방식으로 확인한다.
 */
class SlackOAuthClientTest : DescribeSpec({

    val properties =
        SlackProperties(
            clientId = "111.222",
            clientSecret = "shhh-secret",
            redirectUri = "https://bts.example.com/slack/install/callback",
            scopes = "chat:write,commands,links:read",
        )

    fun sdkSuccess() =
        OAuthV2AccessResponse().apply {
            isOk = true
            accessToken = "xoxb-1-2-secret"
            tokenType = "bot"
            scope = "chat:write,commands,links:read"
            botUserId = "U0BOT"
            appId = "A0APP"
            team =
                OAuthV2AccessResponse.Team().apply {
                    id = "T0TEAM"
                    name = "Acme Workspace"
                }
            isEnterpriseInstall = false
        }

    describe("exchangeCode — 성공 응답") {
        it("code를 oauth.v2.access로 교환해 SlackOAuthTokenResponse로 매핑하고 요청 파라미터를 정확히 싣는다") {
            val methods = mockk<MethodsClient>()
            val configuratorSlot = slot<RequestConfigurator<OAuthV2AccessRequest.OAuthV2AccessRequestBuilder>>()
            every { methods.oauthV2Access(capture(configuratorSlot)) } returns sdkSuccess()

            val client = DefaultSlackOAuthClient(properties, methods)
            val result = client.exchangeCode("auth-code-xyz")

            result.ok shouldBe true
            result.accessToken shouldBe "xoxb-1-2-secret"
            result.tokenType shouldBe "bot"
            result.botUserId shouldBe "U0BOT"
            result.appId shouldBe "A0APP"
            result.teamId shouldBe "T0TEAM"

            // 캡처한 RequestConfigurator를 실제 빌더에 적용해 교환 요청 파라미터를 검증한다.
            val request = configuratorSlot.captured.configure(OAuthV2AccessRequest.builder()).build()
            request.clientId shouldBe "111.222"
            request.clientSecret shouldBe "shhh-secret"
            request.code shouldBe "auth-code-xyz"
            request.redirectUri shouldBe "https://bts.example.com/slack/install/callback"
        }
    }

    describe("exchangeCode — ok:false 실패 응답") {
        it("Slack error 코드를 보존한 응답을 예외 없이 반환한다") {
            val methods = mockk<MethodsClient>()
            every {
                methods.oauthV2Access(any<RequestConfigurator<OAuthV2AccessRequest.OAuthV2AccessRequestBuilder>>())
            } returns
                OAuthV2AccessResponse().apply {
                    isOk = false
                    error = "invalid_code"
                }

            val result = DefaultSlackOAuthClient(properties, methods).exchangeCode("bad-code")

            result.ok shouldBe false
            result.error shouldBe "invalid_code"
            result.accessToken.shouldBeNull()
        }
    }

    describe("exchangeCode — 필수 필드 누락") {
        it("ok:true지만 access_token이 없어도 예외 없이 null로 매핑한다") {
            val methods = mockk<MethodsClient>()
            every {
                methods.oauthV2Access(any<RequestConfigurator<OAuthV2AccessRequest.OAuthV2AccessRequestBuilder>>())
            } returns sdkSuccess().apply { accessToken = null }

            val result = DefaultSlackOAuthClient(properties, methods).exchangeCode("code")

            result.ok shouldBe true
            result.accessToken.shouldBeNull()
        }
    }

    describe("buildAuthorizeUrl") {
        it("authorize 엔드포인트에 client_id·scope·state·redirect_uri를 URL 인코딩해 붙인다") {
            val client = DefaultSlackOAuthClient(properties, mockk())

            val url = client.buildAuthorizeUrl("signed.state.token")

            url shouldContain "https://slack.com/oauth/v2/authorize?"
            url shouldContain "client_id=111.222"
            url shouldContain "state=signed.state.token"
            // scope의 콤마·콜론이 퍼센트 인코딩된다.
            url shouldContain "scope=chat%3Awrite%2Ccommands%2Clinks%3Aread"
            // redirect_uri가 퍼센트 인코딩된다.
            url shouldContain "redirect_uri=https%3A%2F%2Fbts.example.com%2Fslack%2Finstall%2Fcallback"

            // 역인코딩하면 원본 redirect_uri가 복원된다(왕복 검증).
            val encodedRedirect = url.substringAfter("redirect_uri=")
            URLDecoder.decode(encodedRedirect, StandardCharsets.UTF_8) shouldBe
                "https://bts.example.com/slack/install/callback"
        }
    }
})
