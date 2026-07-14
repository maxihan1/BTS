// RuleExecutionRepository 통합 테스트 — 저장/단건조회/룰별목록조회(정렬·필터·keyset)·감사독립성(룰조인없음) (FR-AT-05 Task 2)

package com.bts.automation.adapter

import com.bts.automation.AutomationTestBootApplication
import com.bts.automation.AutomationTestcontainersBase
import com.bts.automation.StubAutomationPermissionResolver
import com.bts.automation.StubIssueMutationPort
import com.bts.automation.StubIssuePermissionResolver
import com.bts.automation.StubIssueSnapshotPort
import com.bts.automation.application.ActionExecutionStatus
import com.bts.automation.application.ActionOutcome
import com.bts.automation.application.RuleExecution
import com.bts.automation.domain.ActionType
import com.bts.automation.domain.TriggerType
import com.bts.shared.issue.IssueMutationPort
import com.bts.shared.issue.IssueSnapshotPort
import com.bts.shared.permission.AutomationPermissionResolver
import com.bts.shared.permission.IssuePermissionResolver
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.util.UUID

/**
 * [RuleExecutionRepository] 통합 테스트 (FR-AT-05 Task 2).
 *
 * [AutomationTestBootApplication] 컴포넌트 스캔으로 실 [RuleExecutionRepository] 를 로드하고,
 * [AutomationTestcontainersBase] 가 배선한 Testcontainers DataSource 위에서 실제 SQL 을 검증한다
 * ([AutomationConditionRepositoryTest] 선례 동형).
 *
 * ## 검증 시나리오
 * - save → findById round-trip(trigger_event 중첩 JSON + outcomes 2건 직렬화/역직렬화 정합,
 *   status/triggerType/replayedFrom null·non-null 왕복)
 * - findById 미존재 → null
 * - findByRule — started_at DESC 정렬 / issueKey 필터 / limit / before keyset
 * - 감사 독립성(NFR-4) — automation_rules 에 없는 rule_id 도 findByRule 로 조회됨(룰 테이블 조인 없음)
 * - findByRule — 다른 project_key 로는 빈 목록(존재 숨김)
 */
