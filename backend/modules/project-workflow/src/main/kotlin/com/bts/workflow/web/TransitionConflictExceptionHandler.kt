// TransitionConflictException 을 HTTP 409 + 이 BC 표준 오류 본문으로 매핑하는 전용 단일 타입 핸들러

package com.bts.workflow.web

import com.bts.workflow.domain.exception.TransitionConflictException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * [TransitionConflictException] → HTTP 409(Conflict) 전용 매핑 핸들러.
 *
 * ## 왜 [WorkflowExceptionHandler] 에 얹지 않는가 (되돌리지 마라)
 * 그 advice 는 이미 detekt `TooManyFunctions`(한도 11)에 닿아 있고, 같은 이유로
 * [WorkflowStatusCompositionExceptionHandler] 가 갈라져 나온 선례가 있다. 임계값을 완화하는 대신
 * **예외 한 종류만 잡는 advice** 를 하나 더 두는 쪽이 이 모듈의 관례다.
 *
 * ## 왜 `@Order` 가 없는가 (형제인 [AmbiguousTransitionExceptionHandler] 와 다른 점)
 * 그쪽은 예외가 **issue-tracking** 의 `IssueController` 경로에서 표면화돼
 * `com.bts.issue.adapter.inbound.rest.IssueExceptionHandler` 의 catch-all
 * `@ExceptionHandler(Exception::class)` 에 먼저 삼켜져 500 이 되는 것이 실측됐고, 그래서
 * `@Order(HIGHEST_PRECEDENCE)` 가 **필요했다**.
 *
 * 이 예외는 그 조건에 해당하지 않는다 — 던지는 곳이
 * `WorkflowCommandService.createTransition`/`deleteTransition` 뿐이고 그 둘을 부르는 컨트롤러는
 * [WorkflowController](`com.bts.workflow.web`) 하나다(실측 — 레포 전역 호출자 grep).
 * 그 패키지를 덮는 catch-all advice 는 없다. 레포의 catch-all 은 전부
 * `basePackages`/`assignableTypes` 로 자기 BC 에 묶여 있고, `com.bts.workflow.web` 를 덮는 유일한
 * 전역 advice 인 [WorkflowExceptionHandler] 에는 catch-all 자체가 없다.
 * 필요 없는 전역 우선권은 **다른 advice 의 매핑을 빼앗아** 응답 형식을 조용히 바꾸므로 붙이지 않는다.
 * (그 사고 형태의 정본 설명은 [AmbiguousTransitionExceptionHandler] KDoc 에 있다.)
 * 나중에 이 예외를 다른 BC 컨트롤러 경로에서 표면화시키려거든 **그때 실측하고** 우선권을 붙여라.
 */
@RestControllerAdvice
class TransitionConflictExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 최초 전환 규칙 충돌 — 409.
     *
     * 두 자리에서 온다 — 최초 전환을 두 개째 만들려 할 때(spec E2), 하나뿐인 최초 전환을 지우려
     * 할 때(spec E5). 어느 쪽인지는 `code` 가 아니라 `message` 가 가른다. 호출자가 할 일(기존 최초
     * 전환을 고치거나 지운다 · 삭제를 포기한다)이 다르므로 사유를 사람 말 그대로 돌려준다.
     *
     * @param ex 충돌이 난 워크플로우 키와 사유를 담은 예외.
     * @return 409 + `WORKFLOW_TRANSITION_CONFLICT` + 사유. 워크플로우 키는 로그로만 남긴다.
     */
    @ExceptionHandler(TransitionConflictException::class)
    fun handleTransitionConflict(ex: TransitionConflictException): ResponseEntity<ErrorResponse> {
        log.info("WORKFLOW_409_TRANSITION_CONFLICT key='{}' reason='{}'", ex.workflowKey, ex.reason)
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            ErrorResponse(error = ErrorBody(code = "WORKFLOW_TRANSITION_CONFLICT", message = ex.reason)),
        )
    }
}
