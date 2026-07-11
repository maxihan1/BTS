// BTS 사용자 ↔ Slack 사용자 연결 오케스트레이션 — 이메일 해석 → users.lookupByEmail → link (FR-SL-02 D6 Task 3)

package com.bts.slack.application

import com.bts.shared.user.UserLookupPort
import com.bts.slack.message.SlackUserLookupClient
import com.bts.slack.message.SlackUserLookupResult
import com.bts.slack.worker.SlackBotTokenResolver
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/**
 * Slack 워크스페이스 연결 플로우(옵션 C — 이메일 자동 매칭)를 오케스트레이션한다 (FR-SL-02 D6 Task 3).
 *
 * [connect] 가 BTS 사용자 이메일로 Slack 사용자를 해석해 [SlackUserMappingService] 에 연결하고,
 * [getStatus]/[disconnect] 가 각각 조회·해제를 위임한다. 웹 레이어(후속 태스크)가 이 세 진입점을
 * `/me/slack-connection` 류 API 에 노출할 예정이다.
 *
 * ## @Transactional 없음 — 의도적 (DATA.md §6 트랜잭션 경계)
 * 클래스/메서드 어디에도 `@Transactional` 을 두지 않는다 — [connect] 의 외부 Slack HTTP 왕복
 * ([SlackUserLookupClient.lookupByEmail])을 트랜잭션 밖에 두어 네트워크 지연 동안 DB 커넥션을
 * 점유하지 않기 위함이다([SlackInstallService] CONCERN-1 hot-fix 선례 동형). 실제 DB 쓰기는
 * [SlackUserMappingService.link]/[unlink]/[resolveByUserId] 각각의 `@Transactional` 이 원자성을
 * 보장하며, 이 클래스는 그 메서드들을 자기호출(self-invocation)하지 않는다.
 *
 * ## 호출 순서 — lookup 이 link 보다 먼저
 * [connect] 는 외부 HTTP 조회([SlackUserLookupClient.lookupByEmail]) 결과가 [SlackUserLookupResult.Found]
 * 일 때만 DB 쓰기([SlackUserMappingService.link])로 진행한다 — 실패할 수 있는 외부 호출을 먼저 완료해
 * 불필요한 DB 쓰기를 피한다.
 *
 * ## TOCTOU(검사-사용 사이 변경) 방어
 * [SlackInstallRepository.findCurrentInstallation] 조회와 [SlackBotTokenResolver.resolve] 조회 사이에
 * 설치가 삭제/재설치될 수 있다. [SlackBotTokenResolver.resolve] 가 null 을 돌려주면(그 사이 삭제)
 * 명시적으로 [WorkspaceNotInstalledException] 으로 거부한다 — `!!` 를 쓰지 않고 두 조회 결과 모두
 * null 체크한다(교훈 advisory-lock-bigint-toctou 계열 — 낙관적 거부).
 *
 * @param userLookupPort BTS 사용자 이메일 해석 cross-BC 포트.
 * @param installRepository `slack_installs` 조회 포트(현재 설치 + team_id 별 조회).
 * @param botTokenResolver 워크스페이스 봇 토큰(평문) 해석기.
 * @param userLookupClient Slack `users.lookupByEmail` 클라이언트.
 * @param userMappingService `user_slack_mapping` 오케스트레이션(link/unlink/resolveByUserId).
 */
