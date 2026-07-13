// SlackChannelMappingService의 채널 매핑 CRUD 오케스트레이션 도메인 예외 모음 (FR-SL-06 Task 6)

package com.bts.slack.application

// SlackChannelMappingService 가 던지는 도메인 예외 모음.
//
// 예외 → HTTP 매핑 예정(웹 레이어 Task 7 이 소비).
//   SlackChannelMappingPermissionDeniedException — 행위자가 대상 프로젝트의 채널 매핑 관리 권한 없음(fail-closed) → 403
//   SlackChannelMappingNotFoundException         — 대상 매핑 id 미존재                                        → 404
//   SlackChannelMappingConflictException         — 같은 team+project+channel 매핑 중복(V704 UNIQUE)          → 409
//   (WorkspaceNotInstalledException 은 설치 0건 시 재사용 — SlackConnectionExceptions.kt, 409)
//
// 예외 메시지 위생 (DEVELOPMENT.md §1.1.2 / 교훈 fr-pm-04-guard-exception-message-http-leak).
// 모든 메시지는 projectKey·channelId·teamId·매핑 id 등 어떤 가변 값도 담지 않는 고정 일반 문구다.
// 실패 사유를 구분하는 정보는 예외 "타입"으로만 전달한다 — 비관리자에게 대상 프로젝트/채널의 존재
// 여부나 내부 정책이 응답 detail 로 새지 않게 한다(fail-closed).

/**
 * 행위자가 대상 프로젝트에서 Slack 채널 매핑을 관리(생성/조회/수정/삭제)할 권한이 없음을 나타낸다.
 *
 * [com.bts.shared.permission.SlackChannelMappingPermissionResolver.hasManageChannelMapping] 이
 * `false`(비관리자·미해석 프로젝트 키·비멤버 포함)를 돌려줄 때 발생한다. 어떤 경우든 동일한 일반 403
 * 으로 수렴시켜 내부 사정을 노출하지 않는다(fail-closed).
 */
class SlackChannelMappingPermissionDeniedException :
    RuntimeException("You are not allowed to manage Slack channel mappings for this project")

/**
 * 수정/삭제 대상 채널 매핑 id 가 존재하지 않음을 나타낸다.
 *
 * [SlackChannelMappingRepository.findById] 가 null 을 돌려줄 때 발생한다(권한 확인 이전 — 대상의
 * projectKey 를 얻어야 게이트할 수 있으므로 조회가 선행한다).
 */
class SlackChannelMappingNotFoundException :
    RuntimeException("Slack channel mapping was not found")

/**
 * 같은 워크스페이스·프로젝트·채널 조합의 매핑이 이미 존재함을 나타낸다(V704 `UNIQUE(team_id,
 * project_key, channel_id)` 위반).
 *
 * [SlackChannelMappingRepository.save]/[SlackChannelMappingRepository.update] 가 던지는
 * [org.springframework.dao.DataIntegrityViolationException] 을 [SlackChannelMappingService] 가 이
 * 예외로 번역한다 — 의도된 거부(fail-closed)이므로 500 이 아닌 409 로 응답해야 한다.
 */
class SlackChannelMappingConflictException :
    RuntimeException("A Slack channel mapping already exists for this channel")
