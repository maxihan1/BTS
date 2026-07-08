// SlackOAuthTokenResponse 파싱/마스킹 단위 테스트 — oauth.v2.access 응답 매핑 (FR-SL-01 Task 3)

package com.bts.slack.oauth

import com.slack.api.methods.response.oauth.OAuthV2AccessResponse
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * [SlackOAuthTokenResponse] 단위 테스트.
 *
 * 검증 범위.
 * - `oauth.v2.access` 성공 응답의 필드가 우리 도메인 타입으로 매핑된다.
 * - `ok:false` 실패 응답의 error 코드가 보존되고 성공 필드는 null 로 남는다.
 * - 필수 필드(access_token)가 없어도 예외 없이 null 로 매핑한다(판정은 상위 책임).
 * - enterprise install(`team` null) 을 감지한다.
 * - toString 이 평문 access_token 을 노출하지 않는다(DEVELOPMENT.md §1.1.2).
 */
class SlackOAuthTokenResponseTest : DescribeSpec({

    fun sdkSuccess() =
        OAuthV2AccessResponse().apply {
            isOk = true
            accessToken = "xoxb-1111-2222-secret"
            tokenType = "bot"
            scope = "chat:write,commands"
            botUserId = "U0BOT"
            appId = "A0APP"
            team =
                OAuthV2AccessResponse.Team().apply {
                    id = "T0TEAM"
                    name = "Acme Workspace"
                }
            isEnterpriseInstall = false
        }

    describe("SlackOAuthTokenResponse.from — 성공 응답") {
        it("SDK 성공 응답의 모든 필드를 우리 도메인 타입으로 매핑한다") {
            val r = SlackOAuthTokenResponse.from(sdkSuccess())
            r.ok shouldBe true
            r.error.shouldBeNull()
            r.accessToken shouldBe "xoxb-1111-2222-secret"
            r.tokenType shouldBe "bot"
            r.scope shouldBe "chat:write,commands"
            r.botUserId shouldBe "U0BOT"
            r.appId shouldBe "A0APP"
            r.teamId shouldBe "T0TEAM"
            r.teamName shouldBe "Acme Workspace"
            r.isEnterpriseInstall shouldBe false
        }
    }

    describe("SlackOAuthTokenResponse.from — ok:false 실패 응답") {
        it("error 코드를 보존하고 성공 필드는 null 로 남긴다") {
            val sdk =
                OAuthV2AccessResponse().apply {
                    isOk = false
                    error = "invalid_code"
                }
            val r = SlackOAuthTokenResponse.from(sdk)
            r.ok shouldBe false
            r.error shouldBe "invalid_code"
            r.accessToken.shouldBeNull()
            r.teamId.shouldBeNull()
        }
    }

    describe("SlackOAuthTokenResponse.from — 필수 필드 누락") {
        it("ok:true 이지만 access_token 이 없어도 예외 없이 null 로 매핑한다") {
            val sdk = sdkSuccess().apply { accessToken = null }
            val r = SlackOAuthTokenResponse.from(sdk)
            r.ok shouldBe true
            r.accessToken.shouldBeNull()
        }
    }

    describe("SlackOAuthTokenResponse.from — enterprise install") {
        it("team 이 null 이면 teamId 를 null 로 두고 isEnterpriseInstall 을 감지한다") {
            val sdk =
                sdkSuccess().apply {
                    team = null
                    isEnterpriseInstall = true
                    enterprise =
                        OAuthV2AccessResponse.Enterprise().apply {
                            id = "E0ENT"
                            name = "Acme Enterprise"
                        }
                }
            val r = SlackOAuthTokenResponse.from(sdk)
            r.teamId.shouldBeNull()
            r.teamName.shouldBeNull()
            r.isEnterpriseInstall shouldBe true
        }
    }

    describe("SlackOAuthTokenResponse.toString — 비밀값 마스킹 (§1.1.2)") {
        it("평문 access_token 을 toString 에 노출하지 않는다") {
            val s = SlackOAuthTokenResponse.from(sdkSuccess()).toString()
            s shouldNotContain "xoxb-1111-2222-secret"
            s shouldContain "teamId=T0TEAM"
        }
    }
})
