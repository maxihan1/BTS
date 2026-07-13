// AutomationRuleService 저장 후 lint 통합 단위 테스트 — create/patch 응답 conflicts + fail-safe + GET 미포함 (FR-AT-04 Task 5 TDD RED)

package com.bts.automation.application

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.bts.automation.StubAutomationPermissionResolver
import com.bts.automation.adapter.AutomationActionRepository
import com.bts.automation.adapter.AutomationConditionRepository
import com.bts.automation.adapter.AutomationRuleRepository
import com.bts.automation.adapter.web.dto.AutomationRuleResponse
import com.bts.automation.adapter.web.dto.CreateAutomationRuleResponse
import com.bts.automation.domain.Action
import com.bts.automation.domain.AutomationRule
import com.bts.automation.domain.ConflictType
import com.bts.automation.domain.RuleConflict
import com.bts.automation.domain.TriggerType
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.TextNode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.isNull
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

private val FIXED_NOW: Instant = Instant.parse("2026-07-13T00:00:00Z")
private const val PROJECT_KEY = "ATLAS"

/**
 * [AutomationRuleService.create]/[AutomationRuleService.patch] 가 저장 성공 후 [RuleConflictAnalyzer] 를
 * 호출해 응답 DTO 에 [RuleConflict] 목록을 실어 보내는지 검증한다(FR-AT-04 Task 5).
 *
 * MockK 로 [AutomationRuleRepository]/[AutomationActionRepository]/[AutomationConditionRepository]/
 * [RuleConflictAnalyzer] 를 대체해 실 DB 없이 순수 오케스트레이션 로직만 검증한다
 * ([com.bts.automation.application.ActionExecutorTest] 동형 패턴).
 *
 * ### 검증 항목
 * - a. create 저장 성공 후 hydrate 된 규칙으로 analyzer 호출 → 응답 DTO(conflicts) 반영
 * - b. patch 동일
 * - c. fail-safe — analyzer 예외는 저장을 훼손하지 않고 conflicts 는 빈 리스트 + 경고 로그
 * - d. 순서 — 저장 실패 시 analyzer 는 호출되지 않는다
 * - e. GET(단건/목록) 응답엔 conflicts 가 없다(analyzer 미호출)
 */
