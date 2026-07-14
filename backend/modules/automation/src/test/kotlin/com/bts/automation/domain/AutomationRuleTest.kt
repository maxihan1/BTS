// AutomationRule 확장 단위 테스트 — condition 필드·create 기본값·updateCondition OCC (FR-AT-03 Task 5)
// + id·enabled 보존 팩토리 파라미터 (FR-AT-06 Task 2)

package com.bts.automation.domain

import com.fasterxml.jackson.databind.node.IntNode
import com.fasterxml.jackson.databind.node.TextNode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.UUID

class AutomationRuleTest : DescribeSpec({

    val fixedNow = Instant.parse("2026-07-12T00:00:00Z")

    fun newRule(): AutomationRule =
        AutomationRule.create(
            projectKey = "PROJ",
            name = "룰",
            triggerType = TriggerType.ISSUE_CREATED,
            createdBy = UUID.randomUUID(),
            now = fixedNow,
        )

    describe("AutomationRule.create — condition 기본값") {
        it("condition 미지정 시 null 이다(조건 없이 항상 통과하는 룰도 유효)") {
            val rule = newRule()

            rule.condition shouldBe null
        }

        it("condition 을 명시하면 그대로 보유한다") {
            val condition =
                Condition.Comparison(
                    field = "issue.status",
                    operator = ComparisonOperator.EQUALS,
                    value = TextNode("Done"),
                )

            val rule =
                AutomationRule.create(
                    projectKey = "PROJ",
                    name = "룰",
                    triggerType = TriggerType.ISSUE_CREATED,
                    createdBy = UUID.randomUUID(),
                    condition = condition,
                    now = fixedNow,
                )

            rule.condition shouldBe condition
        }
    }

    describe("AutomationRule.updateCondition — 조건 게이트 교체 + OCC 버전 bump") {
        it("새 조건으로 교체되고 version+1 한다") {
            val rule = newRule()
            val later = fixedNow.plusSeconds(60)
            val newCondition =
                Condition.Comparison(
                    field = "issue.priority",
                    operator = ComparisonOperator.GREATER_THAN_OR_EQUAL,
                    value = IntNode(3),
                )

            val updated = rule.updateCondition(newCondition, later)

            updated.condition shouldBe newCondition
            updated.version shouldBe rule.version + 1
            updated.updatedAt shouldBe later
        }

        it("null 로 교체(조건 제거)도 허용한다") {
            val withCondition =
                newRule().updateCondition(
                    Condition.Comparison("issue.status", ComparisonOperator.EQUALS, TextNode("Done")),
                    fixedNow.plusSeconds(1),
                )

            val updated = withCondition.updateCondition(null, fixedNow.plusSeconds(2))

            updated.condition shouldBe null
            updated.version shouldBe withCondition.version + 1
        }
    }

    describe("AutomationRule.create — id·enabled 보존 (FR-AT-06 import upsert 전제)") {
        it("명시 id·enabled=false 로 생성하면 그 id 를 보유하고 비활성 상태다") {
            val explicitId = UUID.randomUUID()

            val rule =
                AutomationRule.create(
                    id = explicitId,
                    projectKey = "PROJ",
                    name = "룰",
                    triggerType = TriggerType.ISSUE_CREATED,
                    createdBy = UUID.randomUUID(),
                    enabled = false,
                    now = fixedNow,
                )

            rule.id shouldBe explicitId
            rule.enabled shouldBe false
            rule.version shouldBe 0L
        }

        it("id·enabled 미지정 시 기존과 동일하게 랜덤 id·enabled=true 를 유지한다(회귀 0)") {
            val rule = newRule()

            rule.enabled shouldBe true
            rule.version shouldBe 0L
        }

        it("명시 id 로 생성해도 name 검증(blank)은 그대로 강제한다") {
            shouldThrow<AutomationRuleInvalidException> {
                AutomationRule.create(
                    id = UUID.randomUUID(),
                    projectKey = "PROJ",
                    name = "   ",
                    triggerType = TriggerType.ISSUE_CREATED,
                    createdBy = UUID.randomUUID(),
                    enabled = false,
                    now = fixedNow,
                )
            }
        }

        it("명시 id 로 생성해도 name 검증(200자 초과)은 그대로 강제한다") {
            shouldThrow<AutomationRuleInvalidException> {
                AutomationRule.create(
                    id = UUID.randomUUID(),
                    projectKey = "PROJ",
                    name = "가".repeat(AutomationRule.MAX_NAME_LENGTH + 1),
                    triggerType = TriggerType.ISSUE_CREATED,
                    createdBy = UUID.randomUUID(),
                    enabled = false,
                    now = fixedNow,
                )
            }
        }

        it("명시 id 로 생성해도 actorUserId nil UUID 검증은 그대로 강제한다") {
            shouldThrow<AutomationRuleInvalidException> {
                AutomationRule.create(
                    id = UUID.randomUUID(),
                    projectKey = "PROJ",
                    name = "룰",
                    triggerType = TriggerType.ISSUE_CREATED,
                    createdBy = UUID.randomUUID(),
                    actorUserId = UUID(0L, 0L),
                    enabled = false,
                    now = fixedNow,
                )
            }
        }

        it("명시 id 로 생성해도 TriggerConfig 형식 검증은 그대로 강제한다") {
            shouldThrow<TriggerConfigInvalidException> {
                AutomationRule.create(
                    id = UUID.randomUUID(),
                    projectKey = "PROJ",
                    name = "룰",
                    triggerType = TriggerType.ISSUE_CREATED,
                    triggerConfig = "",
                    createdBy = UUID.randomUUID(),
                    enabled = false,
                    now = fixedNow,
                )
            }
        }
    }
})
