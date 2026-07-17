// 자동화 액션 end-to-end 통합 테스트 — 실 룰 영속→enqueue→워커 폴링→ActionExecutor 디스패치 전 배선 검증 (FR-AT-02 Task 12)

package com.bts.automation

import com.bts.automation.adapter.AutomationConditionRepository
import com.bts.automation.adapter.AutomationExecutionEnqueuer
import com.bts.automation.adapter.AutomationRuleRepository
import com.bts.automation.adapter.RuleExecutionRepository
import com.bts.automation.adapter.WebhookActionClient
import com.bts.automation.adapter.WebhookCallResult
import com.bts.automation.application.ActionExecutionStatus
import com.bts.automation.application.ActionExecutor
import com.bts.automation.domain.Action
import com.bts.automation.domain.AutomationRule
import com.bts.automation.domain.ComparisonOperator
import com.bts.automation.domain.Condition
import com.bts.automation.domain.TriggerConfig
import com.bts.automation.domain.TriggerType
import com.bts.automation.worker.AutomationExecutionWorker
import com.bts.shared.issue.IssueMutationPermissionDeniedException
import com.bts.shared.issue.IssueSnapshot
import com.bts.shared.permission.AutomationPermissionResolver
import com.bts.shared.permission.IssuePermissionResolver
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.TextNode
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * FR-AT-02 자동화 액션 end-to-end 통합 테스트 (Task 12).
 *
 * 실제 룰+액션을 [AutomationRuleRepository.save] 로 DB 에 영속하고, 실 [AutomationExecutionEnqueuer]
 * (또는 [AutomationExecutionWorkerTest] 동형의 직접 pgmq.send)로 `q_automation_execution` 큐에
 * 적재한 뒤 [AutomationExecutionWorker.pollAndProcess] 를 호출해 워커→[ActionExecutor]→포트 디스패치
 * 전체 배선을 검증한다([[no-cross-bc-deployment-assembly]] — prod `IssueMutationPort` 어댑터는
 * issue-tracking 에 있고 automation 테스트 클래스패스엔 없으므로, 이슈 변경 위임은
 * [StubIssueMutationPort](consumer-owns-stub, `AutomationExecutionWorkerTest`/`ModuleBootTest` 동형)로
 * 관측한다).
 *
 * `WebhookActionClient` 는 SSRF 검증기·실 HTTP 클라이언트를 태우지 않도록 `@Primary` mockk 로
 * 컴포넌트 스캔된 실 빈을 오버라이드한다(plan 권장 — 사설 IP 회피).
 *
 * ## 검증 시나리오
 * - 4종 액션(SET_FIELD/ASSIGN/ADD_COMMENT/CALL_WEBHOOK) 을 가진 룰 발화 → 워커 폴링 →
 *   [StubIssueMutationPort] 가 actor=`rule.actorUserId` 로 setField/assign/addComment 를 수신하고,
 *   ADD_COMMENT 본문 템플릿이 치환되고, `WebhookActionClient` 가 호출됨을 검증. 메시지는 archive 된다.
 * - 루프 가드 (a) — executionDepth 가 10 을 초과하면 액션이 전혀 실행되지 않고 스킵+archive 된다.
 * - 루프 가드 (b) — 같은 (ruleId, issueKey) 조합이 60초 이내 연속 발화하면 두 번째는 억제된다.
 * - EC9 at-least-once — 워커 로컬 억제 캐시는 같은 인스턴스·60초 이내에서만 유효한 best-effort 라,
 *   별도 워커 인스턴스(예: 재시작/다중 인스턴스 배포)가 동일 메시지를 재처리하면 부작용이 중복될 수
 *   있음을 관측한다(강한 dedup 은 FR-AT-05 위임 — [AutomationExecutionWorker] 클래스 KDoc "루프 가드
 *   (b)" 참조).
 * - (FR-AT-07 PR-B) [Action.SetFixVersionsAction] — `ISSUE_UPDATED`+`fields=["status"]`+조건으로 발화하는
 *   룰이 [StubIssueMutationPort.setFixVersions] 를 호출하고 `rule_executions` 에 SUCCESS 로 기록됨(S2),
 *   설정된 목록으로 전체 교체하며 무변경 재실행도 매번 재실행됨(S3, EC14 인지), 빈 배열이면 전체
 *   해제됨(S4), 권한 거부는 [classifyPortFailure] 가 타입 있는
 *   [com.bts.shared.issue.IssueMutationPermissionDeniedException] 으로 분류해 FAILED+PERMISSION_DENIED
 *   로 기록됨(S5)을 검증한다.
 */
@SpringBootTest(
    classes = [AutomationTestBootApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
)
@Import(
    AutomationTestcontainersBase::class,
    ActionExecutionEndToEndIntegrationTest.TestSupportConfig::class,
)
class ActionExecutionEndToEndIntegrationTest {
    /**
     * 컨텍스트 로드용 스텁/mock 등록.
     *
     * [AutomationPermissionResolver]/[StubIssueMutationPort]/[StubIssueSnapshotPort](FR-AT-03 Task 7
     * 추가분)/[StubIssuePermissionResolver](FR-AT-04 Task 4 추가분)는 `AutomationExecutionWorkerTest`
     * 동형 consumer-owns-stub 패턴. `WebhookActionClient` 는
     * `@Primary` mockk 로 컴포넌트 스캔된 실 빈
     * (`com.bts.shared.http` 의 `OutboundUrlValidator`/실 `RestClient` 를 태움)을 오버라이드해 실제
     * 네트워크 호출·SSRF 검증 경로를 타지 않게 한다(빈 이름을 실 컴포넌트와 다르게 지어 중복 빈 이름
     * 충돌을 피하고, `@Primary` 로 `ActionExecutor` 주입 시점의 모호성을 해소한다).
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
        @Primary
        fun mockWebhookActionClient(): WebhookActionClient = mockk(relaxed = true)
    }

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var ruleRepository: AutomationRuleRepository

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var actionExecutor: ActionExecutor

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var automationExecutionEnqueuer: AutomationExecutionEnqueuer

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
    private lateinit var issueSnapshotPort: StubIssueSnapshotPort

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var webhookActionClient: WebhookActionClient

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var objectMapper: ObjectMapper

    /** 결정적 테스트를 위한 고정 기준 시각([AutomationExecutionWorkerTest] 동형). */
    private val now: Instant = Instant.parse("2026-07-11T00:00:00Z")

    @BeforeEach
    fun cleanUp() {
        // 테스트 격리 — 각 케이스 전에 룰/큐/archive 를 비우고 스텁·mock 을 초기화한다
        // (automation_actions 는 automation_rules FK ON DELETE CASCADE 로 함께 삭제됨).
        jdbcTemplate.update("DELETE FROM automation_rules")
        jdbcTemplate.execute("SELECT pgmq.purge_queue('q_automation_execution')")
        jdbcTemplate.update("DELETE FROM pgmq.\"${pgmqTable("a")}\"")
        issueMutationPort.reset()
        issueSnapshotPort.reset()
        clearMocks(webhookActionClient)
    }

    // ── 룰/워커/큐 헬퍼 ──────────────────────────────────────────────────────

    private fun worker(clock: Clock = Clock.fixed(now, ZoneOffset.UTC)): AutomationExecutionWorker =
        AutomationExecutionWorker(
            jdbcTemplate,
            objectMapper,
            ruleRepository,
            actionExecutor,
            ruleExecutionRepository,
            clock,
        )

    private fun saveRule(
        actions: List<Action>,
        actorUserId: UUID = UUID.randomUUID(),
        projectKey: String = "ATLAS",
        triggerType: TriggerType = TriggerType.ISSUE_CREATED,
        triggerConfig: String = TriggerConfig.EMPTY,
    ): AutomationRule {
        val rule =
            AutomationRule.create(
                projectKey = projectKey,
                name = "E2E 룰",
                triggerType = triggerType,
                triggerConfig = triggerConfig,
                createdBy = UUID.randomUUID(),
                actorUserId = actorUserId,
                actions = actions,
                now = now,
            )
        ruleRepository.save(rule)
        return rule
    }

    /**
     * `q_automation_execution` 에 `{ruleId, triggerType, triggerEvent(issueKey 포함), executionDepth?}`
     * 를 직접 적재한다([AutomationExecutionEnqueuer] 는 executionDepth 를 지원하지 않으므로 루프 가드
     * (a) 시나리오/의도적 재전달 시뮬레이션엔 직접 pgmq.send 가 필요 — `AutomationExecutionWorkerTest`
     * 의 `enqueueExecution` 헬퍼 동형).
     */
    private fun enqueueRaw(
        ruleId: UUID,
        issueKey: String,
        executionDepth: Int? = null,
    ) {
        val triggerEvent = objectMapper.createObjectNode().put("issueKey", issueKey)
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

    /** pgmq 실제 테이블 이름 동적 조회([AutomationExecutionWorkerTest.pgmqTable] 동형 — 클래스 참조. */
    private fun pgmqTable(prefix: String): String =
        jdbcTemplate.queryForObject(
            "SELECT table_name FROM information_schema.tables" +
                " WHERE table_schema = 'pgmq' AND table_name LIKE ? ESCAPE '!'",
            String::class.java,
            "$prefix!_%automation_execution",
        ) ?: error("pgmq $prefix table for automation_execution not found")

    // ── 4종 액션 end-to-end ──────────────────────────────────────────────────

    @Test
    fun `4종 액션을 가진 룰이 발화하면 워커 폴링으로 SET_FIELD-ASSIGN-ADD_COMMENT-CALL_WEBHOOK 이 모두 실행되고 archive 된다`() {
        val actorUserId = UUID.randomUUID()
        val assigneeId = UUID.randomUUID()
        val rule =
            saveRule(
                actorUserId = actorUserId,
                actions =
                    listOf(
                        Action.SetFieldAction(field = "priority", value = TextNode("High")),
                        Action.AssignAction(assigneeId = assigneeId),
                        Action.AddCommentAction(body = "이슈 {{ issue.issueKey }} 처리됨: {{ issue.summary }}"),
                        // url 은 템플릿 미포함(고정값) — Action.fromJson.validateUrlScheme 이 DB 재조회
                        // 시마다(ActionExecutor.execute → actionRepository.findByRuleId) URI 문법을
                        // 검증하는데, `{{ }}` 는 유효한 URI 문자가 아니라 파싱에 실패한다(발견한 도메인
                        // 갭 — ActionExecutorTest 의 순수 인메모리 E-e 시나리오는 fromJson 라운드트립을
                        // 타지 않아 이 문제를 드러내지 않는다). 템플릿 치환 자체는 body 로 검증한다.
                        Action.CallWebhookAction(
                            url = "https://example.com/hook",
                            method = "POST",
                            body = """{"issueKey":"{{ issue.issueKey }}"}""",
                        ),
                    ),
            )
        every { webhookActionClient.call(any(), any(), any(), any()) } returns
            WebhookCallResult(success = true, statusCode = 200, error = null)

        val triggerEvent = objectMapper.readTree("""{"issueKey":"ATLAS-1","summary":"테스트 이슈"}""")
        automationExecutionEnqueuer.enqueue(rule.id, rule.triggerType, triggerEvent)

        worker().pollAndProcess()

        val setFieldCmd = issueMutationPort.setFieldCalls.single()
        assertThat(setFieldCmd.issueKey).isEqualTo("ATLAS-1")
        assertThat(setFieldCmd.field).isEqualTo("priority")
        assertThat(setFieldCmd.actorUserId).isEqualTo(actorUserId)

        val assignCmd = issueMutationPort.assignCalls.single()
        assertThat(assignCmd.issueKey).isEqualTo("ATLAS-1")
        assertThat(assignCmd.assigneeId).isEqualTo(assigneeId)
        assertThat(assignCmd.actorUserId).isEqualTo(actorUserId)

        val commentCmd = issueMutationPort.addCommentCalls.single()
        assertThat(commentCmd.issueKey).isEqualTo("ATLAS-1")
        assertThat(commentCmd.body).isEqualTo("이슈 ATLAS-1 처리됨: 테스트 이슈")
        assertThat(commentCmd.actorUserId).isEqualTo(actorUserId)

        verify(exactly = 1) {
            webhookActionClient.call("https://example.com/hook", "POST", emptyMap(), """{"issueKey":"ATLAS-1"}""")
        }

        assertThat(pendingCount()).isZero()
        assertThat(archivedCount()).isEqualTo(1)
    }

    // ── 루프 가드 (a): 체인 깊이 ─────────────────────────────────────────────

    @Test
    fun `executionDepth 가 10 을 초과하면 액션이 전혀 실행되지 않고 스킵되어 archive 된다`() {
        val rule = saveRule(actions = listOf(Action.SetFieldAction(field = "priority", value = TextNode("High"))))
        enqueueRaw(rule.id, issueKey = "ATLAS-2", executionDepth = 11)

        worker().pollAndProcess()

        assertThat(issueMutationPort.setFieldCalls).isEmpty()
        assertThat(pendingCount()).isZero()
        assertThat(archivedCount()).isEqualTo(1)
    }

    // ── 루프 가드 (b): (ruleId, issueKey) 60초 억제 ──────────────────────────

    @Test
    fun `같은 룰-이슈 조합이 60초 이내 연속 발화하면 두 번째 실행은 억제된다`() {
        val rule = saveRule(actions = listOf(Action.SetFieldAction(field = "priority", value = TextNode("Low"))))
        val w = worker()

        enqueueRaw(rule.id, issueKey = "ATLAS-3")
        w.pollAndProcess()
        assertThat(issueMutationPort.setFieldCalls).hasSize(1)

        enqueueRaw(rule.id, issueKey = "ATLAS-3")
        w.pollAndProcess()

        assertThat(issueMutationPort.setFieldCalls).hasSize(1) // 억제 — 추가 실행 없음
        assertThat(archivedCount()).isEqualTo(2) // 첫 실행 + 억제 스킵 모두 archive
    }

    // ── EC9: at-least-once 관측 ──────────────────────────────────────────────

    @Test
    fun `EC9 at-least-once — 별도 워커 인스턴스가 동일 메시지를 재처리하면 부작용이 중복될 수 있다(강한 dedup 은 FR-AT-05 위임)`() {
        val rule = saveRule(actions = listOf(Action.AddCommentAction(body = "자동 처리됨")))

        enqueueRaw(rule.id, issueKey = "ATLAS-4")
        worker().pollAndProcess() // 워커 인스턴스 A
        assertThat(issueMutationPort.addCommentCalls).hasSize(1)

        // pgmq at-least-once 재전달(또는 producer 중복 발행)을 시뮬레이션 — 동일 룰·이슈 메시지가 다시 큐에 오른다.
        enqueueRaw(rule.id, issueKey = "ATLAS-4")
        // 별도 워커 인스턴스(신규 인스턴스 — 로컬 억제 캐시 공유 없음, 재시작/다중 인스턴스 배포 시나리오
        // 재현. AutomationExecutionWorker 클래스 KDoc "루프 가드 (b)": "워커 인스턴스 로컬 캐시라 다중
        // 인스턴스 배포에서는 인스턴스별로만 억제된다" 참조).
        worker().pollAndProcess() // 워커 인스턴스 B

        // 부작용 중복 관측 — 워커 로컬 억제 캐시는 같은 인스턴스·60초 이내에서만 유효한 best-effort 이며,
        // 강한 멱등성(정확히 한 번 실행)은 보장하지 않는다(강한 dedup 은 FR-AT-05 위임).
        assertThat(issueMutationPort.addCommentCalls).hasSize(2)
    }

    // ── SET_FIX_VERSIONS end-to-end (FR-AT-07 PR-B, S2~S5) ───────────────────

    @Test
    fun `S2 SET_FIX_VERSIONS 룰이 ISSUE_UPDATED+조건 발화 시 Fix Version 이 설정되고 SUCCESS 로 기록된다`() {
        // TriggerType.TRANSITION 은 존재하지 않는다(실재 6종. TriggerType.kt 참조) — "status 가 Done 으로
        // 전이되면"은 ISSUE_UPDATED 트리거 + triggerConfig.fields=["status"] + 조건(issue.status==Done)
        // 으로 표현한다(스펙 §S2, 개정 4회차).
        val actorUserId = UUID.randomUUID()
        val versionId = UUID.randomUUID()
        val rule =
            saveRule(
                actorUserId = actorUserId,
                actions = listOf(Action.SetFixVersionsAction(versionIds = listOf(versionId))),
                triggerType = TriggerType.ISSUE_UPDATED,
                triggerConfig = """{"fields":["status"]}""",
            )
        conditionRepository.replace(
            rule.id,
            Condition.Comparison(
                field = "issue.status",
                operator = ComparisonOperator.EQUALS,
                value = TextNode("Done"),
            ),
        )
        issueSnapshotPort.seed(
            rule.createdBy,
            "ATLAS-20",
            IssueSnapshot(
                key = "ATLAS-20",
                projectKey = "ATLAS",
                type = "Story",
                status = "Done",
                priority = null,
                assigneeId = null,
                reporterId = null,
                labels = emptyList(),
                summary = "머지 준비 완료",
            ),
        )

        val triggerEvent = objectMapper.readTree("""{"issueKey":"ATLAS-20"}""")
        automationExecutionEnqueuer.enqueue(rule.id, rule.triggerType, triggerEvent)

        worker().pollAndProcess()

        val setFixVersionsCmd = issueMutationPort.setFixVersionsCalls.single()
        assertThat(setFixVersionsCmd.issueKey).isEqualTo("ATLAS-20")
        assertThat(setFixVersionsCmd.versionIds).containsExactly(versionId)
        assertThat(setFixVersionsCmd.actorUserId).isEqualTo(actorUserId)

        val history =
            ruleExecutionRepository.findByRule(rule.projectKey, rule.id, issueKey = null, limit = 10, before = null)
        assertThat(history.single().status).isEqualTo(ActionExecutionStatus.SUCCESS)

        assertThat(pendingCount()).isZero()
        assertThat(archivedCount()).isEqualTo(1)
    }

    @Test
    fun `S3 SET_FIX_VERSIONS 액션은 설정된 목록으로 전체 교체하며 무변경 재실행도 매번 다시 실행된다`() {
        val actorUserId = UUID.randomUUID()
        // 이슈에 이미 걸려 있다고 가정하는 기존 버전("1.0.0") — automation 계층은 이 값을 전혀 모른다.
        // 전체 교체 시맨틱 자체(기존 값을 지우고 설정값만 남기는 동작)는 issue-tracking
        // IssueApplicationService.changeFixVersions → Issue.assignFixVersions 가 강제한다
        // (Task 1 AutomationIssueMutationAdapterTest·Task 6 IssueVersionLinksIntegrationTest 참조).
        // 이 테스트는 automation 계층이 그 값을 덧붙이지 않고 config 그대로("1.2.0"만)를 통째로 넘긴다는
        // 것만 확인한다.
        val oldVersionId = UUID.randomUUID()
        val newVersionId = UUID.randomUUID()
        val rule =
            saveRule(
                actorUserId = actorUserId,
                actions = listOf(Action.SetFixVersionsAction(versionIds = listOf(newVersionId))),
            )
        val triggerEvent = objectMapper.readTree("""{"issueKey":"ATLAS-21"}""")

        automationExecutionEnqueuer.enqueue(rule.id, rule.triggerType, triggerEvent)
        worker().pollAndProcess()

        val firstCmd = issueMutationPort.setFixVersionsCalls.single()
        assertThat(firstCmd.versionIds).containsExactly(newVersionId)
        assertThat(firstCmd.versionIds).doesNotContain(oldVersionId)

        // EC14 인지 — 이미 같은 값이 설정된 이슈에 룰이 다시 실행돼도(무변경) 실 issue-tracking 어댑터는
        // replaceVersionLinks 가 무조건 먼저 bump 해 version 을 올린다(무변경 단락 없음, 스펙 EC14).
        // automation 의 StubIssueMutationPort 는 고정 버전만 반환해 그 bump 자체는 관측할 수 없으므로,
        // 여기서는 "무변경 재실행이 억제되지 않고 매번 포트를 다시 호출하고 매번 rule_executions 에 새
        // SUCCESS 이력을 남긴다"는 사실만 확인한다(별도 워커 인스턴스로 60초 억제 우회 — EC9 케이스 동형).
        automationExecutionEnqueuer.enqueue(rule.id, rule.triggerType, triggerEvent)
        worker().pollAndProcess()

        assertThat(issueMutationPort.setFixVersionsCalls).hasSize(2)
        assertThat(issueMutationPort.setFixVersionsCalls.last().versionIds).containsExactly(newVersionId)

        val history =
            ruleExecutionRepository.findByRule(rule.projectKey, rule.id, issueKey = null, limit = 10, before = null)
        assertThat(history).hasSize(2)
        assertThat(history).allMatch { it.status == ActionExecutionStatus.SUCCESS }
    }

    @Test
    fun `S4 SET_FIX_VERSIONS 액션의 versionIds 가 빈 배열이면 Fix Version 이 전체 해제된다`() {
        val actorUserId = UUID.randomUUID()
        val rule =
            saveRule(
                actorUserId = actorUserId,
                actions = listOf(Action.SetFixVersionsAction(versionIds = emptyList())),
            )

        val triggerEvent = objectMapper.readTree("""{"issueKey":"ATLAS-22"}""")
        automationExecutionEnqueuer.enqueue(rule.id, rule.triggerType, triggerEvent)

        worker().pollAndProcess()

        val cmd = issueMutationPort.setFixVersionsCalls.single()
        assertThat(cmd.versionIds).isEmpty()
        assertThat(cmd.issueKey).isEqualTo("ATLAS-22")
        assertThat(cmd.actorUserId).isEqualTo(actorUserId)

        val history =
            ruleExecutionRepository.findByRule(rule.projectKey, rule.id, issueKey = null, limit = 10, before = null)
        assertThat(history.single().status).isEqualTo(ActionExecutionStatus.SUCCESS)

        assertThat(pendingCount()).isZero()
        assertThat(archivedCount()).isEqualTo(1)
    }

    @Test
    fun `S5 권한 없는 actor 의 SET_FIX_VERSIONS 액션은 rule_executions 에 FAILED-PERMISSION_DENIED 로 기록된다`() {
        val rule =
            saveRule(actions = listOf(Action.SetFixVersionsAction(versionIds = listOf(UUID.randomUUID()))))
        // classifyPortFailure(ActionExecutor.kt) 는 클래스명 문자열 매칭이 아니라 타입
        // (IssueMutationPermissionDeniedException) 으로만 권한 거부를 분류한다(FR-AT-02 C3,
        // [[crossbc-failure-classification-typed-not-name]]) — 여기서 그 타입을 그대로 주입한다.
        issueMutationPort.failNextCallsWith(IssueMutationPermissionDeniedException("이슈 변경 권한이 없습니다(테스트)"))

        val triggerEvent = objectMapper.readTree("""{"issueKey":"ATLAS-23"}""")
        automationExecutionEnqueuer.enqueue(rule.id, rule.triggerType, triggerEvent)

        worker().pollAndProcess()

        val history =
            ruleExecutionRepository.findByRule(rule.projectKey, rule.id, issueKey = null, limit = 10, before = null)
        val execution = history.single()
        assertThat(execution.status).isEqualTo(ActionExecutionStatus.FAILED)
        val outcome = execution.outcomes.single()
        assertThat(outcome.success).isFalse()
        assertThat(outcome.error).isEqualTo("PERMISSION_DENIED")

        assertThat(pendingCount()).isZero()
        assertThat(archivedCount()).isEqualTo(1)
    }
}
