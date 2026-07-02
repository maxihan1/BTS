// 벨로시티 차트 시계열의 단일 스프린트 지점 — 순수 read-model VO (애그리게이트·영속 대상 아님)

package com.bts.agileplanning.domain.velocity

import java.time.LocalDate
import java.util.UUID

/**
 * 벨로시티(Velocity) 차트의 한 스프린트를 나타내는 지점.
 *
 * [SprintVelocityResult.of] 의 입력/출력 요소. 영속되지 않는 순수 read-model VO다.
 *
 * @property sprintId 스프린트 식별자.
 * @property name 스프린트 이름.
 * @property startDate 스프린트 시작일. 미설정 스프린트는 null 허용.
 * @property endDate 스프린트 종료일. 미설정 스프린트는 null 허용.
 * @property commitmentSeconds 계획(Commitment) 시간 합계(초).
 * @property completedSeconds 완료(Completed) 시간 합계(초).
 */
data class VelocityPoint(
    val sprintId: UUID,
    val name: String,
    val startDate: LocalDate?,
    val endDate: LocalDate?,
    val commitmentSeconds: Long,
    val completedSeconds: Long,
)
