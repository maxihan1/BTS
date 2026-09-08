// FSM 워크플로우 Aggregate Root — invariant 검증된 상태/전환 컬렉션

package com.bts.workflow.domain

import java.util.UUID

/**
 * FSM(유한 상태 기계) 워크플로우 Aggregate Root.
 *
 * 직접 생성자 호출을 막고 companion factory [of]를 통해서만 생성한다.
 * factory가 6가지 invariant를 검증하므로 인스턴스가 존재하면 항상 일관된 상태임을 보장한다.
 *
 * @property key 워크플로우 식별 키. 시스템 전역에서 고유.
 * @property name 사람이 읽을 수 있는 워크플로우 이름.
 * @property description 워크플로우 설명. 관리자용. null 허용 (DB nullable 컬럼과 일치).
 * @property states 이 워크플로우가 포함하는 상태 목록. 비어 있을 수 없으며 key 중복 불가.
 * @property transitions 이 워크플로우가 허용하는 전환 목록. to 키는 [states] 집합에 포함돼야 하고,
 *   from 키는 [TransitionKind.NORMAL] 일 때만 존재하며 역시 [states] 집합에 포함돼야 한다.
 */
data class Workflow private constructor(
    val key: String,
    val name: String,
    val description: String?,
    val states: List<WorkflowState>,
    val transitions: List<WorkflowTransition>,
    /**
     * 소유 프로젝트 `projects.id`. null = 전역 공유 워크플로우 (FR-WF-08).
     *
     * ★**읽기 전용 정보다.** 소유를 실제로 정하는 자리는
     * [com.bts.workflow.repository.WorkflowWriteRepository.insertWorkflow] 이고 그쪽은 기본값이
     * 없다 — 쓰기 경로가 이 factory 를 타지 않기 때문이다(PR ② 실측). 여기 기본값 `null` 은
     * 「소유를 모르는 채로 조립한 aggregate 는 전역으로 읽는다」는 뜻이고, 호출부 대부분이
     * 전역이 정답인 테스트 픽스처다.
     */
    val projectId: UUID? = null,
) {
    companion object {
        /**
         * Workflow 인스턴스 생성 factory.
         *
         * 검증하는 invariant.
         * 1. [states] 가 비어 있지 않아야 한다.
         * 2. [states] 내 key 중복이 0건이어야 한다.
         * 3. [WorkflowTransition.fromStateKey] 가 null 이 아닌 전환은 그 키가 [states] 키 집합 안에 있어야 한다.
         * 4. 모든 [transitions]의 [WorkflowTransition.toStateKey] 가 [states] 키 집합 안에 있어야 한다.
         * 5. [TransitionKind.NORMAL] 전환은 [WorkflowTransition.fromStateKey] 가 null 이 아니어야 하고,
         *    [TransitionKind.GLOBAL]·[TransitionKind.INITIAL] 전환은 null 이어야 한다.
         * 6. [TransitionKind.INITIAL] 전환은 워크플로우당 최대 1개여야 한다.
         *
         * 구 invariant 「(fromStateKey, toStateKey) 조합 중복 금지」는 삭제됐다 — 전환 identity 가
         * [WorkflowTransition.id] 로 옮겨가 같은 상태쌍에 이름이 다른 전환을 여럿 둘 수 있다.
         * 그 자리는 5·6 이 대신한다. 「모호하면 런타임이 아니라 정의 시점에 막는다」는 정신은 유지된다
         * (ADR `docs/adr/2026-08-18-workflow-transition-id-identity.md` §D1 · §D4).
         *
         * @throws IllegalArgumentException 위 invariant 중 하나라도 위반 시
         *
         * @suppress LongParameterList — aggregate 의 필드 6개를 그대로 받는 factory 다.
         * 파라미터 객체로 묶으면 「Workflow 를 만들려면 WorkflowSpec 을 먼저 만든다」가 되어
         * 호출부 75곳이 한 겹 더 깊어지기만 한다. 전역 임계값은 건드리지 않는다.
         */
        @Suppress("LongParameterList")
        fun of(
            key: String,
            name: String,
            description: String? = null,
            states: List<WorkflowState>,
            transitions: List<WorkflowTransition>,
            projectId: UUID? = null,
        ): Workflow {
            require(states.isNotEmpty()) {
                "Workflow '$key': states must not be empty"
            }

            val stateKeys = states.map { it.key }
            val duplicateStateKeys = stateKeys.groupBy { it }.filter { it.value.size > 1 }.keys
            require(duplicateStateKeys.isEmpty()) {
                "Workflow '$key': duplicate state keys found — $duplicateStateKeys"
            }

            requireValidTransitions(key, stateKeys.toSet(), transitions)

            return Workflow(
                key = key,
                name = name,
                description = description,
                states = states,
                transitions = transitions,
                projectId = projectId,
            )
        }

        /**
         * 전환 목록의 invariant 검증. [of] 가 states 검증을 마친 뒤 호출한다.
         *
         * @param key 오류 메시지에 실을 워크플로우 키.
         * @param stateKeySet 이 워크플로우가 가진 상태 키 집합.
         * @param transitions 검증 대상 전환 목록.
         * @throws IllegalArgumentException invariant 3~6 중 하나라도 위반 시
         */
        private fun requireValidTransitions(
            key: String,
            stateKeySet: Set<String>,
            transitions: List<WorkflowTransition>,
        ) {
            val invalidFromKeys = transitions.mapNotNull { it.fromStateKey }.filter { it !in stateKeySet }
            require(invalidFromKeys.isEmpty()) {
                "Workflow '$key': transition fromStateKey not in states — $invalidFromKeys"
            }

            val invalidToKeys = transitions.filter { it.toStateKey !in stateKeySet }.map { it.toStateKey }
            require(invalidToKeys.isEmpty()) {
                "Workflow '$key': transition toStateKey not in states — $invalidToKeys"
            }

            val normalWithoutFrom =
                transitions.filter { it.kind == TransitionKind.NORMAL && it.fromStateKey == null }
            require(normalWithoutFrom.isEmpty()) {
                "Workflow '$key': NORMAL transition requires non-null fromStateKey — " +
                    "${normalWithoutFrom.map { it.name }}"
            }

            val originlessWithFrom =
                transitions.filter { it.kind != TransitionKind.NORMAL && it.fromStateKey != null }
            require(originlessWithFrom.isEmpty()) {
                "Workflow '$key': GLOBAL/INITIAL transition must have null fromStateKey — " +
                    "${originlessWithFrom.map { it.name }}"
            }

            val initialCount = transitions.count { it.kind == TransitionKind.INITIAL }
            require(initialCount <= 1) {
                "Workflow '$key': at most one INITIAL transition allowed — found $initialCount"
            }
        }
    }
}
