// BulkAvailableTransitionsService 단위 테스트 — 이슈별 가용 전이 조회 + 교집합 계산 (Task 2 TDD RED)

package com.bts.issue.bulk.application

import com.bts.issue.application.IssueApplicationService
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueAccessDeniedException
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueNotFoundException
import com.bts.issue.domain.IssueWorkflowNotConfiguredException
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssueScope
import com.bts.shared.workflow.AvailableTransitionView
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import java.util.UUID

/**
 * FR-IS-05 Task 2 — BulkAvailableTransitionsService.availableCommonTransitions 단위 테스트.
 *
 * 검증 범위.
 * - 전부 성공 + 공통 전이 → transitions 채워지고 unresolved 빈 목록
 * - 일부 IssueNotFoundException → 해당 키 unresolved, 나머지 교집합
 * - 일부 IssueWorkflowNotConfiguredException → unresolved
 * - 일부 IssueAccessDeniedException → unresolved (C1 핵심 케이스)
 * - 전부 unresolved → transitions=[], unresolved=전체
 * - 교집합 없음(성공했으나 공통 없음) → transitions=[], unresolved=[]
 * - 단일 이슈 → 그 이슈 전이 그대로
 *
 * 단위 테스트 — IssueApplicationService 는 MockK 모의 객체 사용.
 */
