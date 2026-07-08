// Slack App 설치 오케스트레이션 — 관리자 가드 + state 서명 + 토큰 교환/암호화/upsert (FR-SL-01 Task 8)

package com.bts.slack.application

import com.bts.shared.crypto.SecretEncryptor
import com.bts.shared.permission.SystemPermissionResolver
import com.bts.slack.domain.SlackInstall
import com.bts.slack.oauth.SlackOAuthClient
import com.bts.slack.oauth.SlackOAuthStateSigner
import com.bts.slack.oauth.SlackOAuthTokenResponse
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Slack App OAuth 설치 흐름의 두 진입점을 오케스트레이션한다 (FR-SL-01 Task 8).
 *
 * - [startInstall] — 관리자 가드 통과 후 개시자를 실은 서명 state 로 Slack authorize URL 을 만든다.
 * - [completeInstall] — 콜백 state 를 검증(개시자 복원)하고 `code↔token` 을 교환해 봇 토큰을 **암호화**한 뒤
 *   `slack_installs` 에 upsert 한다.
 *
 * ## 관리자 가드는 개시 시점에만 (스펙 §관리자 가드)
 * 시스템 전역 관리자 판정은 [startInstall] 에서 [SystemPermissionResolver] 로 수행하고, 그 사실을 서명
 * state 의 `installedBy` 에 박제한다. 콜백([completeInstall])은 state 서명이 "관리자가 개시함"의 증명이므로
 * 재판정하지 않는다(그 사이 권한 회수는 10분 state TTL 밖 — 수용).
 *
 * ## fail-closed 권한 판정 (교훈 crossbc-resolver-nullable-fail-open)
 * [SystemPermissionResolver] 는 **non-null 생성자 주입**이라 미주입 시 부팅이 실패한다(prod 에서 조용히
 * 전부 허용으로 떨어지지 않는다). 판정 결과 `false` 는 명시적 거부([SlackForbiddenException])로 수렴한다.
 * 가드는 리소스/외부 호출 이전에 먼저 수행한다(교훈 auth-extraction-before-lookup).
 *
 * ## 봇 토큰 위생 (DEVELOPMENT.md §1.1.1 / §1.1.2)
 * 평문 봇 토큰(`xoxb-…`)은 교환 응답에서 **잠깐만** 보유하고 [SecretEncryptor] 로 암호화한 결과만
 * [SlackInstall] 에 담아 저장한다(평문 저장 금지). 예외 메시지·반환값([SlackInstallResult])·로그에는
 * 평문 토큰이나 암호화 키를 담지 않는다.
 *
 * ## @Transactional 없음 — 의도적 (DATA.md §6 트랜잭션 경계)
 * 클래스/메서드 어디에도 `@Transactional` 을 두지 않는다 — [completeInstall] 의 외부 Slack HTTP 왕복
 * ([SlackOAuthClient.exchangeCode])을 트랜잭션 밖에 두어 네트워크 지연 동안 DB 커넥션을 점유하지 않게
 * 하기 위함이다. 실제 DB 쓰기는 단건 [SlackInstallRepository.upsert] 뿐이고, 그 구현
 * [com.bts.slack.persistence.JdbcSlackInstallRepository.upsert] 가 자체 `@Transactional` 로 원자성을
 * 보장한다. [startInstall] 은 DB 쓰기가 없고, [completeInstall] 은 다른 tx 메서드를 자기호출하지 않는다
 * (self-invocation 무효화 무관). issue-tracking `IssueAttachmentService`(MinIO I/O tx밖)·notification
 * `WebhookDispatchWorker`(외부 HTTP tx밖) 관례와 정합.
 *
 * @param permissionResolver 전역 관리자 판정 cross-BC 포트(fail-closed, non-null).
 * @param stateSigner OAuth `state` 서명 발급/검증기(개시자 박제).
 * @param oauthClient `oauth.v2.access` 교환 + authorize URL 생성 클라이언트.
 * @param secretEncryptor 봇 토큰 암호화용(`@Qualifier("slackSecretEncryptor")` by-name — BC 간 키 격리).
 * @param installRepository `slack_installs` 영속화 포트(upsert 멱등).
 */
