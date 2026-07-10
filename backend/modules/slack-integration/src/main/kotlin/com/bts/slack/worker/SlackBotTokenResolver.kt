// teamId → 복호화된 봇 토큰 해석기 — slack_installs 조회 후 SecretEncryptor 로 복호화 (FR-SL-02 Task 7)

package com.bts.slack.worker

import com.bts.shared.crypto.SecretEncryptor
import com.bts.slack.application.SlackInstallRepository
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

/**
 * 워크스페이스 [teamId] 에 설치된 봇 토큰(평문)을 해석한다 (FR-SL-02 Task 7).
 *
 * [SlackDeliveryWorker] 가 DM 발송 직전 이 해석기로 봇 토큰을 얻는다. 저장된 토큰은 암호문이므로
 * `@Qualifier("slackSecretEncryptor")` [SecretEncryptor] 로 복호화한다(FR-SL-01 [SlackInstallService] 동형 —
 * BC 별 키 격리).
 *
 * ## 봇 토큰 비노출 (§1.1.2)
 * 복호화 결과(평문 `xoxb-…`)는 반환값으로만 넘기고 **로그/예외에 남기지 않는다**. 이 클래스는 로깅을
 * 하지 않으며, 결과를 소비하는 [com.bts.slack.message.SlackMessageClient] 도 요청에만 싣는다.
 *
 * @param installRepository 워크스페이스 설치 조회 포트.
 * @param secretEncryptor 봇 토큰 복호화용(`@Qualifier("slackSecretEncryptor")` by-name — BC 간 키 격리).
 */
@Component
class SlackBotTokenResolver(
    private val installRepository: SlackInstallRepository,
    @param:Qualifier("slackSecretEncryptor") private val secretEncryptor: SecretEncryptor,
) {
    /**
     * [teamId] 설치의 봇 토큰(평문)을 반환한다. 설치가 없으면 null(발송 skip).
     *
     * @param teamId Slack 워크스페이스 id(`T…`).
     * @return 복호화된 봇 토큰, 또는 설치 부재 시 null.
     */
    fun resolve(teamId: String): String? =
        installRepository.findByTeamId(teamId)?.let { secretEncryptor.decrypt(it.botTokenEncrypted) }
}
