// RuleConflictAnalyzer CYCLE 검출 단위 테스트 — self-loop/2-cycle/3-cycle/no-cycle/dedup/disabled 제외 (FR-AT-04 Task 2)

package com.bts.automation.application

import com.bts.automation.StubIssuePermissionResolver
import com.bts.automation.domain.Action
import com.bts.automation.domain.AutomationRule
import com.bts.automation.domain.ConflictType
import com.bts.automation.domain.TriggerConfig
import com.bts.automation.domain.TriggerType
import com.fasterxml.jackson.databind.node.TextNode
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.UUID

/**
 * [RuleConflictAnalyzer.analyze] 의 [ConflictType.CYCLE] 검출을 검증한다(FR-AT-04 Task 2, FR-3).
 *
 * 아직 CYCLE 만 구현 범위다 — FIELD_CONFLICT/PRIORITY_AMBIGUITY/PERMISSION_MISSING 은 Task 3/4에서
 * 같은 `analyze()` 에 추가된다.
 */
class RuleConflictAnalyzerCycleTest : DescribeSpec({

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
    // CYCLE 만 검증하므로 항상 허용(AlwaysAllow)하는 stub 을 주입해 PERMISSION_MISSING 이 섞여 들어오지
    // 않게 한다(생성자 변경에 따른 갱신, 로직/단언은 그대로).
    val analyzer = RuleConflictAnalyzer(StubIssuePermissionResolver())

    describe("CYCLE 검출 — self-loop") {
        it("A의 SetField(priority)가 A 자신의 ISSUE_UPDATED{fields:[priority]} 트리거를 유발하면 CYCLE 1건") {
            val ruleA =
                ruleOf(
                    name = "A",
                    triggerType = TriggerType.ISSUE_UPDATED,
                    triggerConfig = """{"fields":["priority"]}""",
                    actions = listOf(Action.SetFieldAction(field = "priority", value = TextNode("High"))),
                )

            val conflicts = analyzer.analyze(listOf(ruleA))

            conflicts.size shouldBe 1
            conflicts.single().type shouldBe ConflictType.CYCLE
            conflicts.single().ruleIds shouldBe listOf(ruleA.id)
        }
    }

    describe("CYCLE 검출 — 2-cycle") {
        it("A↔B가 서로 다른 필드를 SetField로 유발하면 CYCLE 1건, ruleIds가 정렬돼 담긴다") {
            val ruleA =
                ruleOf(
                    name = "A",
                    triggerType = TriggerType.ISSUE_UPDATED,
                    triggerConfig = """{"fields":["priority"]}""",
                    actions = listOf(Action.SetFieldAction(field = "status", value = TextNode("open"))),
                )
            val ruleB =
                ruleOf(
                    name = "B",
                    triggerType = TriggerType.ISSUE_UPDATED,
                    triggerConfig = """{"fields":["status"]}""",
                    actions = listOf(Action.SetFieldAction(field = "priority", value = TextNode("High"))),
                )

            val conflicts = analyzer.analyze(listOf(ruleA, ruleB))

            conflicts.size shouldBe 1
            conflicts.single().type shouldBe ConflictType.CYCLE
            conflicts.single().ruleIds shouldBe listOf(ruleA.id, ruleB.id).sorted()
        }
    }

    describe("CYCLE 검출 — 3-cycle") {
        it("A→B→C→A 를 형성하면 CYCLE 1건, ruleIds=[A,B,C] 가 정렬돼 담긴다") {
            val ruleA =
                ruleOf(
                    name = "A",
                    triggerType = TriggerType.ISSUE_UPDATED,
                    triggerConfig = """{"fields":["f3"]}""",
                    actions = listOf(Action.SetFieldAction(field = "f1", value = TextNode("v"))),
                )
            val ruleB =
                ruleOf(
                    name = "B",
                    triggerType = TriggerType.ISSUE_UPDATED,
                    triggerConfig = """{"fields":["f1"]}""",
                    actions = listOf(Action.SetFieldAction(field = "f2", value = TextNode("v"))),
                )
            val ruleC =
                ruleOf(
                    name = "C",
                    triggerType = TriggerType.ISSUE_UPDATED,
                    triggerConfig = """{"fields":["f2"]}""",
                    actions = listOf(Action.SetFieldAction(field = "f3", value = TextNode("v"))),
                )

            val conflicts = analyzer.analyze(listOf(ruleA, ruleB, ruleC))

            conflicts.size shouldBe 1
            conflicts.single().type shouldBe ConflictType.CYCLE
            conflicts.single().ruleIds shouldBe listOf(ruleA.id, ruleB.id, ruleC.id).sorted()
        }
    }

    describe("CYCLE 미검출 — 직선 체인") {
        it("A→B 로만 이어지고 되돌아오지 않으면 0건이다") {
            val ruleA =
                ruleOf(
                    name = "A",
                    triggerType = TriggerType.ISSUE_CREATED,
                    actions = listOf(Action.SetFieldAction(field = "x", value = TextNode("v"))),
                )
            val ruleB =
                ruleOf(
                    name = "B",
                    triggerType = TriggerType.ISSUE_UPDATED,
                    triggerConfig = """{"fields":["x"]}""",
                )

            val conflicts = analyzer.analyze(listOf(ruleA, ruleB))

            conflicts.shouldBeEmpty()
        }
    }

    describe("CYCLE 검출 — AddComment 엣지") {
        it("A(AddComment)가 B(ISSUE_COMMENTED)를 유발하고 B(SetField)가 A를 되돌리면 CYCLE 1건") {
            val ruleA =
                ruleOf(
                    name = "A",
                    triggerType = TriggerType.ISSUE_UPDATED,
                    triggerConfig = """{"fields":["status"]}""",
                    actions = listOf(Action.AddCommentAction(body = "댓글")),
                )
            val ruleB =
                ruleOf(
                    name = "B",
                    triggerType = TriggerType.ISSUE_COMMENTED,
                    actions = listOf(Action.SetFieldAction(field = "status", value = TextNode("open"))),
                )

            val conflicts = analyzer.analyze(listOf(ruleA, ruleB))

            conflicts.size shouldBe 1
            conflicts.single().type shouldBe ConflictType.CYCLE
            conflicts.single().ruleIds shouldBe listOf(ruleA.id, ruleB.id).sorted()
        }
    }

    describe("CYCLE 미검출 — CallWebhookAction만 있는 규칙은 엣지 없음") {
        it("웹훅 규칙은 트리거를 유발받아도 되돌리는 엣지가 없어 사이클에 참여하지 않는다") {
            val ruleX =
                ruleOf(
                    name = "X",
                    triggerType = TriggerType.ISSUE_UPDATED,
                    triggerConfig = """{"fields":["p"]}""",
                    actions = listOf(Action.SetFieldAction(field = "q", value = TextNode("v"))),
                )
            val ruleWebhook =
                ruleOf(
                    name = "W",
                    triggerType = TriggerType.ISSUE_UPDATED,
                    triggerConfig = """{"fields":["q"]}""",
                    actions = listOf(Action.CallWebhookAction(url = "https://example.com/hook")),
                )

            val conflicts = analyzer.analyze(listOf(ruleX, ruleWebhook))

            conflicts.shouldBeEmpty()
        }
    }

    describe("CYCLE dedup — 같은 사이클이 여러 DFS 경로로 중복 검출돼도 1건만") {
        it("C의 두 액션이 각각 독립적으로 A를 유발해도(다중 엣지) CYCLE은 1건만 리포트된다") {
            val ruleA =
                ruleOf(
                    name = "A",
                    triggerType = TriggerType.ISSUE_UPDATED,
                    triggerConfig = """{"fields":["p"]}""",
                    actions = listOf(Action.SetFieldAction(field = "q", value = TextNode("v"))),
                )
            val ruleC =
                ruleOf(
                    name = "C",
                    triggerType = TriggerType.ISSUE_UPDATED,
                    triggerConfig = """{"fields":["q"]}""",
                    // 두 액션 모두 같은 값("v1")을 SET한다 — 값이 다르면(예: v1/v2) FR-AT-04 Task 3의
                    // FIELD_CONFLICT(규칙 내부 같은 필드·다른 값)도 함께 검출돼 이 CYCLE 전용 테스트의
                    // "conflicts.size shouldBe 1" 단언과 무관한 충돌이 섞인다. 값을 동일하게 둬 멱등
                    // 처리(FIELD_CONFLICT 미검출)로 만들고 다중 엣지 CYCLE dedup만 순수하게 검증한다.
                    actions =
                        listOf(
                            Action.SetFieldAction(field = "p", value = TextNode("v1")),
                            Action.SetFieldAction(field = "p", value = TextNode("v1")),
                        ),
                )

            val conflicts = analyzer.analyze(listOf(ruleA, ruleC))

            conflicts.size shouldBe 1
            conflicts.single().type shouldBe ConflictType.CYCLE
            conflicts.single().ruleIds shouldBe listOf(ruleA.id, ruleC.id).sorted()
        }
    }

    describe("CYCLE 미검출 — disabled 규칙은 노드에서 제외된다") {
        it("self-loop 조건을 만족해도 disabled면 검출되지 않는다") {
            val ruleA =
                ruleOf(
                    name = "A",
                    triggerType = TriggerType.ISSUE_UPDATED,
                    triggerConfig = """{"fields":["priority"]}""",
                    actions = listOf(Action.SetFieldAction(field = "priority", value = TextNode("High"))),
                    enabled = false,
                )

            val conflicts = analyzer.analyze(listOf(ruleA))

            conflicts.shouldBeEmpty()
        }
    }

    describe("CYCLE 미검출 — AssignAction 은 phantom 엣지를 만들지 않는다 (코드리뷰 C1 hotfix)") {
        it(
            "A(AssignAction, ISSUE_UPDATED{status})↔B(SetField(status), ISSUE_UPDATED{assignee}) 는 " +
                "B→A 엣지만 있고 A→B 엣지가 없어 CYCLE 이 아니다",
        ) {
            // 담당자 변경은 issue.assigned 별도 이벤트로 발행되고 automation TriggerMatcher 는 그 이벤트를
            // 소비하지 않는다(issue.created/updated/commented 만 매핑) — 즉 AssignAction 이 실제로
            // ISSUE_UPDATED{fields:[assignee]} 를 유발하는 런타임 경로는 존재하지 않는다. A→B 엣지가 그대로
            // 있었다면 B→A(SetField(status)가 A의 ISSUE_UPDATED{status} 를 유발) 와 합쳐져 A↔B 2-cycle 로
            // 오탐(phantom CYCLE)됐을 시나리오다.
            val ruleA =
                ruleOf(
                    name = "A",
                    triggerType = TriggerType.ISSUE_UPDATED,
                    triggerConfig = """{"fields":["status"]}""",
                    actions = listOf(Action.AssignAction(assigneeId = UUID.randomUUID())),
                )
            val ruleB =
                ruleOf(
                    name = "B",
                    triggerType = TriggerType.ISSUE_UPDATED,
                    triggerConfig = """{"fields":["assignee"]}""",
                    actions = listOf(Action.SetFieldAction(field = "status", value = TextNode("open"))),
                )

            val conflicts = analyzer.analyze(listOf(ruleA, ruleB))

            conflicts.none { it.type == ConflictType.CYCLE } shouldBe true
        }
    }
})
