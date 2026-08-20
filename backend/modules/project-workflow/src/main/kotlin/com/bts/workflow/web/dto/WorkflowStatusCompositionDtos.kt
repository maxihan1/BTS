// 워크플로우↔상태 편성 API 요청 DTO

package com.bts.workflow.web.dto

import jakarta.validation.constraints.NotEmpty
import java.util.UUID

/** 워크플로우에 상태를 편성하는 요청. `statusId` 는 전역 카탈로그의 상태다. */
data class AddWorkflowStatusRequest(
    val statusId: UUID,
    val displayOrder: Int,
)

/**
 * 표시 순서를 통째로 다시 정하는 요청.
 *
 * ★ 그 워크플로우의 상태 **전부**를 정확히 담아야 한다. 부분 목록이면 빠진 상태의 순서가
 * 암묵적으로 정해지고, 그 규칙을 사람이 기억해야 하는 순간 화면과 서버가 갈라진다.
 */
data class ReorderWorkflowStatusesRequest(
    @field:NotEmpty
    val statusIds: List<UUID>,
)
