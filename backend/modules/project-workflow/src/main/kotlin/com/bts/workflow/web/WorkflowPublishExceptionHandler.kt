// 발행 충돌 2종을 HTTP 409 + 이 BC 표준 오류 본문으로 매핑하는 전용 핸들러

package com.bts.workflow.web

import com.bts.workflow.domain.exception.WorkflowDraftNotFoundException
import com.bts.workflow.domain.exception.WorkflowPublishMappingRequiredException
import com.bts.workflow.domain.exception.WorkflowVersionConflictException
import com.bts.workflow.validator.web.TransitionRuleFrameworkErrors
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * 발행 경로의 409 두 종류를 매핑한다.
 *
 * ## 왜 [WorkflowExceptionHandler] 에 얹지 않는가
 * 그 advice 는 이미 detekt `TooManyFunctions`(한도 11)에 닿아 있다. 같은 이유로
 * [WorkflowStatusCompositionExceptionHandler] 와 [TransitionConflictExceptionHandler] 가 갈라져
 * 나온 선례가 있고, 임계값을 완화하는 대신 advice 를 하나 더 두는 것이 이 모듈의 관례다.
 *
 * ## 왜 `@Order` 가 없는가
 * 두 예외를 던지는 곳은 `WorkflowPublishService` 뿐이고, 그것을 부르는 컨트롤러는
 * [WorkflowDraftController](`com.bts.workflow.web`) 하나다. 그 패키지를 덮는 catch-all advice 는
 * 없다. 필요 없는 전역 우선권은 **다른 advice 의 매핑을 빼앗아** 응답 형식을 조용히 바꾸므로
 * 붙이지 않는다 — 그 사고 형태의 정본 설명은 [AmbiguousTransitionExceptionHandler] KDoc 에 있다.
 * 나중에 이 예외를 다른 BC 컨트롤러 경로에서 표면화시키려거든 **그때 실측하고** 우선권을 붙여라.
 *
 * ## 프론트 판별식에 이 파일을 등재해야 한다
 * `apps/web/src/hooks/__tests__/workflow-admin-error.test.ts` 가 백엔드 핸들러 `.kt` 를 직접 읽어
 * 프론트 메시지 표와 양방향 차집합 0 을 검사한다. 그 배열(`HANDLER_FILES`)에 이 파일을 넣지 않으면
 * 여기 코드가 통째로 안 보여 **차집합이 조용히 0 으로 통과한다.** 실제로 그 사고가 한 번 있었고
 * (테스트 KDoc :21), 그래서 이 PR 은 파일 생성과 배열 등재를 같은 커밋에 둔다.
 */
