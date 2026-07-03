// CFD 시계열의 단일 지점 — 날짜별 카테고리 3띠 누적 카운트 read-model VO

package com.bts.issue.cfd.domain

import java.time.LocalDate

/**
 * CFD(Cumulative Flow Diagram, 누적 흐름 다이어그램) 시계열의 하루 단위 지점.
 *
 * [CfdCalculator.calculate]의 출력 요소. 영속되지 않는 순수 read-model VO다.
 *
 * @property date 이 지점이 나타내는 캘린더 일자(UTC 기준).
 * @property todoCount 이 날짜에 TODO 카테고리로 분류된 이슈 누적 개수.
 * @property inProgressCount 이 날짜에 IN_PROGRESS 카테고리로 분류된 이슈 누적 개수.
 * @property doneCount 이 날짜에 DONE 카테고리로 분류된 이슈 누적 개수.
 */
data class CfdPoint(
    val date: LocalDate,
    val todoCount: Int,
    val inProgressCount: Int,
    val doneCount: Int,
)
