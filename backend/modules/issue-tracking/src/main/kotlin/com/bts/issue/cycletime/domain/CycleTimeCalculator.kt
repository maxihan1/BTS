// Cycle/Lead Time 분포를 이슈 소요 시간 입력에서 계산하는 순수 도메인 계산기 — Clock/DB/포트 의존 0

package com.bts.issue.cycletime.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Cycle Time과 Lead Time 분포를 계산하는 순수 함수.
 *
 * DB·Clock·Spring 의존이 전혀 없는 순수 도메인 계산기다.
 */
object CycleTimeCalculator {
    /**
     * [projectKey] 프로젝트의 [from]~[to](inclusive) 창에서 완료된 이슈들의 Cycle/Lead Time 분포를
     * 계산한다.
     *
     * @param projectKey 대상 프로젝트 키.
     * @param from 창 시작일(inclusive).
     * @param to 창 종료일(inclusive).
     * @param inputs 이슈별 소요 시간 입력 목록.
     * @return 계산된 [CycleTimeResult].
     */
    fun calculate(
        projectKey: String,
        from: LocalDate,
        to: LocalDate,
        inputs: List<IssueDurationInput>,
    ): CycleTimeResult {
        val leadSamples = mutableListOf<CycleTimeSample>()
        val cycleSamples = mutableListOf<CycleTimeSample>()

        for (input in inputs) {
            val doneAt = input.lastDoneAt ?: continue
            val doneDate = doneAt.atZone(ZoneOffset.UTC).toLocalDate()
            if (doneDate.isBefore(from) || doneDate.isAfter(to)) continue

            leadSamples += toSample(input.issueKey, input.createdAt, doneAt)

            val startedAt = input.firstInProgressAt
            if (startedAt != null && !doneAt.isBefore(startedAt)) {
                cycleSamples += toSample(input.issueKey, startedAt, doneAt)
            }
        }

        val sortedLead = leadSamples.sortedBy { it.seconds }
        val sortedCycle = cycleSamples.sortedBy { it.seconds }

        return CycleTimeResult(
            projectKey = projectKey,
            from = from,
            to = to,
            cycleTime = CycleTimeMetric(CycleTimeStats.of(sortedCycle.map { it.seconds }), sortedCycle),
            leadTime = CycleTimeMetric(CycleTimeStats.of(sortedLead.map { it.seconds }), sortedLead),
        )
    }

    private fun toSample(
        issueKey: String,
        start: Instant,
        end: Instant,
    ): CycleTimeSample = CycleTimeSample(issueKey, Duration.between(start, end).seconds)
}
