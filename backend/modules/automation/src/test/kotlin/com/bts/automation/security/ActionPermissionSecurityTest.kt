// FR-AT-02 자동화 액션 보안 경계 검증 — rule actor 권한 fail-closed·actor 위조 차단·SSRF·비활성 actor 거부 (Task 13)

package com.bts.automation.security

import com.bts.automation.StubIssueMutationPort
import com.bts.automation.adapter.AutomationActionRepository
import com.bts.automation.adapter.WebhookActionClient
import com.bts.automation.adapter.WebhookCallResult
import com.bts.automation.application.ActionExecutionStatus
import com.bts.automation.application.ActionExecutor
import com.bts.automation.domain.Action
import com.bts.automation.domain.ActionType
import com.bts.automation.domain.AutomationRule
import com.bts.automation.domain.TriggerType
import com.bts.shared.http.OutboundHttpClientConfig
import com.bts.shared.http.OutboundUrlValidator
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.TextNode
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.util.UUID

/**
 * automation 측 자동화 액션 보안 경계 검증 (FR-AT-02 Task 13, 검증 전용 — src/main 무수정).
 *
 * 권한 실판정은 issue-tracking prod 어댑터([com.bts.shared.issue.IssueMutationPort] 의
 * `@Profile("prod")` 구현, Task 7 `AutomationIssueMutationAdapterTest` 에서 검증)가 담당한다. 이
 * 테스트는 automation 측이 지켜야 할 보안 **행위**만 검증한다 — 포트가 던진 권한 예외를 삼키지 않고
 * 액션 실패로 집계하는가, actor 를 이벤트 payload 가 아닌 룰에서만 취하는가, 웹훅 url 을 렌더 후
 * SSRF 재검증하는가.
 *
 * ### 검증 시나리오
 * - 시나리오 1 — 권한 부족 fail-closed 집계. 포트가 권한 예외를 던지면 삼키지 않고
 *   `PERMISSION_DENIED` 실패로 집계하며 나머지 액션은 best-effort 로 진행([[best-effort-loop-permission-exception-nonprod-mask]]).
 * - 시나리오 2 — actor 위조 차단. triggerEvent payload 의 `actor`/`actorId` 값과 무관하게 포트로
 *   전달되는 actor 는 항상 `rule.actorUserId` 다(SecurityContext/payload 미신뢰).
 * - 시나리오 3 — CallWebhook SSRF 차단. 사설/loopback/메타데이터 IP 로 렌더되는 url 은
 *   [OutboundUrlValidator] 가 외부 호출 없이 차단하고 best-effort 로 다음 액션 진행.
 * - 시나리오 4 — rule actor 비활성/미존재(EC11). 포트가 fail-closed 예외를 던지면 액션은 거부
 *   집계되며(성공 아님) 권한 부족과 동일 처리된다.
 *
 * [StubIssueMutationPort](fail-safe 실 stub) 의 [StubIssueMutationPort.failNextCallsWith] 로 포트
 * 예외를 주입하고, 시나리오 3 만 실제 [WebhookActionClient](실 [OutboundUrlValidator] + 실 RestClient)
 * 로 렌더 후 url 검증 경로를 검증한다(차단 url 은 네트워크 전송이 없어 결정적).
 */
