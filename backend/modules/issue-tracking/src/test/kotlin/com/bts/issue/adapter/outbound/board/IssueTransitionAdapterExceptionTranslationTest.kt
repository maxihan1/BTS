// IssueTransitionAdapter 예외 번역 단위 테스트 — 권한 거부·OCC 충돌을 shared-kernel 타입으로 (FR-SL-05 PR1 Task 2)

package com.bts.issue.adapter.outbound.board

import com.bts.issue.application.IssueApplicationService
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueVersionConflictException
import com.bts.shared.board.BoardTransitionCommand
import com.bts.shared.board.IssueOptimisticLockException
import com.bts.shared.board.IssueTransitionPermissionDeniedException
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssueScope
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * [IssueTransitionAdapter] 의 도메인 예외 → shared-kernel 타입 예외 번역 단위 테스트 (FR-SL-05 PR1 Task 2).
 *
 * [IssueApplicationService] 는 MockK 로 격리한다. [TransactionTemplate] 은 MockK 로 대체하되 `execute`
 * 호출 시 전달된 콜백을 즉시 동기 실행하도록 stub 한다([AutomationIssueMutationAdapterTest] 동일 패턴).
 *
 * ## 배경 — BC 격리로 인한 소비자 예외 분류 문제
 *
 * issue-tracking 내부 도메인 예외([IssueAccessDeniedException]/[IssueVersionConflictException])를
 * verbatim 전파하면, issue-tracking 내부를 import 할 수 없는 소비 BC(slack-integration 등)가 권한 거부와
 * OCC 버전 충돌을 구분할 수 없다. 이 테스트는 어댑터가 두 도메인 예외를 shared-kernel 타입
 * ([IssueTransitionPermissionDeniedException]/[IssueOptimisticLockException])으로 번역해 던지는지
 * 검증한다.
 *
 * 검증 항목.
 * - S1. 권한 거부([IssueAccessDeniedException]) → [IssueTransitionPermissionDeniedException] 번역, cause 보존.
 * - S2. OCC 버전 충돌([IssueVersionConflictException]) → [IssueOptimisticLockException] 번역, cause 보존.
 * - S3. 그 외 예외([IllegalStateException])는 번역 없이 verbatim 전파.
 */
class IssueTransitionAdapterExceptionTranslationTest {
    private val issueApplicationService: IssueApplicationService = mockk()
    private val transactionTemplate: TransactionTemplate = mockk()
    private val status: TransactionStatus = mockk(relaxed = true)

    private lateinit var adapter: IssueTransitionAdapter

    private val actorUuid: UUID = UUID.randomUUID()
    private val issueKey = IssueKey("PROJ-1")

    @BeforeEach
    fun setUp() {
        adapter = IssueTransitionAdapter(issueApplicationService, transactionTemplate)
        every { transactionTemplate.execute(any<TransactionCallback<Any>>()) } answers {
            firstArg<TransactionCallback<Any>>().doInTransaction(status)
        }
    }

    private fun cmd(expectedVersion: Long = 1L): BoardTransitionCommand =
        BoardTransitionCommand(
            actorUserId = actorUuid,
            issueKey = issueKey.value,
            toStateKey = "in_progress",
            expectedVersion = expectedVersion,
            resolutionId = null,
        )

    // ── S1. 권한 거부 → IssueTransitionPermissionDeniedException ──────────────────

    @Test
    fun `transition translates IssueAccessDeniedException to IssueTransitionPermissionDeniedException`() {
        val domainException =
            IssueAccessDeniedException(
                actor = ActorId(actorUuid),
                permission = IssuePermission.TRANSITION,
                scope = IssueScope.Issue(issueKey.value),
            )
        every { issueApplicationService.transitionIssue(any(), any(), any()) } throws domainException

        assertThatThrownBy { adapter.transition(cmd()) }
            .isInstanceOf(IssueTransitionPermissionDeniedException::class.java)
            .hasCause(domainException)
    }

    // ── S2. OCC 버전 충돌 → IssueOptimisticLockException ──────────────────────────

    @Test
    fun `transition translates IssueVersionConflictException to IssueOptimisticLockException`() {
        val domainException = IssueVersionConflictException(issueKey, currentVersion = 5L)
        every { issueApplicationService.transitionIssue(any(), any(), any()) } throws domainException

        assertThatThrownBy { adapter.transition(cmd(expectedVersion = 1L)) }
            .isInstanceOf(IssueOptimisticLockException::class.java)
            .hasCause(domainException)
    }

    // ── S3. 그 외 예외는 verbatim 전파 (번역 없음) ─────────────────────────────────

    @Test
    fun `transition propagates other exceptions verbatim without translation`() {
        val otherException = IllegalStateException("워크플로우 엔진 오류")
        every { issueApplicationService.transitionIssue(any(), any(), any()) } throws otherException

        assertThatThrownBy { adapter.transition(cmd()) }
            .isSameAs(otherException)
    }
}
