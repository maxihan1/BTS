// AutomationRuleRepository + AutomationExecutionEnqueuer 통합 테스트 — CRUD/조회/OCC/no-bump/소프트삭제/큐 도달 (FR-AT-01 Task 4)

package com.bts.automation.adapter

import com.bts.automation.AutomationTestBootApplication
import com.bts.automation.AutomationTestcontainersBase
import com.bts.automation.StubAutomationPermissionResolver
import com.bts.automation.StubIssueMutationPort
import com.bts.automation.StubIssueSnapshotPort
import com.bts.automation.domain.AutomationRule
import com.bts.automation.domain.TriggerConfig
import com.bts.automation.domain.TriggerType
import com.bts.shared.issue.IssueMutationPort
import com.bts.shared.issue.IssueSnapshotPort
import com.bts.shared.permission.AutomationPermissionResolver
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.util.UUID

/**
 * [AutomationRuleRepository] + [AutomationExecutionEnqueuer] 통합 테스트 (FR-AT-01 Task 4).
 *
 * automation 모듈의 첫 `@Repository`/`@Component` 를 [AutomationTestBootApplication] 컴포넌트 스캔으로
 * 로드하고, [AutomationTestcontainersBase] 가 배선한 Testcontainers DataSource/JdbcTemplate 위에서
 * 실제 SQL·pgmq 를 검증한다([[new-bc-first-repository-testboot-context-regression]] — 부트스트랩부터
 * DataSource 를 배선해 두어 첫 `@Repository` 도입 시 `NoSuchBeanDefinitionException` 회귀를 예방한다).
 *
 * ## 검증 시나리오
 * - save → findById 복원 / findById 미존재 null / 소프트 삭제 룰 findById 제외
 * - findByProject 삭제·타 프로젝트 제외
 * - update OCC 버전 bump + 버전 불일치 시 [OptimisticLockingFailureException]
 * - softDelete deleted_at 설정 후 findById 제외
 * - findEnabledByProjectAndTriggerType — enabled·프로젝트·트리거타입 매칭만(비활성/삭제/타입/타프로젝트 제외)
 * - findByWebhookTokenHash — enabled·미삭제 매칭(비활성/삭제 제외) / 미존재 null
 * - findScheduledDue(now) — SCHEDULED·enabled·next_fire_at <= now 만(미래·비활성·비스케줄 제외)
 * - updateNextFireAt — no-bump(next_fire_at 만 갱신, version 미변경 — [[no-bump-sidecar-version-double-bump]])
 * - enqueue — q_automation_execution 큐에 { ruleId, triggerType, triggerEvent } 도달
 */
