// 워크플로우 쓰기 API 의 요청 DTO — 커맨드 경계에서 받는 것만 담는다

package com.bts.workflow.web.dto

import com.bts.workflow.application.command.CreateWorkflowCommand
import com.bts.workflow.application.command.UpdateWorkflowCommand
import com.bts.workflow.application.command.WorkflowStatusSeed
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

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
