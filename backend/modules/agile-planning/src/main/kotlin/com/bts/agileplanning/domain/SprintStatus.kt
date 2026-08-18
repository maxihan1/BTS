// 스프린트 상태 열거형 — 허용 전환 규칙을 보유하는 FSM 노드

package com.bts.agileplanning.domain

/**
 * 스프린트 상태 열거형.
 *
 * 각 상태가 allowedTransitions 프로퍼티로 전환 가능한 다음 상태 집합을 선언한다.
 * 전환 검증은 Sprint 애그리게이트가 이 규칙을 참조해 수행한다.
 *
 * ### 전환 규칙
 * - PLANNED: ACTIVE 로만 전환 가능.
 * - ACTIVE: COMPLETED 로만 전환 가능.
 * - COMPLETED: 전환 불허. 종료 상태.
 *
 * @property allowedTransitions 이 상태에서 전환 가능한 다음 상태 집합.
 */
enum class SprintStatus {
    /** 계획 단계. 아직 시작되지 않은 스프린트. */
    PLANNED,

    /** 진행 중. 팀이 스프린트 목표를 향해 작업하는 상태. */
    ACTIVE,

    /** 완료. 스프린트가 종료된 최종 상태. 재활성화 불가. */
    COMPLETED,
    ;

    /**
     * 이 상태에서 전환 가능한 다음 상태 집합.
     *
     * enum 초기화 순환참조를 피하기 위해 lazy 위임으로 구현한다.
     */
    val allowedTransitions: Set<SprintStatus> by lazy {
        when (this) {
            PLANNED -> setOf(ACTIVE)
            ACTIVE -> setOf(COMPLETED)
            COMPLETED -> emptySet()
        }
    }

    /**
     * 대상 상태로의 전환이 허용되는지 반환한다.
     *
     * @param target 전환을 시도할 목표 상태.
     * @return 허용되면 true, 아니면 false.
     */
    fun canTransitionTo(target: SprintStatus): Boolean = target in allowedTransitions
}
