// Slack App OAuth 설치 REST 컨트롤러 — /slack/install(302 authorize) + /slack/install/callback(결과 경로 302) (FR-SL-01 Task 9)

package com.bts.slack.web

import com.bts.slack.application.SlackInstallService
import com.bts.slack.application.SlackOAuthFailedException
import com.bts.slack.application.SlackUnsupportedInstallException
import com.bts.slack.oauth.SlackOAuthExchangeException
import com.bts.slack.oauth.SlackStateInvalidException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Slack App OAuth 2.0 설치 흐름의 두 진입점을 노출하는 REST 컨트롤러 (FR-SL-01 Task 9).
 *
 * 엔드포인트(spec §API).
 * - `GET /slack/install` — JWT + 시스템 관리자. 개시자를 추출해 [SlackInstallService.startInstall] 로
 *   서명 state 를 실은 Slack authorize URL 을 만들어 **302** 리다이렉트한다.
 * - `GET /slack/install/callback` — permitAll(Slack 리다이렉트, 서명 state 로 자체 검증). 결과를 프론트
 *   결과 경로로 **302** 리다이렉트한다(성공 `?installed=<teamName>`, 실패 `?error=<code>`).
 *
 * ## 콜백은 JSON 이 아니라 결과 경로 302 (plan G4)
 * 콜백은 브라우저가 최상위로 이동하는 GET 이므로 JSON 에러 대신 프론트 결과 화면으로 302 한다(화면은
 * 후속 PR — 경로만 예약). 예외별로 실패 코드를 실어 리다이렉트하며, 예외 `message`/`cause`·내부 식별자·
 * 봇 토큰은 응답에 절대 노출하지 않는다(§1.1.2 / 교훈 fr-pm-04-guard-exception-message-http-leak). Slack 이
 * 돌려준 실패 코드(예: `invalid_code`) 같은 **비-비밀** 값만 리다이렉트 쿼리에 싣는다.
 *
 * ## 보안 경계
 * - `startInstall` 의 관리자 판정([SlackInstallService])·미인증 401([SlackActorExtractor])·403 매핑은
 *   컨트롤러 뒤 서비스/추출기/예외 핸들러가 담당한다. 필터 체인이 permitAll/authenticated 를 1차 가드한다(이중).
 * - 콜백은 인가를 서명 state 로 대체한다(사용자 JWT 없음). `code` 도 `state` 도 없으면 실패로 처리한다.
 * - 리다이렉트 값은 [URLEncoder] 로 인코딩하고, 외부 입력(`error` 쿼리)은 [sanitizeErrorCode] 로 안전
 *   문자셋으로 제한해 Location 헤더 주입/오픈 리다이렉트를 차단한다.
 *
 * ## 설정 미비(EC6)
 * state 키/암호화 키/credentials 미설정 시 서비스가 던지는 [IllegalStateException] 은 콜백에서 잡지 않고
 * [SlackInstallExceptionHandler] 가 **비밀값 미포함 500** 으로 처리한다(운영자 설정 오류는 사용자 리다이렉트가
 * 아니라 서버 오류).
 *
 * @param service Slack 설치 오케스트레이션 서비스.
 */
