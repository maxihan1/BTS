// Slack users.lookupByEmail 래퍼 — 이메일→Slack 사용자 id 해석 (FR-SL-02 D6 Task 1)

package com.bts.slack.message

import com.slack.api.Slack
import com.slack.api.methods.MethodsClient
import com.slack.api.methods.SlackApiException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.IOException

/**
 * `users.lookupByEmail` 호출 결과 분류.
 *
 * - [Found]: 이메일에 매칭되는 Slack 사용자를 찾음.
 * - [NotFound]: 워크스페이스에 해당 이메일 사용자 없음(`users_not_found` 및 그 외 논리 오류) → 재시도 무의미.
 * - [MissingScope]: 봇 토큰에 `users:read.email` 스코프 없음(`missing_scope`) → 재시도 무의미(운영자 조치 필요).
 * - [Transient]: 일시 오류(429/5xx/네트워크) → 재시도 가능.
 */
sealed interface SlackUserLookupResult {
    /** 이메일에 매칭되는 Slack 사용자를 찾음. */
    data class Found(val slackUserId: String, val teamId: String) : SlackUserLookupResult

    /** 워크스페이스에 해당 이메일 사용자 없음. */
    data object NotFound : SlackUserLookupResult

    /** 봇 토큰에 필요 스코프 없음. */
    data object MissingScope : SlackUserLookupResult

    /** 재시도 가능한 일시 실패. [reason]은 비밀값을 담지 않는 진단 문자열이다. */
    data class Transient(val reason: String) : SlackUserLookupResult
}

/**
 * Slack 공식 SDK(`slack-api-client`)의 [MethodsClient]로 `users.lookupByEmail`을 호출하는 클라이언트
 * (FR-SL-02 D6 Task 1).
 *
 * ## 용도
 * 이메일로 워크스페이스 연결 시 BTS 사용자 이메일 → Slack 사용자 id를 자동 해석한다(연결 UX 옵션 C).
 *
 * ## 봇 토큰 비노출 (§1.1.2 / FR-SL-01·FR-SL-02 3중 미노출 관례)
 * [lookupByEmail]의 `botToken`은 요청에만 싣고 **로그/예외/반환값에 절대 남기지 않는다**. 실패 결과
 * ([SlackUserLookupResult])의 reason은 오류 종류(예외 클래스명)만 담으며, 로그에도 토큰 대신
 * 예외 클래스명만 남긴다.
 *
 * ## 부팅 안전성 (FR-SL-01 DefaultSlackOAuthClient·SlackMessageClient 동일 패턴)
 * [methods] 기본값 `Slack.getInstance().methods()`는 자격증명·네트워크 없이 생성되므로 부팅이 깨지지 않는다.
 * 테스트는 [methods]에 mock을 주입한다.
 *
 * @param methods Slack SDK MethodsClient.
 */
@Component
class SlackUserLookupClient(
    private val methods: MethodsClient = Slack.getInstance().methods(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * [email]에 매칭되는 Slack 사용자를 조회한다.
     *
     * @param botToken 워크스페이스 봇 토큰(요청에만 사용, 미노출).
     * @param email 조회할 이메일 주소.
     * @return 조회 결과 분류.
     */
    fun lookupByEmail(
        botToken: String,
        email: String,
    ): SlackUserLookupResult {
        return try {
            val response =
                methods.usersLookupByEmail { req ->
                    req.token(botToken).email(email)
                }
            when {
                response.isOk -> {
                    val user = response.user
                    if (user == null) {
                        log.warn("slack_user_lookup_missing_user")
                        SlackUserLookupResult.NotFound
                    } else {
                        SlackUserLookupResult.Found(user.id, user.teamId)
                    }
                }
                response.error == ERROR_USERS_NOT_FOUND -> SlackUserLookupResult.NotFound
                response.error == ERROR_MISSING_SCOPE -> SlackUserLookupResult.MissingScope
                else -> {
                    log.warn("slack_user_lookup_error error={}", response.error ?: "unknown")
                    SlackUserLookupResult.NotFound
                }
            }
        } catch (e: IOException) {
            // 네트워크 오류 — 재시도 가능. 토큰/이메일 미노출(예외 클래스명만).
            log.warn("slack_user_lookup_io_error error={}", e.javaClass.simpleName)
            SlackUserLookupResult.Transient("transport:${e.javaClass.simpleName}")
        } catch (e: SlackApiException) {
            // HTTP 비2xx(429/5xx 등) — 재시도 가능. 토큰/이메일 미노출.
            log.warn("slack_user_lookup_api_error error={}", e.javaClass.simpleName)
            SlackUserLookupResult.Transient("http:${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val ERROR_USERS_NOT_FOUND = "users_not_found"
        const val ERROR_MISSING_SCOPE = "missing_scope"
    }
}