@RestControllerAdvice(assignableTypes = [WorkflowDraftController::class])
class WorkflowPublishExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 낙관적 락 충돌 — 409.
     *
     * 초안을 뜬 뒤 다른 세션이 먼저 발행했다. 덮어쓰지 않는 이유는 지금 초안이 「그 시점의 정의」를
     * 기준으로 편집된 것이라, 그대로 발행하면 남의 편집을 말없이 되돌리기 때문이다.
     *
     * @return 409 + `WORKFLOW_VERSION_CONFLICT`. 버전 숫자는 로그로만 남긴다 — 화면이 할 일은
     *   「다시 불러오기」 하나뿐이라 숫자를 보여도 관리자가 판단할 것이 없다.
     */
    @ExceptionHandler(WorkflowVersionConflictException::class)
    fun handleVersionConflict(ex: WorkflowVersionConflictException): ResponseEntity<ErrorResponse> {
        log.info(
            "WORKFLOW_409_VERSION_CONFLICT key='{}' expected={} actual={}",
            ex.workflowKey,
            ex.expected,
            ex.actual,
        )
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            ErrorResponse(
                error =
                    ErrorBody(
                        code = "WORKFLOW_VERSION_CONFLICT",
                        message = "그 사이 다른 사용자가 이 워크플로우를 발행했습니다. 다시 불러온 뒤 시도해 주세요.",
                    ),
            ),
        )
    }

    /**
     * 이관이 필요해 발행이 막힘 — 409 + 상태별 잔여 건수.
     *
     * Jira Cloud 는 발행 요청이 `statusMappings` 를 함께 받아 이슈를 옮긴다. BTS 는 그 UPDATE 가
     * issue-tracking BC 소유라 아직 실행할 수 없어(다중 BC 트랜잭션 금지) **막고, 무엇이 막는지
     * 알린다.** [PublishPendingResponse.pendingIssueCounts] 가 화면이 이관 모달을 그릴
     * 재료이며, 매핑 수용과 이관 실행은 로드맵 PR 7 이 채운다.
     *
     * 상태 키는 응답에 싣는다 — 관리자가 「어느 상태를 어디로 옮길지」 고르려면 그 키가 화면에
     * 있어야 한다. 이슈 키나 사용자 식별자는 담지 않는다.
     */
    @ExceptionHandler(WorkflowPublishMappingRequiredException::class)
    fun handleMappingRequired(ex: WorkflowPublishMappingRequiredException): ResponseEntity<PublishPendingResponse> {
        log.info("WORKFLOW_409_PUBLISH_MAPPING_REQUIRED key='{}' pending={}", ex.workflowKey, ex.pending.size)
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            PublishPendingResponse(
                error =
                    ErrorBody(
                        code = "WORKFLOW_PUBLISH_MAPPING_REQUIRED",
                        message = "발행하면 사라지는 상태에 아직 이슈가 남아 있습니다. 옮길 상태를 정한 뒤 다시 발행해 주세요.",
                    ),
                pendingIssueCounts = ex.pending,
            ),
        )
    }

    /**
     * 초안 부재 — 404.
     *
     * `WorkflowInvalidRequestException` 으로 접으면 400 이 나가고 프론트 표가 그것을 「요청 내용이
     * 올바르지 않습니다」라는 범용 문구로 옮긴다. 「초안이 없다」와 「요청이 잘못됐다」는 관리자가
     * 할 일이 다르므로 코드를 가른다.
     */
    @ExceptionHandler(WorkflowDraftNotFoundException::class)
    fun handleDraftNotFound(ex: WorkflowDraftNotFoundException): ResponseEntity<ErrorResponse> {
        log.info("WORKFLOW_DRAFT_404 key='{}'", ex.workflowKey)
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ErrorResponse(
                error =
                    ErrorBody(
                        code = "WORKFLOW_DRAFT_NOT_FOUND",
                        message = "폐기할 초안이 없습니다.",
                    ),
            ),
        )
    }

    /**
     * 요청 본문을 읽지 못했다 — 400.
     *
     * `PublishRequest.baseVersion` 은 기본값 없는 non-null 이라 빠뜨리면 Jackson 이 이 예외를
     * 던진다. 잡지 않으면 응답이 이 BC 표준 봉투가 아니라 Boot 기본 오류 본문으로 나가고,
     * 프론트 파서가 실패해 한국어 매핑이 **도달 불가**가 된다. 형제
     * `ValidatorExceptionHandler`·`PostActionExceptionHandler` 가 정확히 이 이유로 같은 처방을 쓴다.
     *
     * 봉투 조립은 [TransitionRuleFrameworkErrors] 한 벌을 그대로 재사용한다 — 문자열을 여기 다시
     * 적으면 프레임워크 층 코드가 세 곳으로 갈린다.
     */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadableBody(ex: HttpMessageNotReadableException): ResponseEntity<ErrorResponse> {
        log.info("WORKFLOW_400 message_not_readable cause='{}'", ex.mostSpecificCause.javaClass.simpleName)
        return TransitionRuleFrameworkErrors.unreadableBody()
    }
}

/**
 * 이관 필요 409 의 응답 본문.
 *
 * 표준 [ErrorResponse] 에 [pendingIssueCounts] 한 필드를 더한 형태다. 별도 타입으로 둔 것은
 * `ErrorResponse` 에 선택 필드를 붙이면 이 BC 의 **모든** 오류 응답 스키마가 넓어져,
 * 프론트 zod 스키마(`.strict()`)가 쓰지도 않는 필드를 매번 다뤄야 하기 때문이다.
 *
 * @property error 표준 오류 본문. `code` 는 `WORKFLOW_PUBLISH_MAPPING_REQUIRED`.
 * @property pendingIssueCounts 사라지는 상태 키 → 그 상태에 남은 이슈 수. 화면의 이관 모달이 이것을 그린다.
 */
data class PublishPendingResponse(
    val error: ErrorBody,
    val pendingIssueCounts: Map<String, Long>,
)
