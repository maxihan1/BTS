// SlackUserConnectionService의 사용자 연결 오케스트레이션 도메인 예외 (FR-SL-02 D6 Task 3)

package com.bts.slack.application

// SlackUserConnectionService 가 던지는 도메인 예외 모음.
//
// 예외 → HTTP 매핑 예정(웹 레이어 후속 태스크가 소비).
//   EmailUnavailableException            — 행위자의 BTS 계정에 이메일이 없음               → 422
//   WorkspaceNotInstalledException       — Slack 워크스페이스 미설치(또는 TOCTOU 삭제 레이스) → 409
//   SlackScopeMissingException           — 봇 토큰에 users:read.email 스코프 없음(운영자 조치) → 409
//   SlackUserNotFoundException           — 이메일에 매칭되는 Slack 사용자 없음               → 404
//   SlackTemporarilyUnavailableException — Slack 일시 오류(429/5xx/네트워크, 재시도 가능)      → 503
//   SlackAccountAlreadyLinkedException   — 이 Slack 계정이 이미 다른 사용자에게 연결됨(V702 UNIQUE) → 409
//
// 예외 메시지 위생 (DEVELOPMENT.md §1.1.2 / 교훈 fr-pm-04-guard-exception-message-http-leak).
// 모든 메시지는 이메일·slack_user_id·봇 토큰·Slack 원본 에러 문자열을 담지 않는 고정 일반 문구다.
// 실패 사유를 구분하는 정보는 예외 "타입"으로만 전달하고, message 로는 어떤 가변 값도 보간하지 않는다.

/**
 * 연결을 시도하는 BTS 사용자 계정에 이메일이 없어 Slack 사용자를 해석할 수 없음을 나타낸다.
 *
 * [com.bts.shared.user.UserLookupPort.findEmailById] 가 null 을 돌려줄 때 발생한다.
 */
class EmailUnavailableException :
    RuntimeException("Slack workspace connection requires a BTS account email")

/**
 * 연결 시점에 유효한 Slack 워크스페이스 설치를 찾지 못했음을 나타낸다.
 *
 * 두 경우를 포괄한다.
 * - [com.bts.slack.application.SlackInstallRepository.findCurrentInstallation] 이 null(설치 자체가 없음).
 * - 설치는 있으나 [com.bts.slack.worker.SlackBotTokenResolver.resolve] 가 null(그 사이 설치가
 *   삭제/재설치된 TOCTOU(검사-사용 사이 변경) 레이스) — 두 조회 사이의 경합을 낙관적으로 거부한다.
 */
class WorkspaceNotInstalledException :
    RuntimeException("No Slack workspace is currently installed")

/**
 * 워크스페이스 봇 토큰에 `users:read.email` 스코프가 없어 이메일 조회가 거부됨을 나타낸다
 * ([com.bts.slack.message.SlackUserLookupResult.MissingScope]).
 *
 * 재시도로 해소되지 않는다 — 운영자가 앱을 재승인해 스코프를 확장해야 한다.
 */
class SlackScopeMissingException :
    RuntimeException("Slack bot token is missing a required scope")

/**
 * 워크스페이스에 해당 이메일과 매칭되는 Slack 사용자가 없음을 나타낸다
 * ([com.bts.slack.message.SlackUserLookupResult.NotFound]).
 */
class SlackUserNotFoundException :
    RuntimeException("No matching Slack user was found for this account")

/**
 * Slack API 가 일시적으로 응답하지 못했음을 나타낸다
 * ([com.bts.slack.message.SlackUserLookupResult.Transient] — 429/5xx/네트워크 오류).
 *
 * 재시도 가능한 실패다. 원본 Slack 에러 문자열은 담지 않는다(§1.1.2).
 */
class SlackTemporarilyUnavailableException :
    RuntimeException("Slack is temporarily unavailable, please try again")

/**
 * 연결하려는 Slack 계정이 이미 다른 BTS 사용자에게 연결되어 있음을 나타낸다(코드리뷰 CONCERN-1 hot-fix).
 *
 * V702 `idx_user_slack_mapping_slack_user` UNIQUE(slack_user_id, team_id) 위반으로 발생한
 * [org.springframework.dao.DuplicateKeyException] 을 [SlackUserConnectionService.completeLink] 가 이
 * 예외로 번역한다 — 의도된 거부(fail-closed)이므로 500 이 아닌 409 로 응답해야 한다.
 */
class SlackAccountAlreadyLinkedException :
    RuntimeException("This Slack account is already linked to another user")
