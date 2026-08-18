// Cycle/Lead Time 분포를 이슈 소요 시간 입력에서 계산하는 순수 도메인 계산기 — Clock/DB/포트 의존 0

package com.bts.issue.cycletime.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Cycle Time과 Lead Time 분포를 계산하는 순수 함수.
 *
 * DB·Clock·Spring 의존이 전혀 없는 순수 도메인 계산기다. 이슈별 소요 시간 입력([IssueDurationInput])
 * 목록을 받아 창(window) 내에서 완료된 이슈들의 Cycle/Lead Time 분포를 계산한다.
 *
 * ### 용어 정의
 * - **Lead Time** — 이슈 생성부터 완료까지 전체 소요 시간(`lastDoneAt - createdAt`). 모집단(완료
 *   이슈) 전원이 표본이 된다.
 * - **Cycle Time** — 실제 작업 착수(첫 IN_PROGRESS 전환)부터 완료까지 소요 시간
 *   (`lastDoneAt - firstInProgressAt`). IN_PROGRESS를 한 번도 거치지 않은 이슈(TODO → DONE 직행)는
 *   "착수" 시점이 정의되지 않으므로 Cycle Time 표본에서 제외한다(Lead Time 표본에는 남는다).
 *
 * ### 모집단 — 완료 판정
 * [IssueDurationInput.lastDoneAt]이 `null`이 아니고, 그 UTC 날짜가 `[from, to]`(inclusive) 안에
 * 있는 이슈만 모집단에 포함한다. 완료 여부와 창 소속 여부를 [IssueDurationInput.lastDoneAt]의 날짜
 * 하나로 판정하는 이유는 "이 기간에 완료된 작업의 분포"라는 지표 정의상 자연스러운 기준점이 완료
 * 시점이기 때문이다.
 *
 * ### 음수 방어
 * `lastDoneAt < firstInProgressAt`인 이슈(데이터 이상 또는 시각 역전)는 Cycle Time 표본에서
 * 제외한다. 음수 소요 시간은 통계적으로 의미가 없고 차트를 왜곡시키기 때문이다. Lead Time은
 * `lastDoneAt ≥ createdAt`이 항상 보장되므로(전환은 생성 이후에만 발생) 이 방어가 불필요하다.
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
        val completed = completedInWindow(inputs, from, to)
        val leadSamples = buildLeadSamples(completed)
        val cycleSamples = buildCycleSamples(completed)

        return CycleTimeResult(
            projectKey = projectKey,
            from = from,
            to = to,
            cycleTime = CycleTimeMetric(CycleTimeStats.of(cycleSamples.map { it.seconds }), cycleSamples),
            leadTime = CycleTimeMetric(CycleTimeStats.of(leadSamples.map { it.seconds }), leadSamples),
        )
    }

    /**
     * [inputs] 중 완료([IssueDurationInput.lastDoneAt] not null)이고 그 UTC 날짜가 [from]~[to]
     * 안인 이슈만 골라 완료 시각을 non-null로 캡처한 [CompletedIssue] 목록으로 변환한다.
     *
     * `mapNotNull` + `let`로 non-null [IssueDurationInput.lastDoneAt]을 로컬 값에 바인딩해
     * `!!` 없이 안전하게 다룬다.
     */
    private fun completedInWindow(
        inputs: List<IssueDurationInput>,
        from: LocalDate,
        to: LocalDate,
    ): List<CompletedIssue> =
        inputs.mapNotNull { input ->
            input.lastDoneAt?.let { doneAt ->
                val doneDate = doneAt.atZone(ZoneOffset.UTC).toLocalDate()
                if (!doneDate.isBefore(from) && !doneDate.isAfter(to)) CompletedIssue(input, doneAt) else null
            }
        }

    /** [completed] 모집단 전원에 대해 생성~완료 소요 시간을 초 오름차순 정렬된 Lead Time 표본으로 만든다. */
    private fun buildLeadSamples(completed: List<CompletedIssue>): List<CycleTimeSample> =
        completed
            .map { toSample(it.input.issueKey, it.input.createdAt, it.doneAt) }
            .sortedBy { it.seconds }

    /**
     * [completed] 중 [IssueDurationInput.firstInProgressAt]이 있고 완료 시각 이후가 아닌(음수 제외)
     * 이슈만 착수~완료 소요 시간을 초 오름차순 정렬된 Cycle Time 표본으로 만든다.
     */
    private fun buildCycleSamples(completed: List<CompletedIssue>): List<CycleTimeSample> =
        completed
            .mapNotNull { row ->
                val startedAt = row.input.firstInProgressAt ?: return@mapNotNull null
                if (row.doneAt.isBefore(startedAt)) return@mapNotNull null
                toSample(row.input.issueKey, startedAt, row.doneAt)
            }.sortedBy { it.seconds }

    /** [issueKey]의 [start]~[end] 구간을 초 단위 [CycleTimeSample]로 변환한다. */
    private fun toSample(
        issueKey: String,
        start: Instant,
        end: Instant,
    ): CycleTimeSample = CycleTimeSample(issueKey, Duration.between(start, end).seconds)

    /** 완료 판정을 마친 이슈 하나 — 원본 입력과 non-null 완료 시각을 함께 캡처하는 내부 헬퍼. */
    private data class CompletedIssue(
        val input: IssueDurationInput,
        val doneAt: Instant,
    )
}
