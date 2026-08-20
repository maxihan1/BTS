// Workflow REST 응답 DTO — 도메인 Aggregate → 계층 구조 직렬화 (mermaid 다이어그램 입력)

package com.bts.workflow.web.dto

import com.bts.workflow.domain.Workflow
import com.bts.workflow.domain.WorkflowState
import com.bts.workflow.domain.WorkflowTransition
import java.util.UUID

/**
 * [Workflow] Aggregate Root 의 REST 응답 DTO.
 *
 * 도메인 객체를 직접 직렬화하지 않고 이 DTO 로 변환하여 반환한다.
 * 컨트롤러 레이어에서 [Workflow.toDto] extension 함수를 통해 생성한다.
 *
 * @property key 워크플로우 식별 키. 시스템 전역에서 고유.
 * @property name 사람이 읽을 수 있는 워크플로우 이름.
 * @property description 워크플로우 설명. 다이어그램 위 헤더 표시용.
 *   도메인 [Workflow.description] 이 null 인 경우 빈 문자열로 흡수한다.
 *   프론트엔드 Zod schema (required string) 와의 타입 일치를 보장한다.
 * @property states 이 워크플로우가 포함하는 상태 목록.
 * @property transitions 이 워크플로우가 허용하는 전환 목록.
 */
data class WorkflowDto(
    val key: String,
    val name: String,
    val description: String,
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
 * ### 쓰기 API 응답과 같은 6필드다
 * `POST/PUT /api/v1/workflows/{key}/transitions` 가 돌려주는
 * [com.bts.workflow.web.dto.TransitionResponse] 와 필드 이름·형태가 같다. 두 모양이 갈리면
 * 프론트가 전환 스키마를 두 벌 유지해야 하고, 그 두 벌은 서로를 보지 않아 조용히 어긋난다.
 *
 * ### 왜 [id]·[kind] 가 **뒤에** 붙었나
 * 종전 4필드의 자리를 그대로 둔다 — 필드 삭제·개명 0 이 이 변경의 제약이다(FR-WF-05 N1).
 * 도메인 [WorkflowTransition] 도 같은 이유로 [id]·[kind] 를 뒤에 뒀다(그 클래스 KDoc).
 * JSON 필드 **순서**는 계약이 아니다 — 계약은 필드 이름·타입·nullability 다.
 *
 * @property key 전환 고유 식별 키. fromStateKey__toStateKey 합성. 향후 라우팅/API 호출용.
 * @property fromStateKey 전환 출발 상태의 키. 출발 상태가 없는 전환(GLOBAL·INITIAL)은 null 이다.
 *   **생략이 아니라 null 로 실린다** — 프론트 Zod 는 이 필드를 nullable 로 받아야 한다.
 * @property toStateKey 전환 도착 상태의 키.
 * @property name 전환 이름. 예: "시작", "완료", "재열기". 사람 친화 라벨이라 매칭에 쓰지 않는다.
 * @property id 전환 1급 식별자(`workflow_transitions.id`). 전환을 실행할 때 `transitionId` 로
 *   지목하는 값이다 — 같은 상태쌍에 전환이 여럿이면 [key] 로는 하나를 고를 수 없다.
 * @property kind 전환 종류 문자열. `NORMAL`·`GLOBAL`·`INITIAL`.
 */
data class WorkflowTransitionDto(
    val key: String,
    val fromStateKey: String?,
    val toStateKey: String,
    val name: String,
    val id: UUID,
    val kind: String,
)

/**
 * [Workflow] 도메인 Aggregate 를 [WorkflowDto] REST 응답 형태로 변환한다.
 */
fun Workflow.toDto(): WorkflowDto =
    WorkflowDto(
        key = key,
        name = name,
        description = description ?: "",
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
        id = id,
        kind = kind.name,
    )
