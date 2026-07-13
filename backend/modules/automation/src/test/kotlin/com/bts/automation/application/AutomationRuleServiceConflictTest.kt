// AutomationRuleService 저장/lint 분리 단위 테스트 (FR-AT-04 BLOCKER hotfix — create/patch 는 analyzer 미호출)

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
import com.bts.automation.domain.Action
import com.bts.automation.domain.AutomationRule
import com.bts.automation.domain.ConflictType
import com.bts.automation.domain.RuleConflict
import com.bts.automation.domain.TriggerType
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

private val FIXED_NOW: Instant = Instant.parse("2026-07-13T00:00:00Z")
private const val PROJECT_KEY = "ATLAS"

/**
 * [AutomationRuleService.create]/[AutomationRuleService.patch] 가 저장(및 OCC/도메인 검증)만 담당하고
 * [RuleConflictAnalyzer] 호출은 [AutomationRuleService.analyzeProjectConflicts] 로 완전히 분리됐는지
 * 검증한다(FR-AT-04 코드리뷰 BLOCKER hotfix).
 *
 * ## 왜 분리됐는가
 * 수정 전에는 `create`/`patch` 자신의 `@Transactional` 경계 **안에서** lint(재조회·hydrate·analyze)를
 * 호출했다 — [AutomationRuleRepository.findByProject]/[AutomationActionRepository.findByRuleId] 는
 * 참여(REQUIRED) 전파로 같은 트랜잭션에 합류하므로, 그 안에서 read 예외가 나면 Spring 이
 * `globalRollbackOnParticipationFailure`(기본 true)로 트랜잭션을 rollback-only 로 표시한다 — 이 표시는
 * 애플리케이션 코드의 `try/catch` 로 예외를 흡수해도 지워지지 않아, 메서드가 정상 반환해 커밋을
 * 시도하면 `UnexpectedRollbackException` 이 나며 방금 저장한 규칙까지 롤백된다. 이 순수 MockK 단위
 * 테스트는 Spring 트랜잭션 전파를 관측할 수 없어 그 증상 자체는 재현하지 못한다(참여 전파는
 * [com.bts.automation.integration.AutomationRuleLintTransactionIsolationIntegrationTest] 가 실 DB
 * round-trip 으로 담당) — 대신 이 테스트는 그 버그의 **근본 원인이 된 아키텍처**(저장 메서드가 lint 를
 * 내부 호출하는 구조) 자체가 제거됐음을 오케스트레이션 레벨에서 증명한다: `create`/`patch` 는
 * [conflictAnalyzer] 를 전혀 호출하지 않는다.
 *
 * MockK 로 [AutomationRuleRepository]/[AutomationActionRepository]/[AutomationConditionRepository]/
 * [RuleConflictAnalyzer] 를 대체해 실 DB 없이 순수 오케스트레이션 로직만 검증한다.
 *
 * ### 검증 항목
 * - a. create 저장 성공 — analyzer 를 호출하지 않고 `conflicts` 는 빈 리스트 placeholder 다.
 * - b. patch 저장 성공(변경/무변경 no-op 모두) — analyzer 를 호출하지 않는다.
 * - c. [AutomationRuleService.analyzeProjectConflicts] — 저장과 무관하게 단독 호출 시 hydrate 된 규칙으로
 *   analyzer 를 호출해 결과를 반환한다.
 * - d. [AutomationRuleService.analyzeProjectConflicts] fail-safe — 참여 read 를 흉내낸
 *   `repository.findByProject` 예외도 전파하지 않고 빈 리스트 + 경고 로그로 흡수한다.
 * - e. GET(단건/목록) 응답엔 conflicts 가 없다(analyzer 미호출, 기존 동작 불변).
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

    // Kotest DescribeSpec 은 기본적으로 spec 인스턴스를 모든 it 블록이 공유한다 — mockk 호출 기록이
    // 테스트 간 누적되면 verify(exactly=...) 가 이전 테스트의 호출까지 세어 오탐한다. 매 it 종료 후
    // 초기화한다([com.bts.automation.application.ActionExecutorTest] 동형 패턴).
    afterEach {
        clearMocks(repository, actionRepository, conditionRepository, conflictAnalyzer)
    }

    describe("create — 저장만 하고 lint 는 호출하지 않는다") {
        it("a. 저장 성공 후 analyzer/findByProject 를 호출하지 않고 conflicts 는 빈 리스트 placeholder 다") {
            every { repository.save(any()) } just runs
            every { actionRepository.replaceForRule(any(), any()) } just runs
            every { conditionRepository.replace(any(), isNull()) } just runs

            val created =
                service.create(
                    actorId = actorId,
                    projectKey = PROJECT_KEY,
                    name = "룰",
                    triggerType = TriggerType.ISSUE_CREATED,
                    triggerConfig = "{}",
                )

            created.conflicts.shouldBeEmpty()
            verify(exactly = 1) { repository.save(any()) }
            verify(exactly = 0) { repository.findByProject(any()) }
            verify(exactly = 0) { conflictAnalyzer.analyze(any()) }
        }

        it("a2. repository.save 가 예외를 던지면 그대로 전파된다(analyzer 는 애초에 create 경로에 없다)") {
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

    describe("patch — 저장만 하고 lint 는 호출하지 않는다") {
        it("b1. 필드 변경 저장 성공 후 analyzer/findByProject 를 호출하지 않는다") {
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
            patched.conflicts.shouldBeEmpty()
            verify(exactly = 1) { repository.update(any()) }
            verify(exactly = 0) { repository.findByProject(any()) }
            verify(exactly = 0) { conflictAnalyzer.analyze(any()) }
        }

        it("b2. 무변경(no-op) PATCH 도 analyzer/findByProject 를 호출하지 않는다") {
            val existingRule =
                AutomationRule.create(
                    projectKey = PROJECT_KEY,
                    name = "무변경 대상",
                    triggerType = TriggerType.ISSUE_CREATED,
                    createdBy = actorId,
                    actorUserId = actorId,
                    now = FIXED_NOW,
                )

            every { repository.findById(existingRule.id) } returns existingRule
            every { actionRepository.findByRuleId(existingRule.id) } returns emptyList()
            every { conditionRepository.findByRuleId(existingRule.id) } returns null

            val patched =
                service.patch(
                    actorId = actorId,
                    projectKey = PROJECT_KEY,
                    id = existingRule.id,
                    expectedVersion = 0,
                    name = null,
                    enabled = null,
                    triggerConfig = null,
                )

            patched.rule.version shouldBe existingRule.version
            patched.conflicts.shouldBeEmpty()
            verify(exactly = 0) { repository.update(any()) }
            verify(exactly = 0) { repository.findByProject(any()) }
            verify(exactly = 0) { conflictAnalyzer.analyze(any()) }
        }
    }

    describe("analyzeProjectConflicts — 저장 트랜잭션과 분리된 lint 전용 공개 메서드") {
        it("c. projectKey 전체 규칙을 재조회·hydrate 해 analyzer 를 호출하고 결과를 반환한다") {
            val sampleAction = Action.AddCommentAction(body = "댓글")
            val rule =
                AutomationRule.create(
                    projectKey = PROJECT_KEY,
                    name = "분석 대상",
                    triggerType = TriggerType.ISSUE_CREATED,
                    createdBy = actorId,
                    actorUserId = actorId,
                    now = FIXED_NOW,
                )

            every { repository.findByProject(PROJECT_KEY) } returns listOf(rule)
            every { actionRepository.findByRuleId(rule.id) } returns listOf(sampleAction)
            every { conditionRepository.findByRuleId(rule.id) } returns null
            every { conflictAnalyzer.analyze(any()) } returns listOf(sampleConflict)

            val conflicts = service.analyzeProjectConflicts(PROJECT_KEY)

            conflicts shouldBe listOf(sampleConflict)
            verify(exactly = 1) { conflictAnalyzer.analyze(match { it.single().actions == listOf(sampleAction) }) }
        }

        it("d. repository.findByProject(참여 read 대역) 가 예외를 던져도 전파하지 않고 빈 리스트 + 경고 로그를 남긴다") {
            every { repository.findByProject(PROJECT_KEY) } throws IllegalStateException("read 실패")

            val logger = LoggerFactory.getLogger(AutomationRuleService::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().also { it.start() }
            logger.addAppender(appender)

            val conflicts =
                try {
                    service.analyzeProjectConflicts(PROJECT_KEY)
                } finally {
                    logger.detachAppender(appender)
                }

            conflicts.shouldBeEmpty()
            verify(exactly = 0) { conflictAnalyzer.analyze(any()) }
            val warnLogs = appender.list.filter { it.level == Level.WARN }
            warnLogs.size shouldBe 1
        }

        it("d2. hydrate 가 부르는 actionRepository.findByRuleId 가 예외를 던져도 빈 리스트로 흡수한다") {
            val rule =
                AutomationRule.create(
                    projectKey = PROJECT_KEY,
                    name = "손상된 액션 보유",
                    triggerType = TriggerType.ISSUE_CREATED,
                    createdBy = actorId,
                    actorUserId = actorId,
                    now = FIXED_NOW,
                )
            every { repository.findByProject(PROJECT_KEY) } returns listOf(rule)
            every { actionRepository.findByRuleId(rule.id) } throws IllegalStateException("action 파싱 실패")

            val conflicts = service.analyzeProjectConflicts(PROJECT_KEY)

            conflicts.shouldBeEmpty()
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
