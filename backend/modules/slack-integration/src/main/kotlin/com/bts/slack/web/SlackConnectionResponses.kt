// 본인 Slack 계정 연결 상태 조회/연결/해제 me-scope 응답 DTO — 표시용 비-비밀 필드만 (FR-SL-02 D6 Task 4)
// 파일명은 복수형(Responses)으로 "응답 DTO 모음" 의미를 표현하고(SlackInstallQueryResponses.kt 선례),
// 현재는 GET/POST/DELETE 3종이 공유하는 단수 클래스 하나뿐이라 ktlint filename 규칙을 억제한다.
@file:Suppress("ktlint:standard:filename", "MatchingDeclarationName")

package com.bts.slack.web

import java.time.Instant

/**
 * `/api/v1/slack/me/connection` GET/POST/DELETE 공통 응답 (FR-SL-02 D6 Task 4).
 *
 * **표시용 비-비밀 필드만** 담는다(DEVELOPMENT.md §1.1.2). `slack_user_id`·연결에 쓰인 이메일·봇 토큰은
 * [com.bts.slack.application.ConnectionStatus] 에 애초에 담기지 않아 타입 상 새어 나갈 수 없다(방어적,
 * [SlackInstallationResponse] 동형).
 *
 * @property connected 연결되어 있으면 true. 미연결/해제 직후는 false.
 * @property workspaceName 연결된 워크스페이스 표시명 — 미연결 시 null.
 * @property linkedAt 최종 연결(upsert) 시각 — 미연결 시 null. JSON 직렬화 시 ISO-8601 문자열.
 */
data class SlackConnectionResponse(
    val connected: Boolean,
    val workspaceName: String?,
    val linkedAt: Instant?,
)
