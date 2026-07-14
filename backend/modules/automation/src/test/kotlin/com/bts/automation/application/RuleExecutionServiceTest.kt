// RuleExecutionService 단위 테스트 — 권한 가드(list/get) + 존재 숨김(getById 타프로젝트) (FR-AT-05 Task 4)

package com.bts.automation.application

import com.bts.automation.StubAutomationPermissionResolver
import com.bts.automation.adapter.AutomationRuleRepository
import com.bts.automation.adapter.RuleExecutionRepository
import com.bts.automation.domain.ActionType
import com.bts.automation.domain.AutomationRule
import com.bts.automation.domain.TriggerType
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

private const val PROJECT_KEY = "ATLAS"
private const val OTHER_PROJECT_KEY = "OTHER"

/**
 * [RuleExecutionService] 단위 테스트 (FR-AT-05 Task 4/5).
 *
 * MockK 로 [RuleExecutionRepository]/[AutomationRuleRepository]/[ActionExecutor] 를, [StubAutomationPermissionResolver]
 * 로 `AutomationPermissionResolver` 를 대체해 실 DB/실 포트 위임 없이 오케스트레이션만 검증한다
 * ([AutomationRuleServiceConflictTest] 동형 패턴).
 *
 * ### 검증 항목
 * - [RuleExecutionService.listByRule] — 권한 없으면 [AutomationForbiddenException], 있으면 repository 결과를
 *   그대로 반환(파라미터 그대로 위임).
 * - [RuleExecutionService.getById] — id 미존재/타 프로젝트(권한 없음) 모두 [RuleExecutionNotFoundException]
 *   로 수렴(존재 숨김, [[auth-extraction-before-resource-lookup]] 계열 — 403 이 아니라 404), 정상 조회는
 *   그대로 반환.
 * - [RuleExecutionService.replay](Task 5) — id 미존재/권한 없음 모두 [RuleExecutionNotFoundException] 수렴,
 *   원본 룰 소프트삭제/부재는 [AutomationRuleUnavailableException], 정상 재실행은 [ActionExecutor.execute]
 *   를 dryRun=false 로 호출하고 replayedFrom·triggerType 을 원본 값으로 저장, 이력 저장 실패는 삼키지 않고
 *   전파(E1 — 클래스 KDoc "replay — 실행-시점 트랜잭션 비대칭" 참조).
 */
