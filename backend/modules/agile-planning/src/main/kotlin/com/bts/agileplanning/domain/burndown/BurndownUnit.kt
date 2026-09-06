// 번다운 세로축의 단위 — 보드 「추정」 탭의 time_tracking 이 고른다 (부채 177 task-35)

package com.bts.agileplanning.domain.burndown

/**
 * 번다운/번업 시계열 값의 **단위**.
 *
 * 한 차트의 모든 점이 같은 단위를 쓴다. 그래서 점이 아니라 **시계열 전체**가 이 값을 하나 갖는다
 * ([com.bts.agileplanning.application.SprintBurndownResult.unit]).
 *
 * ### 누가 정하는가
 * 보드 「추정」 탭의 `time_tracking` 이다(J36). 판정의 정본은
 * [com.bts.agileplanning.application.SprintBurndownService.resolveUnit] KDoc 이다.
 *
 * ### ★[BurndownPoint] 의 필드 이름이 `...Seconds` 인 이유
 * [ISSUE_COUNT] 축에서 그 값들은 초가 아니라 **개수**다. 이름이 계약을 말하지 못하는 자리이고
 * 이 enum 이 그 빈틈을 메운다 — 이름을 고치려면 REST 응답 필드(`remainingSeconds` …)와
 * 프론트 Zod 스키마를 같은 PR 에서 함께 바꿔야 해서 이 task 의 범위를 넘는다.
 * 그 대신 응답이 [BurndownUnit] 을 함께 실어 소비측이 단위를 오해할 수 없게 한다.
 */
enum class BurndownUnit {
    /** 초. 잔여/이상/완료가 전부 시간이다. `time_tracking = REMAINING_AND_SPENT` 의 축이다. */
    SECONDS,

    /** 이슈 개수. `time_tracking = NONE` 의 축 — 시간으로 진행을 재지 않는 보드가 쓸 유일한 척도다. */
    ISSUE_COUNT,
}
