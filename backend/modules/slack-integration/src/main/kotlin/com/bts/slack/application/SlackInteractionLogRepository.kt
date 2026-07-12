// Slack 인터랙티브 상호작용 감사 로그 포트 — 액션 처리 결과를 append-only 로 기록 (FR-SL-05 Task 4)

package com.bts.slack.application

import java.util.UUID

/**
 * `slack_interaction_log` 영속화 포트 — Slack 인터랙티브(Block Kit 버튼/셀렉트) 액션의 처리 결과를
 * append-only 감사 로그로 남긴다(FR-SL-05).
 *
 * 구현체는 [com.bts.slack.persistence.JdbcSlackInteractionLogRepository] — JdbcTemplate 기반
 * ([SlackDeliveryLogRepository]/[SlackUserMappingRepository] 동형, jOOQ 미도입).
 *
 * ## best-effort 기록 (예외 삼키지 않음)
 * 감사 로그 기록은 인터랙션 처리의 부수 작업이지만, 이 포트는 예외를 **삼키지 않는다**. 기록 실패를 무시할지
 * (best-effort) 아니면 전체 실패로 볼지는 호출자가 판단한다 — 여기서 조용히 삼키면 감사 누락이 은폐되기 때문이다
 * (best-effort-loop-permission-exception-nonprod-mask 계열 교훈).
 */
interface SlackInteractionLogRepository {
    /**
     * 인터랙티브 액션 처리 결과 한 건을 기록한다.
     *
     * @param teamId Slack workspace(team) id.
     * @param slackUserId 액션을 실행한 Slack 사용자 id(Uxxxx).
     * @param btsUserId 연결된 BTS user id — Slack 계정이 BTS 에 연결되지 않았으면(UNMAPPED) null.
     * @param actionType COMPLETE / ASSIGN / COMMENT / VIEW.
     * @param outcome SUCCESS / UNMAPPED / PERMISSION_DENIED / CONFLICT / ERROR / NOT_APPLICABLE.
     * @param issueKey 대상 이슈 키 — 없으면 null.
     *
     * 감사 테이블 컬럼(team/slackUser/btsUser/action/outcome/issue)을 그대로 받는 flat insert이며
     * 단일 호출처(SlackInteractionService)뿐이라 VO 번들은 불필요한 간접화다 — LongParameterList 억제.
     */
    @Suppress("LongParameterList")
    fun record(
        teamId: String,
        slackUserId: String,
        btsUserId: UUID?,
        actionType: String,
        outcome: String,
        issueKey: String?,
    )
}
