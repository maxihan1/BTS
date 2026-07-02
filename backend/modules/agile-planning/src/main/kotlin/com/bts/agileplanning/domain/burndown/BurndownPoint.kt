// 번다운/번업 차트 시계열의 단일 지점 — 순수 read-model VO (애그리게이트·영속 대상 아님)

package com.bts.agileplanning.domain.burndown

import java.time.LocalDate

/**
 * 번다운(Burndown) / 번업(Burnup) 차트 시계열의 하루 단위 지점.
 *
 * [BurndownCalculator.calculate] 의 출력 요소. 영속되지 않는 순수 read-model VO다(ADR 2026-07-02-fr-rp-01).
 *
 * @property date 이 지점이 나타내는 캘린더 일자.
 * @property remainingSeconds Actual 잔여 시간(초). asOf(= min(end, today)) 이후 미래 일자는 null(아직 미도래).
 *   0 미만으로 내려가지 않도록 클램프된다.
 * @property idealSeconds Ideal 시간(초). start 에서 총 스코프, end 에서 0 으로 선형 보간. 전 구간 non-null.
 * @property completedSeconds Burnup 누적 완료 시간(초). remainingSeconds 와 동일한 null 규칙을 따른다.
 *   총 스코프를 초과할 수 있다(로그가 추정치보다 많은 경우, 클램프하지 않음).
 * @property scopeSeconds Burnup 총 스코프(초). 전 구간 평탄(flat), non-null.
 */
data class BurndownPoint(
    val date: LocalDate,
    val remainingSeconds: Long?,
    val idealSeconds: Long,
    val completedSeconds: Long?,
    val scopeSeconds: Long,
)
