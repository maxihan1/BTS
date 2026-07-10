// AutomationEventWorker 통합 테스트 — q_automation_events 폴링→TriggerMatcher 매칭→q_automation_execution 도달 (FR-AT-01 Task 7)

package com.bts.automation.worker

import com.bts.automation.AutomationTestBootApplication
import com.bts.automation.AutomationTestcontainersBase
import com.bts.automation.adapter.AutomationRuleRepository
import com.bts.automation.domain.AutomationRule
import com.bts.automation.domain.TriggerConfig
import com.bts.automation.domain.TriggerType
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.util.UUID

/**
 * [AutomationEventWorker] 통합 테스트 (FR-AT-01 Task 7).
 *
 * [AutomationTestBootApplication] 컴포넌트 스캔으로 실 [AutomationRuleRepository]·
 * `AutomationExecutionEnqueuer` 를 로드하고, [AutomationTestcontainersBase] 가 배선한 Testcontainers
 * pgmq 위에서 `q_automation_events` → `q_automation_execution` end-to-end 를 검증한다.
 *
 * `@EnableScheduling` 은 이 Task 범위 밖(Task 11) 이므로 [AutomationEventWorker.pollAndProcess] 를
 * 직접 호출한다(스케줄 대기 없음, plan-eng-review E5).
 *
 * ## 검증 시나리오
 * - ISSUE_CREATED/ISSUE_COMMENTED 이벤트가 매칭 룰을 발화시켜 q_automation_execution 도달
 * - ISSUE_UPDATED triggerConfig `fields` 교집합 필터(있음→발화 / 없음→미발화 / 빈 설정→전체 발화)
 * - disabled·soft-deleted 룰은 매칭 이벤트가 와도 발화하지 않음(EC7)
 * - 미관심 타입(issue.transitioned)은 skip 되고 q_automation_events 에서 삭제됨(EC4, under-processing 방지)
 * - 빈 큐는 아무 처리도 하지 않음
 */
