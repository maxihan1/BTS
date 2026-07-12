// ActionExecutor 단위 테스트 — 4종 액션 디스패치/actor 전달/템플릿 치환/dryRun 전파/부분실패 집계 (FR-AT-02 Task 9 TDD RED)

package com.bts.automation.application

import com.bts.automation.StubIssueMutationPort
import com.bts.automation.StubIssueSnapshotPort
import com.bts.automation.adapter.AutomationActionRepository
import com.bts.automation.adapter.AutomationConditionRepository
import com.bts.automation.adapter.WebhookActionClient
import com.bts.automation.adapter.WebhookCallResult
import com.bts.automation.domain.Action
import com.bts.automation.domain.ActionType
import com.bts.automation.domain.AutomationRule
import com.bts.automation.domain.ComparisonOperator
import com.bts.automation.domain.Condition
import com.bts.automation.domain.TriggerType
import com.bts.shared.issue.IssueMutationPermissionDeniedException
import com.bts.shared.issue.IssueSnapshot
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.TextNode
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.util.UUID

/**
 * [ActionExecutor] 단위 테스트 (FR-AT-02 Task 9).
 *
 * [StubIssueMutationPort](fail-safe 실 구현) + `mockk<WebhookActionClient>` + 실
 * [TemplateRenderer](object 싱글턴, 기본 주입값 그대로 사용) 조합으로 이슈-불변식/HTTP 없이 디스패치
 * 로직만 검증한다.
 *
 * ### 검증 항목
 * - E-a. 빈 액션 리스트 → no-op SUCCESS(EC2), 포트/클라이언트 미호출
 * - E-b. SET_FIELD → [com.bts.shared.issue.IssueMutationPort.setField] 위임 + actor 전달 + value 인코딩
 * - E-c. ASSIGN → [com.bts.shared.issue.IssueMutationPort.assign] 위임 + actor 전달
 * - E-d. ADD_COMMENT → 템플릿 치환 후 [com.bts.shared.issue.IssueMutationPort.addComment] 위임
 * - E-e. CALL_WEBHOOK → url/body 템플릿 치환 후 [WebhookActionClient.call] 위임(이슈 불필요)
 * - E-f. dryRun 전파 — 커맨드의 dryRun 플래그
 * - E-g. 한 액션 실패(issueKey 없음) 시 나머지 액션은 계속 실행되고 상태는 PARTIAL
 * - E-h. 전부 성공 → SUCCESS
 * - E-i. 전부 실패(1건) → FAILED + 예외 클래스명 기반 PERMISSION_DENIED/FAILED 사유 분류
 * - E-j. 조건 게이트(FR-AT-03) — 조건 없음/충족/불충족/스냅샷 조회 불가/issueKey 없음/UUID 필드 매핑
 */
