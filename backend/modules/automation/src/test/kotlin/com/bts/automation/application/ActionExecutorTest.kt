// ActionExecutor 단위 테스트 — 4종 액션 디스패치/actor 전달/템플릿 치환/dryRun 전파/부분실패 집계 (FR-AT-02 Task 9 TDD RED)

package com.bts.automation.application

import com.bts.automation.StubIssueMutationPort
import com.bts.automation.adapter.AutomationActionRepository
import com.bts.automation.adapter.WebhookActionClient
import com.bts.automation.adapter.WebhookCallResult
import com.bts.automation.domain.Action
import com.bts.automation.domain.ActionType
import com.bts.automation.domain.AutomationRule
import com.bts.automation.domain.TriggerType
import com.bts.shared.issue.IssueMutationPermissionDeniedException
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
 */
class ActionExecutorTest : DescribeSpec({

    val fixedNow: Instant = Instant.parse("2026-07-11T00:00:00Z")
    val objectMapper = ObjectMapper()

    val issueMutationPort = StubIssueMutationPort()
    val webhookActionClient = mockk<WebhookActionClient>()
    val actionRepository = mockk<AutomationActionRepository>()

    val executor = ActionExecutor(issueMutationPort, webhookActionClient, actionRepository, objectMapper)

    afterEach {
        clearMocks(webhookActionClient, actionRepository)
        issueMutationPort.reset()
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
})

/**
 * [ActionExecutorTest] E-i 전용 — 클래스명에 `AccessDenied` 를 포함하지만 포트 계약의 타입 있는
 * [IssueMutationPermissionDeniedException] 이 **아닌** 가짜 예외. 문자열 휴리스틱 제거(FR-AT-02 C3)
 * 이후 이 예외는 PERMISSION_DENIED 가 아니라 FAILED 로 분류돼야 한다(회귀 방지).
 */
private class FakeIssueAccessDeniedException : RuntimeException("permission denied (fake)")
