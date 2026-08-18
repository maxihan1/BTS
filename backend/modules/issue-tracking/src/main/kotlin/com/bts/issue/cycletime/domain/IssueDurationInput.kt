// Cycle/Lead Time 계산기 입력 — 이슈 하나의 상태 이력을 축약한 순수 VO

package com.bts.issue.cycletime.domain

import java.time.Instant

/**
 * [CycleTimeCalculator]의 입력으로 사용되는 이슈 하나의 소요 시간 원자재.
 *
 * 서비스 계층이 상태 이력(DB)에서 첫 IN_PROGRESS 전환 시각과 마지막 DONE 전환 시각만 축약해
 * 전달한다. 나머지 전환 상세는 이 계산기의 관심사가 아니다.
 *
 * @property issueKey 이슈 키.
 * @property createdAt 이슈 생성 시각.
 * @property firstInProgressAt 첫 IN_PROGRESS 전환 시각. 한 번도 IN_PROGRESS를 거치지 않았으면 `null`.
 * @property lastDoneAt 마지막 DONE 전환 시각. 아직 완료되지 않았으면 `null`(= 미완료).
 */
data class IssueDurationInput(
    val issueKey: String,
    val createdAt: Instant,
    val firstInProgressAt: Instant?,
    val lastDoneAt: Instant?,
)
