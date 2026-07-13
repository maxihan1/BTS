// RuleConflictAnalyzer PERMISSION_MISSING 검출 단위 테스트 — actor UPDATE 권한 부재 검출 + 메모이제이션 (FR-AT-04 Task 4)

package com.bts.automation.application

import com.bts.automation.domain.Action
import com.bts.automation.domain.AutomationRule
import com.bts.automation.domain.ConflictType
import com.bts.automation.domain.TriggerConfig
import com.bts.automation.domain.TriggerType
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.fasterxml.jackson.databind.node.TextNode
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.util.UUID

/**
 * [RuleConflictAnalyzer.analyze] 의 [ConflictType.PERMISSION_MISSING] 검출을 검증한다(FR-AT-04 Task 4, 스펙 FR-6).
 *
 * CYCLE/FIELD_CONFLICT/PRIORITY_AMBIGUITY 는 각각 [RuleConflictAnalyzerCycleTest]/
 * [RuleConflictAnalyzerFieldPriorityTest] 가 담당한다 — 이 파일은 mockk 로 대체한 [IssuePermissionResolver]
 * 로 PERMISSION_MISSING 만 검증한다. 모든 시나리오가 `triggerType = ISSUE_CREATED` 단일 규칙(또는 서로 다른
 * 필드의 규칙 쌍)만 써서 CYCLE 이 함께 검출되지 않게 한다.
 */