@RestController
class SlackInstallController(
    private val service: SlackInstallService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 설치를 개시한다 — 관리자 가드를 통과하면 Slack authorize URL 로 302 리다이렉트한다.
     *
     * @return 302 + `Location: <Slack authorize URL>`.
     */
    @GetMapping("/slack/install")
    fun startInstall(): ResponseEntity<Void> {
        val actorId = SlackActorExtractor.extract()
        val authorizeUrl = service.startInstall(actorId)
        log.info("SLACK_INSTALL_START actor={}", actorId)
        return redirect(authorizeUrl)
    }

    /**
     * Slack 콜백을 처리하고 프론트 결과 경로로 302 리다이렉트한다.
     *
     * @param code Slack 1회용 인가 코드(성공 시 존재).
     * @param state [startInstall] 이 발급한 서명 state.
     * @param error 사용자가 Slack 동의 화면에서 취소했을 때의 코드(예: `access_denied`, EC2).
     * @return 302 + 결과 경로(`/admin/slack?installed=…` 또는 `?error=…`).
     */
    @GetMapping("/slack/install/callback")
    fun callback(
        @RequestParam(required = false) code: String?,
        @RequestParam(required = false) state: String?,
        @RequestParam(required = false) error: String?,
    ): ResponseEntity<Void> {
        // EC2 — 사용자가 Slack 동의 화면에서 취소(외부 입력 error 코드는 안전 문자셋으로 정화).
        if (!error.isNullOrBlank()) {
            log.info("SLACK_CALLBACK cancelled")
            return redirectFailure(sanitizeErrorCode(error))
        }
        return if (code.isNullOrBlank() || state.isNullOrBlank()) {
            // 필수 파라미터 부재 — 저장 없이 실패 처리.
            log.info("SLACK_CALLBACK missing_params")
            redirectFailure(MISSING_PARAMS)
        } else {
            completeInstall(code, state)
        }
    }

    /**
     * state 검증 → `code↔token` 교환 → 봇 토큰 암호화 upsert 를 수행하고 결과 경로로 302 한다.
     *
     * 실패는 예외별 비-비밀 코드로 실패 302 로 매핑한다(EC1/EC3/EC5/EC7 + 전송 오류). 예외 `message`·`cause`·
     * 내부 식별자는 응답에 노출하지 않고 코드만 싣는다(§1.1.2). state 검증 실패([SlackStateInvalidException])는
     * 예상된 클라이언트 오류라 원인을 전파하지 않고 일반 코드로 치환한다 — 이 의도적 swallow 를 detekt 에
     * 알린다.
     */
    @Suppress("SwallowedException")
    private fun completeInstall(
        code: String,
        state: String,
    ): ResponseEntity<Void> {
        return try {
            val result = service.completeInstall(code, state)
            log.info("SLACK_CALLBACK installed team={}", result.teamId)
            redirectSuccess(result.teamName)
        } catch (e: SlackStateInvalidException) {
            // 형식/서명/만료 실패(EC1). 원인·내부 사정은 노출하지 않는다(의도적 swallow).
            log.info("SLACK_CALLBACK state_invalid")
            redirectFailure(INVALID_STATE)
        } catch (e: SlackOAuthFailedException) {
            // ok:false 또는 access_token 부재(EC3/EC7). Slack 이 준 코드도 방어심층으로 안전 문자셋만 싣는다.
            log.info("SLACK_CALLBACK oauth_failed code={}", e.errorCode)
            redirectFailure(sanitizeErrorCode(e.errorCode))
        } catch (e: SlackUnsupportedInstallException) {
            // enterprise 등 미지원 설치 유형(EC5/G1). 내부 상수지만 리다이렉트 전 일관되게 정화한다.
            log.info("SLACK_CALLBACK unsupported code={}", e.errorCode)
            redirectFailure(sanitizeErrorCode(e.errorCode))
        } catch (e: SlackOAuthExchangeException) {
            // 전송/네트워크 오류로 교환 자체 실패 — 사용자에겐 일반 실패 화면으로 302(요청 값 미노출).
            log.warn("SLACK_CALLBACK exchange_failed", e)
            redirectFailure(EXCHANGE_FAILED)
        }
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /** 완료 화면으로 302 — `?installed=<teamName>`(URL 인코딩). */
    private fun redirectSuccess(teamName: String): ResponseEntity<Void> {
        return redirect("$FRONT_RESULT_PATH?installed=${encode(teamName)}")
    }

    /** 실패 화면으로 302 — `?error=<code>`(비-비밀 코드, URL 인코딩). */
    private fun redirectFailure(errorCode: String): ResponseEntity<Void> {
        return redirect("$FRONT_RESULT_PATH?error=${encode(errorCode)}")
    }

    /** 302 Found + Location 헤더. [location] 은 이미 안전하게 인코딩된 문자열이어야 한다. */
    private fun redirect(location: String): ResponseEntity<Void> =
        ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build()

    /** 쿼리 파라미터 값을 퍼센트 인코딩한다(공백·제어문자 포함 — Location 헤더 주입 차단). */
    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    /**
     * Slack 이 준 에러 코드(콜백 `error` 쿼리 + 교환 응답 `error`)를 안전 문자셋(`[A-Za-z0-9_-]`)으로 제한한다.
     *
     * 빈 값·허용 문자 외 문자만 있는 경우 일반 코드로 치환하고, 과도한 길이는 잘라 오픈 리다이렉트/헤더
     * 주입 표면을 없앤다. Slack 이 실제로 보내는 코드(`access_denied`·`invalid_code` 등)는 그대로 통과한다.
     * [URLEncoder] 인코딩만으로도 주입은 막히지만, 방어심층으로 컨트롤러에서도 문자셋을 좁힌다.
     */
    private fun sanitizeErrorCode(raw: String): String {
        val cleaned = raw.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(MAX_ERROR_CODE_LEN)
        return cleaned.ifBlank { INSTALL_FAILED }
    }

    private companion object {
        /** 프론트 설치 결과 화면 경로(관리자 Slack 연결 페이지, spec §완료 리다이렉트 G4). */
        const val FRONT_RESULT_PATH = "/admin/slack"

        /** state 검증 실패(EC1) 리다이렉트 코드. */
        const val INVALID_STATE = "invalid_state"

        /** code·error 모두 부재 시 실패 코드. */
        const val MISSING_PARAMS = "missing_params"

        /** 교환 전송 오류 실패 코드. */
        const val EXCHANGE_FAILED = "exchange_failed"

        /** 외부 error 코드가 안전 문자셋을 벗어났을 때의 일반 실패 코드. */
        const val INSTALL_FAILED = "install_failed"

        /** 리다이렉트에 싣는 에러 코드 최대 길이. */
        const val MAX_ERROR_CODE_LEN = 64
    }
}
