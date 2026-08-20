// 워크플로우↔상태 편성 REST 컨트롤러 — 추가·제거·순서변경

package com.bts.workflow.web

import com.bts.workflow.application.WorkflowStatusCompositionService
import com.bts.workflow.port.outbound.toUuid
import com.bts.workflow.web.dto.AddWorkflowStatusRequest
import com.bts.workflow.web.dto.ReorderWorkflowStatusesRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 워크플로우에 상태를 넣고 빼고 순서를 바꾸는 API.
 *
 * ### 왜 [WorkflowController] 와 나뉘어 있나
 * 경로가 `/api/v1/workflows/{key}/statuses` **하위**라 자원이 다르다(워크플로우가 아니라 편성).
 * 게이트 1 리뷰가 「분리는 하위 경로 단위로 하는 것이 자연스럽다」고 판정했고,
 * 워크플로우 컨트롤러가 이미 300줄 한도(`DEVELOPMENT.md §2.1`)에 근접했다.
 *
 * 로드맵 PR 4~6 이 전환·규칙·초안을 붙일 때도 같은 기준으로 나눈다.
 */
@RestController
@RequestMapping("/api/v1/workflows/{key}/statuses")
class WorkflowStatusCompositionController(
    private val compositionService: WorkflowStatusCompositionService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 카탈로그의 상태를 이 워크플로우에 편성한다. 이미 있으면 표시 순서만 갱신한다. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun addStatus(
        @PathVariable key: String,
        @RequestBody request: AddWorkflowStatusRequest,
    ) {
        val actor = CurrentActor.current()
        log.info("WorkflowStatusCompositionController.addStatus workflow={} status={}", key, request.statusId)
        compositionService.addStatus(actor.toUuid(), key, request.statusId, request.displayOrder)
    }

    /**
     * 편성을 뗀다. 카탈로그의 상태 자체는 남는다.
     *
     * 마지막 상태면 400, 이슈가 쓰고 있으면 409.
     */
    @DeleteMapping("/{statusId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun removeStatus(
        @PathVariable key: String,
        @PathVariable statusId: UUID,
    ) {
        val actor = CurrentActor.current()
        log.info("WorkflowStatusCompositionController.removeStatus workflow={} status={}", key, statusId)
        compositionService.removeStatus(actor.toUuid(), key, statusId)
    }

    /** 표시 순서를 통째로 다시 정한다. 요청은 그 워크플로우의 상태 전부를 담아야 한다. */
    @PutMapping("/order")
    fun reorder(
        @PathVariable key: String,
        @RequestBody request: ReorderWorkflowStatusesRequest,
    ): DataResponse<Nothing?> {
        val actor = CurrentActor.current()
        log.info("WorkflowStatusCompositionController.reorder workflow={} count={}", key, request.statusIds.size)
        compositionService.reorder(actor.toUuid(), key, request.statusIds)
        return DataResponse(null)
    }
}
