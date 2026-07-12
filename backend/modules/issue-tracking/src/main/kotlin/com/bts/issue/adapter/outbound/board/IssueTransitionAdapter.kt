// 보드 카드 이동(전이 위임) cross-BC 포트 구현체 — agile-planning → issue-tracking 전이 경로 위임

package com.bts.issue.adapter.outbound.board

import com.bts.issue.application.IssueApplicationService
import com.bts.issue.application.TransitionIssueRequest
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueVersionConflictException
import com.bts.shared.board.BoardTransitionCommand
import com.bts.shared.board.BoardTransitionResult
import com.bts.shared.board.IssueOptimisticLockException
import com.bts.shared.board.IssueTransitionPermissionDeniedException
import com.bts.shared.board.IssueTransitionPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

/**
 * [IssueTransitionPort] 구현체 (FR-BD-01 Task 5).
 *
 * agile-planning BC 의 카드 이동 요청을 issue-tracking BC 의 기존 전이 경로에 위임한다.
 *
 * ### actor — cmd.actorUserId 신뢰 (SecurityContext 직접 추출 안 함)
 *
 * adapter 는 SecurityContext 를 직접 읽지 않고 [BoardTransitionCommand.actorUserId] 를 신뢰한다.
 * actor 는 호출 컨트롤러([com.bts.agileplanning.web.BoardController])가 SecurityContext 에서
 * 추출해 cmd 로 전달한다. adapter 가 SecurityContext 에 의존하지 않으므로 스레드 무관(async 안전)이다.
 * 위조 차단은 cmd 를 채우는 유일한 곳이 컨트롤러의 SecurityContext 추출이라는 점으로 보장된다
 * (컨트롤러는 body/param 으로 actor 를 받지 않는다, sec codereview-fix P1).
 *
 * ### 도메인 직접 UPDATE 금지
 *
 * 이슈 상태를 repository 로 직접 변경하면 워크플로우 불변식 검증이 우회된다.
 * 반드시 [IssueApplicationService.transitionIssue] 경로를 통해야 한다
 * (PATCH 도메인 우회 금지 패턴, DEVELOPMENT.md §회귀 방지).
 *
 * ### 예외 전파 — 권한 거부·OCC 충돌만 타입 번역, 그 외는 verbatim (FR-SL-05 PR1 Task 2)
 *
 * issue-tracking 내부 예외 대부분([com.bts.issue.domain.IssueTransitionNotAllowedException],
 * [com.bts.issue.domain.IssueWorkflowNotConfiguredException] 등)은 agile-planning 처럼 issue-tracking
 * 과 같은 모듈 그래프에 있는 소비자가 catch 할 수 있도록 그대로 전파한다.
 *
 * 다만 [com.bts.issue.domain.IssueAccessDeniedException](권한 거부)과
 * [com.bts.issue.domain.IssueVersionConflictException](OCC 버전 충돌) 두 가지는 shared-kernel 타입
 * ([IssueTransitionPermissionDeniedException]/[IssueOptimisticLockException])으로 번역해 던진다.
 * BC 격리로 issue-tracking 내부 클래스를 import 할 수 없는 소비 BC(slack-integration 등)가 클래스명
 * 문자열 매칭 없이 두 실패를 구분할 수 있어야 하기 때문이다([com.bts.shared.issue.IssueMutationPermissionDeniedException]
 * 과 동형 패턴, FR-AT-02 C3 선례). 원본 도메인 예외는 `cause` 로 보존한다(진단용).
 *
 * @param issueApplicationService 기존 이슈 CRUD + 전이 유스케이스 Application Service.
 * @param transactionTemplate 트랜잭션 경계 제공. `IssueApplicationService.transitionIssue` 가
 *   `@Transactional(REQUIRED)` 이므로 adapter 에서도 트랜잭션 내부에서 호출해야 `MANDATORY`
 *   하위 포트(WorkflowKeyResolverImpl 등)가 정상 동작한다.
 */