class BulkAvailableTransitionsServiceTest : DescribeSpec({

    val issueService = mockk<IssueApplicationService>()
    val sut = BulkAvailableTransitionsService(issueService)

    val actor = ActorId(UUID.fromString("11111111-1111-4111-8111-111111111111"))

    /** 테스트용 AvailableTransitionView 생성 헬퍼. */
    fun transition(
        from: String,
        to: String,
        name: String = to,
    ) = AvailableTransitionView(fromStateKey = from, toStateKey = to, name = name)

    fun accessDenied() =
        IssueAccessDeniedException(
            actor = actor,
            permission = IssuePermission.VIEW,
            scope = IssueScope.Global,
        )

    beforeEach {
        clearMocks(issueService)
    }

    // ── 전부 성공 ──────────────────────────────────────────────────────────────

    describe("전부 성공 + 공통 전이 존재") {
        it("transitions 에 교집합을 담고 unresolvedIssueKeys 는 빈 목록이다") {
            every { issueService.availableTransitions(actor, IssueKey("ATLAS-1")) } returns
                listOf(
                    transition("OPEN", "IN_PROGRESS"),
                    transition("OPEN", "DONE"),
                )
            every { issueService.availableTransitions(actor, IssueKey("ATLAS-2")) } returns
                listOf(
                    transition("OPEN", "IN_PROGRESS"),
                )

            val result = sut.availableCommonTransitions(actor, listOf("ATLAS-1", "ATLAS-2"))

            result.transitions shouldHaveSize 1
            result.transitions.first().toStateKey shouldBe "IN_PROGRESS"
            result.unresolvedIssueKeys.shouldBeEmpty()
        }
    }

    // ── IssueNotFoundException ─────────────────────────────────────────────────

    describe("일부 이슈가 IssueNotFoundException") {
        it("해당 키를 unresolvedIssueKeys 에 담고 나머지 교집합을 계산한다") {
            every { issueService.availableTransitions(actor, IssueKey("ATLAS-1")) } returns
                listOf(transition("OPEN", "IN_PROGRESS"))
            every { issueService.availableTransitions(actor, IssueKey("ATLAS-99")) } throws
                IssueNotFoundException(IssueKey("ATLAS-99"))

            val result = sut.availableCommonTransitions(actor, listOf("ATLAS-1", "ATLAS-99"))

            result.transitions shouldHaveSize 1
            result.transitions.first().toStateKey shouldBe "IN_PROGRESS"
            result.unresolvedIssueKeys shouldContainExactly listOf("ATLAS-99")
        }
    }

    // ── IssueWorkflowNotConfiguredException ───────────────────────────────────

    describe("일부 이슈가 IssueWorkflowNotConfiguredException") {
        it("해당 키를 unresolvedIssueKeys 에 담고 나머지 교집합을 계산한다") {
            every { issueService.availableTransitions(actor, IssueKey("ATLAS-1")) } returns
                listOf(transition("OPEN", "DONE"))
            every { issueService.availableTransitions(actor, IssueKey("ATLAS-2")) } throws
                IssueWorkflowNotConfiguredException("TEST", null)

            val result = sut.availableCommonTransitions(actor, listOf("ATLAS-1", "ATLAS-2"))

            result.transitions shouldHaveSize 1
            result.transitions.first().toStateKey shouldBe "DONE"
            result.unresolvedIssueKeys shouldContainExactly listOf("ATLAS-2")
        }
    }

    // ── IssueAccessDeniedException (C1 핵심 케이스) ───────────────────────────

    describe("일부 이슈가 IssueAccessDeniedException") {
        it("해당 키를 unresolvedIssueKeys 에 담고 전체를 중단시키지 않는다") {
            every { issueService.availableTransitions(actor, IssueKey("ATLAS-1")) } returns
                listOf(transition("OPEN", "IN_PROGRESS"))
            every { issueService.availableTransitions(actor, IssueKey("ATLAS-98")) } throws
                accessDenied()

            val result = sut.availableCommonTransitions(actor, listOf("ATLAS-1", "ATLAS-98"))

            result.transitions shouldHaveSize 1
            result.transitions.first().toStateKey shouldBe "IN_PROGRESS"
            result.unresolvedIssueKeys shouldContainExactly listOf("ATLAS-98")
        }
    }

    // ── 전부 unresolved ───────────────────────────────────────────────────────

    describe("모든 이슈가 unresolved") {
        it("transitions 는 빈 목록이고 unresolvedIssueKeys 에 전체 키가 담긴다") {
            every { issueService.availableTransitions(actor, IssueKey("ATLAS-1")) } throws
                IssueNotFoundException(IssueKey("ATLAS-1"))
            every { issueService.availableTransitions(actor, IssueKey("ATLAS-2")) } throws
                IssueWorkflowNotConfiguredException("TEST", null)

            val result = sut.availableCommonTransitions(actor, listOf("ATLAS-1", "ATLAS-2"))

            result.transitions.shouldBeEmpty()
            result.unresolvedIssueKeys shouldContainExactly listOf("ATLAS-1", "ATLAS-2")
        }
    }

    // ── 교집합 없음 ───────────────────────────────────────────────────────────

    describe("전부 성공이나 공통 전이가 없음") {
        it("transitions 는 빈 목록이고 unresolvedIssueKeys 도 빈 목록이다") {
            every { issueService.availableTransitions(actor, IssueKey("ATLAS-1")) } returns
                listOf(transition("OPEN", "IN_PROGRESS"))
            every { issueService.availableTransitions(actor, IssueKey("ATLAS-2")) } returns
                listOf(transition("OPEN", "DONE"))

            val result = sut.availableCommonTransitions(actor, listOf("ATLAS-1", "ATLAS-2"))

            result.transitions.shouldBeEmpty()
            result.unresolvedIssueKeys.shouldBeEmpty()
        }
    }

    // ── 단일 이슈 ─────────────────────────────────────────────────────────────

    describe("이슈가 하나뿐일 때") {
        it("그 이슈의 가용 전이를 그대로 반환한다") {
            val transitions =
                listOf(
                    transition("OPEN", "IN_PROGRESS"),
                    transition("OPEN", "DONE"),
                )
            every { issueService.availableTransitions(actor, IssueKey("ATLAS-1")) } returns transitions

            val result = sut.availableCommonTransitions(actor, listOf("ATLAS-1"))

            result.transitions shouldHaveSize 2
            result.transitions.map { it.toStateKey } shouldContainExactly listOf("IN_PROGRESS", "DONE")
            result.unresolvedIssueKeys.shouldBeEmpty()
        }
    }

    // ── 예상 못한 예외는 전파 ─────────────────────────────────────────────────

    describe("예상 못한 예외(RuntimeException)가 발생할 때") {
        it("예외를 그대로 전파한다") {
            every { issueService.availableTransitions(actor, IssueKey("ATLAS-1")) } throws
                RuntimeException("unexpected")

            io.kotest.assertions.throwables.shouldThrow<RuntimeException> {
                sut.availableCommonTransitions(actor, listOf("ATLAS-1"))
            }
        }
    }
})