class ActionPermissionSecurityTest : DescribeSpec({

    val fixedNow: Instant = Instant.parse("2026-07-11T00:00:00Z")
    val objectMapper = ObjectMapper()

    val issueMutationPort = StubIssueMutationPort()
    val mockWebhookClient = mockk<WebhookActionClient>()
    val actionRepository = mockk<AutomationActionRepository>()

    // 시나리오 1·2·4 — 웹훅은 mock(네트워크 없이 best-effort 진행만 관측).
    val executor = ActionExecutor(issueMutationPort, mockWebhookClient, actionRepository, objectMapper)

    // 시나리오 3 — 실제 SSRF 검증기 + 실제 RestClient 로 "렌더 후 url 검증" 경로를 검증.
    // 차단 url 은 검증 단계에서 막혀 실제 HTTP 전송이 발생하지 않으므로 결정적이다.
    val realWebhookClient =
        WebhookActionClient(OutboundUrlValidator(), OutboundHttpClientConfig().outboundHttpRestClient())
    val ssrfExecutor = ActionExecutor(issueMutationPort, realWebhookClient, actionRepository, objectMapper)

    afterEach {
        clearMocks(mockWebhookClient, actionRepository)
        issueMutationPort.reset()
    }

    fun ruleWithActor(actorUserId: UUID): AutomationRule =
        AutomationRule.create(
            projectKey = "PROJ",
            name = "보안 룰",
            triggerType = TriggerType.ISSUE_CREATED,
            createdBy = actorUserId,
            actorUserId = actorUserId,
            now = fixedNow,
        )

    fun issueEvent(
        issueKey: String,
        actorId: UUID = UUID.randomUUID(),
    ): JsonNode =
        objectMapper.readTree(
            """
            {"type":"issue.created","issueKey":"$issueKey","projectKey":"PROJ",
             "summary":"요약","reporterId":"$actorId","actorId":"$actorId",
             "occurredAt":"2026-07-11T00:00:00Z"}
            """.trimIndent(),
        )

    describe("시나리오 1 — 권한 부족 fail-closed 집계") {
        it("이슈 변경 3종이 권한 예외로 실패해도 삼키지 않고 PERMISSION_DENIED 로 집계하며 나머지 액션은 계속 진행한다") {
            val rule = ruleWithActor(UUID.randomUUID())
            val actions =
                listOf(
                    Action.SetFieldAction(field = "priority", value = TextNode("High")),
                    Action.AssignAction(assigneeId = UUID.randomUUID()),
                    Action.AddCommentAction(body = "자동 처리"),
                    Action.CallWebhookAction(url = "https://example.com/hook", body = "{}"),
                )
            every { actionRepository.findByRuleId(rule.id) } returns actions
            every { mockWebhookClient.call(any(), any(), any(), any()) } returns
                WebhookCallResult(success = true, statusCode = 200, error = null)
            issueMutationPort.failNextCallsWith(FakePermissionAccessDeniedException())

            val result = executor.execute(rule, issueEvent("PROJ-1"))

            // 권한 예외가 조용히 무시돼 성공으로 집계되면 안 됨 — 이슈 변경 3종 전부 실패 + PERMISSION_DENIED.
            val issueOutcomes = result.outcomes.filter { it.actionType != ActionType.CALL_WEBHOOK }
            issueOutcomes.size shouldBe 3
            issueOutcomes.forEach {
                it.success shouldBe false
                it.error shouldBe "PERMISSION_DENIED"
            }
            // best-effort — 권한 실패 뒤에도 웹훅은 그대로 실행되어 성공.
            result.outcomes.single { it.actionType == ActionType.CALL_WEBHOOK }.success shouldBe true
            result.status shouldBe ActionExecutionStatus.PARTIAL
            verify(exactly = 1) { mockWebhookClient.call(any(), any(), any(), any()) }
        }

        it("이슈 변경 액션만 있고 전부 권한 예외이면 차단율 100% — 전부 실패·FAILED·성공 0건") {
            val rule = ruleWithActor(UUID.randomUUID())
            val actions =
                listOf(
                    Action.SetFieldAction(field = "priority", value = TextNode("High")),
                    Action.AssignAction(assigneeId = UUID.randomUUID()),
                    Action.AddCommentAction(body = "차단"),
                )
            every { actionRepository.findByRuleId(rule.id) } returns actions
            issueMutationPort.failNextCallsWith(FakePermissionAccessDeniedException())

            val result = executor.execute(rule, issueEvent("PROJ-1"))

            result.status shouldBe ActionExecutionStatus.FAILED
            result.outcomes.count { it.success } shouldBe 0
            result.outcomes.forEach { it.error shouldBe "PERMISSION_DENIED" }
        }
    }

    describe("시나리오 2 — actor 위조 차단") {
        it("triggerEvent 의 actor/actorId 위조 값과 무관하게 포트로 전달되는 actor 는 항상 rule.actorUserId 다") {
            val ruleActor = UUID.randomUUID()
            val forgedActor = UUID.randomUUID()
            val rule = ruleWithActor(ruleActor)
            val actions =
                listOf(
                    Action.SetFieldAction(field = "priority", value = TextNode("High")),
                    Action.AssignAction(assigneeId = UUID.randomUUID()),
                    Action.AddCommentAction(body = "댓글"),
                )
            every { actionRepository.findByRuleId(rule.id) } returns actions

            // payload 에 위조된 actorId + 중첩 actor.id 를 심어 executor 가 이 값을 취하지 않음을 검증.
            val forgedEvent =
                objectMapper.readTree(
                    """
                    {"type":"issue.created","issueKey":"PROJ-9","projectKey":"PROJ",
                     "actorId":"$forgedActor","actor":{"id":"$forgedActor"},
                     "reporterId":"$forgedActor","occurredAt":"2026-07-11T00:00:00Z"}
                    """.trimIndent(),
                )

            executor.execute(rule, forgedEvent)

            ruleActor shouldNotBe forgedActor
            issueMutationPort.setFieldCalls.single().actorUserId shouldBe ruleActor
            issueMutationPort.assignCalls.single().actorUserId shouldBe ruleActor
            issueMutationPort.addCommentCalls.single().actorUserId shouldBe ruleActor
            // 위조 값이 포트 커맨드로 새어 들어가지 않았음을 명시.
            issueMutationPort.setFieldCalls.single().actorUserId shouldNotBe forgedActor
            issueMutationPort.assignCalls.single().actorUserId shouldNotBe forgedActor
            issueMutationPort.addCommentCalls.single().actorUserId shouldNotBe forgedActor
        }
    }

    describe("시나리오 3 — CallWebhook SSRF 차단(렌더 후 url 검증)") {
        listOf(
            "http://127.0.0.1/webhook" to "loopback",
            "http://169.254.169.254/latest/meta-data/" to "클라우드 메타데이터(link-local)",
            "http://10.0.0.1/hook" to "사설망 10.0.0.0/8",
            "http://192.168.0.10/hook" to "사설망 192.168.0.0/16",
        ).forEach { (blockedUrl, label) ->
            it("$label URL 은 외부 호출 없이 차단되고 best-effort 로 다음 액션이 진행된다 — $blockedUrl") {
                val rule = ruleWithActor(UUID.randomUUID())
                val actions =
                    listOf(
                        Action.CallWebhookAction(url = blockedUrl, body = "{}"),
                        // 차단 뒤에도 실행돼야 하는 후속 액션(best-effort 관측용, Stub 기본 성공).
                        Action.SetFieldAction(field = "priority", value = TextNode("High")),
                    )
                every { actionRepository.findByRuleId(rule.id) } returns actions

                val result = ssrfExecutor.execute(rule, issueEvent("PROJ-1"))

                val webhookOutcome = result.outcomes.single { it.actionType == ActionType.CALL_WEBHOOK }
                webhookOutcome.success shouldBe false
                webhookOutcome.error shouldNotBe null
                // best-effort — SSRF 차단 후에도 후속 SET_FIELD 는 실행되어 성공.
                result.outcomes.single { it.actionType == ActionType.SET_FIELD }.success shouldBe true
                result.status shouldBe ActionExecutionStatus.PARTIAL
            }
        }

        it("리터럴 url 은 공인 도메인이지만 템플릿 렌더 결과가 사설 IP 면(렌더 후 검증) 차단된다") {
            val rule = ruleWithActor(UUID.randomUUID())
            val actions =
                listOf(Action.CallWebhookAction(url = "http://{{ issue.host }}/latest/meta-data/", body = "{}"))
            every { actionRepository.findByRuleId(rule.id) } returns actions

            // 원본 url 리터럴엔 사설 IP 가 없지만 렌더 후 169.254.169.254 로 확정 → 차단돼야 함.
            val event =
                objectMapper.readTree(
                    """
                    {"type":"issue.created","issueKey":"PROJ-1","host":"169.254.169.254",
                     "occurredAt":"2026-07-11T00:00:00Z"}
                    """.trimIndent(),
                )

            val result = ssrfExecutor.execute(rule, event)

            result.outcomes.single().success shouldBe false
            result.status shouldBe ActionExecutionStatus.FAILED
        }
    }

    describe("시나리오 4 — rule actor 비활성/미존재 fail-closed(EC11)") {
        it("포트가 actor 비활성으로 fail-closed 예외를 던지면 액션은 거부 집계되며(성공 아님) 권한 부족과 동일 처리된다") {
            val rule = ruleWithActor(UUID.randomUUID())
            val actions = listOf(Action.SetFieldAction(field = "priority", value = TextNode("High")))
            every { actionRepository.findByRuleId(rule.id) } returns actions
            issueMutationPort.failNextCallsWith(FakeInactiveActorAccessDeniedException())

            val result = executor.execute(rule, issueEvent("PROJ-1"))

            val outcome = result.outcomes.single()
            outcome.success shouldBe false
            outcome.error shouldBe "PERMISSION_DENIED"
            result.status shouldBe ActionExecutionStatus.FAILED
        }
    }
})

/** 권한 부족을 흉내내는 가짜 예외 — 클래스명에 `AccessDenied` 포함(BC 격리상 issue-tracking 실 예외 타입 미노출). */
private class FakePermissionAccessDeniedException : RuntimeException("permission denied (fake)")

/** rule actor 비활성/미존재를 흉내내는 가짜 예외 — 권한 검증 경로에서 access-denied 로 귀결(클래스명에 `AccessDenied`). */
private class FakeInactiveActorAccessDeniedException : RuntimeException("actor is inactive (fake)")
