// Workflow REST 응답 DTO — 도메인 Aggregate → 계층 구조 직렬화 (mermaid 다이어그램 입력)

package com.bts.workflow.web.dto

import com.bts.workflow.domain.Workflow
import com.bts.workflow.domain.WorkflowState
import com.bts.workflow.domain.WorkflowTransition

/**
 * [Workflow] Aggregate Root 의 REST 응답 DTO.
 *
 * 도메인 객체를 직접 직렬화하지 않고 이 DTO 로 변환하여 반환한다.
 * 컨트롤러 레이어에서 [Workflow.toDto] extension 함수를 통해 생성한다.
 *
 * @property key 워크플로우 식별 키. 시스템 전역에서 고유.
 * @property name 사람이 읽을 수 있는 워크플로우 이름.
 * @property description 워크플로우 설명. 다이어그램 위 헤더 표시용. null 가능.
 * @property states 이 워크플로우가 포함하는 상태 목록.
 * @property transitions 이 워크플로우가 허용하는 전이 목록.
 */
data class WorkflowDto(
    val key: String,
    val name: String,
    val description: String?,
    val states: List<WorkflowStateDto>,
    val transitions: List<WorkflowTransitionDto>,
)

/**
 * [WorkflowState] 의 REST 응답 DTO.
 *
 * @property key 상태 식별 키. [WorkflowDto] 내에서 고유.
 * @property name 사람이 읽을 수 있는 상태 이름.
 * @property category 상위 카테고리 문자열 ("TODO" / "IN_PROGRESS" / "DONE").
 * @property displayOrder 보드/목록에서 표시되는 순서. 작을수록 앞.
 */
data class WorkflowStateDto(
    val key: String,
    val name: String,
    val category: String,
    val displayOrder: Int,
)

/**
 * [WorkflowTransition] 의 REST 응답 DTO.
 *
 * @property key 전이 고유 식별 키. fromStateKey__toStateKey 합성. 향후 라우팅/API 호출용.
 * @property fromStateKey 전이 출발 상태의 키.
 * @property toStateKey 전이 도착 상태의 키.
 * @property name 전이 이름. 예: "시작", "완료", "재열기".
 */
data class WorkflowTransitionDto(
    val key: String,
    val fromStateKey: String,
    val toStateKey: String,
    val name: String,
)

/**
 * [Workflow] 도메인 Aggregate 를 [WorkflowDto] REST 응답 형태로 변환한다.
 */
fun Workflow.toDto(): WorkflowDto =
    WorkflowDto(
        key = key,
        name = name,
        description = description,
        states = states.map { it.toDto() },
        transitions = transitions.map { it.toDto() },
    )

/**
 * [WorkflowState] 도메인 객체를 [WorkflowStateDto] 로 변환한다.
 *
 * [category] 는 enum 이름 문자열로 직렬화한다 (예: "TODO", "IN_PROGRESS", "DONE").
 */
fun WorkflowState.toDto(): WorkflowStateDto =
    WorkflowStateDto(
        key = key,
        name = name,
        category = category.name,
        displayOrder = displayOrder,
    )

/**
 * [WorkflowTransition] 도메인 객체를 [WorkflowTransitionDto] 로 변환한다.
 */
fun WorkflowTransition.toDto(): WorkflowTransitionDto =
    WorkflowTransitionDto(
        key = key,
        fromStateKey = fromStateKey,
        toStateKey = toStateKey,
        name = name,
    )