class RuleExecutionServiceTest : DescribeSpec({
    val repository = mockk<RuleExecutionRepository>()
    val permissionResolver = StubAutomationPermissionResolver()
    val ruleRepository = mockk<AutomationRuleRepository>()
    val actionExecutor = mockk<ActionExecutor>()
    val fixedNow = Instant.parse("2026-07-14T00:00:00Z")
    val clock = Clock.fixed(fixedNow, ZoneOffset.UTC)
    val service =
        RuleExecutionService(
            repository = repository,
            permissionResolver = permissionResolver,
            ruleRepository = ruleRepository,
            actionExecutor = actionExecutor,
            clock = clock,
        )

    val actorId = UUID.randomUUID()
    val ruleId = UUID.randomUUID()
    val objectMapper = ObjectMapper()

    fun sampleExecution(
        id: UUID = UUID.randomUUID(),
        projectKey: String = PROJECT_KEY,
    ): RuleExecution =
        RuleExecution(
            id = id,
            ruleId = ruleId,
            projectKey = projectKey,
            triggerType = TriggerType.ISSUE_CREATED,
            triggerEvent = objectMapper.readTree("""{"issueKey":"ATLAS-1"}"""),
            issueKey = "ATLAS-1",
            status = ActionExecutionStatus.SUCCESS,
            outcomes =
                listOf(ActionOutcome(position = 0, actionType = ActionType.SET_FIELD, success = true, error = null)),
            replayedFrom = null,
            startedAt = Instant.parse("2026-07-12T00:00:00Z"),
            finishedAt = Instant.parse("2026-07-12T00:00:01Z"),
        )

    fun sampleRule(
        id: UUID = ruleId,
        projectKey: String = PROJECT_KEY,
    ): AutomationRule =
        AutomationRule
            .create(
                projectKey = projectKey,
                name = "replay 대상 룰",
                triggerType = TriggerType.ISSUE_CREATED,
                createdBy = UUID.randomUUID(),
                now = Instant.parse("2026-07-01T00:00:00Z"),
            ).copy(id = id)

    // Kotest DescribeSpec 은 spec 인스턴스를 모든 it 블록이 공유한다 — mock 호출 기록/permission 등록 상태가
    // 테스트 간 누적되면 오탐한다([AutomationRuleServiceConflictTest] 동형 정리).
    afterEach {
        clearMocks(repository, ruleRepository, actionExecutor)
        permissionResolver.reset()
    }

    describe("listByRule") {
        it("권한이 없으면 AutomationForbiddenException 을 던지고 repository 를 호출하지 않는다") {
            shouldThrow<AutomationForbiddenException> {
                service.listByRule(actorId, PROJECT_KEY, ruleId, issueKey = null, limit = 50, before = null)
            }

            verify(exactly = 0) { repository.findByRule(any(), any(), any(), any(), any()) }
        }

        it("권한이 있으면 repository 결과를 그대로(파라미터 위임) 반환한다") {
            permissionResolver.allow(PROJECT_KEY)
            val executions = listOf(sampleExecution())
            val before = Instant.parse("2026-07-13T00:00:00Z")
            every {
                repository.findByRule(PROJECT_KEY, ruleId, issueKey = "ATLAS-1", limit = 50, before = before)
            } returns executions

            val result =
                service.listByRule(actorId, PROJECT_KEY, ruleId, issueKey = "ATLAS-1", limit = 50, before = before)

            result shouldBe executions
            verify(exactly = 1) {
                repository.findByRule(PROJECT_KEY, ruleId, issueKey = "ATLAS-1", limit = 50, before = before)
            }
        }
    }

    describe("getById") {
        it("id 가 존재하지 않으면 RuleExecutionNotFoundException 을 던진다") {
            val id = UUID.randomUUID()
            every { repository.findById(id) } returns null

            shouldThrow<RuleExecutionNotFoundException> {
                service.getById(actorId, id)
            }
        }

        it("record 는 있으나 소속 프로젝트 권한이 없으면 RuleExecutionNotFoundException 으로 수렴한다(존재 숨김, 403 아님)") {
            val execution = sampleExecution(projectKey = OTHER_PROJECT_KEY)
            every { repository.findById(execution.id) } returns execution
            // OTHER_PROJECT_KEY 는 allow 하지 않는다 — StubAutomationPermissionResolver 는 fail-closed 기본값.

            shouldThrow<RuleExecutionNotFoundException> {
                service.getById(actorId, execution.id)
            }
        }

        it("record 가 있고 권한도 있으면 정상 반환한다") {
            permissionResolver.allow(PROJECT_KEY)
            val execution = sampleExecution()
            every { repository.findById(execution.id) } returns execution

            val result = service.getById(actorId, execution.id)

            result shouldBe execution
        }
    }

    describe("replay") {
        it("실행 이력이 존재하지 않으면 RuleExecutionNotFoundException 을 던지고 actionExecutor 를 호출하지 않는다") {
            val id = UUID.randomUUID()
            every { repository.findById(id) } returns null

            shouldThrow<RuleExecutionNotFoundException> {
                service.replay(actorId, id)
            }

            verify(exactly = 0) { actionExecutor.execute(any(), any(), any()) }
        }

        it("record 는 있으나 소속 프로젝트 권한이 없으면 RuleExecutionNotFoundException 으로 수렴한다(존재 숨김, 403 아님)") {
            val execution = sampleExecution(projectKey = OTHER_PROJECT_KEY)
            every { repository.findById(execution.id) } returns execution
            // OTHER_PROJECT_KEY 는 allow 하지 않는다 — StubAutomationPermissionResolver 는 fail-closed 기본값.

            shouldThrow<RuleExecutionNotFoundException> {
                service.replay(actorId, execution.id)
            }

            verify(exactly = 0) { actionExecutor.execute(any(), any(), any()) }
        }

        it("원본 룰이 소프트 삭제/부재이면 AutomationRuleUnavailableException 을 던지고 actionExecutor 를 호출하지 않는다") {
            permissionResolver.allow(PROJECT_KEY)
            val execution = sampleExecution()
            every { repository.findById(execution.id) } returns execution
            every { ruleRepository.findById(ruleId) } returns null

            shouldThrow<AutomationRuleUnavailableException> {
                service.replay(actorId, execution.id)
            }

            verify(exactly = 0) { actionExecutor.execute(any(), any(), any()) }
        }

        it("정상 재실행 시 actionExecutor.execute 를 dryRun=false 로 호출하고 replayedFrom·triggerType 을 원본 값으로 저장한 새 실행을 반환한다") {
            permissionResolver.allow(PROJECT_KEY)
            val execution = sampleExecution()
            val rule = sampleRule()
            val result =
                ActionExecutionResult(
                    status = ActionExecutionStatus.SUCCESS,
                    outcomes =
                        listOf(
                            ActionOutcome(position = 0, actionType = ActionType.SET_FIELD, success = true, error = null),
                        ),
                )
            every { repository.findById(execution.id) } returns execution
            every { ruleRepository.findById(ruleId) } returns rule
            every { actionExecutor.execute(rule, execution.triggerEvent, dryRun = false) } returns result
            val savedSlot = slot<RuleExecution>()
            every { repository.save(capture(savedSlot)) } returns Unit

            val replayed = service.replay(actorId, execution.id)

            verify(exactly = 1) { actionExecutor.execute(rule, execution.triggerEvent, dryRun = false) }
            replayed.replayedFrom shouldBe execution.id
            replayed.triggerType shouldBe execution.triggerType
            replayed.triggerEvent shouldBe execution.triggerEvent
            replayed.issueKey shouldBe execution.issueKey
            replayed.status shouldBe ActionExecutionStatus.SUCCESS
            replayed.ruleId shouldBe rule.id
            replayed.projectKey shouldBe rule.projectKey
            replayed.startedAt shouldBe fixedNow
            replayed.finishedAt shouldBe fixedNow
            savedSlot.captured shouldBe replayed
        }

        it("이력 저장(save) 실패 시 예외를 삼키지 않고 그대로 전파한다(이슈 변경은 이미 커밋된 뒤라 삼키면 안 됨)") {
            permissionResolver.allow(PROJECT_KEY)
            val execution = sampleExecution()
            val rule = sampleRule()
            val result = ActionExecutionResult(status = ActionExecutionStatus.SUCCESS, outcomes = emptyList())
            every { repository.findById(execution.id) } returns execution
            every { ruleRepository.findById(ruleId) } returns rule
            every { actionExecutor.execute(rule, execution.triggerEvent, dryRun = false) } returns result
            every { repository.save(any()) } throws RuntimeException("db down")

            shouldThrow<RuntimeException> {
                service.replay(actorId, execution.id)
            }

            verify(exactly = 1) { actionExecutor.execute(rule, execution.triggerEvent, dryRun = false) }
        }
    }
})
