// AutomationRule 확장 단위 테스트 — actions/actorUserId 필드·create 기본값·updateActions/changeActor OCC (FR-AT-02 Task 5)

package com.bts.automation.domain

import com.fasterxml.jackson.databind.node.TextNode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.UUID

class AutomationRuleActionsTest : DescribeSpec({

    val fixedNow = Instant.parse("2026-07-11T00:00:00Z")

    fun newRule(): AutomationRule =
        AutomationRule.create(
            projectKey = "PROJ",
            name = "룰",
            triggerType = TriggerType.ISSUE_CREATED,
            createdBy = UUID.randomUUID(),
            now = fixedNow,
        )

    describe("AutomationRule.create — actorUserId 기본값·검증") {
        it("actorUserId 미지정 시 createdBy 로 폴백한다") {
            val createdBy = UUID.randomUUID()

            val rule =
                AutomationRule.create(
                    projectKey = "PROJ",
                    name = "룰",
                    triggerType = TriggerType.ISSUE_CREATED,
                    createdBy = createdBy,
                    now = fixedNow,
                )

            rule.actorUserId shouldBe createdBy
        }

        it("actorUserId 를 명시하면 createdBy 와 다른 사용자로 지정할 수 있다") {
            val createdBy = UUID.randomUUID()
            val actor = UUID.randomUUID()

            val rule =
                AutomationRule.create(
                    projectKey = "PROJ",
                    name = "룰",
                    triggerType = TriggerType.ISSUE_CREATED,
                    createdBy = createdBy,
                    actorUserId = actor,
                    now = fixedNow,
                )

            rule.actorUserId shouldBe actor
        }

        it("actorUserId 가 nil UUID 이면 거부한다") {
            shouldThrow<AutomationRuleInvalidException> {
                AutomationRule.create(
                    projectKey = "PROJ",
                    name = "룰",
                    triggerType = TriggerType.ISSUE_CREATED,
                    createdBy = UUID.randomUUID(),
                    actorUserId = UUID(0L, 0L),
                    now = fixedNow,
                )
            }
        }
    }

    describe("AutomationRule.create — actions 기본값") {
        it("actions 미지정 시 빈 리스트다(트리거만 있는 룰도 유효)") {
            val rule = newRule()

            rule.actions shouldBe emptyList()
        }

        it("actions 를 명시하면 그대로 보유한다") {
            val actions = listOf(Action.AddCommentAction(body = "자동 코멘트"))

            val rule =
                AutomationRule.create(
                    projectKey = "PROJ",
                    name = "룰",
                    triggerType = TriggerType.ISSUE_CREATED,
                    createdBy = UUID.randomUUID(),
                    actions = actions,
                    now = fixedNow,
                )

            rule.actions shouldBe actions
        }
    }

    describe("AutomationRule.updateActions — 액션 목록 교체 + OCC 버전 bump") {
        it("새 액션 목록으로 교체되고 version+1 한다") {
            val rule = newRule()
            val later = fixedNow.plusSeconds(60)
            val newActions =
                listOf(
                    Action.SetFieldAction(field = "priority", value = TextNode("High")),
                    Action.AddCommentAction(body = "자동 코멘트"),
                )

            val updated = rule.updateActions(newActions, later)

            updated.actions shouldBe newActions
            updated.version shouldBe rule.version + 1
            updated.updatedAt shouldBe later
        }

        it("빈 리스트로 교체(액션 전체 제거)도 허용한다") {
            val withActions =
                newRule().updateActions(listOf(Action.AddCommentAction(body = "코멘트")), fixedNow.plusSeconds(1))

            val updated = withActions.updateActions(emptyList(), fixedNow.plusSeconds(2))

            updated.actions shouldBe emptyList()
            updated.version shouldBe withActions.version + 1
        }
    }

    describe("AutomationRule.changeActor — 실행 주체 교체 + OCC 버전 bump") {
        it("새 actorUserId 로 교체되고 version+1 한다") {
            val rule = newRule()
            val later = fixedNow.plusSeconds(60)
            val newActor = UUID.randomUUID()

            val updated = rule.changeActor(newActor, later)

            updated.actorUserId shouldBe newActor
            updated.version shouldBe rule.version + 1
            updated.updatedAt shouldBe later
        }

        it("nil UUID 로 교체를 시도하면 거부한다") {
            val rule = newRule()

            shouldThrow<AutomationRuleInvalidException> {
                rule.changeActor(UUID(0L, 0L), fixedNow.plusSeconds(60))
            }
        }
    }
})