class RuleConflictAnalyzerPermissionTest : DescribeSpec({

    val fixedNow: Instant = Instant.parse("2026-07-13T00:00:00Z")
    val projectKey = "PROJ"

    fun ruleOf(
        name: String,
        actorUserId: UUID,
        actions: List<Action>,
        enabled: Boolean = true,
    ): AutomationRule {
        val rule =
            AutomationRule.create(
                projectKey = projectKey,
                name = name,
                triggerType = TriggerType.ISSUE_CREATED,
                triggerConfig = TriggerConfig.EMPTY,
                createdBy = actorUserId,
                actorUserId = actorUserId,
                actions = actions,
                now = fixedNow,
            )
        return if (enabled) rule else rule.disable(fixedNow)
    }

    describe("PERMISSION_MISSING — SetFieldAction, actor가 UPDATE 권한이 없음") {
        it("resolver가 false를 반환하면 PERMISSION_MISSING 1건, detail에 ruleId·actorId가 포함된다") {
            val actorId = UUID.randomUUID()
            val resolver = mockk<IssuePermissionResolver>()
            every {
                resolver.hasPermission(actorId, IssuePermission.UPDATE, IssueScope.Project(projectKey))
            } returns false
            val analyzer = RuleConflictAnalyzer(resolver)
            val rule =
                ruleOf(
                    name = "A",
                    actorUserId = actorId,
                    actions = listOf(Action.SetFieldAction(field = "priority", value = TextNode("High"))),
                )

            val conflicts = analyzer.analyze(listOf(rule))

            conflicts.size shouldBe 1
            conflicts.single().type shouldBe ConflictType.PERMISSION_MISSING
            conflicts.single().ruleIds shouldBe listOf(rule.id)
            conflicts.single().detail shouldContain rule.id.toString()
            conflicts.single().detail shouldContain actorId.toString()
        }
    }

    describe("PERMISSION_MISSING — AssignAction, actor가 UPDATE 권한이 없음") {
        it("resolver가 false를 반환하면 PERMISSION_MISSING 1건") {
            val actorId = UUID.randomUUID()
            val resolver = mockk<IssuePermissionResolver>()
            every {
                resolver.hasPermission(actorId, IssuePermission.UPDATE, IssueScope.Project(projectKey))
            } returns false
            val analyzer = RuleConflictAnalyzer(resolver)
            val rule =
                ruleOf(
                    name = "A",
                    actorUserId = actorId,
                    actions = listOf(Action.AssignAction(assigneeId = UUID.randomUUID())),
                )

            val conflicts = analyzer.analyze(listOf(rule))

            conflicts.size shouldBe 1
            conflicts.single().type shouldBe ConflictType.PERMISSION_MISSING
            conflicts.single().ruleIds shouldBe listOf(rule.id)
        }
    }

    describe("PERMISSION_MISSING 미검출 — actor가 UPDATE 권한을 보유") {
        it("resolver가 true를 반환하면 검출되지 않는다") {
            val actorId = UUID.randomUUID()
            val resolver = mockk<IssuePermissionResolver>()
            every {
                resolver.hasPermission(actorId, IssuePermission.UPDATE, IssueScope.Project(projectKey))
            } returns true
            val analyzer = RuleConflictAnalyzer(resolver)
            val rule =
                ruleOf(
                    name = "A",
                    actorUserId = actorId,
                    actions = listOf(Action.SetFieldAction(field = "priority", value = TextNode("High"))),
                )

            val conflicts = analyzer.analyze(listOf(rule))

            conflicts.shouldBeEmpty()
        }
    }

    describe("PERMISSION_MISSING 제외 — AddCommentAction") {
        it("AddCommentAction만 있으면 resolver를 호출하지 않고 미검출이다") {
            val actorId = UUID.randomUUID()
            val resolver = mockk<IssuePermissionResolver>()
            val analyzer = RuleConflictAnalyzer(resolver)
            val rule =
                ruleOf(
                    name = "A",
                    actorUserId = actorId,
                    actions = listOf(Action.AddCommentAction(body = "댓글")),
                )

            val conflicts = analyzer.analyze(listOf(rule))

            conflicts.none { it.type == ConflictType.PERMISSION_MISSING } shouldBe true
            verify(exactly = 0) { resolver.hasPermission(any(), any(), any()) }
        }
    }

    describe("PERMISSION_MISSING 제외 — CallWebhookAction") {
        it("CallWebhookAction만 있으면 resolver를 호출하지 않고 미검출이다") {
            val actorId = UUID.randomUUID()
            val resolver = mockk<IssuePermissionResolver>()
            val analyzer = RuleConflictAnalyzer(resolver)
            val rule =
                ruleOf(
                    name = "A",
                    actorUserId = actorId,
                    actions = listOf(Action.CallWebhookAction(url = "https://example.com/hook")),
                )

            val conflicts = analyzer.analyze(listOf(rule))

            conflicts.none { it.type == ConflictType.PERMISSION_MISSING } shouldBe true
            verify(exactly = 0) { resolver.hasPermission(any(), any(), any()) }
        }
    }

    describe("PERMISSION_MISSING 메모이제이션 — 같은 (actorId, projectKey, UPDATE) 조합의 여러 규칙/액션") {
        it("resolver.hasPermission은 1회만 호출된다") {
            val actorId = UUID.randomUUID()
            val resolver = mockk<IssuePermissionResolver>()
            every {
                resolver.hasPermission(actorId, IssuePermission.UPDATE, IssueScope.Project(projectKey))
            } returns false
            val analyzer = RuleConflictAnalyzer(resolver)
            val ruleA =
                ruleOf(
                    name = "A",
                    actorUserId = actorId,
                    actions = listOf(Action.SetFieldAction(field = "priority", value = TextNode("High"))),
                )
            val ruleB =
                ruleOf(
                    name = "B",
                    actorUserId = actorId,
                    actions =
                        listOf(
                            Action.SetFieldAction(field = "status", value = TextNode("open")),
                            Action.AssignAction(assigneeId = UUID.randomUUID()),
                        ),
                )

            val conflicts = analyzer.analyze(listOf(ruleA, ruleB))

            conflicts.count { it.type == ConflictType.PERMISSION_MISSING } shouldBe 2
            verify(exactly = 1) {
                resolver.hasPermission(actorId, IssuePermission.UPDATE, IssueScope.Project(projectKey))
            }
        }
    }

    describe("PERMISSION_MISSING 제외 — disabled 규칙") {
        it("disabled 규칙은 권한 분석에서 제외된다(resolver 미호출)") {
            val actorId = UUID.randomUUID()
            val resolver = mockk<IssuePermissionResolver>()
            val analyzer = RuleConflictAnalyzer(resolver)
            val rule =
                ruleOf(
                    name = "A",
                    actorUserId = actorId,
                    actions = listOf(Action.SetFieldAction(field = "priority", value = TextNode("High"))),
                    enabled = false,
                )

            val conflicts = analyzer.analyze(listOf(rule))

            conflicts.shouldBeEmpty()
            verify(exactly = 0) { resolver.hasPermission(any(), any(), any()) }
        }
    }
})
