// 전역 상태 카탈로그 REST 컨트롤러 — 목록·생성·수정·삭제

package com.bts.workflow.status.web

import com.bts.workflow.port.outbound.toUuid
import com.bts.workflow.status.application.StatusCommandService
import com.bts.workflow.status.web.dto.CreateStatusRequest
import com.bts.workflow.status.web.dto.CreatedStatusResponse
import com.bts.workflow.status.web.dto.StatusResponse
import com.bts.workflow.status.web.dto.UpdateStatusRequest
import com.bts.workflow.web.CurrentActor
import com.bts.workflow.web.DataResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * 사이트 전역 상태 카탈로그 REST API.
 *
 * ### 왜 워크플로우 컨트롤러와 나뉘어 있나
 * 카탈로그는 **워크플로우에 종속되지 않는다.** 여러 워크플로우가 같은 상태를 가져다 쓴다
 * (ADR 2026-08-18-workflow-global-status-catalog). 경로도 `/api/v1/statuses` 로 독립이다.
 *
 * ### 읽기는 권한을 묻지 않는다
 * 이슈 화면이 상태 목록을 그리므로 로그인 사용자면 볼 수 있어야 한다
 * (워크플로우 스킴 읽기 게이트 ADR 2026-07-26 과 같은 판단).
 * 쓰기는 [StatusCommandService] 가 전역 권한을 검사한다.
 */
@RestController
@RequestMapping("/api/v1/statuses")
class StatusController(
    private val statusCommandService: StatusCommandService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 살아 있는 상태 전체. 소프트 삭제된 것은 나오지 않는다. */
    @GetMapping
    fun list(): DataResponse<List<StatusResponse>> {
        log.debug("StatusController.list")
        return DataResponse(statusCommandService.list().map(StatusResponse::from))
    }

    /**
     * 상태를 만든다.
     *
     * key 가 살아 있는 상태와 겹치면 409, 이름이 대소문자 무시 기준으로 겹쳐도 409.
     * 소프트 삭제된 key 는 다시 쓸 수 있다(`V206`).
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @RequestBody request: CreateStatusRequest,
    ): DataResponse<CreatedStatusResponse> {
        val actor = CurrentActor.current()
        log.info("StatusController.create key={}", request.key)
        val id = statusCommandService.create(actor.toUuid(), request.toCommand())
        return DataResponse(CreatedStatusResponse(id, request.key))
    }

    /**
     * 이름·설명·카테고리를 고친다.
     *
     * ★ `key` 는 **받지 않는다.** 요청 DTO 에 자리가 없다 — 이슈·보드가 문자열로 참조하는
     * 식별자라 바뀌면 그 참조가 조용히 끊긴다.
     */
    @PutMapping("/{id}")
    fun update(
        @PathVariable id: UUID,
        @RequestBody request: UpdateStatusRequest,
    ): DataResponse<Nothing?> {
        val actor = CurrentActor.current()
        log.info("StatusController.update id={}", id)
        statusCommandService.update(actor.toUuid(), id, request.toCommand())
        return DataResponse(null)
    }

    /**
     * 소프트 삭제한다.
     *
     * 어느 워크플로우가 편성 중이면 409, 시스템 예약 상태여도 409 — 두 원인은 다른 코드로 구분된다.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @PathVariable id: UUID,
    ) {
        val actor = CurrentActor.current()
        log.info("StatusController.delete id={}", id)
        statusCommandService.delete(actor.toUuid(), id)
    }
}
