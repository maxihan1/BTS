// 워크플로우 쓰기 API 의 요청 DTO — 커맨드 경계에서 받는 것만 담는다

package com.bts.workflow.web.dto

import com.bts.workflow.application.command.CreateWorkflowCommand
import com.bts.workflow.application.command.TransitionDefinitionCommand
import com.bts.workflow.application.command.UpdateWorkflowCommand
import com.bts.workflow.application.command.WorkflowStatusSeed
import com.bts.workflow.domain.WorkflowTransition
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.util.UUID

/** 새 워크플로우에 처음 편성할 상태. `key` 가 카탈로그에 있으면 재사용한다. */
data class WorkflowStatusSeedRequest(
    @field:NotBlank
    @field:Size(max = 50)
    val key: String,
    @field:NotBlank
    @field:Size(max = 100)
    val name: String,
    @field:Pattern(regexp = "TODO|IN_PROGRESS|DONE")
    val category: String,
    val displayOrder: Int,
)

/**
 * 워크플로우 생성 요청.
 *
 * [statuses] 가 비면 400 이다 — `Workflow.of()` invariant 가 「상태 하나 이상」을 요구해
 * 상태 없는 워크플로우는 만들자마자 조회가 죽는다.
 */
data class CreateWorkflowRequest(
    @field:NotBlank
    @field:Size(max = 100)
    val key: String,
    @field:NotBlank
    @field:Size(max = 200)
    val name: String,
    val description: String? = null,
    @field:NotEmpty
    @field:Valid
    val statuses: List<WorkflowStatusSeedRequest>,
) {
    fun toCommand(): CreateWorkflowCommand =
        CreateWorkflowCommand(
            key = key,
            name = name,
            description = description,
            statuses = statuses.map { WorkflowStatusSeed(it.key, it.name, it.category, it.displayOrder) },
        )
}

/**
 * 워크플로우 수정 요청.
 *
 * ★ `key` 를 **받지 않는다.** 이슈·자동화·검색이 문자열로 참조하는 식별자라 바뀌면 조용히 끊긴다
 * (ADR 2026-08-18-workflow-global-status-catalog 의 키 불변 원칙).
 */
data class UpdateWorkflowRequest(
    @field:NotBlank
    @field:Size(max = 200)
    val name: String,
    val description: String? = null,
) {
    fun toCommand(): UpdateWorkflowCommand = UpdateWorkflowCommand(name = name, description = description)
}

/** 워크플로우 복제 요청. 상태 편성과 전환을 함께 복사한다. */
data class DuplicateWorkflowRequest(
    @field:NotBlank
    @field:Size(max = 100)
    val key: String,
    @field:NotBlank
    @field:Size(max = 200)
    val name: String,
)

/**
 * 전환 정의 요청. 생성(`POST`)과 수정(`PUT`)이 같은 바디를 쓴다 — `PUT` 은 표현 전체 교체다.
 *
 * ### 왜 `@field:NotBlank` 가 없는가
 * 이 모듈에는 `jakarta.validation-api` 만 있고 **구현체(hibernate-validator)가 없다**. 어노테이션을
 * 달아도 아무도 읽지 않아 「검증하는 것처럼 보이는」 장식이 된다(MEMORY
 * `decorative-annotation-copied-from-sibling`). 실제 판정은 `WorkflowCommandService` 가 지고,
 * 위반은 `WORKFLOW_INVALID_REQUEST`(400)로 나간다.
 *
 * @property fromStatusKey 출발 상태 키. `kind` 가 `GLOBAL`·`INITIAL` 이면 **보내지 않는다**
 * @property toStatusKey 도착 상태 키
 * @property name 사람 친화 표시 라벨
 * @property kind `NORMAL`(기본)·`GLOBAL`·`INITIAL`
 */
data class TransitionDefinitionRequest(
    val toStatusKey: String,
    val name: String,
    val fromStatusKey: String? = null,
    val kind: String? = null,
) {
    fun toCommand(): TransitionDefinitionCommand =
        TransitionDefinitionCommand(
            fromStatusKey = fromStatusKey,
            toStatusKey = toStatusKey,
            name = name,
            kind = kind,
        )
}

/**
 * 전환 정의 응답.
 *
 * 필드 이름을 **읽기 API 와 맞춘다** — `GET /api/v1/workflows/{key}` 의 `transitions[]` 원소와 같은
 * 모양이라 프론트가 전환 스키마를 하나만 유지한다. 요청 바디만 `fromStatusKey`/`toStatusKey` 인 것은
 * spec §API 계약이 그렇게 못박았기 때문이다.
 *
 * @property id 전환 1급 식별자. 실행 시 `transitionId` 로 지목하는 값이다
 * @property key 하위호환 계산 키(`from__to`). 매칭 기준이 아니다
 * @property kind `NORMAL`·`GLOBAL`·`INITIAL`
 * @property fromStateKey 출발 상태 키. `GLOBAL`·`INITIAL` 은 null
 * @property toStateKey 도착 상태 키
 * @property name 표시 라벨
 */
data class TransitionResponse(
    val id: UUID,
    val key: String,
    val kind: String,
    val fromStateKey: String?,
    val toStateKey: String,
    val name: String,
)

/** 도메인 전환을 응답 DTO 로 옮긴다. */
fun WorkflowTransition.toTransitionResponse(): TransitionResponse =
    TransitionResponse(
        id = id,
        key = key,
        kind = kind.name,
        fromStateKey = fromStateKey,
        toStateKey = toStateKey,
        name = name,
    )
