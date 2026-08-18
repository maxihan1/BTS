// 스프린트 애그리게이트 루트 — 기간 기반 반복 계획 단위

package com.bts.agileplanning.domain

import java.time.LocalDate
import java.util.UUID

/**
 * 스프린트 상태 전환 위반 예외.
 *
 * Sprint.start() 또는 Sprint.complete() 가 허용되지 않는 현재 상태에서 호출될 때 던진다.
 *
 * @property message 오류 설명 메시지.
 */
class InvalidSprintTransitionException(message: String) : IllegalStateException(message)

/**
 * 스프린트 애그리게이트 루트.
 *
 * 정해진 기간(startDate ~ endDate) 동안 백로그 아이템을 처리하는 반복 계획 단위.
 * PLANNED -> ACTIVE -> COMPLETED 단방향 FSM 으로 전환한다.
 *
 * ### BC 격리
 * projectKey 는 문자열로 보관한다. issue-tracking 또는 project-workflow BC 를 직접 import 하지 않는다.
 *
 * ### 불변 계약
 * - name 은 공백만 있으면 안 된다.
 * - startDate 와 endDate 가 모두 존재할 때 startDate 는 endDate 이하여야 한다.
 * - start()/complete() 는 새 Sprint 를 반환한다. 원본 객체는 변경되지 않는다.
 *
 * @property id 스프린트 UUID (PK).
 * @property projectKey 소속 프로젝트 키. BC 격리 목적으로 문자열 보관. 예: "ATLAS".
 * @property name 스프린트 표시 이름.
 * @property goal 스프린트 목표 설명. null 허용.
 * @property status 현재 상태. PLANNED / ACTIVE / COMPLETED.
 * @property startDate 스프린트 시작일. null 이면 미지정.
 * @property endDate 스프린트 종료일. null 이면 미지정.
 * @property version 낙관적 잠금 버전.
 */
data class Sprint(
    val id: UUID,
    val projectKey: String,
    val name: String,
    val goal: String?,
    val status: SprintStatus,
    val startDate: LocalDate?,
    val endDate: LocalDate?,
    val version: Long,
) {
    init {
        require(name.isNotBlank()) { "Sprint.name must not be blank." }
        if (startDate != null && endDate != null) {
            require(!startDate.isAfter(endDate)) {
                "Sprint.startDate ($startDate) must not be after endDate ($endDate)."
            }
        }
    }

    /**
     * 스프린트를 시작한다.
     *
     * 현재 상태가 PLANNED 일 때만 허용된다.
     * ACTIVE 또는 COMPLETED 상태에서 호출하면 InvalidSprintTransitionException 을 던진다.
     *
     * @return ACTIVE 상태의 새 Sprint 인스턴스.
     * @throws InvalidSprintTransitionException 전환이 허용되지 않는 상태에서 호출된 경우.
     */
    fun start(): Sprint {
        if (!status.canTransitionTo(SprintStatus.ACTIVE)) {
            throw InvalidSprintTransitionException(
                "Cannot start sprint in status $status. " +
                    "Allowed transitions: ${status.allowedTransitions}.",
            )
        }
        return copy(status = SprintStatus.ACTIVE)
    }

    /**
     * 스프린트를 완료 처리한다.
     *
     * 현재 상태가 ACTIVE 일 때만 허용된다.
     * PLANNED 또는 COMPLETED 상태에서 호출하면 InvalidSprintTransitionException 을 던진다.
     *
     * @return COMPLETED 상태의 새 Sprint 인스턴스.
     * @throws InvalidSprintTransitionException 전환이 허용되지 않는 상태에서 호출된 경우.
     */
    fun complete(): Sprint {
        if (!status.canTransitionTo(SprintStatus.COMPLETED)) {
            throw InvalidSprintTransitionException(
                "Cannot complete sprint in status $status. " +
                    "Allowed transitions: ${status.allowedTransitions}.",
            )
        }
        return copy(status = SprintStatus.COMPLETED)
    }
}