@SpringBootTest(
    classes = [AutomationTestBootApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
)
@Import(
    AutomationTestcontainersBase::class,
    AutomationRuleRepositoryTest.PermissionResolverStubConfig::class,
)
class AutomationRuleRepositoryTest {
    /**
     * `com.bts.automation` 전체 스캔 시 함께 로드되는 `AutomationRuleService`(Task 6)가 non-null 로
     * 요구하는 [AutomationPermissionResolver] 포트를 test-boot용 stub 으로 등록한다(prod 구현은
     * identity-access 라 automation 클래스패스에 없음 — BC 격리, consumer-owns-stub, plan-eng-review E4).
     * 이 Repository 테스트 자체는 권한 판정을 쓰지 않지만 컨텍스트 로드를 위해 필요하다.
     *
     * `ActionExecutor`(FR-AT-02 Task 9)가 non-null 로 요구하는 [IssueMutationPort] 도 동일 사유로
     * 대신 등록한다(prod 구현은 issue-tracking `@Profile("prod")` 어댑터 — automation 클래스패스에 없음).
     *
     * `ActionExecutor`(FR-AT-03 Task 7)가 non-null 로 요구하는 [IssueSnapshotPort] 도 동일 사유로
     * [StubIssueSnapshotPort] 를 대신 등록한다.
     */
    @TestConfiguration
    class PermissionResolverStubConfig {
        @Bean
        fun automationPermissionResolver(): AutomationPermissionResolver = StubAutomationPermissionResolver()

        @Bean
        fun issueMutationPort(): IssueMutationPort = StubIssueMutationPort()

        @Bean
        fun issueSnapshotPort(): IssueSnapshotPort = StubIssueSnapshotPort()
    }

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var repository: AutomationRuleRepository

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var enqueuer: AutomationExecutionEnqueuer

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var objectMapper: ObjectMapper

    /** 결정적 테스트를 위한 고정 기준 시각(Clock 주입 대신 헬퍼 인자로 전달). */
    private val now: Instant = Instant.parse("2026-07-10T00:00:00Z")

    @BeforeEach
    fun cleanUp() {
        // 테스트 격리 — 각 케이스 전에 테이블/큐를 비운다(테스트 전용 전량 삭제, slack 리포지토리 테스트 동형).
        jdbcTemplate.update("DELETE FROM automation_rules")
        jdbcTemplate.execute("SELECT pgmq.purge_queue('q_automation_execution')")
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    @Suppress("LongParameterList")
    private fun newRule(
        projectKey: String = "ATLAS",
        name: String = "자동 라벨 룰",
        triggerType: TriggerType = TriggerType.ISSUE_CREATED,
        triggerConfig: String = TriggerConfig.EMPTY,
        webhookTokenHash: String? = null,
        nextFireAt: Instant? = null,
        createdAt: Instant = now,
    ): AutomationRule =
        AutomationRule.create(
            projectKey = projectKey,
            name = name,
            triggerType = triggerType,
            triggerConfig = triggerConfig,
            createdBy = UUID.randomUUID(),
            webhookTokenHash = webhookTokenHash,
            nextFireAt = nextFireAt,
            now = createdAt,
        )

    private fun scheduledRule(
        nextFireAt: Instant,
        projectKey: String = "ATLAS",
    ): AutomationRule =
        newRule(
            projectKey = projectKey,
            name = "매일 9시 리마인더",
            triggerType = TriggerType.SCHEDULED,
            // Spring CronExpression 은 6필드(초 분 시 일 월 요일). 매일 09:00:00.
            triggerConfig = """{"cron":"0 0 9 * * *"}""",
            nextFireAt = nextFireAt,
        )

    private fun webhookRule(
        tokenHash: String,
        projectKey: String = "ATLAS",
    ): AutomationRule =
        newRule(
            projectKey = projectKey,
            name = "인바운드 웹훅 룰",
            triggerType = TriggerType.WEBHOOK,
            webhookTokenHash = tokenHash,
        )

    // ── save / findById ────────────────────────────────────────────────────────

    @Test
    fun `save 후 findById 로 원본이 복원된다`() {
        val rule = newRule()

        repository.save(rule)
        val found = repository.findById(rule.id)

        assertThat(found).isEqualTo(rule)
    }

    @Test
    fun `findById — 존재하지 않는 id 는 null 을 반환한다`() {
        assertThat(repository.findById(UUID.randomUUID())).isNull()
    }

    @Test
    fun `findById — 소프트 삭제된 룰은 조회에서 제외된다`() {
        val rule = newRule()
        repository.save(rule)

        repository.softDelete(rule.id, now)

        assertThat(repository.findById(rule.id)).isNull()
    }

    // ── findByProject ────────────────────────────────────────────────────────────

    @Test
    fun `findByProject — 같은 프로젝트의 미삭제 룰만 반환한다`() {
        val a = newRule(name = "룰 A")
        val b = newRule(name = "룰 B")
        val deleted = newRule(name = "룰 C(삭제)")
        val otherProject = newRule(projectKey = "OTHER", name = "타 프로젝트 룰")
        listOf(a, b, deleted, otherProject).forEach(repository::save)
        repository.softDelete(deleted.id, now)

        val found = repository.findByProject("ATLAS")

        assertThat(found.map { it.id }).containsExactlyInAnyOrder(a.id, b.id)
    }

    // ── update (OCC) ─────────────────────────────────────────────────────────────

    @Test
    fun `update — 필드 갱신과 함께 version 이 bump 된다`() {
        val rule = newRule(name = "원래 이름")
        repository.save(rule)

        val renamed = rule.rename("바뀐 이름", now)
        repository.update(renamed)

        val found = repository.findById(rule.id)
        assertThat(found?.name).isEqualTo("바뀐 이름")
        assertThat(found?.version).isEqualTo(1L)
    }

    @Test
    fun `update — 버전이 불일치하면 OptimisticLockingFailureException 을 던진다`() {
        val rule = newRule(name = "원래 이름")
        repository.save(rule)

        // 두 사용자가 같은 version 0 사본을 각자 로드한 상황을 재현한다.
        val first = rule.rename("첫 번째 갱신", now)
        val second = rule.rename("두 번째 갱신", now)

        repository.update(first) // DB version 0 → 1 (성공)

        assertThatThrownBy { repository.update(second) } // 기대 version 0 이나 DB 는 1 → 충돌
            .isInstanceOf(OptimisticLockingFailureException::class.java)
    }

    // ── softDelete ───────────────────────────────────────────────────────────────

    @Test
    fun `softDelete — deleted_at 이 설정되고 이후 조회에서 제외된다`() {
        val rule = newRule()
        repository.save(rule)

        repository.softDelete(rule.id, now)

        assertThat(repository.findById(rule.id)).isNull()
        assertThat(repository.findByProject("ATLAS")).isEmpty()
    }

    // ── findEnabledByProjectAndTriggerType (이벤트 매칭) ──────────────────────────

    @Test
    fun `findEnabledByProjectAndTriggerType — enabled·프로젝트·트리거타입 일치 룰만 반환한다`() {
        val match = newRule(name = "매칭 대상", triggerType = TriggerType.ISSUE_CREATED)
        val disabled = newRule(name = "비활성", triggerType = TriggerType.ISSUE_CREATED)
        val otherType = newRule(name = "타 트리거", triggerType = TriggerType.ISSUE_UPDATED)
        val otherProject =
            newRule(projectKey = "OTHER", name = "타 프로젝트", triggerType = TriggerType.ISSUE_CREATED)
        val deleted = newRule(name = "삭제됨", triggerType = TriggerType.ISSUE_CREATED)
        listOf(match, disabled, otherType, otherProject, deleted).forEach(repository::save)
        repository.update(disabled.disable(now))
        repository.softDelete(deleted.id, now)

        val found = repository.findEnabledByProjectAndTriggerType("ATLAS", TriggerType.ISSUE_CREATED)

        assertThat(found.map { it.id }).containsExactly(match.id)
    }

    // ── findByWebhookTokenHash ────────────────────────────────────────────────────

    @Test
    fun `findByWebhookTokenHash — enabled·미삭제 룰을 해시로 조회한다`() {
        val rule = webhookRule(tokenHash = "a".repeat(64))
        repository.save(rule)

        val found = repository.findByWebhookTokenHash("a".repeat(64))

        assertThat(found?.id).isEqualTo(rule.id)
    }

    @Test
    fun `findByWebhookTokenHash — 비활성 룰은 조회되지 않는다`() {
        val rule = webhookRule(tokenHash = "b".repeat(64))
        repository.save(rule)
        repository.update(rule.disable(now))

        assertThat(repository.findByWebhookTokenHash("b".repeat(64))).isNull()
    }

    @Test
    fun `findByWebhookTokenHash — 존재하지 않는 해시는 null 을 반환한다`() {
        assertThat(repository.findByWebhookTokenHash("c".repeat(64))).isNull()
    }

    // ── findScheduledDue ──────────────────────────────────────────────────────────

    @Test
    fun `findScheduledDue — next_fire_at 이 now 이하인 SCHEDULED·enabled 룰만 반환한다`() {
        val due = scheduledRule(nextFireAt = now.minusSeconds(60))
        val notYet = scheduledRule(nextFireAt = now.plusSeconds(3600))
        val disabledDue = scheduledRule(nextFireAt = now.minusSeconds(60))
        val nonScheduled = newRule(triggerType = TriggerType.ISSUE_CREATED)
        listOf(due, notYet, disabledDue, nonScheduled).forEach(repository::save)
        repository.update(disabledDue.disable(now))

        val found = repository.findScheduledDue(now)

        assertThat(found.map { it.id }).containsExactly(due.id)
    }

    // ── updateNextFireAt (no-bump) ────────────────────────────────────────────────

    @Test
    fun `updateNextFireAt — next_fire_at 만 갱신하고 version 은 bump 하지 않는다`() {
        val rule = scheduledRule(nextFireAt = now)
        repository.save(rule)
        val next = now.plusSeconds(86_400)

        repository.updateNextFireAt(rule.id, next)

        val found = repository.findById(rule.id)
        assertThat(found?.nextFireAt).isEqualTo(next)
        assertThat(found?.version).isEqualTo(0L)
    }

    // ── enqueue (실행 큐 도달) ─────────────────────────────────────────────────────

    @Test
    fun `enqueue — q_automation_execution 큐에 ruleId·triggerType·triggerEvent 가 적재된다`() {
        val ruleId = UUID.randomUUID()
        val triggerEvent = objectMapper.createObjectNode().put("issueKey", "ATLAS-1")

        enqueuer.enqueue(ruleId, TriggerType.ISSUE_CREATED, triggerEvent)

        val message =
            jdbcTemplate.queryForObject(
                "SELECT message::text FROM pgmq.read('q_automation_execution', 30, 10) LIMIT 1",
                String::class.java,
            )
        val node = objectMapper.readTree(message)
        assertThat(node.get("ruleId").asText()).isEqualTo(ruleId.toString())
        assertThat(node.get("triggerType").asText()).isEqualTo("ISSUE_CREATED")
        assertThat(node.get("triggerEvent").get("issueKey").asText()).isEqualTo("ATLAS-1")
    }
}