class ActionExecutorTest : DescribeSpec({

    val fixedNow: Instant = Instant.parse("2026-07-11T00:00:00Z")
    val objectMapper = ObjectMapper()

    val issueMutationPort = StubIssueMutationPort()
    val webhookActionClient = mockk<WebhookActionClient>()
    val actionRepository = mockk<AutomationActionRepository>()
    val issueSnapshotPort = StubIssueSnapshotPort()
    val conditionRepository = mockk<AutomationConditionRepository>(relaxed = true)

    val executor =
        ActionExecutor(
            issueMutationPort = issueMutationPort,
            webhookActionClient = webhookActionClient,
            actionRepository = actionRepository,
            objectMapper = objectMapper,
            issueSnapshotPort = issueSnapshotPort,
            conditionRepository = conditionRepository,
        )

    afterEach {
        clearMocks(webhookActionClient, actionRepository, conditionRepository)
        issueMutationPort.reset()
        issueSnapshotPort.reset()
    }

    fun newRule(actorUserId: UUID = UUID.randomUUID()): AutomationRule =
        AutomationRule.create(
            projectKey = "PROJ",
            name = "룰",
            triggerType = TriggerType.ISSUE_CREATED,
            createdBy = actorUserId,
            actorUserId = actorUserId,
            now = fixedNow,
        )

    fun issueEvent(
        issueKey: String,
        summary: String = "요약",
        actorId: UUID = UUID.randomUUID(),
    ) = objectMapper.readTree(
        """
        {"type":"issue.created","issueKey":"$issueKey","projectKey":"PROJ",
         "summary":"$summary","reporterId":"$actorId","actorId":"$actorId",
         "occurredAt":"2026-07-11T00:00:00Z"}
        """.trimIndent(),
    )

    fun issueSnapshot(
        key: String = "PROJ-1",
        status: String = "open",
        assigneeId: UUID? = null,
    ): IssueSnapshot =
        IssueSnapshot(
            key = key,
            projectKey = "PROJ",
            type = "Task",
            status = status,
            priority = 3,
            assigneeId = assigneeId,
            reporterId = UUID.randomUUID(),
            labels = emptyList(),
            summary = "요약",
        )

    describe("E-a 빈 액션 리스트") {
        it("no-op SUCCESS 를 반환하고 포트/클라이언트를 호출하지 않는다") {
            val rule = newRule()
            every { actionRepository.findByRuleId(rule.id) } returns emptyList()

            val result = executor.execute(rule, issueEvent("PROJ-1"))

            result.status shouldBe ActionExecutionStatus.SUCCESS
            result.outcomes.shouldBeEmpty()
            issueMutationPort.setFieldCalls.shouldBeEmpty()
            verify(exactly = 0) { webhookActionClient.call(any(), any(), any(), any()) }
        }
    }

    describe("E-b SET_FIELD 디스패치") {
        it("actor·이슈키·필드·JSON 인코딩 value 로 setField 를 호출한다") {
            val rule = newRule()
            val actions = listOf(Action.SetFieldAction(field = "priority", value = TextNode("High")))
            every { actionRepository.findByRuleId(rule.id) } returns actions

            val result = executor.execute(rule, issueEvent("PROJ-1"))

            result.status shouldBe ActionExecutionStatus.SUCCESS
            result.outcomes shouldBe listOf(ActionOutcome(0, ActionType.SET_FIELD, success = true, error = null))
            val cmd = issueMutationPort.setFieldCalls.single()
            cmd.actorUserId shouldBe rule.actorUserId
            cmd.issueKey shouldBe "PROJ-1"
            cmd.field shouldBe "priority"
            cmd.value shouldBe "\"High\""
            cmd.dryRun shouldBe false
        }

        it("value 가 JSON null 이면 command.value 는 null 이다(필드 해제)") {
            val rule = newRule()
            val actions = listOf(Action.SetFieldAction(field = "assignee", value = NullNode.instance))
            every { actionRepository.findByRuleId(rule.id) } returns actions

            executor.execute(rule, issueEvent("PROJ-1"))

            issueMutationPort.setFieldCalls.single().value shouldBe null
        }
    }

    describe("E-c ASSIGN 디스패치") {
        it("actor·이슈키·assigneeId 로 assign 을 호출한다") {
            val rule = newRule()
            val assigneeId = UUID.randomUUID()
            val actions = listOf(Action.AssignAction(assigneeId = assigneeId))
            every { actionRepository.findByRuleId(rule.id) } returns actions

            val result = executor.execute(rule, issueEvent("PROJ-1"))

            result.status shouldBe ActionExecutionStatus.SUCCESS
            val cmd = issueMutationPort.assignCalls.single()
            cmd.actorUserId shouldBe rule.actorUserId
            cmd.issueKey shouldBe "PROJ-1"
            cmd.assigneeId shouldBe assigneeId
        }
    }

    describe("E-d ADD_COMMENT 디스패치") {
        it("body 를 triggerEvent 컨텍스트로 템플릿 치환한 뒤 addComment 를 호출한다") {
            val rule = newRule()
            val actions = listOf(Action.AddCommentAction(body = "이슈 {{ issue.issueKey }} - {{ issue.summary }}"))
            every { actionRepository.findByRuleId(rule.id) } returns actions

            val result = executor.execute(rule, issueEvent("PROJ-2", summary = "템플릿 테스트"))

            result.status shouldBe ActionExecutionStatus.SUCCESS
            val cmd = issueMutationPort.addCommentCalls.single()
            cmd.actorUserId shouldBe rule.actorUserId
            cmd.issueKey shouldBe "PROJ-2"
            cmd.body shouldBe "이슈 PROJ-2 - 템플릿 테스트"
        }
    }

    describe("E-e CALL_WEBHOOK 디스패치") {
        it("url/body 를 템플릿 치환한 뒤 WebhookActionClient.call 을 호출한다(이슈 불필요)") {
            val rule = newRule()
            val actions =
                listOf(
                    Action.CallWebhookAction(
                        url = "https://example.com/hook/{{ issue.issueKey }}",
                        method = "POST",
                        headers = mapOf("X-Test" to "1"),
                        body = """{"key":"{{ issue.issueKey }}"}""",
                    ),
                )
            every { actionRepository.findByRuleId(rule.id) } returns actions
            every { webhookActionClient.call(any(), any(), any(), any()) } returns
                WebhookCallResult(success = true, statusCode = 200, error = null)

            val result = executor.execute(rule, issueEvent("PROJ-3"))

            result.status shouldBe ActionExecutionStatus.SUCCESS
            verify(exactly = 1) {
                webhookActionClient.call(
                    "https://example.com/hook/PROJ-3",
                    "POST",
                    mapOf("X-Test" to "1"),
                    """{"key":"PROJ-3"}""",
                )
            }
            issueMutationPort.setFieldCalls.shouldBeEmpty()
            issueMutationPort.assignCalls.shouldBeEmpty()
            issueMutationPort.addCommentCalls.shouldBeEmpty()
        }
    }

    describe("E-f dryRun 전파") {
        it("execute(dryRun=true) 는 SetFieldCommand.dryRun 을 true 로 전달한다") {
            val rule = newRule()
            val actions = listOf(Action.SetFieldAction(field = "priority", value = TextNode("Low")))
            every { actionRepository.findByRuleId(rule.id) } returns actions

            executor.execute(rule, issueEvent("PROJ-1"), dryRun = true)

            issueMutationPort.setFieldCalls.single().dryRun shouldBe true
        }
    }

    describe("E-g 부분 실패 — issueKey 없는 이슈 액션은 실패, 나머지는 계속 진행") {
        it("SET_FIELD 는 실패(ISSUE_KEY_MISSING), CALL_WEBHOOK 은 성공 → PARTIAL") {
            val rule = newRule()
            val actions =
                listOf(
                    Action.SetFieldAction(field = "priority", value = TextNode("High")),
                    Action.CallWebhookAction(url = "https://example.com/hook", body = "{}"),
                )
            every { actionRepository.findByRuleId(rule.id) } returns actions
            every { webhookActionClient.call(any(), any(), any(), any()) } returns
                WebhookCallResult(success = true, statusCode = 200, error = null)

            // 원천 이벤트에 issueKey 가 전혀 없는 상황(예: SCHEDULED/WEBHOOK 빈 payload) 재현.
            val result = executor.execute(rule, objectMapper.readTree("{}"))

            result.status shouldBe ActionExecutionStatus.PARTIAL
            result.outcomes shouldBe
                listOf(
                    ActionOutcome(0, ActionType.SET_FIELD, success = false, error = "ISSUE_KEY_MISSING"),
                    ActionOutcome(1, ActionType.CALL_WEBHOOK, success = true, error = null),
                )
            issueMutationPort.setFieldCalls.shouldBeEmpty()
        }
    }

    describe("E-h 전부 성공") {
        it("4종 액션이 모두 성공하면 SUCCESS 를 반환한다") {
            val rule = newRule()
            val assigneeId = UUID.randomUUID()
            val actions =
                listOf(
                    Action.SetFieldAction(field = "priority", value = TextNode("High")),
                    Action.AssignAction(assigneeId = assigneeId),
                    Action.AddCommentAction(body = "완료: {{ issue.issueKey }}"),
                    Action.CallWebhookAction(url = "https://example.com/hook", body = "{}"),
                )
            every { actionRepository.findByRuleId(rule.id) } returns actions
            every { webhookActionClient.call(any(), any(), any(), any()) } returns
                WebhookCallResult(success = true, statusCode = 200, error = null)

            val result = executor.execute(rule, issueEvent("PROJ-4"))

            result.status shouldBe ActionExecutionStatus.SUCCESS
            result.outcomes.size shouldBe 4
            result.outcomes.forEach { it.success shouldBe true }
            result.outcomes.map { it.position } shouldBe listOf(0, 1, 2, 3)
        }
    }

    describe("E-i 전부 실패 — 사유 분류") {
        it("타입 있는 IssueMutationPermissionDeniedException 이면 PERMISSION_DENIED 로 분류하고 FAILED 를 반환한다") {
            val rule = newRule()
            val actions = listOf(Action.SetFieldAction(field = "priority", value = TextNode("High")))
            every { actionRepository.findByRuleId(rule.id) } returns actions
            issueMutationPort.failNextCallsWith(IssueMutationPermissionDeniedException("이슈 변경 권한이 없습니다(테스트)"))

            val result = executor.execute(rule, issueEvent("PROJ-1"))

            result.status shouldBe ActionExecutionStatus.FAILED
            result.outcomes shouldBe
                listOf(ActionOutcome(0, ActionType.SET_FIELD, success = false, error = "PERMISSION_DENIED"))
        }

        it("클래스명에 AccessDenied 가 들어있어도 타입이 아니면 FAILED 로 분류한다(문자열 휴리스틱 제거 회귀 방지)") {
            val rule = newRule()
            val actions = listOf(Action.SetFieldAction(field = "priority", value = TextNode("High")))
            every { actionRepository.findByRuleId(rule.id) } returns actions
            issueMutationPort.failNextCallsWith(FakeIssueAccessDeniedException())

            val result = executor.execute(rule, issueEvent("PROJ-1"))

            result.status shouldBe ActionExecutionStatus.FAILED
            result.outcomes shouldBe
                listOf(ActionOutcome(0, ActionType.SET_FIELD, success = false, error = "FAILED"))
        }

        it("그 외 예외는 FAILED 로 분류하고 전체 상태도 FAILED 를 반환한다") {
            val rule = newRule()
            val actions = listOf(Action.SetFieldAction(field = "priority", value = TextNode("High")))
            every { actionRepository.findByRuleId(rule.id) } returns actions
            issueMutationPort.failNextCallsWith(RuntimeException("이슈를 찾을 수 없습니다"))

            val result = executor.execute(rule, issueEvent("PROJ-1"))

            result.status shouldBe ActionExecutionStatus.FAILED
            result.outcomes shouldBe listOf(ActionOutcome(0, ActionType.SET_FIELD, success = false, error = "FAILED"))
        }
    }

    describe("E-j 조건 게이트 (FR-AT-03)") {
        it("조건이 없으면 게이트를 통과해 기존과 동일하게 액션을 실행한다") {
            val rule = newRule()
            val actions = listOf(Action.SetFieldAction(field = "priority", value = TextNode("High")))
            every { actionRepository.findByRuleId(rule.id) } returns actions
            every { conditionRepository.findByRuleId(rule.id) } returns null

            val result = executor.execute(rule, issueEvent("PROJ-1"))

            result.status shouldBe ActionExecutionStatus.SUCCESS
            issueMutationPort.setFieldCalls.single().field shouldBe "priority"
        }

        it("조건을 만족하면 액션이 정상 실행된다") {
            val rule = newRule()
            val actions = listOf(Action.SetFieldAction(field = "priority", value = TextNode("High")))
            every { actionRepository.findByRuleId(rule.id) } returns actions
            val condition = Condition.Comparison("issue.status", ComparisonOperator.EQUALS, TextNode("open"))
            every { conditionRepository.findByRuleId(rule.id) } returns condition
            issueSnapshotPort.seed(rule.actorUserId, "PROJ-1", issueSnapshot(status = "open"))

            val result = executor.execute(rule, issueEvent("PROJ-1"))

            result.status shouldBe ActionExecutionStatus.SUCCESS
            issueMutationPort.setFieldCalls.single().field shouldBe "priority"
        }

        it("조건을 만족하지 못하면 SKIPPED 를 반환하고 액션은 전혀 실행되지 않는다") {
            val rule = newRule()
            val actions = listOf(Action.SetFieldAction(field = "priority", value = TextNode("High")))
            every { actionRepository.findByRuleId(rule.id) } returns actions
            val condition = Condition.Comparison("issue.status", ComparisonOperator.EQUALS, TextNode("done"))
            every { conditionRepository.findByRuleId(rule.id) } returns condition
            issueSnapshotPort.seed(rule.actorUserId, "PROJ-1", issueSnapshot(status = "open"))

            val result = executor.execute(rule, issueEvent("PROJ-1"))

            result.status shouldBe ActionExecutionStatus.SKIPPED
            result.outcomes.shouldBeEmpty()
            issueMutationPort.setFieldCalls.shouldBeEmpty()
        }

        it("스냅샷을 조회할 수 없으면(시드되지 않음/가시성 제한) fail-safe 로 SKIPPED 를 반환한다") {
            val rule = newRule()
            val actions = listOf(Action.SetFieldAction(field = "priority", value = TextNode("High")))
            every { actionRepository.findByRuleId(rule.id) } returns actions
            val condition = Condition.Comparison("issue.status", ComparisonOperator.EQUALS, TextNode("open"))
            every { conditionRepository.findByRuleId(rule.id) } returns condition
            // 의도적으로 시드하지 않는다 — issueSnapshotPort.fetch 가 null 을 반환하는 상황 재현.

            val result = executor.execute(rule, issueEvent("PROJ-1"))

            result.status shouldBe ActionExecutionStatus.SKIPPED
            issueMutationPort.setFieldCalls.shouldBeEmpty()
        }

        it("triggerEvent 에 이슈 키가 없으면(SCHEDULED/WEBHOOK 빈 payload) 조건이 있을 때 SKIPPED 로 처리된다") {
            val rule = newRule()
            val actions = listOf(Action.SetFieldAction(field = "priority", value = TextNode("High")))
            every { actionRepository.findByRuleId(rule.id) } returns actions
            val condition = Condition.Comparison("issue.status", ComparisonOperator.EQUALS, TextNode("open"))
            every { conditionRepository.findByRuleId(rule.id) } returns condition

            val result = executor.execute(rule, objectMapper.readTree("{}"))

            result.status shouldBe ActionExecutionStatus.SKIPPED
            issueMutationPort.setFieldCalls.shouldBeEmpty()
        }

        it("조건 불충족이면 액션 리스트가 비어 있어도 SUCCESS 가 아니라 SKIPPED 다(빈 액션 SUCCESS 보다 조건 우선)") {
            val rule = newRule()
            every { actionRepository.findByRuleId(rule.id) } returns emptyList()
            val condition = Condition.Comparison("issue.status", ComparisonOperator.EQUALS, TextNode("done"))
            every { conditionRepository.findByRuleId(rule.id) } returns condition
            issueSnapshotPort.seed(rule.actorUserId, "PROJ-1", issueSnapshot(status = "open"))

            val result = executor.execute(rule, issueEvent("PROJ-1"))

            result.status shouldBe ActionExecutionStatus.SKIPPED
        }

        it("UUID 필드(assignee)는 문자열로 매핑돼 조건 리터럴(문자열)과 비교된다") {
            val rule = newRule()
            val actions = listOf(Action.SetFieldAction(field = "priority", value = TextNode("High")))
            every { actionRepository.findByRuleId(rule.id) } returns actions
            val assigneeId = UUID.randomUUID()
            val condition =
                Condition.Comparison("issue.assignee", ComparisonOperator.EQUALS, TextNode(assigneeId.toString()))
            every { conditionRepository.findByRuleId(rule.id) } returns condition
            issueSnapshotPort.seed(rule.actorUserId, "PROJ-1", issueSnapshot(assigneeId = assigneeId))

            val result = executor.execute(rule, issueEvent("PROJ-1"))

            result.status shouldBe ActionExecutionStatus.SUCCESS
        }

        it("dryRun=true 에서도 조건 게이트가 동일하게 적용된다") {
            val rule = newRule()
            val actions = listOf(Action.SetFieldAction(field = "priority", value = TextNode("High")))
            every { actionRepository.findByRuleId(rule.id) } returns actions
            val condition = Condition.Comparison("issue.status", ComparisonOperator.EQUALS, TextNode("done"))
            every { conditionRepository.findByRuleId(rule.id) } returns condition
            issueSnapshotPort.seed(rule.actorUserId, "PROJ-1", issueSnapshot(status = "open"))

            val result = executor.execute(rule, issueEvent("PROJ-1"), dryRun = true)

            result.status shouldBe ActionExecutionStatus.SKIPPED
            issueMutationPort.setFieldCalls.shouldBeEmpty()
        }
    }
})

/**
 * [ActionExecutorTest] E-i 전용 — 클래스명에 `AccessDenied` 를 포함하지만 포트 계약의 타입 있는
 * [IssueMutationPermissionDeniedException] 이 **아닌** 가짜 예외. 문자열 휴리스틱 제거(FR-AT-02 C3)
 * 이후 이 예외는 PERMISSION_DENIED 가 아니라 FAILED 로 분류돼야 한다(회귀 방지).
 */
private class FakeIssueAccessDeniedException : RuntimeException("permission denied (fake)")