@SpringBootTest(
    classes = [AutomationTestBootApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
)
@Import(
    AutomationTestcontainersBase::class,
    RuleExecutionRepositoryTest.PermissionResolverStubConfig::class,
)
class RuleExecutionRepositoryTest {
    /**
     * `com.bts.automation` 전체 스캔 시 함께 로드되는 협력자([AutomationRuleService]/`ActionExecutor`/
     * `RuleConflictAnalyzer`)가 non-null 로 요구하는 cross-BC 포트를 test-boot용 stub 으로 등록한다
     * ([AutomationRuleRepositoryTest] 동형 — 이 테스트 자체는 이 포트들을 쓰지 않지만 컨텍스트 로드에 필요).
     */
    @TestConfiguration
    class PermissionResolverStubConfig {
        @Bean
        fun automationPermissionResolver(): AutomationPermissionResolver = StubAutomationPermissionResolver()

        @Bean
        fun issueMutationPort(): IssueMutationPort = StubIssueMutationPort()

        @Bean
        fun issueSnapshotPort(): IssueSnapshotPort = StubIssueSnapshotPort()

        @Bean
        fun issuePermissionResolver(): IssuePermissionResolver = StubIssuePermissionResolver()
    }

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var repository: RuleExecutionRepository

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var objectMapper: ObjectMapper

    private val ruleId: UUID = UUID.randomUUID()
    private val projectKey = "ATLAS"

    @BeforeEach
    fun cleanUp() {
        jdbcTemplate.update("DELETE FROM rule_executions")
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private fun sampleTriggerEvent(issueKey: String): JsonNode =
        objectMapper.readTree(
            """{"issueKey":"$issueKey","issue":{"key":"$issueKey","status":"Open"},"actorId":"${UUID.randomUUID()}"}""",
        )

    private fun sampleOutcomes(): List<ActionOutcome> =
        listOf(
            ActionOutcome(position = 0, actionType = ActionType.SET_FIELD, success = true, error = null),
            ActionOutcome(position = 1, actionType = ActionType.CALL_WEBHOOK, success = false, error = "FAILED"),
        )

    private fun sampleExecution(
        ruleId: UUID = this.ruleId,
        projectKey: String = this.projectKey,
        issueKey: String? = "ATLAS-1",
        startedAt: Instant = Instant.parse("2026-07-12T00:00:00Z"),
        replayedFrom: UUID? = null,
    ): RuleExecution =
        RuleExecution(
            id = UUID.randomUUID(),
            ruleId = ruleId,
            projectKey = projectKey,
            triggerType = TriggerType.ISSUE_CREATED,
            triggerEvent = sampleTriggerEvent(issueKey ?: "ATLAS-1"),
            issueKey = issueKey,
            status = ActionExecutionStatus.PARTIAL,
            outcomes = sampleOutcomes(),
            replayedFrom = replayedFrom,
            startedAt = startedAt,
            finishedAt = startedAt.plusSeconds(1),
        )

    // ── save / findById round-trip ────────────────────────────────────────────

    @Test
    fun `save 후 findById 로 trigger_event·outcomes·상태값이 그대로 복원된다`() {
        val execution = sampleExecution()

        repository.save(execution)
        val found = repository.findById(execution.id)

        assertThat(found).isEqualTo(execution)
    }

    @Test
    fun `save — replayedFrom 이 있으면 그대로 왕복된다`() {
        val original = sampleExecution()
        repository.save(original)
        val replay = sampleExecution(replayedFrom = original.id)

        repository.save(replay)
        val found = repository.findById(replay.id)

        assertThat(found?.replayedFrom).isEqualTo(original.id)
    }

    @Test
    fun `findById — 존재하지 않는 id 는 null 을 반환한다`() {
        assertThat(repository.findById(UUID.randomUUID())).isNull()
    }

    // ── findByRule — 정렬 ─────────────────────────────────────────────────────

    @Test
    fun `findByRule — started_at DESC 로 정렬된다`() {
        val oldest = sampleExecution(startedAt = Instant.parse("2026-07-10T00:00:00Z"))
        val middle = sampleExecution(startedAt = Instant.parse("2026-07-11T00:00:00Z"))
        val newest = sampleExecution(startedAt = Instant.parse("2026-07-12T00:00:00Z"))
        listOf(oldest, middle, newest).forEach { repository.save(it) }

        val found = repository.findByRule(projectKey, ruleId, issueKey = null, limit = 10, before = null)

        assertThat(found.map { it.id }).containsExactly(newest.id, middle.id, oldest.id)
    }

    // ── findByRule — issueKey 필터 ────────────────────────────────────────────

    @Test
    fun `findByRule — issueKey 를 지정하면 해당 이슈 실행만 반환한다`() {
        val forIssue1 = sampleExecution(issueKey = "ATLAS-1")
        val forIssue2 = sampleExecution(issueKey = "ATLAS-2")
        repository.save(forIssue1)
        repository.save(forIssue2)

        val found = repository.findByRule(projectKey, ruleId, issueKey = "ATLAS-2", limit = 10, before = null)

        assertThat(found.map { it.id }).containsExactly(forIssue2.id)
    }

    // ── findByRule — limit ────────────────────────────────────────────────────

    @Test
    fun `findByRule — limit 만큼만 반환한다`() {
        repository.save(sampleExecution(startedAt = Instant.parse("2026-07-10T00:00:00Z")))
        repository.save(sampleExecution(startedAt = Instant.parse("2026-07-11T00:00:00Z")))
        repository.save(sampleExecution(startedAt = Instant.parse("2026-07-12T00:00:00Z")))

        val found = repository.findByRule(projectKey, ruleId, issueKey = null, limit = 2, before = null)

        assertThat(found).hasSize(2)
    }

    // ── findByRule — before keyset ────────────────────────────────────────────

    @Test
    fun `findByRule — before 이전 실행만 반환한다(keyset)`() {
        val early = sampleExecution(startedAt = Instant.parse("2026-07-10T00:00:00Z"))
        val late = sampleExecution(startedAt = Instant.parse("2026-07-12T00:00:00Z"))
        repository.save(early)
        repository.save(late)

        val found =
            repository.findByRule(
                projectKey,
                ruleId,
                issueKey = null,
                limit = 10,
                before = Instant.parse("2026-07-11T00:00:00Z"),
            )

        assertThat(found.map { it.id }).containsExactly(early.id)
    }

    // ── 감사 독립성(NFR-4) — 룰 테이블 조인 없음 ─────────────────────────────

    @Test
    fun `findByRule — automation_rules 에 없는 rule_id 도 조회된다(소프트삭제 룰 이력 보존)`() {
        // automation_rules 에 이 rule_id 로 저장된 룰이 전혀 없다(하드 FK 부재를 전제로 한 시나리오).
        val orphanRuleId = UUID.randomUUID()
        val execution = sampleExecution(ruleId = orphanRuleId)

        repository.save(execution)
        val found = repository.findByRule(projectKey, orphanRuleId, issueKey = null, limit = 10, before = null)

        assertThat(found.map { it.id }).containsExactly(execution.id)
    }

    // ── findByRule — 다른 project_key ─────────────────────────────────────────

    @Test
    fun `findByRule — 다른 project_key 로는 빈 목록을 반환한다(존재 숨김)`() {
        repository.save(sampleExecution())

        val found = repository.findByRule("OTHER", ruleId, issueKey = null, limit = 10, before = null)

        assertThat(found).isEmpty()
    }
}
