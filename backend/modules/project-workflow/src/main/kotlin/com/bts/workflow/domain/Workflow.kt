// FSM 워크플로우 Aggregate Root — invariant 검증된 상태/전이 컬렉션

package com.bts.workflow.domain

/**
 * FSM(유한 상태 기계) 워크플로우 Aggregate Root.
 *
 * 직접 생성자 호출을 막고 companion factory [of]를 통해서만 생성한다.
 * factory가 4가지 invariant를 검증하므로 인스턴스가 존재하면 항상 일관된 상태임을 보장한다.
 *
 * @property key 워크플로우 식별 키. 시스템 전역에서 고유.
 * @property name 사람이 읽을 수 있는 워크플로우 이름.
 * @property description 워크플로우 설명. 관리자용. null 허용 (DB nullable 컬럼과 일치).
 * @property states 이 워크플로우가 포함하는 상태 목록. 비어 있을 수 없으며 key 중복 불가.
 * @property transitions 이 워크플로우가 허용하는 전이 목록. from/to 키는 모두 [states] 집합에 포함돼야 한다.
 */
data class Workflow private constructor(
    val key: String,
    val name: String,
    val description: String?,
    val states: List<WorkflowState>,
    val transitions: List<WorkflowTransition>,
) {
    companion object {
        /**
         * Workflow 인스턴스 생성 factory.
         *
         * 검증하는 invariant.
         * 1. [states] 가 비어 있지 않아야 한다.
         * 2. [states] 내 key 중복이 0건이어야 한다.
         * 3. 모든 [transitions]의 [WorkflowTransition.fromStateKey] 가 [states] 키 집합 안에 있어야 한다.
         * 4. 모든 [transitions]의 [WorkflowTransition.toStateKey] 가 [states] 키 집합 안에 있어야 한다.
         * 5. [transitions] 내 (fromStateKey, toStateKey) 조합 중복이 0건이어야 한다.
         *    name 이 달라도 (from, to) 가 같으면 중복으로 간주한다.
         *
         * @throws IllegalArgumentException 위 invariant 중 하나라도 위반 시
         */
        fun of(
            key: String,
            name: String,
            description: String? = null,
            states: List<WorkflowState>,
            transitions: List<WorkflowTransition>,
        ): Workflow {
            require(states.isNotEmpty()) {
                "Workflow '$key': states must not be empty"
            }

            val stateKeys = states.map { it.key }
            val duplicateStateKeys = stateKeys.groupBy { it }.filter { it.value.size > 1 }.keys
            require(duplicateStateKeys.isEmpty()) {
                "Workflow '$key': duplicate state keys found — $duplicateStateKeys"
            }

            val stateKeySet = stateKeys.toSet()

            val invalidFromKeys = transitions.filter { it.fromStateKey !in stateKeySet }.map { it.fromStateKey }
            require(invalidFromKeys.isEmpty()) {
                "Workflow '$key': transition fromStateKey not in states — $invalidFromKeys"
            }

            val invalidToKeys = transitions.filter { it.toStateKey !in stateKeySet }.map { it.toStateKey }
            require(invalidToKeys.isEmpty()) {
                "Workflow '$key': transition toStateKey not in states — $invalidToKeys"
            }

            val transitionKeys = transitions.map { it.fromStateKey to it.toStateKey }
            val duplicateTransitions = transitionKeys.groupBy { it }.filter { it.value.size > 1 }.keys
            require(duplicateTransitions.isEmpty()) {
                "Workflow '$key': duplicate transition (from, to) combinations found — $duplicateTransitions"
            }

            return Workflow(
                key = key,
                name = name,
                description = description,
                states = states,
                transitions = transitions,
            )
        }
    }
}
