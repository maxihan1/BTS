// RuleConflictAnalyzer FIELD_CONFLICT/PRIORITY_AMBIGUITY 검출 단위 테스트 (FR-AT-04 Task 3)

package com.bts.automation.application

import com.bts.automation.StubIssuePermissionResolver
import com.bts.automation.domain.Action
import com.bts.automation.domain.AutomationRule
import com.bts.automation.domain.ConflictType
import com.bts.automation.domain.TriggerConfig
import com.bts.automation.domain.TriggerType
import com.fasterxml.jackson.databind.node.IntNode
import com.fasterxml.jackson.databind.node.TextNode
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.UUID

/**
 * [RuleConflictAnalyzer.analyze] 의 [ConflictType.FIELD_CONFLICT] / [ConflictType.PRIORITY_AMBIGUITY]
 * 검출을 검증한다(FR-AT-04 Task 3, 스펙 FR-4/FR-5).
 *
 * CYCLE 검출은 [RuleConflictAnalyzerCycleTest] 가 이미 담당한다 — 이 파일의 시나리오는 CYCLE 이 함께
 * 검출되지 않는 조합으로 구성해 타입 필터 없이도 안전하게 단언할 수 있는 경우 `conflicts.shouldBeEmpty()`
 * 등 전체 목록을 검증하고, CYCLE 과 공존 가능한 조합은 `type` 필터로 좁혀 검증한다.
 */