@Component
class IssueTransitionAdapter(
    private val issueApplicationService: IssueApplicationService,
    private val transactionTemplate: TransactionTemplate,
) : IssueTransitionPort {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 보드 카드 이동 요청을 [IssueApplicationService.transitionIssue] 에 위임한다.
     *
     * actor 는 [BoardTransitionCommand.actorUserId] 를 신뢰한다(호출 컨트롤러가 SecurityContext 에서
     * 추출해 채운 값). adapter 는 SecurityContext 를 직접 읽지 않으므로 스레드 무관(async 안전)하다.
     * 401 인증 강제는 호출 컨트롤러가 actor 추출 단계에서 담당한다.
     *
     * @param cmd 전이 커맨드. actorUserId·issueKey·toStateKey·expectedVersion·resolutionId 포함.
     * @return 전이 완료 후 상태 키·버전을 담은 [BoardTransitionResult].
     * @throws com.bts.issue.domain.IssueTransitionNotAllowedException 전이 규칙 위반 또는 미정의 전이.
     * @throws com.bts.issue.domain.IssueWorkflowNotConfiguredException 워크플로우 스킴 미배정.
     * @throws IssueTransitionPermissionDeniedException TRANSITION 권한 없을 때(원래
     *   [com.bts.issue.domain.IssueAccessDeniedException] 를 번역, FR-SL-05 PR1 Task 2).
     * @throws IssueOptimisticLockException OCC 버전 충돌 시(원래
     *   [com.bts.issue.domain.IssueVersionConflictException] 를 번역, FR-SL-05 PR1 Task 2).
     */
    @Suppress("TooGenericExceptionCaught")
    override fun transition(cmd: BoardTransitionCommand): BoardTransitionResult {
        // actor 는 cmd.actorUserId 를 신뢰한다 — 컨트롤러가 SecurityContext 에서 추출해 채운 값.
        // adapter 가 SecurityContext 를 직접 읽지 않으므로 async 안전하다 (sec codereview-fix P1).
        val actor = ActorId(cmd.actorUserId)

        log.info(
            "board_card_move issueKey={} toStateKey={} actor={}",
            cmd.issueKey,
            cmd.toStateKey,
            actor.value,
        )

        val request =
            TransitionIssueRequest(
                toStateKey = cmd.toStateKey,
                expectedVersion = cmd.expectedVersion,
                resolutionId = cmd.resolutionId,
            )

        // transitionIssue 는 @Transactional(REQUIRED) — 트랜잭션이 없으면 새로 시작,
        // 이미 있으면 참여한다. TransactionTemplate 은 외부 컨텍스트에서 트랜잭션을 보장하기 위한 래퍼.
        val issueResponse =
            transactionTemplate.execute {
                translatingDomainExceptions {
                    issueApplicationService.transitionIssue(actor, IssueKey(cmd.issueKey), request)
                }
            } ?: error("transitionIssue returned null — unexpected (transactionTemplate.execute 반환)")

        return BoardTransitionResult(
            issueKey = cmd.issueKey,
            currentStateKey = issueResponse.currentStateKey,
            version = issueResponse.version,
        )
    }

    /**
     * [attempt] 를 실행하고, 권한 거부·OCC 충돌 도메인 예외만 shared-kernel 타입으로 번역해 던진다.
     *
     * 클래스 KDoc "예외 전파" 절 참조. 두 예외 외에는 번역하지 않고 그대로 전파한다 — 문자열 매칭이
     * 아닌 타입(catch 절) 기준으로만 분류한다(BC 격리, 클래스명 문자열 매칭 금지 원칙).
     *
     * @throws IssueTransitionPermissionDeniedException [IssueAccessDeniedException] 번역.
     * @throws IssueOptimisticLockException [IssueVersionConflictException] 번역.
     */
    private fun <T> translatingDomainExceptions(attempt: () -> T): T =
        try {
            attempt()
        } catch (e: IssueAccessDeniedException) {
            throw IssueTransitionPermissionDeniedException(e.message ?: "이슈 전이 권한이 없습니다", e)
        } catch (e: IssueVersionConflictException) {
            throw IssueOptimisticLockException(e.message ?: "버전 충돌이 발생했습니다", e)
        }
}
