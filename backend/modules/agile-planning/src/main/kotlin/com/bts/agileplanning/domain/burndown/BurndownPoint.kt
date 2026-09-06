// 번다운/번업 차트 시계열의 단일 지점 — 순수 read-model VO (애그리게이트·영속 대상 아님)

package com.bts.agileplanning.domain.burndown

import java.time.LocalDate

/**
 * 번다운(Burndown) / 번업(Burnup) 차트 시계열의 하루 단위 지점.
 *
 * [BurndownCalculator.calculate] · [BurndownCalculator.calculateIssueCount] 의 출력 요소.
 * 영속되지 않는 순수 read-model VO다(ADR 2026-07-02-fr-rp-01).
 *
 * ### ★값의 단위는 이 클래스가 정하지 않는다 (부채 177 task-35)
 * `...Seconds` 라는 이름은 **시간 축에서 붙은 역사적 이름**이다. 보드의 `time_tracking` 이 `NONE` 이면
 * 같은 칸에 **이슈 개수**가 들어간다(3 = 3개). 어느 쪽인지는 시계열 전체가 하나로 갖는
 * [BurndownUnit] 이 말한다 — [com.bts.agileplanning.application.SprintBurndownResult.unit] ·
 * REST 응답의 `unit` 필드.
 *
 * 이름을 단위 중립으로 고치지 않은 이유는 그것이 **공개 REST 계약**(`remainingSeconds` …)이고
 * 프론트 Zod 스키마가 같은 이름으로 파싱하기 때문이다. 백엔드만 고치면 두 자리가 서로 다른 이름을
 * 말하게 된다 — 함께 고치는 것은 별도 정리 대상이다([BurndownUnit] KDoc).
 *
 * @property date 이 지점이 나타내는 캘린더 일자.
 * @property remainingSeconds Actual 잔여값(시간 축이면 초, 개수 축이면 이슈 수).
 *   asOf(= min(end, today)) 이후 미래 일자는 null(아직 미도래). 0 미만으로 내려가지 않도록 클램프된다.
 * @property idealSeconds Ideal 값. start 에서 총 스코프, end 에서 0 으로 선형 보간. 전 구간 non-null.
 * @property completedSeconds Burnup 누적 완료값. remainingSeconds 와 동일한 null 규칙을 따른다.
 *   시간 축에서는 총 스코프를 초과할 수 있다(로그가 추정치보다 많은 경우, 클램프하지 않음).
 * @property scopeSeconds Burnup 총 스코프. 전 구간 평탄(flat), non-null.
 */
data class BurndownPoint(
    val date: LocalDate,
    val remainingSeconds: Long?,
    val idealSeconds: Long,
    val completedSeconds: Long?,
    val scopeSeconds: Long,
)