class AutomationRuleServiceConflictTest : DescribeSpec({

    val repository = mockk<AutomationRuleRepository>()
    val actionRepository = mockk<AutomationActionRepository>()
    val conditionRepository = mockk<AutomationConditionRepository>()
    val conflictAnalyzer = mockk<RuleConflictAnalyzer>()
    val permissionResolver = StubAutomationPermissionResolver().apply { allow(PROJECT_KEY) }
    val clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC)

    val service =
        AutomationRuleService(
            repository = repository,
            actionRepository = actionRepository,
            conditionRepository = conditionRepository,
            permissionResolver = permissionResolver,
            objectMapper = ObjectMapper(),
            conflictAnalyzer = conflictAnalyzer,
            clock = clock,
        )

    val actorId = UUID.randomUUID()
    val sampleConflict =
        RuleConflict.of(type = ConflictType.CYCLE, ruleIds = listOf(UUID.randomUUID()), detail = "테스트 충돌")

    describe("create — 저장 후 lint 통합") {
        it("a. 저장 성공 후 hydrate 된 규칙으로 analyzer 를 호출해 응답 DTO 에 conflicts 를 포함한다") {
            val savedRuleSlot = slot<AutomationRule>()
            val analyzedRulesSlot = slot<List<AutomationRule>>()
            val sampleAction = Action.SetFieldAction(field = "priority", value = TextNode("High"))

            every { repository.save(capture(savedRuleSlot)) } just runs
            every { actionRepository.replaceForRule(any(), any()) } just runs
            every { conditionRepository.replace(any(), isNull()) } just runs
            every { repository.findByProject(PROJECT_KEY) } answers {
                listOf(savedRuleSlot.captured.copy(actions = emptyList()))
            }
            every { actionRepository.findByRuleId(any()) } returns listOf(sampleAction)
            every { conditionRepository.findByRuleId(any()) } returns null
            every { conflictAnalyzer.analyze(capture(analyzedRulesSlot)) } returns listOf(sampleConflict)

            val created =
                service.create(
                    actorId = actorId,
                    projectKey = PROJECT_KEY,
                    name = "룰",
                    triggerType = TriggerType.ISSUE_CREATED,
                    triggerConfig = "{}",
                )

            created.conflicts shouldBe listOf(sampleConflict)
            analyzedRulesSlot.captured.single().actions shouldBe listOf(sampleAction)
            verifyOrder {
                repository.save(any())
                conflictAnalyzer.analyze(any())
            }

            val response = CreateAutomationRuleResponse.from(created)
            response.rule.conflicts?.map { it.type } shouldBe listOf(ConflictType.CYCLE)
        }
    }

    describe("patch — 저장 후 lint 통합") {
        it("b. 저장 성공 후 analyzer 를 호출해 응답 DTO 에 conflicts 를 포함한다") {
            val existingRule =
                AutomationRule.create(
                    projectKey = PROJECT_KEY,
                    name = "원래 이름",
                    triggerType = TriggerType.ISSUE_CREATED,
                    createdBy = actorId,
                    actorUserId = actorId,
                    now = FIXED_NOW,
                )

            every { repository.findById(existingRule.id) } returns existingRule
            every { actionRepository.findByRuleId(existingRule.id) } returns emptyList()
            every { conditionRepository.findByRuleId(existingRule.id) } returns null
            every { repository.update(any()) } just runs
            every { repository.findByProject(PROJECT_KEY) } returns listOf(existingRule)
            every { conflictAnalyzer.analyze(any()) } returns listOf(sampleConflict)

            val patched =
                service.patch(
                    actorId = actorId,
                    projectKey = PROJECT_KEY,
                    id = existingRule.id,
                    expectedVersion = 0,
                    name = "바뀐 이름",
                    enabled = null,
                    triggerConfig = null,
                )

            patched.rule.name shouldBe "바뀐 이름"
            patched.conflicts shouldBe listOf(sampleConflict)
            verifyOrder {
                repository.update(any())
                conflictAnalyzer.analyze(any())
            }

            AutomationRuleResponse.from(patched).conflicts?.map { it.type } shouldBe listOf(ConflictType.CYCLE)
        }
    }

    describe("fail-safe — analyzer 예외는 저장을 훼손하지 않는다") {
        it("c. analyzer 가 예외를 던져도 저장은 성공하고 conflicts 는 빈 리스트 + 경고 로그를 남긴다") {
            every { repository.save(any()) } just runs
            every { actionRepository.replaceForRule(any(), any()) } just runs
            every { conditionRepository.replace(any(), isNull()) } just runs
            every { repository.findByProject(PROJECT_KEY) } returns emptyList()
            every { actionRepository.findByRuleId(any()) } returns emptyList()
            every { conditionRepository.findByRuleId(any()) } returns null
            every { conflictAnalyzer.analyze(any()) } throws IllegalStateException("분석 실패")

            val logger = LoggerFactory.getLogger(AutomationRuleService::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().also { it.start() }
            logger.addAppender(appender)

            val created =
                try {
                    service.create(
                        actorId = actorId,
                        projectKey = PROJECT_KEY,
                        name = "fail-safe 룰",
                        triggerType = TriggerType.ISSUE_CREATED,
                        triggerConfig = "{}",
                    )
                } finally {
                    logger.detachAppender(appender)
                }

            created.conflicts.shouldBeEmpty()
            verify(exactly = 1) { repository.save(any()) }
            val warnLogs = appender.list.filter { it.level == Level.WARN }
            warnLogs.size shouldBe 1
        }
    }

    describe("순서 보장 — 저장 실패 시 analyzer 를 호출하지 않는다") {
        it("d. repository.save 가 예외를 던지면 analyzer 는 호출되지 않고 예외가 그대로 전파된다") {
            every { repository.save(any()) } throws RuntimeException("db down")

            shouldThrow<RuntimeException> {
                service.create(
                    actorId = actorId,
                    projectKey = PROJECT_KEY,
                    name = "저장 실패 룰",
                    triggerType = TriggerType.ISSUE_CREATED,
                    triggerConfig = "{}",
                )
            }

            verify(exactly = 0) { conflictAnalyzer.analyze(any()) }
        }
    }

    describe("GET 단건/목록 — conflicts 미포함, analyzer 미호출") {
        it("e. get/list 는 analyzer 를 호출하지 않고 응답 DTO 의 conflicts 는 null 이다") {
            val rule =
                AutomationRule.create(
                    projectKey = PROJECT_KEY,
                    name = "조회 대상",
                    triggerType = TriggerType.ISSUE_CREATED,
                    createdBy = actorId,
                    actorUserId = actorId,
                    now = FIXED_NOW,
                )

            every { repository.findById(rule.id) } returns rule
            every { repository.findByProject(PROJECT_KEY) } returns listOf(rule)
            every { actionRepository.findByRuleId(rule.id) } returns emptyList()
            every { conditionRepository.findByRuleId(rule.id) } returns null

            val single = service.get(actorId, PROJECT_KEY, rule.id)
            val list = service.list(actorId, PROJECT_KEY)

            AutomationRuleResponse.from(single).conflicts.shouldBeNull()
            AutomationRuleResponse.from(list.single()).conflicts.shouldBeNull()
            verify(exactly = 0) { conflictAnalyzer.analyze(any()) }
        }
    }
})
