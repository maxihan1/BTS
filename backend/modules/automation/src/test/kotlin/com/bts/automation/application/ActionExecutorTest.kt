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
import com.bts.shared.issue.IssueSnapshotPort
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
 * - E-m. 프로젝트 경계 게이트(FR-AT-07 PR-C) — 타 프로젝트 이슈 키 SKIPPED/접두 하이픈/이슈 키 없음
 *   통과(SCHEDULED 보호)/조건 없는 룰/replay 경로
 */
class ActionExecutorTest : DescribeSpec({

    val fixedNow: Instant = Instant.parse("2026-07-11T00:00:00Z")
    val objectMapper = ObjectMapper()

    val issueMutationPort = StubIssueMutationPort()
    val webhookActionClient = mockk<WebhookActionClient>()
    val actionRepository = mockk<AutomationActionRepository>()
    val issueSnapshotPort = StubIssueSnapshotPort()
    // relaxed 미사용 — nullable 반환(Condition?) 기본값은 beforeEach 에서 명시 고정한다(아래 주석 참조).
    val conditionRepository = mockk<AutomationConditionRepository>()

    val executor =
        ActionExecutor(
            issueMutationPort = issueMutationPort,
            webhookActionClient = webhookActionClient,
            actionRepository = actionRepository,
            objectMapper = objectMapper,
            issueSnapshotPort = issueSnapshotPort,
            conditionRepository = conditionRepository,
        )

    beforeEach {
        // relaxed mockk 는 nullable 반환 타입(Condition?)에도 합성 프록시를 만들어 null 이 아닌 값을
        // 반환할 수 있다 — 조건 게이트가 항상 활성화돼 기존 E-a~E-i 시나리오가 깨지므로, "조건 없음"을
        // 기본값으로 명시 고정한다(조건이 필요한 E-j 케이스는 이 뒤에 더 구체적인 every 로 오버라이드).
        every { conditionRepository.findByRuleId(any()) } returns null
    }

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

    // createdBy 와 actorUserId 가 서로 다른 룰(§12.4 게이트 조회 주체 검증용) — changeActor 로 actor 를
    // 위조 교체한 상황을 재현한다. createdBy 는 위조 불가(생성 시 고정), actorUserId 는 임의 교체 가능.
    fun ruleWithDistinctActor(
        createdBy: UUID,
        actorUserId: UUID,
    ): AutomationRule =
        AutomationRule.create(
            projectKey = "PROJ",
            name = "룰",
            triggerType = TriggerType.ISSUE_CREATED,
            createdBy = createdBy,
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

    describe("E-k §12.4 조건 게이트는 createdBy 가시성으로 강제된다 (관리자 우회 없음)") {
        it("actorUserId 만 이슈를 볼 수 있고 createdBy 는 못 보면 SKIPPED 다 — 게이트는 createdBy 로 조회") {
            // §12.4 회귀 방지. actorUserId 는 changeActor 로 위조 교체 가능하므로, 조건 관측은 위조 불가한
            // createdBy(룰 작성자) 가시성으로만 이뤄져야 한다. 스냅샷을 actorUserId 로만 시드하면(=actor 만
            // 볼 수 있는 상태), 게이트가 createdBy 로 조회할 때 null → SKIPPED 여야 한다. 게이트가 잘못
            // actorUserId 로 조회하면 조건이 평가돼 액션이 실행되고 이 테스트가 실패하여 회귀를 잡는다.
            val createdBy = UUID.randomUUID()
            val actorUserId = UUID.randomUUID()
            val rule = ruleWithDistinctActor(createdBy = createdBy, actorUserId = actorUserId)
            val actions = listOf(Action.SetFieldAction(field = "priority", value = TextNode("High")))
            every { actionRepository.findByRuleId(rule.id) } returns actions
            val condition = Condition.Comparison("issue.status", ComparisonOperator.EQUALS, TextNode("open"))
            every { conditionRepository.findByRuleId(rule.id) } returns condition
            issueSnapshotPort.seed(actorUserId, "PROJ-1", issueSnapshot(status = "open"))

            val result = executor.execute(rule, issueEvent("PROJ-1"))

            result.status shouldBe ActionExecutionStatus.SKIPPED
            issueMutationPort.setFieldCalls.shouldBeEmpty()
        }

        it("createdBy 가 이슈를 볼 수 있으면 조건이 정상 평가된다 — 게이트는 createdBy 로 조회") {
            // 위 테스트의 대칭. createdBy 로 시드하면(작성자가 볼 수 있는 상태) 조건이 평가돼 액션 실행.
            // 게이트가 잘못 actorUserId 로 조회하면 null → SKIPPED 로 이 테스트가 실패한다.
            val createdBy = UUID.randomUUID()
            val actorUserId = UUID.randomUUID()
            val rule = ruleWithDistinctActor(createdBy = createdBy, actorUserId = actorUserId)
            val actions = listOf(Action.SetFieldAction(field = "priority", value = TextNode("High")))
            every { actionRepository.findByRuleId(rule.id) } returns actions
            val condition = Condition.Comparison("issue.status", ComparisonOperator.EQUALS, TextNode("open"))
            every { conditionRepository.findByRuleId(rule.id) } returns condition
            issueSnapshotPort.seed(createdBy, "PROJ-1", issueSnapshot(status = "open"))

            val result = executor.execute(rule, issueEvent("PROJ-1"))

            result.status shouldBe ActionExecutionStatus.SUCCESS
            issueMutationPort.setFieldCalls.single().field shouldBe "priority"
        }
    }

    describe("E-l 게이트 fail-safe — 조건 조회/스냅샷 조회 예외는 전파하지 않고 SKIPPED") {
        it("conditionRepository.findByRuleId 가 예외를 던져도 SKIPPED 로 처리하고 예외를 전파하지 않는다") {
            val rule = newRule()
            val actions = listOf(Action.SetFieldAction(field = "priority", value = TextNode("High")))
            every { actionRepository.findByRuleId(rule.id) } returns actions
            every { conditionRepository.findByRuleId(rule.id) } throws RuntimeException("조건 DB 조회 실패")

            val result = executor.execute(rule, issueEvent("PROJ-1"))

            result.status shouldBe ActionExecutionStatus.SKIPPED
            issueMutationPort.setFieldCalls.shouldBeEmpty()
        }

        it("issueSnapshotPort.fetch 가 예외(이슈 이동 등)를 던져도 SKIPPED 로 처리하고 전파하지 않는다") {
            val rule = newRule()
            val actions = listOf(Action.SetFieldAction(field = "priority", value = TextNode("High")))
            every { actionRepository.findByRuleId(rule.id) } returns actions
            val condition = Condition.Comparison("issue.status", ComparisonOperator.EQUALS, TextNode("open"))
            every { conditionRepository.findByRuleId(rule.id) } returns condition
            // StubIssueSnapshotPort 는 예외를 던지지 않으므로, 이 케이스 한정으로 예외를 던지는 포트로
            // executor 를 별도 구성한다(어댑터가 IssueMovedException 류를 재전파하는 상황 재현).
            val throwingSnapshotPort = mockk<IssueSnapshotPort>()
            every { throwingSnapshotPort.fetch(any(), any()) } throws RuntimeException("이슈가 다른 프로젝트로 이동됨")
            val throwingExecutor =
                ActionExecutor(
                    issueMutationPort = issueMutationPort,
                    webhookActionClient = webhookActionClient,
                    actionRepository = actionRepository,
                    objectMapper = objectMapper,
                    issueSnapshotPort = throwingSnapshotPort,
                    conditionRepository = conditionRepository,
                )

            val result = throwingExecutor.execute(rule, issueEvent("PROJ-1"))

            result.status shouldBe ActionExecutionStatus.SKIPPED
            issueMutationPort.setFieldCalls.shouldBeEmpty()
        }

        it("조건이 미설정(null)이면 SKIPPED 가 아니라 액션이 정상 진행된다 — 예외 fail-safe 와 구분(시맨틱 보존)") {
            val rule = newRule()
            val actions = listOf(Action.SetFieldAction(field = "priority", value = TextNode("High")))
            every { actionRepository.findByRuleId(rule.id) } returns actions
            every { conditionRepository.findByRuleId(rule.id) } returns null

            val result = executor.execute(rule, issueEvent("PROJ-1"))

            result.status shouldBe ActionExecutionStatus.SUCCESS
            issueMutationPort.setFieldCalls.single().field shouldBe "priority"
        }
    }

    describe("E-m 프로젝트 경계 게이트 — triggerEvent 이슈 키는 룰 projectKey 소속이어야 한다 (FR-AT-07 PR-C)") {
        // triggerEvent 는 인바운드 웹훅(permitAll 경로)으로 외부에서 주입될 수 있는 신뢰 불가 입력이다.
        // 게이트가 없으면 공격자가 issueKey 를 임의 프로젝트로 지정해 룰 actor 권한으로 cross-project
        // 변경을 일으킬 수 있다(폭발 반경 = 룰 actor 권한). issueEvent() 픽스처의 payload 는 자기
        // 자신을 `"projectKey":"PROJ"` 라고 주장하지만, 그 자기 신고 값은 신뢰 대상이 아니다 —
        // 판정은 rule.projectKey(서버 보유 값) 대 추출된 issueKey 의 접두 비교로만 이뤄져야 한다.

        it("조건이 없는 룰이어도 다른 프로젝트 이슈 키(OTHER-1)면 SKIPPED 다") {
            // 조건 게이트는 조건 미설정 룰을 그대로 통과시키므로(isConditionUnmet 의 조건 null 분기),
            // 이 시나리오에서 SKIPPED 를 만들 수 있는 주체는 프로젝트 경계 게이트뿐이다.
            val rule = newRule() // projectKey = "PROJ"
            val actions = listOf(Action.SetFieldAction(field = "priority", value = TextNode("High")))
            every { actionRepository.findByRuleId(rule.id) } returns actions
            every { conditionRepository.findByRuleId(rule.id) } returns null

            val result = executor.execute(rule, issueEvent("OTHER-1"))

            result.status shouldBe ActionExecutionStatus.SKIPPED
            result.outcomes.shouldBeEmpty()
            issueMutationPort.setFieldCalls.shouldBeEmpty()
        }

        it("조건이 충족되는 상황에서도 다른 프로젝트 이슈 키면 SKIPPED 다 — 조건 게이트와 독립") {
            val rule = newRule()
            val actions = listOf(Action.SetFieldAction(field = "priority", value = TextNode("High")))
            every { actionRepository.findByRuleId(rule.id) } returns actions
            val condition = Condition.Comparison("issue.status", ComparisonOperator.EQUALS, TextNode("open"))
            every { conditionRepository.findByRuleId(rule.id) } returns condition
            // 조건이 통과하도록 스냅샷을 시드한다 — 게이트가 없으면 SUCCESS 가 되어 이 테스트가 실패한다.
            issueSnapshotPort.seed(rule.createdBy, "OTHER-1", issueSnapshot(key = "OTHER-1", status = "open"))

            val result = executor.execute(rule, issueEvent("OTHER-1"))

            result.status shouldBe ActionExecutionStatus.SKIPPED
            issueMutationPort.setFieldCalls.shouldBeEmpty()
        }

        it("같은 프로젝트 이슈 키(PROJ-42)는 게이트를 통과해 정상 실행된다") {
            val rule = newRule()
            val actions = listOf(Action.SetFieldAction(field = "priority", value = TextNode("High")))
            every { actionRepository.findByRuleId(rule.id) } returns actions

            val result = executor.execute(rule, issueEvent("PROJ-42"))

            result.status shouldBe ActionExecutionStatus.SUCCESS
            issueMutationPort.setFieldCalls.single().issueKey shouldBe "PROJ-42"
        }

        it("PROJ2-1 은 PROJ 룰에서 SKIPPED 다 — 접두 비교에 하이픈이 빠지면 통과해버린다") {
            // 이슈 키 접두 정규식(^[A-Z][A-Z0-9]{1,9}$)은 PROJ 와 PROJ2 를 둘 다 허용하므로 실재
            // 가능한 조합이다. startsWith(rule.projectKey) 만으로 비교하면 PROJ2-1 이 PROJ 룰을 통과한다.
            val rule = newRule()
            val actions = listOf(Action.SetFieldAction(field = "priority", value = TextNode("High")))
            every { actionRepository.findByRuleId(rule.id) } returns actions

            val result = executor.execute(rule, issueEvent("PROJ2-1"))

            result.status shouldBe ActionExecutionStatus.SKIPPED
            issueMutationPort.setFieldCalls.shouldBeEmpty()
        }

        it("이슈 키 없는 빈 payload({})는 게이트를 통과한다 — SCHEDULED/이슈 없는 WEBHOOK 룰 보호") {
            // AutomationScheduleWorker 는 빈 {} 를 발행하므로 이슈 키가 null 이다. null 을 SKIPPED 로
            // 처리하면 모든 SCHEDULED 룰과 이슈를 쓰지 않는 CALL_WEBHOOK 룰이 정지한다(FR-AT-01 사문화).
            // 이슈 키가 필요한 액션은 기존대로 ISSUE_KEY_MISSING 으로 처리된다(E-g).
            val rule = newRule()
            val actions = listOf(Action.CallWebhookAction(url = "https://example.com/hook", body = "{}"))
            every { actionRepository.findByRuleId(rule.id) } returns actions
            every { webhookActionClient.call(any(), any(), any(), any()) } returns
                WebhookCallResult(success = true, statusCode = 200, error = null)

            val result = executor.execute(rule, objectMapper.readTree("{}"))

            result.status shouldBe ActionExecutionStatus.SUCCESS
            result.outcomes shouldBe listOf(ActionOutcome(0, ActionType.CALL_WEBHOOK, success = true, error = null))
        }

        it("replay 경로(RuleExecutionService.replay 동형 호출)에도 게이트가 걸린다") {
            // RuleExecutionService.replay 는 저장된 rule_executions.trigger_event 를 그대로
            // executor.execute(rule, record.triggerEvent, dryRun = false) 로 재실행하며, 워커의 루프
            // 가드를 거치지 않는다. 웹훅으로 심어진 오염 triggerEvent 는 이력에 영구 보존되므로,
            // 게이트가 워커에만 있으면 관리자의 replay 로 cross-project 변경이 재발한다. 게이트를
            // execute 내부에 두어 워커/replay/미래 경로가 같은 choke point 를 지나게 한다.
            val rule = newRule()
            val actions = listOf(Action.SetFieldAction(field = "priority", value = TextNode("High")))
            every { actionRepository.findByRuleId(rule.id) } returns actions
            val persistedPoisonedTriggerEvent = issueEvent("OTHER-1")

            val result = executor.execute(rule, persistedPoisonedTriggerEvent, dryRun = false)

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