class RuleConflictAnalyzerFieldPriorityTest : DescribeSpec({

    val fixedNow: Instant = Instant.parse("2026-07-13T00:00:00Z")

    fun ruleOf(
        name: String,
        triggerType: TriggerType,
        triggerConfig: String = TriggerConfig.EMPTY,
        actions: List<Action> = emptyList(),
        enabled: Boolean = true,
    ): AutomationRule {
        val rule =
            AutomationRule.create(
                projectKey = "PROJ",
                name = name,
                triggerType = triggerType,
                triggerConfig = triggerConfig,
                createdBy = UUID.randomUUID(),
                actions = actions,
                now = fixedNow,
            )
        return if (enabled) rule else rule.disable(fixedNow)
    }

    // PERMISSION_MISSING(FR-AT-04 Task 4)은 RuleConflictAnalyzerPermissionTest 가 전담한다 — 이 파일은
    // FIELD_CONFLICT/PRIORITY_AMBIGUITY 만 검증하므로 항상 허용(AlwaysAllow)하는 stub 을 주입해
    // PERMISSION_MISSING 이 섞여 들어오지 않게 한다(생성자 변경에 따른 갱신, 로직/단언은 그대로).
    val analyzer = RuleConflictAnalyzer(StubIssuePermissionResolver())

    describe("FIELD_CONFLICT — 같은 트리거 두 규칙이 같은 필드를 다른 값으로 SET") {
        it("A·B가 둘 다 ISSUE_CREATED이고 priority를 다른 값으로 SET하면 FIELD_CONFLICT [A,B]") {
            val ruleA =
                ruleOf(
                    name = "A",
                    triggerType = TriggerType.ISSUE_CREATED,
                    actions = listOf(Action.SetFieldAction(field = "priority", value = IntNode(1))),
                )
            val ruleB =
                ruleOf(
                    name = "B",
                    triggerType = TriggerType.ISSUE_CREATED,
                    actions = listOf(Action.SetFieldAction(field = "priority", value = IntNode(5))),
                )

            val fieldConflicts =
                analyzer.analyze(listOf(ruleA, ruleB)).filter { it.type == ConflictType.FIELD_CONFLICT }

            fieldConflicts.size shouldBe 1
            fieldConflicts.single().ruleIds shouldBe listOf(ruleA.id, ruleB.id).sorted()
        }
    }

    describe("FIELD_CONFLICT 미검출 — 같은 값(멱등)") {
        it("A·B가 둘 다 priority를 같은 값으로 SET하면 FIELD_CONFLICT가 검출되지 않는다") {
            val ruleA =
                ruleOf(
                    name = "A",
                    triggerType = TriggerType.ISSUE_CREATED,
                    actions = listOf(Action.SetFieldAction(field = "priority", value = IntNode(1))),
                )
            val ruleB =
                ruleOf(
                    name = "B",
                    triggerType = TriggerType.ISSUE_CREATED,
                    actions = listOf(Action.SetFieldAction(field = "priority", value = IntNode(1))),
                )

            val conflicts = analyzer.analyze(listOf(ruleA, ruleB))

            conflicts.none { it.type == ConflictType.FIELD_CONFLICT } shouldBe true
        }
    }

    describe("FIELD_CONFLICT — 규칙 내부 액션 간 충돌") {
        it("한 규칙 내부에 같은 필드를 다른 값으로 SET하는 액션이 2개면 FIELD_CONFLICT, ruleIds=[self]") {
            val rule =
                ruleOf(
                    name = "SELF",
                    triggerType = TriggerType.ISSUE_CREATED,
                    actions =
                        listOf(
                            Action.SetFieldAction(field = "status", value = TextNode("open")),
                            Action.SetFieldAction(field = "status", value = TextNode("done")),
                        ),
                )

            val conflicts = analyzer.analyze(listOf(rule))

            conflicts.size shouldBe 1
            conflicts.single().type shouldBe ConflictType.FIELD_CONFLICT
            conflicts.single().ruleIds shouldBe listOf(rule.id)
        }
    }

    describe("FIELD_CONFLICT 미검출 — 트리거 타입이 달라 동시 매칭이 안 됨") {
        it("A=ISSUE_CREATED, B=ISSUE_COMMENTED가 같은 필드를 SET해도 미검출이다") {
            val ruleA =
                ruleOf(
                    name = "A",
                    triggerType = TriggerType.ISSUE_CREATED,
                    actions = listOf(Action.SetFieldAction(field = "priority", value = IntNode(1))),
                )
            val ruleB =
                ruleOf(
                    name = "B",
                    triggerType = TriggerType.ISSUE_COMMENTED,
                    actions = listOf(Action.SetFieldAction(field = "priority", value = IntNode(5))),
                )

            val conflicts = analyzer.analyze(listOf(ruleA, ruleB))

            conflicts.shouldBeEmpty()
        }
    }

    describe("PRIORITY_AMBIGUITY — 같은 트리거에 매칭되는 서로 다른 부수효과 규칙 2개") {
        it("A(Assign)·B(SetField)가 같은 트리거에 매칭되면 PRIORITY_AMBIGUITY [A,B]") {
            val ruleA =
                ruleOf(
                    name = "A",
                    triggerType = TriggerType.ISSUE_CREATED,
                    actions = listOf(Action.AssignAction(assigneeId = UUID.randomUUID())),
                )
            val ruleB =
                ruleOf(
                    name = "B",
                    triggerType = TriggerType.ISSUE_CREATED,
                    actions = listOf(Action.SetFieldAction(field = "priority", value = IntNode(1))),
                )

            val priorityConflicts =
                analyzer.analyze(listOf(ruleA, ruleB)).filter { it.type == ConflictType.PRIORITY_AMBIGUITY }

            priorityConflicts.size shouldBe 1
            priorityConflicts.single().ruleIds shouldBe listOf(ruleA.id, ruleB.id).sorted()
        }
    }

    describe("PRIORITY_AMBIGUITY 미검출 — CallWebhookAction만 있는 조합은 부수효과가 없다") {
        it("A·B가 둘 다 CallWebhookAction만 있으면 미검출이다") {
            val ruleA =
                ruleOf(
                    name = "A",
                    triggerType = TriggerType.ISSUE_CREATED,
                    actions = listOf(Action.CallWebhookAction(url = "https://example.com/a")),
                )
            val ruleB =
                ruleOf(
                    name = "B",
                    triggerType = TriggerType.ISSUE_CREATED,
                    actions = listOf(Action.CallWebhookAction(url = "https://example.com/b")),
                )

            val conflicts = analyzer.analyze(listOf(ruleA, ruleB))

            conflicts.shouldBeEmpty()
        }
    }

    describe("PRIORITY_AMBIGUITY 억제 — 같은 쌍이 FIELD_CONFLICT면 PRIORITY_AMBIGUITY는 억제된다") {
        it("A·B가 priority를 다른 값으로 SET해 FIELD_CONFLICT가 나면 그 쌍의 PRIORITY_AMBIGUITY는 없다") {
            val ruleA =
                ruleOf(
                    name = "A",
                    triggerType = TriggerType.ISSUE_CREATED,
                    actions = listOf(Action.SetFieldAction(field = "priority", value = IntNode(1))),
                )
            val ruleB =
                ruleOf(
                    name = "B",
                    triggerType = TriggerType.ISSUE_CREATED,
                    actions = listOf(Action.SetFieldAction(field = "priority", value = IntNode(5))),
                )

            val conflicts = analyzer.analyze(listOf(ruleA, ruleB))

            conflicts.size shouldBe 1
            conflicts.count { it.type == ConflictType.FIELD_CONFLICT } shouldBe 1
            conflicts.count { it.type == ConflictType.PRIORITY_AMBIGUITY } shouldBe 0
        }
    }

    describe("FIELD_CONFLICT — ISSUE_UPDATED fields 겹침 여부에 따른 동시 매칭 판정") {
        // 트리거 필터 필드(watchField)와 액션 SET 대상 필드(priority)를 분리한다 — 같은 필드로 두면
        // 규칙 자신의 액션이 자신의 트리거를 되유발해(self-loop) CYCLE이 함께 검출되므로 이 describe의
        // 관심사(동시 매칭 판정)만 순수하게 검증할 수 없다.
        it("fields가 겹치면([watch]/[watch]) 동시 매칭으로 보고 FIELD_CONFLICT를 검출한다") {
            val ruleA =
                ruleOf(
                    name = "A",
                    triggerType = TriggerType.ISSUE_UPDATED,
                    triggerConfig = """{"fields":["watch"]}""",
                    actions = listOf(Action.SetFieldAction(field = "priority", value = IntNode(1))),
                )
            val ruleB =
                ruleOf(
                    name = "B",
                    triggerType = TriggerType.ISSUE_UPDATED,
                    triggerConfig = """{"fields":["watch"]}""",
                    actions = listOf(Action.SetFieldAction(field = "priority", value = IntNode(5))),
                )

            val fieldConflicts =
                analyzer.analyze(listOf(ruleA, ruleB)).filter { it.type == ConflictType.FIELD_CONFLICT }

            fieldConflicts.size shouldBe 1
            fieldConflicts.single().ruleIds shouldBe listOf(ruleA.id, ruleB.id).sorted()
        }

        it("fields가 겹치지 않으면([watchA]/[watchB]) 동시 매칭이 아니라 미검출이다") {
            val ruleA =
                ruleOf(
                    name = "A",
                    triggerType = TriggerType.ISSUE_UPDATED,
                    triggerConfig = """{"fields":["watchA"]}""",
                    actions = listOf(Action.SetFieldAction(field = "priority", value = IntNode(1))),
                )
            val ruleB =
                ruleOf(
                    name = "B",
                    triggerType = TriggerType.ISSUE_UPDATED,
                    triggerConfig = """{"fields":["watchB"]}""",
                    actions = listOf(Action.SetFieldAction(field = "priority", value = IntNode(5))),
                )

            val conflicts = analyzer.analyze(listOf(ruleA, ruleB))

            conflicts.shouldBeEmpty()
        }
    }

    describe("coFire 미검출 — WEBHOOK 규칙 두 개는 각자 고유 토큰 엔드포인트라 동시 발화가 불가능하다 (코드리뷰 C2 hotfix)") {
        it("A·B가 둘 다 WEBHOOK 이고 같은 필드를 다른 값으로 SET 해도 FIELD_CONFLICT/PRIORITY_AMBIGUITY 가 검출되지 않는다") {
            val ruleA =
                ruleOf(
                    name = "A",
                    triggerType = TriggerType.WEBHOOK,
                    actions = listOf(Action.SetFieldAction(field = "priority", value = IntNode(1))),
                )
            val ruleB =
                ruleOf(
                    name = "B",
                    triggerType = TriggerType.WEBHOOK,
                    actions = listOf(Action.SetFieldAction(field = "priority", value = IntNode(5))),
                )

            val conflicts = analyzer.analyze(listOf(ruleA, ruleB))

            conflicts.none { it.type == ConflictType.FIELD_CONFLICT || it.type == ConflictType.PRIORITY_AMBIGUITY } shouldBe true
        }
    }

    describe("coFire 유지 — SCHEDULED 는 cron 이 겹칠 수 있어 보수적으로 동시 매칭 취급한다") {
        it("A·B가 둘 다 SCHEDULED 이고 같은 필드를 다른 값으로 SET 하면 FIELD_CONFLICT 가 그대로 검출된다") {
            val ruleA =
                ruleOf(
                    name = "A",
                    triggerType = TriggerType.SCHEDULED,
                    triggerConfig = """{"cron":"0 0 9 * * *"}""",
                    actions = listOf(Action.SetFieldAction(field = "priority", value = IntNode(1))),
                )
            val ruleB =
                ruleOf(
                    name = "B",
                    triggerType = TriggerType.SCHEDULED,
                    triggerConfig = """{"cron":"0 0 9 * * *"}""",
                    actions = listOf(Action.SetFieldAction(field = "priority", value = IntNode(5))),
                )

            val fieldConflicts =
                analyzer.analyze(listOf(ruleA, ruleB)).filter { it.type == ConflictType.FIELD_CONFLICT }

            fieldConflicts.size shouldBe 1
            fieldConflicts.single().ruleIds shouldBe listOf(ruleA.id, ruleB.id).sorted()
        }
    }
})