@Service
class SlackInstallService(
    private val permissionResolver: SystemPermissionResolver,
    private val stateSigner: SlackOAuthStateSigner,
    private val oauthClient: SlackOAuthClient,
    @param:Qualifier("slackSecretEncryptor") private val secretEncryptor: SecretEncryptor,
    private val installRepository: SlackInstallRepository,
) {
    /**
     * 설치를 개시한다 — 관리자 가드를 통과하면 개시자([actorId])를 박제한 서명 state 로 Slack authorize URL 을
     * 만들어 돌려준다. 웹 레이어는 이 URL 로 302 리다이렉트한다.
     *
     * 가드는 state 발급·URL 생성 등 어떤 작업보다도 **먼저** 수행한다(auth-extraction-before-lookup).
     *
     * @param actorId 설치를 개시하는 행위자(JWT 에서 추출한 사용자 id).
     * @return Slack authorize URL(`https://slack.com/oauth/v2/authorize?...`).
     * @throws SlackForbiddenException [actorId] 가 시스템 전역 관리자가 아닌 경우(스펙 S5).
     */
    fun startInstall(actorId: UUID): String {
        if (!permissionResolver.isSystemAdmin(actorId)) {
            throw SlackForbiddenException()
        }
        val state = stateSigner.issue(actorId)
        return oauthClient.buildAuthorizeUrl(state)
    }

    /**
     * 콜백을 처리한다 — state 를 검증해 개시자를 복원하고 `code↔token` 교환 → 봇 토큰 암호화 → upsert 한다.
     *
     * 실패 경로에서는 어떤 것도 저장하지 않는다.
     * - state 부재/위조/만료 → [com.bts.slack.oauth.SlackStateInvalidException] 전파(EC1).
     * - 교환 `ok:false` 또는 `access_token` 부재 → [SlackOAuthFailedException](EC3/EC7).
     * - enterprise 등 미지원 설치 유형 → [SlackUnsupportedInstallException](EC5/G1).
     *
     * @param code Slack 콜백의 1회용 인가 코드.
     * @param state [startInstall] 이 발급한 서명 state(개시자·만료 포함).
     * @return 완료 리다이렉트에 필요한 메타([SlackInstallResult]) — 평문 토큰 미포함.
     * @throws com.bts.slack.oauth.SlackStateInvalidException state 검증 실패(EC1).
     * @throws SlackOAuthFailedException 교환 실패 또는 토큰 부재(EC3/EC7).
     * @throws SlackUnsupportedInstallException 미지원 설치 유형(EC5/G1).
     */
    fun completeInstall(
        code: String,
        state: String,
    ): SlackInstallResult {
        // state 서명 검증 → 개시자 복원. 실패 시 SlackStateInvalidException 이 전파되어 교환을 시작하지 않는다.
        val installedBy = stateSigner.verify(state)

        val response = oauthClient.exchangeCode(code)
        // 평문 봇 토큰은 여기서만 잠깐 보유하고 곧바로 암호화한다(§1.1.1 평문 저장 금지).
        val plaintextToken = requireUsableBotToken(response)
        val encryptedToken = secretEncryptor.encrypt(plaintextToken)

        // 워크스페이스 설치 조립. enterprise install/필수 필드 부재는 fromToken 이 IllegalArgumentException 으로
        // 거부하며(EC5/EC7), 이를 미지원 설치 유형으로 변환한다. 원인은 진단용으로만 남기고 HTTP 노출 금지.
        val install =
            try {
                SlackInstall.fromToken(response, installedBy, encryptedToken)
            } catch (e: IllegalArgumentException) {
                throw SlackUnsupportedInstallException(cause = e)
            }

        installRepository.upsert(install)
        return SlackInstallResult(teamId = install.teamId, teamName = install.teamName)
    }

    /**
     * 교환 응답에서 저장에 쓸 **평문 봇 토큰**을 꺼낸다.
     *
     * Slack 이 정상 응답으로 돌려준 실패(`ok:false`, EC3)와 `ok:true` 이지만 `access_token` 이 없는
     * 경우(EC7)를 모두 [SlackOAuthFailedException] 으로 거부한다. `ok:false` 의 error 코드는 리다이렉트용으로만
     * 보존하고 예외 메시지로는 노출하지 않는다(§1.1.2).
     */
    private fun requireUsableBotToken(response: SlackOAuthTokenResponse): String {
        if (!response.ok) {
            throw SlackOAuthFailedException(response.error)
        }
        return response.accessToken ?: throw SlackOAuthFailedException(MISSING_ACCESS_TOKEN)
    }

    private companion object {
        /** `ok:true` 이지만 `access_token` 이 없는 EC7 상황의 리다이렉트용 에러 코드. */
        const val MISSING_ACCESS_TOKEN = "missing_access_token"
    }
}

/**
 * 설치 완료 결과 — 완료 화면 리다이렉트(`/settings/slack?installed=<teamName>`)에 필요한 메타만 담는다.
 *
 * **평문 봇 토큰이나 암호문을 포함하지 않는다**(§1.1.2 — 비밀값 노출 최소화). 웹 레이어는 [teamName] 을
 * 완료 배너에 노출한다.
 *
 * @property teamId 설치된 Slack 워크스페이스 id(`T…`).
 * @property teamName 워크스페이스 이름(완료 배너 표시용).
 */
data class SlackInstallResult(
    val teamId: String,
    val teamName: String,
)