@SpringBootTest(
    classes = [AutomationTestBootApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
)
@Import(AutomationTestcontainersBase::class)
class AutomationEventWorkerTest {
    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var worker: AutomationEventWorker

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var ruleRepository: AutomationRuleRepository

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var objectMapper: ObjectMapper

    /** 결정적 테스트를 위한 고정 기준 시각. */
    private val now: Instant = Instant.parse("2026-07-10T00:00:00Z")

    @BeforeEach
    fun cleanUp() {
        // 테스트 격리 — 각 케이스 전에 테이블/큐를 비운다(AutomationRuleRepositoryTest 동형).
        jdbcTemplate.update("DELETE FROM automation_rules")
        jdbcTemplate.execute("SELECT pgmq.purge_queue('q_automation_events')")
        jdbcTemplate.execute("SELECT pgmq.purge_queue('q_automation_execution')")
    }

    // ── 룰 헬퍼 ────────────────────────────────────────────────────────────────

    private fun saveEnabledRule(
        triggerType: TriggerType,
        triggerConfig: String = TriggerConfig.EMPTY,
        projectKey: String = "ATLAS",
    ): AutomationRule {
        val rule =
            AutomationRule.create(
                projectKey = projectKey,
                name = "테스트 룰",
                triggerType = triggerType,
                triggerConfig = triggerConfig,
                createdBy = UUID.randomUUID(),
                now = now,
            )
        ruleRepository.save(rule)
        return rule
    }

    private fun saveDisabledRule(
        triggerType: TriggerType,
        projectKey: String = "ATLAS",
    ): AutomationRule {
        val rule = saveEnabledRule(triggerType = triggerType, projectKey = projectKey)
        ruleRepository.update(rule.disable(now))
        return rule
    }

    // ── 이벤트 JSON 헬퍼 (issue-tracking IssueDomainEvent wire 계약 미러, BC 격리 — 클래스 import 없음) ──

    private fun issueCreatedJson(issueKey: String): String {
        val node = objectMapper.createObjectNode()
        node.put("type", "issue.created")
        node.put("issueKey", issueKey)
        node.put("projectKey", issueKey.substringBefore('-'))
        node.put("summary", "제목")
        node.putObject("reporterId").put("value", UUID.randomUUID().toString())
        node.putObject("actorId").put("value", UUID.randomUUID().toString())
        node.put("occurredAt", now.toString())
        return objectMapper.writeValueAsString(node)
    }

    private fun issueUpdatedJson(
        issueKey: String,
        fields: List<String>,
    ): String {
        val node = objectMapper.createObjectNode()
        node.put("type", "issue.updated")
        node.put("issueKey", issueKey)
        val fieldsArray = node.putArray("fields")
        fields.forEach { fieldsArray.add(it) }
        node.put("occurredAt", now.toString())
        return objectMapper.writeValueAsString(node)
    }

    private fun issueCommentedJson(issueKey: String): String {
        val node = objectMapper.createObjectNode()
        node.put("type", "issue.commented")
        node.put("issueKey", issueKey)
        node.put("projectKey", issueKey.substringBefore('-'))
        node.put("commentId", UUID.randomUUID().toString())
        node.putObject("actorId").put("value", UUID.randomUUID().toString())
        node.put("occurredAt", now.toString())
        return objectMapper.writeValueAsString(node)
    }

    private fun issueTransitionedJson(issueKey: String): String {
        val node = objectMapper.createObjectNode()
        node.put("type", "issue.transitioned")
        node.put("issueKey", issueKey)
        node.put("fromState", "open")
        node.put("toState", "in_progress")
        node.putObject("actorId").put("value", UUID.randomUUID().toString())
        node.put("occurredAt", now.toString())
        return objectMapper.writeValueAsString(node)
    }

    // ── pgmq 헬퍼 ─────────────────────────────────────────────────────────────

    private fun sendEvent(json: String) {
        jdbcTemplate.queryForObject(
            "SELECT pgmq.send(?, ?::jsonb)",
            Long::class.java,
            "q_automation_events",
            json,
        )
    }

    private fun readExecutionMessages(): List<JsonNode> =
        jdbcTemplate
            .queryForList(
                "SELECT message::text FROM pgmq.read('q_automation_execution', 1, 50)",
                String::class.java,
            ).map { objectMapper.readTree(it) }

    private fun countPendingEventMessages(): Int =
        jdbcTemplate
            .queryForList(
                "SELECT msg_id FROM pgmq.read('q_automation_events', 1, 50)",
                Long::class.java,
            ).size

    // ── 테스트 ────────────────────────────────────────────────────────────────

    @Test
    fun `ISSUE_CREATED 이벤트가 매칭 룰을 발화시켜 q_automation_execution 에 도달한다`() {
        val rule = saveEnabledRule(TriggerType.ISSUE_CREATED)
        sendEvent(issueCreatedJson("ATLAS-1"))

        worker.pollAndProcess()

        val messages = readExecutionMessages()
        assertThat(messages).hasSize(1)
        assertThat(messages[0].get("ruleId").asText()).isEqualTo(rule.id.toString())
        assertThat(messages[0].get("triggerType").asText()).isEqualTo("ISSUE_CREATED")
        assertThat(messages[0].get("triggerEvent").get("issueKey").asText()).isEqualTo("ATLAS-1")
        assertThat(countPendingEventMessages()).isZero()
    }

    @Test
    fun `ISSUE_COMMENTED 이벤트가 매칭 룰을 발화시켜 q_automation_execution 에 도달한다`() {
        val rule = saveEnabledRule(TriggerType.ISSUE_COMMENTED)
        sendEvent(issueCommentedJson("ATLAS-2"))

        worker.pollAndProcess()

        val messages = readExecutionMessages()
        assertThat(messages).hasSize(1)
        assertThat(messages[0].get("ruleId").asText()).isEqualTo(rule.id.toString())
        assertThat(messages[0].get("triggerType").asText()).isEqualTo("ISSUE_COMMENTED")
    }

    @Test
    fun `ISSUE_UPDATED 이벤트 — triggerConfig fields 와 교집합이 있으면 발화한다`() {
        val rule = saveEnabledRule(TriggerType.ISSUE_UPDATED, triggerConfig = """{"fields":["priority"]}""")
        sendEvent(issueUpdatedJson("ATLAS-3", listOf("priority", "summary")))

        worker.pollAndProcess()

        val messages = readExecutionMessages()
        assertThat(messages).hasSize(1)
        assertThat(messages[0].get("ruleId").asText()).isEqualTo(rule.id.toString())
    }

    @Test
    fun `ISSUE_UPDATED 이벤트 — triggerConfig fields 와 교집합이 없으면 발화하지 않는다`() {
        saveEnabledRule(TriggerType.ISSUE_UPDATED, triggerConfig = """{"fields":["priority"]}""")
        sendEvent(issueUpdatedJson("ATLAS-4", listOf("summary")))

        worker.pollAndProcess()

        assertThat(readExecutionMessages()).isEmpty()
        // 처리 자체는 성공(매칭 룰이 없을 뿐)이므로 원본 이벤트 메시지는 삭제된다.
        assertThat(countPendingEventMessages()).isZero()
    }

    @Test
    fun `ISSUE_UPDATED 이벤트 — triggerConfig fields 가 비어있으면 모든 변경에 발화한다`() {
        val rule = saveEnabledRule(TriggerType.ISSUE_UPDATED)
        sendEvent(issueUpdatedJson("ATLAS-5", listOf("description")))

        worker.pollAndProcess()

        val messages = readExecutionMessages()
        assertThat(messages).hasSize(1)
        assertThat(messages[0].get("ruleId").asText()).isEqualTo(rule.id.toString())
    }

    @Test
    fun `disabled 룰은 매칭 이벤트가 와도 발화하지 않는다`() {
        saveDisabledRule(TriggerType.ISSUE_CREATED)
        sendEvent(issueCreatedJson("ATLAS-6"))

        worker.pollAndProcess()

        assertThat(readExecutionMessages()).isEmpty()
    }

    @Test
    fun `soft-delete 된 룰은 매칭 이벤트가 와도 발화하지 않는다`() {
        val rule = saveEnabledRule(TriggerType.ISSUE_CREATED)
        ruleRepository.softDelete(rule.id, now)
        sendEvent(issueCreatedJson("ATLAS-7"))

        worker.pollAndProcess()

        assertThat(readExecutionMessages()).isEmpty()
    }

    @Test
    fun `미관심 타입(issue_transitioned) 이벤트는 skip 되고 q_automation_events 에서 삭제된다`() {
        saveEnabledRule(TriggerType.ISSUE_CREATED)
        sendEvent(issueTransitionedJson("ATLAS-8"))

        worker.pollAndProcess()

        assertThat(readExecutionMessages()).isEmpty()
        assertThat(countPendingEventMessages()).isZero()
    }

    @Test
    fun `빈 큐는 아무 처리도 하지 않는다`() {
        worker.pollAndProcess()

        assertThat(readExecutionMessages()).isEmpty()
    }
}
