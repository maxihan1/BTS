// slack-integration 통합 테스트용 settable IssueMutationPort stub (FR-SL-05 PR2 Task 3)

package com.bts.slack

import com.bts.shared.issue.AddCommentCommand
import com.bts.shared.issue.AssignCommand
import com.bts.shared.issue.IssueMutationPort
import com.bts.shared.issue.MutationResult
import com.bts.shared.issue.SetFieldCommand
import com.bts.shared.issue.SetFixVersionsCommand

/**
 * 통합 테스트가 이슈 변경(담당자 배정/댓글 추가/필드 변경/수정 예정 버전 설정) 결과를 명시 시드하는
 * [IssueMutationPort] stub (FR-SL-05 PR2 Task 3).
 *
 * cross-BC 이슈 변경 쓰기 포트는 prod 에서 issue-tracking 어댑터가 제공하나 slack test-boot 컨텍스트에는
 * 실 구현이 없다. `SlackInteractionService`(담당자 지정/댓글 추가, FR-SL-05 PR2 후속 Task)의 생성자가
 * non-null [IssueMutationPort] 를 요구할 예정이므로, 이 stub 을 [SlackTestcontainersConfig] 가 `@Bean`
 * 으로 선제 등록해 컨텍스트 로드가 `NoSuchBeanDefinitionException` 으로 깨지지 않게 한다
 * ([StubIssueTransitionPort] 동형 철학 — settable + fail-closed).
 *
 * ## settable — 메서드별 독립된 성공 결과/실패 예외
 * [nextSetFieldResult]/[nextSetFieldError], [nextAssignResult]/[nextAssignError],
 * [nextCommentResult]/[nextCommentError], [nextSetFixVersionsResult]/[nextSetFixVersionsError]
 * 네 쌍을 각각 세터로 직접 시드한다. 같은 메서드의 결과/예외 필드는
 * 서로 배타적으로 다뤄야 한다 — 예외를 세팅했다면 결과 필드는 null 로 두는 것이 호출자 책임이다(예외가
 * 세팅돼 있으면 결과보다 우선 던진다).
 *
 * ## 미시드 기본값 = 명시 오류 (fail-closed, [IssueMutationPort] KDoc "default 구현 없음" 철학)
 * 네 메서드 모두 결과/예외를 시드하지 않고 호출하면 [IllegalStateException] 으로 즉시 실패해, 통합 테스트가
 * 시나리오 세팅을 빠뜨렸음을 표면화한다(성공으로 위장하지 않는다) — silent success 는 "슬랙에서 담당자를
 * 지정했는데 실제로는 아무 일도 안 일어남" 같은 사고로 이어진다.
 *
 * ## setField — 이 PR(FR-SL-05 PR2)에서 slack 은 아직 미사용
 * `SlackInteractionService`가 담당자 배정/댓글 추가만 사용할 예정이지만, [IssueMutationPort] 인터페이스
 * 계약상 구현이 필요해 동일 settable/fail-closed 패턴으로 채운다.
 */
class StubIssueMutationPort : IssueMutationPort {
    /** 다음 [setField] 호출이 반환할 결과. null 이고 [nextSetFieldError] 도 null 이면 fail-closed 오류. */
    @Volatile
    var nextSetFieldResult: MutationResult? = null

    /** 다음 [setField] 호출이 던질 예외. 세팅돼 있으면 [nextSetFieldResult] 보다 우선한다. */
    @Volatile
    var nextSetFieldError: Throwable? = null

    /** [setField]에 마지막으로 넘어온 커맨드. 미호출이면 null. */
    @Volatile
    var lastSetFieldCommand: SetFieldCommand? = null
        private set

    /** 다음 [assign] 호출이 반환할 결과. null 이고 [nextAssignError] 도 null 이면 fail-closed 오류. */
    @Volatile
    var nextAssignResult: MutationResult? = null

    /** 다음 [assign] 호출이 던질 예외. 세팅돼 있으면 [nextAssignResult] 보다 우선한다. */
    @Volatile
    var nextAssignError: Throwable? = null

    /** [assign]에 마지막으로 넘어온 커맨드. 미호출이면 null. 테스트가 인자 정합(actor·담당자 등)을 검증한다. */
    @Volatile
    var lastAssignCommand: AssignCommand? = null
        private set

    /** 다음 [addComment] 호출이 반환할 결과. null 이고 [nextCommentError] 도 null 이면 fail-closed 오류. */
    @Volatile
    var nextCommentResult: MutationResult? = null

    /** 다음 [addComment] 호출이 던질 예외. 세팅돼 있으면 [nextCommentResult] 보다 우선한다. */
    @Volatile
    var nextCommentError: Throwable? = null

    /** [addComment]에 마지막으로 넘어온 커맨드. 미호출이면 null. 테스트가 인자 정합(actor·본문 등)을 검증한다. */
    @Volatile
    var lastAddCommentCommand: AddCommentCommand? = null
        private set

    /** 다음 [setFixVersions] 호출이 반환할 결과. null 이고 [nextSetFixVersionsError] 도 null 이면 fail-closed 오류. */
    @Volatile
    var nextSetFixVersionsResult: MutationResult? = null

    /** 다음 [setFixVersions] 호출이 던질 예외. 세팅돼 있으면 [nextSetFixVersionsResult] 보다 우선한다. */
    @Volatile
    var nextSetFixVersionsError: Throwable? = null

    /** [setFixVersions]에 마지막으로 넘어온 커맨드. 미호출이면 null. */
    @Volatile
    var lastSetFixVersionsCommand: SetFixVersionsCommand? = null
        private set

    override fun setField(cmd: SetFieldCommand): MutationResult {
        lastSetFieldCommand = cmd
        nextSetFieldError?.let { throw it }
        return nextSetFieldResult
            ?: error("StubIssueMutationPort 미설정: setField 시나리오를 먼저 시드해야 합니다: cmd=$cmd")
    }

    override fun assign(cmd: AssignCommand): MutationResult {
        lastAssignCommand = cmd
        nextAssignError?.let { throw it }
        return nextAssignResult
            ?: error("StubIssueMutationPort 미설정: assign 시나리오를 먼저 시드해야 합니다: cmd=$cmd")
    }

    override fun addComment(cmd: AddCommentCommand): MutationResult {
        lastAddCommentCommand = cmd
        nextCommentError?.let { throw it }
        return nextCommentResult
            ?: error("StubIssueMutationPort 미설정: addComment 시나리오를 먼저 시드해야 합니다: cmd=$cmd")
    }

    override fun setFixVersions(cmd: SetFixVersionsCommand): MutationResult {
        lastSetFixVersionsCommand = cmd
        nextSetFixVersionsError?.let { throw it }
        return nextSetFixVersionsResult
            ?: error("StubIssueMutationPort 미설정: setFixVersions 시나리오를 먼저 시드해야 합니다: cmd=$cmd")
    }

    /** 테스트 간 상태 격리를 위해 시드/캡처를 모두 초기화한다. */
    fun reset() {
        nextSetFieldResult = null
        nextSetFieldError = null
        lastSetFieldCommand = null
        nextAssignResult = null
        nextAssignError = null
        lastAssignCommand = null
        nextCommentResult = null
        nextCommentError = null
        lastAddCommentCommand = null
        nextSetFixVersionsResult = null
        nextSetFixVersionsError = null
        lastSetFixVersionsCommand = null
    }
}
