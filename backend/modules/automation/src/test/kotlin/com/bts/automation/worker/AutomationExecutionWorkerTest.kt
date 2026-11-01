// AutomationExecutionWorker 통합 테스트 — q_automation_execution 폴링·루프가드 2단·ActionExecutor 실행·pgmq 생명주기 (FR-AT-02 Task 10)

package com.bts.automation.worker

import com.bts.automation.AutomationTestBootApplication
import com.bts.automation.AutomationTestcontainersBase
import com.bts.automation.StubAutomationPermissionResolver
import com.bts.automation.StubIssueMutationPort
import com.bts.automation.StubIssuePermissionResolver
import com.bts.automation.StubIssueSnapshotPort
import com.bts.automation.adapter.AutomationConditionRepository
import com.bts.automation.adapter.AutomationExecutionEnqueuer
import com.bts.automation.adapter.AutomationRuleRepository
import com.bts.automation.adapter.RuleExecutionRepository
import com.bts.automation.application.ActionExecutionStatus
import com.bts.automation.application.ActionExecutor
import com.bts.automation.application.WebhookFanOut
import com.bts.automation.domain.Action
import com.bts.automation.domain.AutomationRule
import com.bts.automation.domain.ComparisonOperator
import com.bts.automation.domain.Condition
import com.bts.automation.domain.TriggerType
import com.bts.shared.permission.AutomationPermissionResolver
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.search.IssueSearchHit
import com.bts.shared.search.IssueSearchPage
import com.bts.shared.search.IssueSearchPort
import com.bts.shared.search.IssueSearchQuery
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.TextNode
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [AutomationExecutionWorker] 통합 테스트 (FR-AT-02 Task 10, [[pgmq-consumer-message-lifecycle-p0]]).
 *
 * [AutomationTestBootApplication] 컴포넌트 스캔으로 실 [AutomationRuleRepository]·[ActionExecutor] 를
 * 로드하고, [AutomationTestcontainersBase] 가 배선한 Testcontainers pgmq 위에서 `q_automation_execution`
 * 폴링→처리→pgmq 생명주기(archive) end-to-end 를 검증한다.
 *
 * `@EnableScheduling` 은 이 Task 범위 밖이므로 [AutomationExecutionWorker.pollAndProcess] 를 직접
 * 호출한다([AutomationScheduleWorkerTest] 동형, plan-eng-review E5). Clock 은 워커 생성자에 직접
 * 주입한다([AutomationScheduleWorkerTest] 의 수동 생성 패턴 재사용) — 억제 캐시(루프 가드 (b))는 워커
 * 인스턴스 로컬 상태이므로, 시각 전진이 필요한 시나리오는 같은 워커 인스턴스에 [MutableClock] 을
 * 주입해 재사용한다(매 호출 새 인스턴스를 만들면 캐시가 초기화돼 억제 검증이 무력화된다).
 *
 * ## 처리 실패 시나리오는 [ActionExecutor] mock 사용
 * [ActionExecutor.execute] 는 액션 1건 실패를 예외로 전파하지 않고 [com.bts.automation.application.ActionOutcome]
 * 으로 흡수하는 best-effort 설계라([StubIssueMutationPort] 실패 주입으로는 워커 레벨 예외를 재현할 수
 * 없다), read_ct/dead-letter 검증 2건만 `mockk<ActionExecutor>()` 로 워커 레벨 예외를 직접 주입한다
 * (그 외 시나리오는 전부 실 [ActionExecutor] + [StubIssueMutationPort] 조합).
 *
 * ## 검증 시나리오
 * - 정상 메시지 → [ActionExecutor] 액션 실행([StubIssueMutationPort] 호출 검증) + archive(큐 비워짐)
 * - 존재하지 않는 룰/disabled 룰 → 스킵 + archive(EC7), 액션 미실행
 * - executionDepth > 10 → 스킵 + archive, 액션 미실행(루프 가드 a). 10 이하는 경계값으로 실행됨을 확인
 * - (ruleId, issueKey) 60초 이내 재실행 → 스킵 + archive, 액션 미실행(루프 가드 b) — 60초 경과 후에는
 *   다시 실행된다(Clock 조작). 다른 이슈에는 억제가 적용되지 않는다
 * - 처리 중 예외가 반복돼도 read_ct 가 MAX_RECEIVE_COUNT 를 넘기 전까지는 큐에 남아 재시도 대기하고,
 *   넘기면 archive 로 수렴한다(무한 재시도 방지)
 * - 빈 큐는 아무 것도 처리하지 않는다
 * - (FR-AT-05) [ActionExecutor.execute] 시도분만 `rule_executions` 에 이력이 남는다 — 정상/SKIPPED 실행은
 *   기록되고, 억제창 스킵·룰 부재/disabled·malformed payload(모두 액션 실행 전에 archive 되는 경로)는
 *   기록되지 않는다. 이력 저장 자체가 실패해도 archive 는 fail-safe 로 정상 진행된다.
 */
