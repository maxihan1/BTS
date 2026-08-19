// 상태 편성 예외 → HTTP 상태 매핑 — 컨트롤러 경계와 같은 단위로 나눈다

package com.bts.workflow.web

import com.bts.workflow.domain.exception.WorkflowStatusCompositionException
import com.bts.workflow.domain.exception.WorkflowStatusInUseException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * [WorkflowStatusCompositionController] 전용 예외 핸들러.
 *
 * ### 왜 [WorkflowExceptionHandler] 와 나뉘어 있나
 * 그쪽이 함수 한도(detekt `TooManyFunctions` = 11)에 닿았고, 컨트롤러가 이미 하위 경로 단위로
 * 나뉘어 있다. 핸들러를 **컨트롤러와 같은 경계**로 두면 어느 쪽에 추가할지가 자명해진다.
 */
@RestControllerAdvice(assignableTypes = [WorkflowStatusCompositionController::class])
class WorkflowStatusCompositionExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 편성 요청 위반 — 400. 마지막 상태 제거 · 집합 불일치 · 남의 상태 혼입 · 중복. */
    @ExceptionHandler(WorkflowStatusCompositionException::class)
    fun handleComposition(ex: WorkflowStatusCompositionException): ResponseEntity<ErrorResponse> {
        log.info("WORKFLOW_400_COMPOSITION key='{}' reason='{}'", ex.workflowKey, ex.reason)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ErrorResponse(error = ErrorBody(code = "WORKFLOW_STATUS_COMPOSITION_INVALID", message = ex.reason)),
        )
    }

    /** 이슈가 쓰는 상태를 빼려 함 — 409. 일괄 이관은 로드맵 PR 10 의 마법사가 한다. */
    @ExceptionHandler(WorkflowStatusInUseException::class)
    fun handleStatusInUse(ex: WorkflowStatusInUseException): ResponseEntity<ErrorResponse> {
        log.info("WORKFLOW_409_STATUS_IN_USE workflow='{}' status='{}'", ex.workflowKey, ex.statusKey)
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            ErrorResponse(
                error =
                    ErrorBody(
                        code = "WORKFLOW_STATUS_IN_USE",
                        message = "이 상태에 있는 이슈가 ${ex.issueCount}건입니다. 이슈를 다른 상태로 옮긴 뒤 빼 주세요.",
                    ),
            ),
        )
    }
}
