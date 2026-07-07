// SlackInstall 도메인 VO 팩토리/마스킹 단위 테스트 — fromToken 매핑, enterprise 거부 (FR-SL-01 Task 3)

package com.bts.slack.domain

import com.bts.slack.oauth.SlackOAuthTokenResponse
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.util.UUID

/**
 * [SlackInstall] 도메인 VO 단위 테스트.
 *
 * 검증 범위.
 * - [SlackInstall.fromToken] 이 응답 메타 + installedBy + 암호화 토큰을 VO 로 조립한다.
 * - enterprise install(team 부재) 응답은 거부한다(`IllegalArgumentException`).
 * - `ok:false`/필수 필드 누락/빈 암호화 토큰을 거부한다.
 * - toString 이 봇 토큰(암호문 포함)을 노출하지 않는다(DEVELOPMENT.md §1.1).
 */
class SlackInstallTest : DescribeSpec({

    fun successResponse(
        teamId: String? = "T0TEAM",
        teamName: String? = "Acme Workspace",
        isEnterpriseInstall: Boolean = false,
    ) = SlackOAuthTokenResponse(
        ok = true,
        error = null,
        accessToken = "xoxb-plaintext-secret",
        tokenType = "bot",
        scope = "chat:write,commands",
        botUserId = "U0BOT",
        appId = "A0APP",
        teamId = teamId,
        teamName = teamName,
        isEnterpriseInstall = isEnterpriseInstall,
    )

    val installedBy = UUID.randomUUID()
    // 서비스(Task 8)가 SecretEncryptor 로 암호화한 결과 대역 — 평문 토큰은 VO 에 들어가지 않는다.
    val encryptedToken = "enc:deadbeefcafe"

    describe("SlackInstall.fromToken — 성공 매핑") {
        it("응답 메타 + installedBy + 암호화 토큰을 VO 로 조립한다") {
            val install = SlackInstall.fromToken(successResponse(), installedBy, encryptedToken)
            install.teamId shouldBe "T0TEAM"
            install.teamName shouldBe "Acme Workspace"
            install.botUserId shouldBe "U0BOT"
            install.appId shouldBe "A0APP"
            install.scopes shouldBe "chat:write,commands"
            install.isEnterpriseInstall shouldBe false
            install.installedBy shouldBe installedBy
            install.botTokenEncrypted shouldBe encryptedToken
        }
    }

    describe("SlackInstall.fromToken — enterprise install 거부") {
        it("team 이 없는(enterprise) 응답이면 IllegalArgumentException") {
            shouldThrow<IllegalArgumentException> {
                SlackInstall.fromToken(
                    successResponse(teamId = null, teamName = null, isEnterpriseInstall = true),
                    installedBy,
                    encryptedToken,
                )
            }
        }
    }

    describe("SlackInstall.fromToken — ok:false 응답 거부") {
        it("실패 응답으로는 설치 VO 를 만들 수 없다") {
            val failed = successResponse().copy(ok = false, error = "invalid_code", accessToken = null)
            shouldThrow<IllegalArgumentException> {
                SlackInstall.fromToken(failed, installedBy, encryptedToken)
            }
        }
    }

    describe("SlackInstall.fromToken — 필수 필드/토큰 누락 거부") {
        it("team_name 이 없으면 IllegalArgumentException") {
            shouldThrow<IllegalArgumentException> {
                SlackInstall.fromToken(successResponse(teamName = null), installedBy, encryptedToken)
            }
        }

        it("암호화 토큰이 비어 있으면 IllegalArgumentException") {
            shouldThrow<IllegalArgumentException> {
                SlackInstall.fromToken(successResponse(), installedBy, "  ")
            }
        }
    }

    describe("SlackInstall.toString — 봇 토큰 마스킹 (§1.1)") {
        it("암호화된 봇 토큰조차 toString 에 노출하지 않는다(방어적)") {
            val s = SlackInstall.fromToken(successResponse(), installedBy, encryptedToken).toString()
            s shouldNotContain encryptedToken
            s shouldNotContain "xoxb-plaintext-secret"
        }
    }
})
