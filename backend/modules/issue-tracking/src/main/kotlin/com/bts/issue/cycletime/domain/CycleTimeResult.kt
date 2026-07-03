// Cycle/Lead Time 계산 최종 결과 — 프로젝트·창·두 지표를 담는 read-model VO

package com.bts.issue.cycletime.domain

import java.time.LocalDate

/**
 * Cycle/Lead Time 계산 최종 결과.
 *
 * [CycleTimeCalculator.calculate]가 반환하는 read-model VO다. 영속 대상이 아니다.
 *
 * @property projectKey 대상 프로젝트 키.
 * @property from 창 시작일(inclusive).
 * @property to 창 종료일(inclusive).
 * @property cycleTime Cycle Time(첫 IN_PROGRESS → 마지막 DONE) 지표.
 * @property leadTime Lead Time(생성 → 마지막 DONE) 지표.
 */
data class CycleTimeResult(
    val projectKey: String,
    val from: LocalDate,
    val to: LocalDate,
    val cycleTime: CycleTimeMetric,
    val leadTime: CycleTimeMetric,
)
