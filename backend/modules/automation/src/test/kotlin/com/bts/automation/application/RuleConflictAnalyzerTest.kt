// RuleConflictAnalyzer coFire — PR_MERGED targetBranch 정밀 판정 단위 테스트 (FR-AT-07 PR-C Task 4, DEC-27)

package com.bts.automation.application

import com.bts.automation.StubIssuePermissionResolver
import com.bts.automation.domain.Action
import com.bts.automation.domain.AutomationRule
import com.bts.automation.domain.ConflictType
import com.bts.automation.domain.TriggerConfig
import com.bts.automation.domain.TriggerType
import com.fasterxml.jackson.databind.node.IntNode
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.UUID

/**
 * [RuleConflictAnalyzer.analyze] 의 [ConflictType.FIELD_CONFLICT]/[ConflictType.PRIORITY_AMBIGUITY] 가
 * [TriggerType.PR_MERGED] `targetBranch` 를 정밀 판정하는지 검증한다(FR-AT-07 PR-C Task 4, DEC-27).
 *
 * [RuleConflictAnalyzerFieldPriorityTest] 가 이미 검증한 WEBHOOK/SCHEDULED/ISSUE_UPDATED coFire
 * 판정에는 회귀가 없어야 한다 — 이 파일은 PR_MERGED 전용 [targetBranchCoFire] 판정만 다룬다.
 */
class RuleConflictAnalyzerTest : DescribeSpec({

    val fixedNow: Instant = Instant.parse("2026-07-17T00:00:00Z")

    fun ruleOf(
        name: String,
        triggerType: TriggerType,
        triggerConfig: String = TriggerConfig.EMPTY,
        actions: List<Action> = emptyList(),
    ): AutomationRule =
        AutomationRule.create(
            projectKey = "PROJ",
            name = name,
            triggerType = triggerType,
            triggerConfig = triggerConfig,
            createdBy = UUID.randomUUID(),
            actions = actions,
            now = fixedNow,
        )

    // PERMISSION_MISSING(FR-AT-04 Task 4)은 이 파일의 관심사가 아니다 — 항상 허용하는 stub 을 주입해
    // 다른 ConflictType 이 섞여 들어오지 않게 한다(RuleConflictAnalyzerFieldPriorityTest 와 동형).
    val analyzer = RuleConflictAnalyzer(StubIssuePermissionResolver())

    describe("coFire 미검출 — PR_MERGED 두 규칙이 서로 다른 targetBranch 를 지정하면 동시 발화가 불가능하다 (DEC-27)") {
        it("A=release/1.2, B=release/2.0 이 같은 필드를 다른 값으로 SET 해도 FIELD_CONFLICT/PRIORITY_AMBIGUITY 가 검출되지 않는다") {
            val ruleA =
                ruleOf(
                    name = "A",
                    triggerType = TriggerType.PR_MERGED,
                    triggerConfig = """{"targetBranch":"release/1.2"}""",
                    actions = listOf(Action.SetFieldAction(field = "priority", value = IntNode(1))),
                )
            val ruleB =
                ruleOf(
                    name = "B",
                    triggerType = TriggerType.PR_MERGED,
                    triggerConfig = """{"targetBranch":"release/2.0"}""",
                    actions = listOf(Action.SetFieldAction(field = "priority", value = IntNode(5))),
                )

            val conflicts = analyzer.analyze(listOf(ruleA, ruleB))

            conflicts.shouldBeEmpty()
        }
    }

    describe("coFire 검출 — PR_MERGED 두 규칙이 같은 targetBranch 를 지정하면 동시 발화 가능") {
        it("A·B가 둘 다 targetBranch=release/1.2 이고 같은 필드를 다른 값으로 SET 하면 FIELD_CONFLICT [A,B]") {
            val ruleA =
                ruleOf(
                    name = "A",
                    triggerType = TriggerType.PR_MERGED,
                    triggerConfig = """{"targetBranch":"release/1.2"}""",
                    actions = listOf(Action.SetFieldAction(field = "priority", value = IntNode(1))),
                )
            val ruleB =
                ruleOf(
                    name = "B",
                    triggerType = TriggerType.PR_MERGED,
                    triggerConfig = """{"targetBranch":"release/1.2"}""",
                    actions = listOf(Action.SetFieldAction(field = "priority", value = IntNode(5))),
                )

            val fieldConflicts =
                analyzer.analyze(listOf(ruleA, ruleB)).filter { it.type == ConflictType.FIELD_CONFLICT }

            fieldConflicts.size shouldBe 1
            fieldConflicts.single().ruleIds shouldBe listOf(ruleA.id, ruleB.id).sorted()
        }
    }

    describe("coFire 검출 — 한쪽이 targetBranch 미지정이면 전체 브랜치 발화라 동시 발화 가능") {
        it("A=미지정, B=release/1.2 가 같은 필드를 다른 값으로 SET 하면 FIELD_CONFLICT [A,B]") {
            val ruleA =
                ruleOf(
                    name = "A",
                    triggerType = TriggerType.PR_MERGED,
                    triggerConfig = TriggerConfig.EMPTY,
                    actions = listOf(Action.SetFieldAction(field = "priority", value = IntNode(1))),
                )
            val ruleB =
                ruleOf(
                    name = "B",
                    triggerType = TriggerType.PR_MERGED,
                    triggerConfig = """{"targetBranch":"release/1.2"}""",
                    actions = listOf(Action.SetFieldAction(field = "priority", value = IntNode(5))),
                )

            val fieldConflicts =
                analyzer.analyze(listOf(ruleA, ruleB)).filter { it.type == ConflictType.FIELD_CONFLICT }

            fieldConflicts.size shouldBe 1
            fieldConflicts.single().ruleIds shouldBe listOf(ruleA.id, ruleB.id).sorted()
        }
    }

    describe("coFire 검출 — 양쪽 다 targetBranch 미지정이면 둘 다 전체 브랜치 발화라 동시 발화 가능") {
        it("A·B 둘 다 targetBranch 미지정이고 같은 필드를 다른 값으로 SET 하면 FIELD_CONFLICT [A,B]") {
            val ruleA =
                ruleOf(
                    name = "A",
                    triggerType = TriggerType.PR_MERGED,
                    triggerConfig = TriggerConfig.EMPTY,
                    actions = listOf(Action.SetFieldAction(field = "priority", value = IntNode(1))),
                )
            val ruleB =
                ruleOf(
                    name = "B",
                    triggerType = TriggerType.PR_MERGED,
                    triggerConfig = TriggerConfig.EMPTY,
                    actions = listOf(Action.SetFieldAction(field = "priority", value = IntNode(5))),
                )

            val fieldConflicts =
                analyzer.analyze(listOf(ruleA, ruleB)).filter { it.type == ConflictType.FIELD_CONFLICT }

            fieldConflicts.size shouldBe 1
            fieldConflicts.single().ruleIds shouldBe listOf(ruleA.id, ruleB.id).sorted()
        }
    }
})
