// 발행 충돌 2종을 HTTP 409 + 이 BC 표준 오류 본문으로 매핑하는 전용 핸들러

package com.bts.workflow.web

import com.bts.workflow.domain.exception.WorkflowDraftNotFoundException
import com.bts.workflow.domain.exception.WorkflowMigrationInFlightException
import com.bts.workflow.domain.exception.WorkflowMigrationInvalidMappingException
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
                        // ★ 「다시 불러오라」로 끝내면 안 된다 — 초안의 앵커는 한 번 정해지면 저장으로
                        //   바뀌지 않으므로(upsert 의 DO UPDATE 가 그 컬럼을 뺀다), 다시 불러와 발행해도
                        //   같은 409 가 반복된다. 그 상태의 유일한 출구는 초안을 버리고 다시 시작하는 것이다.
                        message =
                            "그 사이 다른 사용자가 이 워크플로우를 발행했습니다. " +
                                "지금 초안은 옛 버전을 기준으로 만들어져 그대로는 발행할 수 없습니다 — " +
                                "초안을 폐기하고 최신 정의에서 다시 편집해 주세요.",
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

    /**
     * 이관 매핑이 규칙을 어겼다 — 400.
     *
     * ### 왜 축마다 코드를 가르지 않는가
     * 위반 축은 여덟이다(빠지지 않는 출발지 · 초안에 없는 도착지 · 발행 전 도착지 · 빈 목록 ·
     * 중복 출발지 · 범위 없음 · 형제 워크플로우 · 상한 초과). 축마다 코드를 내면 프론트의
     * **양방향 차집합 가드**가 축 수만큼 행을 요구하고, 축이 하나 늘 때마다 프론트가 red 로 막힌다.
     * 관리자가 읽을 것은 「무엇을 고쳐야 하는가」이고 그것은 코드가 아니라 문장이 싣는다.
     *
     * @return 400 + `WORKFLOW_MIGRATION_INVALID_MAPPING`. 어느 축인지는 메시지가 싣는다.
     */
    @ExceptionHandler(WorkflowMigrationInvalidMappingException::class)
    fun handleMigrationInvalidMapping(
        ex: WorkflowMigrationInvalidMappingException,
    ): ResponseEntity<ErrorResponse> {
        log.info("WORKFLOW_400_MIGRATION_INVALID_MAPPING key='{}' reason='{}'", ex.workflowKey, ex.reason)
        return ResponseEntity.badRequest().body(
            ErrorResponse(error = ErrorBody(code = "WORKFLOW_MIGRATION_INVALID_MAPPING", message = ex.reason)),
        )
    }

    /**
     * 어댑터가 던진 `IllegalArgumentException` 백스톱 — 400.
     *
     * ### 왜 필요한가
     * 이관 어댑터(`WorkflowStatusMigrationAdapter`)는 도착 상태와 프로젝트 범위를 `require` 로
     * 검사한다. 그 예외를 잡는 advice 가 이 BC 에 하나도 없어(`WorkflowExceptionHandler:168` 이
     * 「IAE 를 잡지 않는다」고 명시) 400 이어야 할 것이 **500** 으로 나갔다. 관리자는 무엇이
     * 잘못됐는지 못 보고 재시도 말고 할 게 없다.
     *
     * ### ★이것은 백스톱이지 1차 판정이 아니다
     * 정상 경로에서는 [WorkflowPublishService] 의 선제 가드가 먼저 막으므로 여기까지 오지 않는다.
     * 그 가드를 뚫고 온 것은 **가드와 어댑터의 판정이 갈라졌다는 신호**이므로 `warn` 으로 남긴다 —
     * `info` 로 묻으면 갈라짐이 조용히 누적된다.
     *
     * ### 왜 전역 advice 에 두면 안 되는가
     * `IllegalArgumentException` 은 어느 BC 의 도메인 invariant 도 던진다. 전역으로 잡으면 500
     * 이어야 할 서버 결함이 400 으로 둔갑해 장애 대응이 엉뚱한 곳을 판다. 이 advice 가
     * `assignableTypes = [WorkflowDraftController::class]` 로 **스코프가 있어서** 안전한 것이지,
     * IAE 를 400 으로 접는 것 자체가 안전한 것이 아니다.
     */
    /**
     * 끝나지 않은 이관이 이미 있다 — 409.
     *
     * 요청은 멀쩡하고 지금 상태와 부딪힐 뿐이다. 앞선 이관이 끝나면 같은 요청이 통과하므로
     * 화면이 안내할 것은 「고쳐라」가 아니라 「기다렸다 다시」다.
     *
     * @return 409 + `WORKFLOW_MIGRATION_IN_FLIGHT`.
     */
    @ExceptionHandler(WorkflowMigrationInFlightException::class)
    fun handleMigrationInFlight(ex: WorkflowMigrationInFlightException): ResponseEntity<ErrorResponse> {
        log.info("WORKFLOW_409_MIGRATION_IN_FLIGHT key='{}'", ex.workflowKey)
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            ErrorResponse(
                error =
                    ErrorBody(
                        code = "WORKFLOW_MIGRATION_IN_FLIGHT",
                        message = "이미 진행 중인 상태 이관이 있습니다. 끝난 뒤 다시 시도하세요.",
                    ),
            ),
        )
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleAdapterRequireViolation(ex: IllegalArgumentException): ResponseEntity<ErrorResponse> {
        log.warn("WORKFLOW_400_ADAPTER_REQUIRE 선제 가드를 뚫었다 — 가드와 어댑터 판정이 갈라졌다", ex)
        return ResponseEntity.badRequest().body(
            ErrorResponse(
                error =
                    ErrorBody(
                        code = "WORKFLOW_MIGRATION_INVALID_MAPPING",
                        message = ex.message ?: "이관 요청을 처리할 수 없습니다.",
                    ),
            ),
        )
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
