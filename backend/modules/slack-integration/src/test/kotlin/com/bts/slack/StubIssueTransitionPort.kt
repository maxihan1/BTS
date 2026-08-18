// slack-integration 통합 테스트용 settable IssueTransitionPort stub — 성공 결과 또는 실패 예외 시드 (FR-SL-05 PR1 Task 9)

package com.bts.slack

import com.bts.shared.board.BoardTransitionCommand
import com.bts.shared.board.BoardTransitionResult
import com.bts.shared.board.IssueTransitionPort

/**
 * 통합 테스트가 완료 전환 결과를 명시 시드하는 [IssueTransitionPort] stub (FR-SL-05 PR1 Task 9).
 *
 * cross-BC 전환 실행 쓰기 포트는 prod 에서 issue-tracking `IssueTransitionAdapter`가 제공하나 slack
 * test-boot 컨텍스트에는 실 구현이 없다. [com.bts.slack.interaction.SlackInteractionService] 생성자가
 * non-null [IssueTransitionPort]를 요구하므로, 이 stub 을 [SlackTestcontainersConfig] 가 `@Bean` 으로
 * 등록해 컨텍스트 로드를 복구한다([SlackContextLoadTest] 회귀 방지).
 *
 * ## settable — 성공 결과 또는 실패 예외 시드 ([StubIssueMutationPort](automation 모듈) 동형 철학)
 * [succeedWith]로 다음 호출이 반환할 [BoardTransitionResult]를, [failWith]로 다음 호출이 던질 예외
 * (`IssueTransitionPermissionDeniedException`·`IssueOptimisticLockException` 등)를 시드한다. 둘은
 * 서로 배타적으로 초기화된다 — 마지막에 시드한 쪽만 유효하다. 통합 테스트는 이 조합으로 happy path,
 * 무권한 거부, OCC(낙관적 락) 충돌을 각각 재현한다.
 *
 * ## 미시드 기본값 = 명시 오류 (fail-closed, [IssueTransitionPort] KDoc "default 구현 없음" 철학)
 * [succeedWith]/[failWith] 로 시나리오를 시드하지 않고 [transition]을 호출하면, 통합 테스트가 시나리오
 * 세팅을 빠뜨렸음을 즉시 표면화하도록 `error()`로 실패한다(성공으로 위장하지 않는다).
 */
class StubIssueTransitionPort : IssueTransitionPort {
    @Volatile
    private var nextResult: BoardTransitionResult? = null

    @Volatile
    private var nextFailure: RuntimeException? = null

    /** [transition]에 마지막으로 넘어온 커맨드. 미호출이면 null. 테스트가 인자 정합(actor·버전 등)을 검증한다. */
    @Volatile
    var lastCommand: BoardTransitionCommand? = null
        private set

    override fun transition(cmd: BoardTransitionCommand): BoardTransitionResult {
        lastCommand = cmd
        nextFailure?.let { throw it }
        return nextResult
            ?: error("StubIssueTransitionPort — succeedWith()/failWith() 로 시나리오를 먼저 시드해야 합니다: cmd=$cmd")
    }

    /** 다음 [transition] 호출이 [result]를 반환하도록 시드한다(이전에 시드한 실패는 해제된다). */
    fun succeedWith(result: BoardTransitionResult) {
        nextResult = result
        nextFailure = null
    }

    /** 다음 [transition] 호출이 [exception]을 던지도록 시드한다(이전에 시드한 성공 결과는 해제된다). */
    fun failWith(exception: RuntimeException) {
        nextFailure = exception
        nextResult = null
    }

    /** 테스트 간 상태 격리를 위해 시드/캡처를 초기화한다. */
    fun reset() {
        nextResult = null
        nextFailure = null
        lastCommand = null
    }
}