@Service
class SlackUserConnectionService(
    private val userLookupPort: UserLookupPort,
    private val installRepository: SlackInstallRepository,
    private val botTokenResolver: SlackBotTokenResolver,
    private val userLookupClient: SlackUserLookupClient,
    private val userMappingService: SlackUserMappingService,
) {
    /**
     * [userId] 의 BTS 계정 이메일로 Slack 사용자를 해석해 연결한다.
     *
     * @param userId 연결을 요청하는 BTS 사용자 id.
     * @return 연결 완료 상태(워크스페이스 이름 + 연결 시각).
     * @throws EmailUnavailableException [userId] 계정에 이메일이 없는 경우.
     * @throws WorkspaceNotInstalledException Slack 워크스페이스가 설치되어 있지 않은 경우(TOCTOU 포함).
     * @throws SlackScopeMissingException 봇 토큰에 필요 스코프가 없는 경우.
     * @throws SlackUserNotFoundException 이메일에 매칭되는 Slack 사용자가 없는 경우.
     * @throws SlackTemporarilyUnavailableException Slack 이 일시적으로 응답하지 못한 경우(재시도 가능).
     * @throws SlackAccountAlreadyLinkedException 이 Slack 계정이 이미 다른 사용자에게 연결되어 있는 경우
     * (V702 UNIQUE 위반, 코드리뷰 CONCERN-1 hot-fix).
     */
    fun connect(userId: UUID): ConnectionStatus {
        val email = requireEmail(userId)
        val installation = requireInstallation()
        val botToken = requireBotToken(installation.teamId)
        val found = resolveLookupOutcome(botToken, email)
        return completeLink(userId, found, installation.teamName)
    }

    /**
     * [userId] 의 현재 Slack 연결 상태를 조회한다.
     *
     * 매핑이 있으면 워크스페이스 이름은 [SlackInstallRepository.findByTeamId] 로 조회하되, 그 사이 워크스페이스가
     * 삭제되어 설치를 찾지 못해도(fail-safe) `connected=true` 는 유지하고 [ConnectionStatus.workspaceName] 만
     * null 이 된다.
     *
     * @param userId 조회할 BTS 사용자 id.
     * @return 매핑이 없으면 `ConnectionStatus(connected=false, null, null)`.
     */
    fun getStatus(userId: UUID): ConnectionStatus {
        val mapping = userMappingService.resolveByUserId(userId) ?: return ConnectionStatus(false, null, null)
        val workspaceName = installRepository.findByTeamId(mapping.teamId)?.teamName
        return ConnectionStatus(connected = true, workspaceName = workspaceName, linkedAt = mapping.linkedAt)
    }

    /**
     * [userId] 의 Slack 연결을 해제한다.
     *
     * @param userId 해제할 BTS 사용자 id.
     */
    fun disconnect(userId: UUID) {
        userMappingService.unlink(userId)
    }

    /** [userId] 계정 이메일을 조회한다. 이메일이 없으면 [EmailUnavailableException]. */
    private fun requireEmail(userId: UUID): String {
        return userLookupPort.findEmailById(userId) ?: throw EmailUnavailableException()
    }

    /** 현재 워크스페이스 설치를 조회한다. 설치가 없으면 [WorkspaceNotInstalledException]. */
    private fun requireInstallation(): SlackInstallationView {
        return installRepository.findCurrentInstallation() ?: throw WorkspaceNotInstalledException()
    }

    /**
     * [teamId] 의 봇 토큰을 해석한다. null 이면(설치 조회 이후 삭제된 TOCTOU 레이스)
     * [WorkspaceNotInstalledException].
     */
    private fun requireBotToken(teamId: String): String {
        return botTokenResolver.resolve(teamId) ?: throw WorkspaceNotInstalledException()
    }

    /**
     * Slack 사용자 lookup 을 호출해 [SlackUserLookupResult.Found] 만 통과시킨다.
     *
     * 실패 분류(NotFound/MissingScope/Transient)는 [lookupFailureException] 이 예외 인스턴스로 만들고,
     * 이 함수가 단 한 곳에서만 `throw` 한다(detekt `ThrowsCount` — 분기당 throw 대신 예외를 값으로 반환받아
     * 한 번만 던지는 패턴).
     */
    private fun resolveLookupOutcome(
        botToken: String,
        email: String,
    ): SlackUserLookupResult.Found {
        val result = userLookupClient.lookupByEmail(botToken, email)
        if (result is SlackUserLookupResult.Found) return result
        throw lookupFailureException(result)
    }

    /**
     * lookup 실패 분류를 대응 예외 인스턴스로 매핑한다(호출부가 던진다 — 이 함수는 던지지 않는다).
     *
     * [SlackUserLookupResult.Found] 분기는 [resolveLookupOutcome] 이 먼저 걸러내 도달하지 않는다.
     * `when` 이 sealed 인터페이스를 전수 커버해야 하므로 방어적으로만 남겨둔다.
     */
    private fun lookupFailureException(result: SlackUserLookupResult): RuntimeException =
        when (result) {
            SlackUserLookupResult.NotFound -> SlackUserNotFoundException()
            SlackUserLookupResult.MissingScope -> SlackScopeMissingException()
            is SlackUserLookupResult.Transient -> SlackTemporarilyUnavailableException()
            is SlackUserLookupResult.Found ->
                error("lookupFailureException 은 Found 를 다루지 않는다 — 호출부(resolveLookupOutcome)가 선행 필터링한다")
        }

    /**
     * lookup 이 성공([SlackUserLookupResult.Found])했을 때만 진입 — DB 에 연결을 기록하고, 방금 기록한 값을
     * [SlackUserMappingService.resolveByUserId] 로 다시 읽어 [ConnectionStatus.linkedAt] 을 DB 저장 시각(`now()`)
     * 그대로 돌려준다(애플리케이션 서버 시계가 아닌 DB 를 진실 원천으로 삼는다).
     *
     * ## DuplicateKeyException → SlackAccountAlreadyLinkedException 번역 (코드리뷰 CONCERN-1 hot-fix)
     * [SlackUserMappingService.link] 는 `user_id` 기준 `ON CONFLICT` upsert 라 같은 사용자의 재연결은
     * 통과하지만, 이 Slack 계정(slack_user_id+team_id)이 **다른** 사용자에게 이미 연결돼 있으면 V702
     * `idx_user_slack_mapping_slack_user` UNIQUE 위반으로 [DuplicateKeyException] 이 던져진다. 이는 의도된
     * 거부(fail-closed)이므로 분류되지 않은 예외로 500 취급되지 않도록 여기서 [SlackAccountAlreadyLinkedException]
     * 으로 번역해 다시 던진다. 그 외 [org.springframework.dao.DataIntegrityViolationException] 하위 타입(예:
     * FK 위반)은 이 catch 가 좁게 [DuplicateKeyException] 만 잡으므로 그대로 전파된다.
     */
    private fun completeLink(
        userId: UUID,
        result: SlackUserLookupResult.Found,
        workspaceName: String,
    ): ConnectionStatus {
        try {
            userMappingService.link(userId, result.slackUserId, result.teamId)
        } catch (ex: DuplicateKeyException) {
            throw SlackAccountAlreadyLinkedException().apply { initCause(ex) }
        }
        val mapping = userMappingService.resolveByUserId(userId)
        return ConnectionStatus(connected = true, workspaceName = workspaceName, linkedAt = mapping?.linkedAt)
    }
}

/**
 * Slack 연결 상태 표시용 응답 — 사용자 설정 화면(D6)의 "연결됨/연결 안 됨" 카드에 노출한다.
 *
 * Slack 사용자 id(`U…`)는 담지 않는다(원시 식별자 미노출, DEVELOPMENT.md §1.1.2) — 표시에는
 * 워크스페이스 이름과 연결 시각만 쓰인다.
 *
 * @property connected 연결되어 있으면 true.
 * @property workspaceName 연결된 워크스페이스 표시명 — 미연결 또는 워크스페이스 미해석 시 null.
 * @property linkedAt 최종 연결(upsert) 시각 — 미연결 시 null.
 */
data class ConnectionStatus(
    val connected: Boolean,
    val workspaceName: String?,
    val linkedAt: Instant?,
)
