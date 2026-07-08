// Slack 연결 상태/설치 URL JSON 조회 REST 컨트롤러 — SPA(Bearer) 전용 view-layer (FR-SL-01 D6/D7 Task 3)

package com.bts.slack.web

import com.bts.slack.application.SlackInstallService
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Slack 연결 상태/설치 URL 을 JSON 으로 노출하는 REST 컨트롤러 (FR-SL-01 D6/D7 Task 3).
 *
 * SPA(Bearer 인증)의 관리자 Slack 연결 페이지가 호출하는 **view-layer** 다. 브라우저 최상위 이동을 전제로
 * 302 리다이렉트하는 [SlackInstallController](설치 개시·콜백)와 달리, 여기서는 SPA 가 fetch 로 소비할 수
 * 있도록 상태/URL 을 JSON 으로 돌려준다(SPA 는 Location 헤더를 따라갈 수 없다).
 *
 * ## 엔드포인트
 * - `GET /api/v1/slack/installation` — 현재 워크스페이스 연결 상태([SlackInstallationResponse]). 상태 배너용.
 * - `GET /api/v1/slack/install-url` — 설치 개시 authorize URL([SlackInstallUrlResponse]). "Slack 연결" 버튼용.
 *
 * ## 인가 순서 — 추출(401) → 관리자(403) → 조회 (교훈 auth-extraction-before-resource-lookup)
 * 두 핸들러 모두 [SlackActorExtractor.extract] 로 행위자를 **먼저** 추출(미인증 401)한 뒤
 * [SlackInstallService] 가 시스템 관리자 여부를 fail-closed 로 판정한다(비관리자 [SlackForbiddenException]
 * → 403). 관리자 판정은 리소스 조회/외부 상태 접근보다 앞서므로 비관리자에게는 설치 유무조차 노출되지
 * 않는다. 필터 체인이 `/api/v1/slack/…` authenticated 를 1차 가드한다(이중).
 *
 * ## 예외 → HTTP 매핑
 * [SlackForbiddenException](403)·미인증 [org.springframework.web.server.ResponseStatusException](401)·기타
 * 500 은 [SlackInstallExceptionHandler] 가 일반 메시지로 치환해 처리한다(assignableTypes 에 이 컨트롤러가
 * 포함돼 있어야 403 이 500 으로 변질되지 않는다 — 교훈 domain-exception-http-handler-basepackage-scope).
 *
 * ## 비밀값 미노출 (§1.1.2)
 * 상태 응답은 [SlackInstallationResponse] 의 표시용 필드(connected/teamId/teamName/botUserId/installedAt/
 * updatedAt/installerName)만 담는다 — 봇 토큰(평문/암호문)·설치자 원시 id(`installedBy` UUID)는
 * [com.bts.slack.application.SlackInstallationStatus] 에 애초에 로드되지 않아 타입 상 새어 나갈 수 없다.
 * 설치자는 이미 해석된 표시명(installerName)으로만 노출한다.
 *
 * @param service Slack 설치 오케스트레이션 서비스(관리자 가드 + 상태 조회 + authorize URL 생성).
 */
@RestController
class SlackInstallQueryController(
    private val service: SlackInstallService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 현재 Slack 워크스페이스 연결 상태를 조회한다 — 관리자 상태 배너용.
     *
     * @return 200 + 연결 상태(미설치 시 `connected:false`, 나머지 null).
     */
    @GetMapping("/api/v1/slack/installation")
    fun getInstallation(): SlackInstallationResponse {
        val actorId = SlackActorExtractor.extract()
        val status = service.getInstallation(actorId)
        log.info("SLACK_INSTALLATION_QUERY actor={} connected={}", actorId, status.connected)
        return SlackInstallationResponse(
            connected = status.connected,
            teamId = status.teamId,
            teamName = status.teamName,
            botUserId = status.botUserId,
            installedAt = status.installedAt,
            updatedAt = status.updatedAt,
            // 설치자는 표시명만 노출 — status.installerName 은 이미 UserLookupPort 로 해석된 값이며
            // 원시 installed_by UUID 는 SlackInstallationStatus 에 애초에 담기지 않는다(타입 경계, §1.1.2).
            installerName = status.installerName,
        )
    }

    /**
     * 설치 개시 authorize URL 을 발급한다 — SPA "Slack 연결" 버튼용.
     *
     * @return 200 + 서명 state 를 실은 Slack authorize URL.
     */
    @GetMapping("/api/v1/slack/install-url")
    fun getInstallUrl(): SlackInstallUrlResponse {
        val actorId = SlackActorExtractor.extract()
        val url = service.startInstall(actorId)
        log.info("SLACK_INSTALL_URL_QUERY actor={}", actorId)
        return SlackInstallUrlResponse(url = url)
    }
}
