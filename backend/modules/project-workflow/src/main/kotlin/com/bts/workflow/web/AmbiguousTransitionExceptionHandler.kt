// AmbiguousTransitionException 을 HTTP 409 로 매핑하는 전용 단일 타입 핸들러 (FR-WF-05 결정 D-2)

package com.bts.workflow.web

import com.bts.workflow.domain.exception.AmbiguousTransitionException
import com.bts.workflow.domain.exception.TransitionCandidate
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * [AmbiguousTransitionException] → HTTP 409(Conflict) + 후보 목록 전용 매핑 핸들러.
 *
 * ## 왜 [WorkflowExceptionHandler] 에서 떼어 별도 advice 로 두는가 (되돌리지 마라)
 * 이 예외를 실제로 마주치는 컨트롤러는 project-workflow 가 아니라 **issue-tracking** 의
 * `IssueController` (`POST /api/v1/issues/{key}/transition`) 다. 그 패키지에는
 * `com.bts.issue.adapter.inbound.rest.IssueExceptionHandler` 가 catch-all
 * `@ExceptionHandler(Exception::class)` 을 달고 있어, advice 등록 순서에 따라 이 예외를 먼저 삼켜
 * **500 `INTERNAL_ERROR`** 로 변질시킨다(실측 확인 — 이 advice 도입 전 응답 status 500).
 * 결정 D-2("모호하면 조용히 고르지 말고 후보를 돌려준다")가 그 자리에서 통째로 무효가 된다.
 *
 * 처방은 `@Order(HIGHEST_PRECEDENCE)` 로 catch-all 보다 앞세우는 것이다. 그런데 그 우선권을
 * [WorkflowExceptionHandler] 에 주면 **안 된다** — 그쪽은 예외 10종을 잡는 다중 advice 라
 * 전역 최우선이 되는 순간 `WorkflowNotFoundException` 까지
 * `com.bts.workflow.scheme.web.WorkflowSchemeExceptionHandler`
 * (`basePackages = ["com.bts.workflow.scheme"]`)에서 빼앗는다. 그러면 스킴 404 응답이
 * RFC 7807 [org.springframework.http.ProblemDetail] 에서 `{error:{code,message}}` 로
 * **조용히 바뀐다**(계약 파괴인데 어느 테스트도 이 경로를 안 밟으면 초록으로 지나간다).
 *
 * 그래서 우선권은 **예외 한 종류만 잡는 이 advice** 에만 준다. 잡는 타입이 하나뿐이라
 * 401/403/500·[org.springframework.web.server.ResponseStatusException] 등 다른 예외를
 * 구조적으로 삼킬 수 없다. 선례는 같은 문제(catch-all 삼킴)를 같은 형태로 푼
 * `com.bts.issue.project.archive.web.ProjectArchivedExceptionHandler` 다.
 *
 * ## 응답에 요청 정보를 싣지 않는다
 * 선례가 `instance` 를 URN 으로 정화한 이유는 [org.springframework.http.ProblemDetail] 을 쓸 때
 * Spring MVC 의 `RequestResponseBodyMethodProcessor` 가 `instance` 가 null 이면 **요청 URI 로
 * 자동으로 채우기** 때문이다. 이 advice 는 선택자가 없어 레포의 어느 컨트롤러에도 붙는데
 * 그중 일부 경로는 경로 세그먼트에 원문 비밀 토큰을 싣는다.
 * 여기서는 [ProblemDetail] 대신 project-workflow 표준 [AmbiguousTransitionErrorResponse] 를
 * 돌려주므로 `instance` 필드 자체가 없고 요청 URI 가 본문에 실릴 여지가 없다.
 * 본문에 담기는 것은 고정 안내 문구와 전환 후보([TransitionCandidate] = 전환 UUID + 표시 라벨)뿐이며,
 * 워크플로우 키·이슈 키 같은 식별자는 로그로만 남긴다.
 * **[ProblemDetail] 로 바꾸려거든 `instance` 를 반드시 명시하라.**
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class AmbiguousTransitionExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 모호 전환 — 409 + 후보 목록.
     *
     * 후보가 둘 이상인데 호출자가 `transitionId` 를 주지 않았다. 조용히 아무거나 고르지 않고
     * 후보를 그대로 돌려줘 호출자가 [TransitionCandidate.transitionId] 를 실어 재요청하게 한다
     * (spec FR-WF-05 §S4). 응답 골격의 선례는
     * `WorkflowSchemeExceptionHandler` 의 `SchemeInUseException(usedByProjects) → 409` 다.
     *
     * @param ex 모호성이 발생한 워크플로우 키와 후보 전량을 담은 예외.
     * @return 409 + `AMBIGUOUS_TRANSITION` + 후보 전량.
     */
    @ExceptionHandler(AmbiguousTransitionException::class)
    fun handleAmbiguousTransition(ex: AmbiguousTransitionException): ResponseEntity<AmbiguousTransitionErrorResponse> {
        log.info("WORKFLOW_409_AMBIGUOUS key='{}' candidates={}", ex.workflowKey, ex.candidates.size)
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            AmbiguousTransitionErrorResponse(
                error =
                    ErrorBody(
                        code = "AMBIGUOUS_TRANSITION",
                        message =
                            "이동할 수 있는 전환이 ${ex.candidates.size}개입니다. " +
                                "어느 전환인지 골라 주세요.",
                    ),
                candidates = ex.candidates,
            ),
        )
    }
}

/**
 * 모호 전환 409 전용 응답.
 *
 * 표준 [ErrorResponse] 와 같은 `error` 를 그대로 두고 최상위에 `candidates` 를 **덧붙이기만** 한다 —
 * 기존 에러 응답을 읽는 클라이언트는 영향받지 않는다.
 *
 * @property error 표준 에러 상세. `code` 는 항상 `AMBIGUOUS_TRANSITION`.
 * @property candidates 호출자가 다시 지목할 수 있는 전환 후보 전량. 던진 순서를 그대로 보존한다.
 */
data class AmbiguousTransitionErrorResponse(
    val error: ErrorBody,
    val candidates: List<TransitionCandidate>,
)
