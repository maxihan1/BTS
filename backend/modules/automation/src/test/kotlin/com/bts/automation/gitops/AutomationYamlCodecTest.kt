// AutomationYamlCodec toYaml/fromYaml 구조적 round-trip + wire 비대칭 흡수 단위 테스트 (FR-AT-06 Task 1)

package com.bts.automation.gitops

import com.bts.automation.domain.Action
import com.bts.automation.domain.ActionConfig
import com.bts.automation.domain.ActionType
import com.bts.automation.domain.ComparisonOperator
import com.bts.automation.domain.Condition
import com.bts.automation.domain.TriggerConfig
import com.bts.automation.domain.TriggerType
import com.fasterxml.jackson.databind.node.IntNode
import com.fasterxml.jackson.databind.node.TextNode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.util.UUID

class AutomationYamlCodecTest : DescribeSpec({

    val ruleId1 = UUID.fromString("11111111-1111-1111-1111-111111111111")
    val ruleId2 = UUID.fromString("22222222-2222-2222-2222-222222222222")
    val actorId = UUID.fromString("33333333-3333-3333-3333-333333333333")

    fun sampleCondition(): Condition =
        Condition.And(
            listOf(
                Condition.Comparison(
                    field = "issue.type",
                    operator = ComparisonOperator.EQUALS,
                    value = TextNode("Bug"),
                ),
            ),
        )

    fun sampleActions(): List<Action> =
        listOf(
            Action.SetFieldAction(field = "priority", value = IntNode(1)),
            Action.AddCommentAction(body = "자동 처리됨"),
        )

    fun sampleRule(
        id: UUID,
        name: String,
    ): ExportRuleInput =
        ExportRuleInput(
            id = id,
            name = name,
            enabled = true,
            actorUserId = actorId,
            triggerType = TriggerType.ISSUE_UPDATED,
            triggerConfig = """{"fields":["status","priority"]}""",
            condition = sampleCondition(),
            actions = sampleActions(),
        )

    describe("AutomationYamlCodec.toYaml") {
        it("규칙을 YAML로 방출한다 — 파싱해 되읽으면 구조가 일치한다") {
            val rule = sampleRule(ruleId1, "버그 리드에게 할당")

            val yaml = AutomationYamlCodec.toYaml("PROJ", listOf(rule))
            val reparsed = AutomationYamlCodec.fromYaml(yaml)

            reparsed.projectKey shouldBe "PROJ"
            reparsed.rules shouldHaveSize 1
            val command = reparsed.rules[0]
            command.id shouldBe ruleId1
            command.name shouldBe "버그 리드에게 할당"
            command.enabled shouldBe true
            command.actorUserId shouldBe actorId
            command.triggerType shouldBe TriggerType.ISSUE_UPDATED
            TriggerConfig.validate(TriggerType.ISSUE_UPDATED, command.triggerConfig)
            Condition.fromJson(requireNotNull(command.condition)) shouldBe rule.condition
            command.actions.map { it.type } shouldBe listOf(ActionType.SET_FIELD, ActionType.ADD_COMMENT)
            command.actions.forEach { ActionConfig.validate(it.type, it.config) }
        }

        it("웹훅 토큰·OCC version·nextFireAt 문자열을 포함하지 않는다") {
            val yaml = AutomationYamlCodec.toYaml("PROJ", listOf(sampleRule(ruleId1, "규칙 A")))

            yaml shouldNotContain "webhookTokenHash"
            yaml shouldNotContain "webhookToken"
            yaml shouldNotContain "nextFireAt"
        }

        it("규칙 순서를 입력 순서 그대로 결정적으로 방출한다") {
            val ruleA = sampleRule(ruleId1, "규칙 A")
            val ruleB = sampleRule(ruleId2, "규칙 B")

            val yaml = AutomationYamlCodec.toYaml("PROJ", listOf(ruleA, ruleB))
            val reparsed = AutomationYamlCodec.fromYaml(yaml)

            reparsed.rules.map { it.name } shouldBe listOf("규칙 A", "규칙 B")
        }

        it("규칙 id를 포함한다") {
            val yaml = AutomationYamlCodec.toYaml("PROJ", listOf(sampleRule(ruleId1, "규칙 A")))

            yaml shouldContain ruleId1.toString()
        }
    }

    describe("AutomationYamlCodec.fromYaml — wire 비대칭 흡수") {
        it("trigger.config/action.config/condition YAML 객체를 도메인 파서가 소비 가능한 JSON 문자열로 변환한다") {
            val rawYaml =
                """
                version: 1
                projectKey: PROJ
                rules:
                  - id: $ruleId1
                    name: "버그를 리드에게 자동 할당"
                    enabled: true
                    actorUserId: $actorId
                    trigger:
                      type: ISSUE_CREATED
                      config: {}
                    condition: {"and": [{"==": [{"var": "issue.type"}, "Bug"]}]}
                    actions:
                      - type: SET_FIELD
                        config: {"field": "priority", "value": 1}
                      - type: ADD_COMMENT
                        config: {"body": "자동 처리됨"}
                """.trimIndent()

            val parsed = AutomationYamlCodec.fromYaml(rawYaml)

            parsed.projectKey shouldBe "PROJ"
            val command = parsed.rules.single()
            TriggerConfig.validate(TriggerType.ISSUE_CREATED, command.triggerConfig)
            val condition = Condition.fromJson(requireNotNull(command.condition))
            condition shouldBe sampleCondition()
            command.actions.forEach { ActionConfig.validate(it.type, it.config) }
            val setFieldAction = Action.fromJson(ActionType.SET_FIELD, command.actions[0].config)
            (setFieldAction as Action.SetFieldAction).field shouldBe "priority"
        }
    }

    describe("AutomationYamlCodec — 순수 round-trip") {
        it("fromYaml(toYaml(rules)) 가 동등한 커맨드를 재현한다") {
            val ruleA = sampleRule(ruleId1, "규칙 A")
            val ruleB =
                ExportRuleInput(
                    id = ruleId2,
                    name = "규칙 B",
                    enabled = false,
                    actorUserId = actorId,
                    triggerType = TriggerType.ISSUE_CREATED,
                    triggerConfig = "{}",
                    condition = null,
                    actions = emptyList(),
                )

            val parsed = AutomationYamlCodec.fromYaml(AutomationYamlCodec.toYaml("PROJ", listOf(ruleA, ruleB)))

            parsed.rules shouldHaveSize 2
            val commandA = parsed.rules[0]
            commandA.id shouldBe ruleA.id
            commandA.enabled shouldBe true
            Condition.fromJson(requireNotNull(commandA.condition)) shouldBe ruleA.condition

            val commandB = parsed.rules[1]
            commandB.id shouldBe ruleB.id
            commandB.enabled shouldBe false
            commandB.condition shouldBe null
            commandB.actions shouldBe emptyList()
        }
    }

    describe("AutomationYamlCodec.fromYaml — 오류 처리") {
        it("malformed YAML은 예외를 던지고 원본 값을 echo하지 않는다") {
            val malformed = "not: [valid: yaml: structure"

            val exception = shouldThrow<AutomationYamlInvalidException> { AutomationYamlCodec.fromYaml(malformed) }

            exception.message shouldNotContain malformed
        }

        it("version이 1이 아니면 예외를 던진다") {
            val rawYaml =
                """
                version: 2
                projectKey: PROJ
                rules: []
                """.trimIndent()

            shouldThrow<AutomationYamlInvalidException> { AutomationYamlCodec.fromYaml(rawYaml) }
        }

        it("빈 YAML 본문은 예외를 던진다") {
            shouldThrow<AutomationYamlInvalidException> { AutomationYamlCodec.fromYaml("") }
        }
    }
})
