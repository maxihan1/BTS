// RuleExecutionService 단위 테스트 — 권한 가드(list/get) + 존재 숨김(getById 타프로젝트) (FR-AT-05 Task 4)

package com.bts.automation.application

import com.bts.automation.StubAutomationPermissionResolver
import com.bts.automation.adapter.RuleExecutionRepository
import com.bts.automation.domain.ActionType
import com.bts.automation.domain.TriggerType
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.util.UUID

private const val PROJECT_KEY = "ATLAS"
private const val OTHER_PROJECT_KEY = "OTHER"

/**
 * [RuleExecutionService] 단위 테스트 (FR-AT-05 Task 4).
 *
 * MockK 로 [RuleExecutionRepository] 를, [StubAutomationPermissionResolver] 로 `AutomationPermissionResolver`
 * 를 대체해 실 DB 없이 권한 오케스트레이션만 검증한다([AutomationRuleServiceConflictTest] 동형 패턴).
 *
 * ### 검증 항목
 * - [RuleExecutionService.listByRule] — 권한 없으면 [AutomationForbiddenException], 있으면 repository 결과를
 *   그대로 반환(파라미터 그대로 위임).
 * - [RuleExecutionService.getById] — id 미존재/타 프로젝트(권한 없음) 모두 [RuleExecutionNotFoundException]
 *   로 수렴(존재 숨김, [[auth-extraction-before-resource-lookup]] 계열 — 403 이 아니라 404), 정상 조회는
 *   그대로 반환.
 */
class RuleExecutionServiceTest : DescribeSpec({
    val repository = mockk<RuleExecutionRepository>()
    val permissionResolver = StubAutomationPermissionResolver()
    val service = RuleExecutionService(repository = repository, permissionResolver = permissionResolver)

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

    // Kotest DescribeSpec 은 spec 인스턴스를 모든 it 블록이 공유한다 — mock 호출 기록/permission 등록 상태가
    // 테스트 간 누적되면 오탐한다([AutomationRuleServiceConflictTest] 동형 정리).
    afterEach {
        clearMocks(repository)
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
})