@SpringBootTest(
    classes = [AutomationTestBootApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
)
@Import(
    AutomationTestcontainersBase::class,
    AutomationExecutionWorkerTest.TestSupportConfig::class,
)
class AutomationExecutionWorkerTest {
    /**
     * 컨텍스트 로드용 [AutomationPermissionResolver]/[StubIssueMutationPort] 스텁 등록
     * ([AutomationEventWorkerTest] 동형, 클래스 KDoc 참조). [StubIssueMutationPort] 는 구체 타입으로
     * 등록해 테스트가 [issueMutationPort] 로 직접 주입받아 호출 기록을 검증할 수 있게 한다
     * ([com.bts.automation.web.AutomationRuleControllerTest] 동형). [StubIssueSnapshotPort] 는
     * `ActionExecutor`(FR-AT-03 Task 7)가 non-null 로 요구하는 [com.bts.shared.issue.IssueSnapshotPort]
     * 를 동일 사유로 대신 등록한다. `RuleConflictAnalyzer`(FR-AT-04 Task 4)가 non-null 로 요구하는
     * [IssuePermissionResolver] 도 동일 사유로 [StubIssuePermissionResolver] 를 대신 등록한다.
     */
    @TestConfiguration
    class TestSupportConfig {
        @Bean
        fun automationPermissionResolver(): AutomationPermissionResolver = StubAutomationPermissionResolver()

        @Bean
        fun stubIssueMutationPort(): StubIssueMutationPort = StubIssueMutationPort()

        @Bean
        fun stubIssueSnapshotPort(): StubIssueSnapshotPort = StubIssueSnapshotPort()

        @Bean
        fun issuePermissionResolver(): IssuePermissionResolver = StubIssuePermissionResolver()

        @Bean
        fun issueSearchPort(): FakeIssueSearchPort = FakeIssueSearchPort()
    }

    /** 웹훅 상태 검색(BTS-F2-01)용 — [keys] 를 그대로 돌려주고 [total] 을 실제 수보다 크게 둘 수 있다. 받은 질의는 [queries]. */
    class FakeIssueSearchPort : IssueSearchPort {
        var keys: List<String> = emptyList()
        var total: Long? = null
        val queries: MutableList<IssueSearchQuery> = CopyOnWriteArrayList()

        override fun search(query: IssueSearchQuery): IssueSearchPage {
            queries += query
            val hits =
                keys.map { key ->
                    IssueSearchHit(key, "s", "task", "deploy_wait", null, 3, "Medium", query.projectKey, Instant.EPOCH)
                }
            return IssueSearchPage(hits, total ?: hits.size.toLong(), query.page, query.size)
        }
    }

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var ruleRepository: AutomationRuleRepository

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var actionExecutor: ActionExecutor

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var ruleExecutionRepository: RuleExecutionRepository

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var conditionRepository: AutomationConditionRepository

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var issueMutationPort: StubIssueMutationPort

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var issueSearchPort: FakeIssueSearchPort

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var webhookFanOut: WebhookFanOut

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var enqueuer: AutomationExecutionEnqueuer

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var objectMapper: ObjectMapper

    /** 결정적 테스트를 위한 고정 기준 시각. */
    private val now: Instant = Instant.parse("2026-07-11T00:00:00Z")

    @BeforeEach
    fun cleanUp() {
        // 테스트 격리 — 각 케이스 전에 테이블/큐/archive 를 비운다(AutomationEventWorkerTest 동형 +
        // archive 는 purge_queue 로 안 비워지므로 별도 DELETE, SlackDeliveryWorkerIntegrationTest 동형).
        // rule_executions 는 automation_rules 와 하드 FK 가 없어(감사 독립성, NFR-4) automation_rules
        // DELETE 로 cascade 되지 않으므로 별도 DELETE 가 필요하다(RuleExecutionRepository 클래스 KDoc 참조).
        jdbcTemplate.update("DELETE FROM automation_rules")
        jdbcTemplate.update("DELETE FROM rule_executions")
        jdbcTemplate.execute("SELECT pgmq.purge_queue('q_automation_execution')")
        jdbcTemplate.update("DELETE FROM pgmq.\"${pgmqTable("a")}\"")
        issueMutationPort.reset()
        issueSearchPort.keys = emptyList()
        issueSearchPort.total = null
        issueSearchPort.queries.clear()
    }

    // ── 워커/룰/메시지 헬퍼 ───────────────────────────────────────────────────

    private fun worker(
        clock: Clock = Clock.fixed(now, ZoneOffset.UTC),
        executor: ActionExecutor = actionExecutor,
        executionRepository: RuleExecutionRepository = ruleExecutionRepository,
    ): AutomationExecutionWorker {
        return AutomationExecutionWorker(
            jdbcTemplate,
            objectMapper,
            ruleRepository,
            executor,
            executionRepository,
            webhookFanOut,
            clock,
        )
    }

    private fun saveEnabledRule(projectKey: String = "ATLAS"): AutomationRule {
        val rule =
            AutomationRule.create(
                projectKey = projectKey,
                name = "테스트 룰",
                triggerType = TriggerType.ISSUE_CREATED,
                createdBy = UUID.randomUUID(),
                actions = listOf(Action.SetFieldAction(field = "priority", value = TextNode("High"))),
                now = now,
            )
        ruleRepository.save(rule)
        return rule
    }

    private fun saveDisabledRule(): AutomationRule {
        val rule = saveEnabledRule()
        ruleRepository.update(rule.disable(now))
        return rule
    }

    private fun enqueueExecution(
        ruleId: UUID,
        issueKey: String? = "ATLAS-1",
        executionDepth: Int? = null,
    ) {
        val triggerEvent = objectMapper.createObjectNode()
        if (issueKey != null) triggerEvent.put("issueKey", issueKey)
        val payload =
            objectMapper.createObjectNode().apply {
                put("ruleId", ruleId.toString())
                put("triggerType", TriggerType.ISSUE_CREATED.name)
                set<JsonNode>("triggerEvent", triggerEvent)
                if (executionDepth != null) put("executionDepth", executionDepth)
            }
        jdbcTemplate.queryForObject(
            "SELECT pgmq.send(?, ?::jsonb)",
            Long::class.java,
            "q_automation_execution",
            objectMapper.writeValueAsString(payload),
        )
    }

    private fun pendingCount(): Int {
        val sql = "SELECT count(*) FROM pgmq.\"${pgmqTable("q")}\""
        return jdbcTemplate.queryForObject(sql, Int::class.java) ?: 0
    }

    private fun archivedCount(): Int {
        val sql = "SELECT count(*) FROM pgmq.\"${pgmqTable("a")}\""
        return jdbcTemplate.queryForObject(sql, Int::class.java) ?: 0
    }

    private fun forceReadCount(value: Int) {
        jdbcTemplate.update("UPDATE pgmq.\"${pgmqTable("q")}\" SET read_ct = ?", value)
    }

    /**
     * pgmq 스키마에서 [prefix](`q`=큐·`a`=archive)로 시작하는 automation_execution 테이블 이름을
     * 찾는다. pgmq 는 큐 생성 시 넘긴 이름 앞에 다시 `q_`/`a_` 를 붙여 실제 테이블을 만들기 때문에,
     * 이미 `q_` 로 시작하는 우리 큐 이름(`q_automation_execution`)은 실제로 `pgmq.q_q_automation_execution`
     * (이중 접두사)이 된다 — 하드코딩 대신 동적 조회로 이 비직관적 명명 규칙에서 자유로워진다
     * ([SlackDeliveryWorkerIntegrationTest] 의 `pgmqTable` 헬퍼 동형).
     */
    private fun pgmqTable(prefix: String): String =
        jdbcTemplate.queryForObject(
            "SELECT table_name FROM information_schema.tables" +
                " WHERE table_schema = 'pgmq' AND table_name LIKE ? ESCAPE '!'",
            String::class.java,
            "$prefix!_%automation_execution",
        ) ?: error("pgmq $prefix table for automation_execution not found")

    private fun failingExecutor(): ActionExecutor =
        mockk<ActionExecutor>().also {
            every { it.execute(any(), any(), any()) } throws IllegalStateException("boom")
        }

    /** [RuleExecutionRepository.save] 가 항상 예외를 던지는 mock — 이력 저장 fail-safe(FR-AT-05) 검증용. */
    private fun failingRuleExecutionRepository(): RuleExecutionRepository =
        mockk<RuleExecutionRepository>().also {
            every { it.save(any()) } throws IllegalStateException("history persist boom")
        }

    // ── 정상 실행 ─────────────────────────────────────────────────────────────

    @Test
    fun `정상 메시지는 ActionExecutor 로 액션을 실행하고 archive 된다`() {
        val rule = saveEnabledRule()
        enqueueExecution(rule.id, issueKey = "ATLAS-1")

        worker().pollAndProcess()

        val cmd = issueMutationPort.setFieldCalls.single()
        assertThat(cmd.issueKey).isEqualTo("ATLAS-1")
        assertThat(cmd.field).isEqualTo("priority")
        assertThat(cmd.actorUserId).isEqualTo(rule.actorUserId)
        assertThat(pendingCount()).isZero()
        assertThat(archivedCount()).isEqualTo(1)
    }

    @Test
    fun `빈 큐는 아무 것도 처리하지 않는다`() {
        worker().pollAndProcess()

        assertThat(issueMutationPort.setFieldCalls).isEmpty()
        assertThat(archivedCount()).isZero()
    }

    // ── EC7: 룰 부재/disabled ────────────────────────────────────────────────

    @Test
    fun `존재하지 않는 룰을 참조하는 메시지는 스킵되고 archive 된다`() {
        enqueueExecution(UUID.randomUUID(), issueKey = "ATLAS-2")

        worker().pollAndProcess()

        assertThat(issueMutationPort.setFieldCalls).isEmpty()
        assertThat(pendingCount()).isZero()
        assertThat(archivedCount()).isEqualTo(1)
    }

    @Test
    fun `disabled 룰을 참조하는 메시지는 스킵되고 archive 된다`() {
        val rule = saveDisabledRule()
        enqueueExecution(rule.id, issueKey = "ATLAS-3")

        worker().pollAndProcess()

        assertThat(issueMutationPort.setFieldCalls).isEmpty()
        assertThat(pendingCount()).isZero()
        assertThat(archivedCount()).isEqualTo(1)
    }

    // ── 루프 가드 (a): 체인 깊이 ─────────────────────────────────────────────

    @Test
    fun `executionDepth 가 10 을 초과하면 실행을 중단하고 archive 된다`() {
        val rule = saveEnabledRule()
        enqueueExecution(rule.id, issueKey = "ATLAS-4", executionDepth = 11)

        worker().pollAndProcess()

        assertThat(issueMutationPort.setFieldCalls).isEmpty()
        assertThat(pendingCount()).isZero()
        assertThat(archivedCount()).isEqualTo(1)
    }

    @Test
    fun `executionDepth 가 10 이하이면 실행된다(경계값)`() {
        val rule = saveEnabledRule()
        enqueueExecution(rule.id, issueKey = "ATLAS-5", executionDepth = 10)

        worker().pollAndProcess()

        assertThat(issueMutationPort.setFieldCalls).hasSize(1)
    }

    // ── 루프 가드 (b): (ruleId, issueKey) 60초 억제 ──────────────────────────

    @Test
    fun `같은 룰-이슈 조합이 60초 이내 재실행되면 스킵되고, 60초 경과 후에는 다시 실행된다`() {
        val rule = saveEnabledRule()
        val clock = MutableClock(now)
        val w = worker(clock = clock)

        enqueueExecution(rule.id, issueKey = "ATLAS-6")
        w.pollAndProcess()
        assertThat(issueMutationPort.setFieldCalls).hasSize(1)

        // 60초 이내 재발화 — 억제(스킵)되어 액션이 추가로 실행되지 않는다.
        enqueueExecution(rule.id, issueKey = "ATLAS-6")
        w.pollAndProcess()
        assertThat(issueMutationPort.setFieldCalls).hasSize(1)
        assertThat(archivedCount()).isEqualTo(2) // 첫 실행 + 억제 스킵 모두 archive

        // 60초 경과 후 재발화 — 억제가 풀려 다시 실행된다.
        clock.advance(Duration.ofSeconds(61))
        enqueueExecution(rule.id, issueKey = "ATLAS-6")
        w.pollAndProcess()
        assertThat(issueMutationPort.setFieldCalls).hasSize(2)
        assertThat(archivedCount()).isEqualTo(3)
    }

    @Test
    fun `다른 이슈에는 60초 억제가 적용되지 않는다`() {
        val rule = saveEnabledRule()
        val w = worker()

        enqueueExecution(rule.id, issueKey = "ATLAS-7")
        w.pollAndProcess()
        enqueueExecution(rule.id, issueKey = "ATLAS-8")
        w.pollAndProcess()

        assertThat(issueMutationPort.setFieldCalls).hasSize(2)
    }

    // ── pgmq 생명주기: 처리 실패 → 무한 재시도 방지 ──────────────────────────

    @Test
    fun `처리 중 예외가 나면 read_ct 가 MAX_RECEIVE_COUNT 를 넘기 전까지는 재시도 대기하며 archive 되지 않는다`() {
        val rule = saveEnabledRule()
        enqueueExecution(rule.id, issueKey = "ATLAS-9")

        worker(executor = failingExecutor()).pollAndProcess()

        assertThat(pendingCount()).isEqualTo(1) // vt 만료 후 재전달 대기, 아직 dead-letter 아님
        assertThat(archivedCount()).isZero()
    }

    @Test
    fun `처리 중 예외가 반복돼 read_ct 가 MAX_RECEIVE_COUNT 를 초과하면 archive 로 수렴한다(무한 재시도 방지)`() {
        val rule = saveEnabledRule()
        enqueueExecution(rule.id, issueKey = "ATLAS-10")
        forceReadCount(MAX_RECEIVE_COUNT_FIXTURE) // AutomationExecutionWorker.MAX_RECEIVE_COUNT(5) 초과 시뮬레이션

        worker(executor = failingExecutor()).pollAndProcess()

        assertThat(pendingCount()).isZero()
        assertThat(archivedCount()).isEqualTo(1)
    }

    // ── FR-AT-05: 실행 이력 영속화 ─────────────────────────────────────────────

    @Test
    fun `정상 실행은 rule_executions 에 SUCCESS 이력을 남긴다`() {
        val rule = saveEnabledRule()
        enqueueExecution(rule.id, issueKey = "ATLAS-11")

        worker().pollAndProcess()

        val history =
            ruleExecutionRepository.findByRule(rule.projectKey, rule.id, issueKey = null, limit = 10, before = null)
        val execution = history.single()
        assertThat(execution.status).isEqualTo(ActionExecutionStatus.SUCCESS)
        assertThat(execution.outcomes).hasSize(1)
        assertThat(execution.outcomes.single().success).isTrue()
        assertThat(execution.projectKey).isEqualTo(rule.projectKey)
        assertThat(execution.triggerType).isEqualTo(TriggerType.ISSUE_CREATED)
        assertThat(execution.issueKey).isEqualTo("ATLAS-11")
        assertThat(execution.startedAt).isBeforeOrEqualTo(execution.finishedAt)
    }

    @Test
    fun `조건 불충족으로 SKIPPED 되면 outcomes 가 빈 상태로 이력이 남는다`() {
        val rule = saveEnabledRule()
        conditionRepository.replace(
            rule.id,
            Condition.Comparison(field = "issue.key", operator = ComparisonOperator.EXISTS, value = NullNode.instance),
        )
        // triggerEvent 에 issueKey 가 없으면(SCHEDULED/WEBHOOK 류) 조건이 있을 때 게이트가 불충족으로
        // 판정해 SKIPPED 된다(ActionExecutor 클래스 KDoc "조건 게이트" 참조).
        enqueueExecution(rule.id, issueKey = null)

        worker().pollAndProcess()

        val history =
            ruleExecutionRepository.findByRule(rule.projectKey, rule.id, issueKey = null, limit = 10, before = null)
        val execution = history.single()
        assertThat(execution.status).isEqualTo(ActionExecutionStatus.SKIPPED)
        assertThat(execution.outcomes).isEmpty()
        assertThat(execution.issueKey).isNull()
    }

    @Test
    fun `억제창 스킵은 이력을 남기지 않는다`() {
        val rule = saveEnabledRule()
        val w = worker()

        enqueueExecution(rule.id, issueKey = "ATLAS-12")
        w.pollAndProcess()
        // 60초 이내 재발화 — 억제(스킵)돼 ActionExecutor 가 호출되지 않으므로 이력도 남지 않는다.
        enqueueExecution(rule.id, issueKey = "ATLAS-12")
        w.pollAndProcess()

        val history =
            ruleExecutionRepository.findByRule(
                rule.projectKey,
                rule.id,
                issueKey = "ATLAS-12",
                limit = 10,
                before = null,
            )
        assertThat(history).hasSize(1) // 최초 실행분만 기록, 억제 스킵은 미기록
    }

    @Test
    fun `존재하지 않는 룰·disabled 룰·malformed 메시지는 이력을 남기지 않는다`() {
        val disabledRule = saveDisabledRule()
        enqueueExecution(UUID.randomUUID(), issueKey = "ATLAS-13") // 존재하지 않는 룰(EC7)
        enqueueExecution(disabledRule.id, issueKey = "ATLAS-14") // disabled 룰(EC7)
        // ruleId 필드 없음 → UUID.fromString 실패(malformed)
        jdbcTemplate.queryForObject(
            "SELECT pgmq.send(?, ?::jsonb)",
            Long::class.java,
            "q_automation_execution",
            """{"triggerEvent":{}}""",
        )

        worker().pollAndProcess()

        val count = jdbcTemplate.queryForObject("SELECT count(*) FROM rule_executions", Int::class.java)
        assertThat(count).isZero()
    }

    @Test
    fun `이력 저장이 실패해도 archive 는 정상 진행된다(fail-safe)`() {
        val rule = saveEnabledRule()
        enqueueExecution(rule.id, issueKey = "ATLAS-15")

        worker(executionRepository = failingRuleExecutionRepository()).pollAndProcess()

        assertThat(issueMutationPort.setFieldCalls).hasSize(1) // 액션 실행 자체는 정상
        assertThat(pendingCount()).isZero()
        assertThat(archivedCount()).isEqualTo(1)
    }

    // ── 웹훅 규칙이 여러 이슈에 걸린다 (BTS-F2-01) ─────────────────────────────

    private fun saveWebhookRule(
        triggerConfig: String,
        actions: List<Action> = listOf(Action.TransitionAction("deploy_wait")),
    ): AutomationRule {
        val rule =
            AutomationRule.create(
                projectKey = "ATLAS",
                name = "웹훅 룰",
                triggerType = TriggerType.WEBHOOK,
                triggerConfig = triggerConfig,
                createdBy = UUID.randomUUID(),
                webhookTokenHash = UUID.randomUUID().toString().replace("-", ""),
                actions = actions,
                now = now,
            )
        ruleRepository.save(rule)
        return rule
    }

    private fun enqueueWebhook(
        ruleId: UUID,
        body: String,
    ) = enqueuer.enqueue(ruleId, TriggerType.WEBHOOK, objectMapper.readTree(body))

    private fun executionsOf(rule: AutomationRule) =
        ruleExecutionRepository.findByRule("ATLAS", rule.id, issueKey = null, limit = 10, before = null)

    /** 큐가 빌 때까지 돌린다 — 첫 번은 대상 고르기, 다음은 이슈마다 실행. */
    private fun drain(worker: AutomationExecutionWorker = worker()) {
        repeat(DRAIN_ROUNDS) { worker.pollAndProcess() }
    }

    @Test
    fun `keysField 규칙은 몸의 키마다 한 번 돈다 - 겹친 키는 한 번 · 다른 프로젝트 키는 막힌다`() {
        val rule = saveWebhookRule("""{"keysField":"rollbackKeys"}""")
        enqueueWebhook(
            rule.id,
            """{"build":"b1","rollback":true,"rollbackKeys":["ATLAS-1","ATLAS-2","OTHER-9","ATLAS-1"]}""",
        )

        drain()

        assertThat(issueMutationPort.transitionCalls.map { it.issueKey to it.toStateKey })
            .containsExactlyInAnyOrder("ATLAS-1" to "deploy_wait", "ATLAS-2" to "deploy_wait")
        val executions = executionsOf(rule)
        assertThat(executions.map { it.issueKey to it.status }).containsExactlyInAnyOrder(
            "ATLAS-1" to ActionExecutionStatus.SUCCESS,
            "ATLAS-2" to ActionExecutionStatus.SUCCESS,
            "OTHER-9" to ActionExecutionStatus.SKIPPED,
        )
        assertThat(pendingCount()).isZero()
    }

    @Test
    fun `keysField 키가 200개를 넘으면 하나도 안 바꾸고 상한 넘음 이력 한 건을 남긴다`() {
        val rule = saveWebhookRule("""{"keysField":"rollbackKeys"}""")
        val keys = (1..201).joinToString(",") { "\"ATLAS-$it\"" }
        enqueueWebhook(rule.id, """{"rollbackKeys":[$keys]}""")

        drain()

        assertThat(issueMutationPort.transitionCalls).isEmpty()
        val executions = executionsOf(rule)
        assertThat(executions).hasSize(1)
        assertThat(executions.single().status).isEqualTo(ActionExecutionStatus.FAILED)
        assertThat(executions.single().outcomes.map { it.error }).containsExactly("TARGET_LIMIT_EXCEEDED")
        assertThat(pendingCount()).isZero()
    }

    @Test
    fun `keysField 키가 정확히 200개면 모두 돈다`() {
        val rule = saveWebhookRule("""{"keysField":"rollbackKeys"}""")
        val keys = (1..200).joinToString(",") { "\"ATLAS-$it\"" }
        enqueueWebhook(rule.id, """{"rollbackKeys":[$keys]}""")

        drain()

        assertThat(issueMutationPort.transitionCalls).hasSize(200)
    }

    @Test
    fun `targetStates 규칙은 그 상태 이슈 전부에 돌고 댓글에 웹훅 몸의 build 를 넣는다`() {
        val rule =
            saveWebhookRule(
                """{"targetStates":["deploy_wait"],"skipWhenTrue":"rollback"}""",
                actions =
                    listOf(
                        Action.TransitionAction("qa_request"),
                        Action.AddCommentAction("QA 서버 {{ webhook.build }} 배포됨"),
                    ),
            )
        issueSearchPort.keys = listOf("ATLAS-3", "ATLAS-4")
        enqueueWebhook(rule.id, """{"build":"a1b2c3d-202611011200"}""")

        drain()

        val query = issueSearchPort.queries.single()
        assertThat(query.projectKey).isEqualTo("ATLAS")
        assertThat(query.viewerUserId).isEqualTo(rule.createdBy)
        assertThat(query.size).isEqualTo(200)
        assertThat(issueMutationPort.transitionCalls.map { it.issueKey to it.toStateKey })
            .containsExactlyInAnyOrder("ATLAS-3" to "qa_request", "ATLAS-4" to "qa_request")
        assertThat(issueMutationPort.addCommentCalls.map { it.issueKey to it.body }).containsExactlyInAnyOrder(
            "ATLAS-3" to "QA 서버 a1b2c3d-202611011200 배포됨",
            "ATLAS-4" to "QA 서버 a1b2c3d-202611011200 배포됨",
        )
    }

    @Test
    fun `targetStates 검색 총수가 200을 넘으면 하나도 안 바꾼다`() {
        val rule = saveWebhookRule("""{"targetStates":["deploy_wait"]}""")
        issueSearchPort.keys = listOf("ATLAS-3")
        issueSearchPort.total = 201
        enqueueWebhook(rule.id, """{"build":"b"}""")

        drain()

        assertThat(issueMutationPort.transitionCalls).isEmpty()
        val executions = executionsOf(rule)
        assertThat(executions.single().outcomes.map { it.error }).containsExactly("TARGET_LIMIT_EXCEEDED")
    }

    @Test
    fun `skipWhenTrue 칸이 true 인 몸이면 규칙이 돌지 않는다 - 롤백 배포에서 QA 요청 규칙`() {
        val rule = saveWebhookRule("""{"targetStates":["deploy_wait"],"skipWhenTrue":"rollback"}""")
        issueSearchPort.keys = listOf("ATLAS-3")
        enqueueWebhook(rule.id, """{"build":"b","rollback":true,"rollbackKeys":["ATLAS-3"]}""")

        drain()

        assertThat(issueSearchPort.queries).isEmpty()
        assertThat(issueMutationPort.transitionCalls).isEmpty()
        assertThat(executionsOf(rule)).isEmpty()
        assertThat(pendingCount()).isZero()
    }

    @Test
    fun `대상 칸이 없는 웹훅 규칙은 지금처럼 이슈 없이 한 번 돈다`() {
        val rule = saveWebhookRule("{}")
        enqueueWebhook(rule.id, """{"build":"b"}""")

        drain()

        val executions = executionsOf(rule)
        assertThat(executions.map { it.issueKey to it.outcomes.single().error })
            .containsExactly(null to "ISSUE_KEY_MISSING")
    }

    private companion object {
        /** 대상 고르기 1회 + 200건(배치 5)을 비우기에 넉넉한 횟수. */
        const val DRAIN_ROUNDS = 45

        /** [AutomationExecutionWorker.MAX_RECEIVE_COUNT] 미러(private 상수라 테스트에서 직접 참조 불가). */
        const val MAX_RECEIVE_COUNT_FIXTURE = 5
    }

    /** 테스트에서 시각을 [advance] 로 전진시킬 수 있는 가변 [Clock](WebhookCircuitBreakerTest 동형). */
    private class MutableClock(private var current: Instant) : Clock() {
        override fun instant(): Instant = current

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        fun advance(by: Duration) {
            current = current.plus(by)
        }
    }
}
