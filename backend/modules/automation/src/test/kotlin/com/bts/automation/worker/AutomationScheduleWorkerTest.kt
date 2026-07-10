// AutomationScheduleWorker 통합 테스트 — SCHEDULED cron 발화·nextFireAt 갱신·중복억제·Clock 결정성 (FR-AT-01 Task 8)

package com.bts.automation.worker

import com.bts.automation.AutomationTestBootApplication
import com.bts.automation.AutomationTestcontainersBase
import com.bts.automation.StubAutomationPermissionResolver
import com.bts.automation.adapter.AutomationExecutionEnqueuer
import com.bts.automation.adapter.AutomationRuleRepository
import com.bts.automation.domain.AutomationRule
import com.bts.automation.domain.TriggerType
import com.bts.shared.permission.AutomationPermissionResolver
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * [AutomationScheduleWorker] 통합 테스트 (FR-AT-01 Task 8, 스펙 S6/EC5, ADR D4).
 *
 * [AutomationTestcontainersBase] 위에서 실제 [AutomationRuleRepository]/[AutomationExecutionEnqueuer] 를
 * 사용한다. 스케줄 결선(`@EnableScheduling`)은 Task 11 범위이므로 이 테스트는
 * [AutomationScheduleWorker.pollAndFire] 를 직접 호출한다(plan-eng-review E5).
 *
 * ## 검증 시나리오
 * - nextFireAt 경과 SCHEDULED 룰 → 발화(q_automation_execution 도달, triggerEvent 는 빈 객체 — ADR D4 §G6)
 * - 발화 후 nextFireAt 이 폴링 시각(now) 이후 최초 cron occurrence 로 갱신된다
 * - 한 폴링에 cron 주기가 여러 번 밀려 있어도 1회만 발화한다(EC5, 소급 catch-up 없음)
 * - 발화 대상이 없으면 아무 것도 하지 않는다(enqueue 없음, nextFireAt 불변)
 * - 고정 [Clock] 주입 — 실제 벽시계와 무관하게 주입된 시각 기준으로만 결정적으로 계산된다
 *   (역사적 과거 시각으로 고정해, Clock 을 무시하고 실제 시스템 시각을 쓰는 회귀를 확실히 잡는다)
 *
 * ## [TestSupportConfig] — [AutomationPermissionResolver] 스텁 빈
 * [AutomationTestBootApplication] 이 `com.bts.automation` 전체를 컴포넌트 스캔하므로, Task 6 산출물인
 * `AutomationRuleController`/`AutomationRuleService` 도 이 테스트의 컨텍스트 기동 대상에 포함된다.
 * 이 서비스는 [AutomationPermissionResolver] 를 non-null 생성자 주입으로 요구하므로([[crossbc-resolver-nullable-fail-open]]
 * 회귀 방지), 이 테스트 파일도 [AutomationRuleControllerTest] 와 동일하게 자신의 nested
 * `@TestConfiguration` 에서 [StubAutomationPermissionResolver] 를 등록한다(consumer-owns-stub, 파일
 * 범위 제약상 [AutomationTestcontainersBase] 는 수정 불가 — Task 8 파일 범위 밖).이 워커 테스트 자체는
 * 권한 판정 경로를 타지 않으므로(레포지토리/enqueuer 직접 호출) 스텁의 allow/deny 상태는 무관하다.
 */
@SpringBootTest(
    classes = [AutomationTestBootApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
)
@Import(
    AutomationTestcontainersBase::class,
    AutomationScheduleWorkerTest.TestSupportConfig::class,
)
class AutomationScheduleWorkerTest {
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

    /** 룰 생성 시각(created_at) 고정값 — cron 평가 기준 시각과는 별개. */
    private val createdAt: Instant = Instant.parse("2026-07-10T00:00:00Z")

    /** 폴링 기준 시각 — daily 09:00:00 UTC cron 이 이미 지난 시점. */
    private val now: Instant = Instant.parse("2026-07-10T10:00:00Z")

    @BeforeEach
    fun cleanUp() {
        // 테스트 격리 — 각 케이스 전에 테이블/큐를 비운다(AutomationRuleRepositoryTest 동형).
        jdbcTemplate.update("DELETE FROM automation_rules")
        jdbcTemplate.execute("SELECT pgmq.purge_queue('q_automation_execution')")
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private fun worker(fixedNow: Instant = now): AutomationScheduleWorker =
        AutomationScheduleWorker(repository, enqueuer, objectMapper, Clock.fixed(fixedNow, ZoneOffset.UTC))

    private fun scheduledRule(
        nextFireAt: Instant,
        cron: String = DAILY_9AM_CRON,
        projectKey: String = "ATLAS",
    ): AutomationRule =
        AutomationRule.create(
            projectKey = projectKey,
            name = "매일 9시 리마인더",
            triggerType = TriggerType.SCHEDULED,
            // Spring CronExpression 은 6필드(초 분 시 일 월 요일).
            triggerConfig = """{"cron":"$cron"}""",
            createdBy = UUID.randomUUID(),
            nextFireAt = nextFireAt,
            now = createdAt,
        )

    private fun queueMessages(): List<JsonNode> =
        jdbcTemplate.queryForList(
            "SELECT message::text FROM pgmq.read('q_automation_execution', 30, 10)",
            String::class.java,
        ).map(objectMapper::readTree)

    // ── nextFireAt 경과 → 발화 ────────────────────────────────────────────────

    @Test
    fun `nextFireAt 이 지난 SCHEDULED 룰은 발화되어 q_automation_execution 에 도달한다`() {
        val rule = scheduledRule(nextFireAt = now.minusSeconds(60))
        repository.save(rule)

        worker().pollAndFire()

        val messages = queueMessages()
        assertThat(messages).hasSize(1)
        val message = messages.first()
        assertThat(message.get("ruleId").asText()).isEqualTo(rule.id.toString())
        assertThat(message.get("triggerType").asText()).isEqualTo("SCHEDULED")
        assertThat(message.get("triggerEvent").isObject).isTrue()
        assertThat(message.get("triggerEvent").size()).isEqualTo(0)
    }

    // ── nextFireAt 갱신 ───────────────────────────────────────────────────────

    @Test
    fun `발화 후 nextFireAt 이 now 이후 최초 cron occurrence 로 갱신된다`() {
        val rule = scheduledRule(nextFireAt = now.minusSeconds(60))
        repository.save(rule)

        worker().pollAndFire()

        val found = repository.findById(rule.id)
        // now=2026-07-10T10:00:00Z(당일 09:00:00 UTC 는 이미 지남) → 다음 occurrence 는 다음날 09:00:00 UTC.
        assertThat(found?.nextFireAt).isEqualTo(Instant.parse("2026-07-11T09:00:00Z"))
    }

    // ── EC5: 한 폴링에 여러 주기 경과 시 1회만 발화 ──────────────────────────────

    @Test
    fun `한 폴링에 cron 주기가 여러 번 밀려 있어도 1회만 발화한다`() {
        val threeDaysBehind = now.minusSeconds(3 * 24 * 3600)
        val rule = scheduledRule(nextFireAt = threeDaysBehind)
        repository.save(rule)

        worker().pollAndFire()

        assertThat(queueMessages()).hasSize(1)
        val found = repository.findById(rule.id)
        // 밀린 3주기를 소급 발화하지 않고, now 이후 최초 occurrence 로 곧장 갱신된다.
        assertThat(found?.nextFireAt).isEqualTo(Instant.parse("2026-07-11T09:00:00Z"))
    }

    // ── 발화 대상 없음 ────────────────────────────────────────────────────────

    @Test
    fun `발화 대상이 없으면 아무 것도 하지 않는다`() {
        val notYetDue = now.plusSeconds(3600)
        val rule = scheduledRule(nextFireAt = notYetDue)
        repository.save(rule)

        worker().pollAndFire()

        assertThat(queueMessages()).isEmpty()
        assertThat(repository.findById(rule.id)?.nextFireAt).isEqualTo(notYetDue)
    }

    // ── Clock 주입 결정성 ─────────────────────────────────────────────────────

    @Test
    fun `고정 Clock 주입 — 실제 시스템 시각과 무관하게 주입된 시각 기준으로만 계산된다`() {
        // 2019년 과거 시각으로 고정 — 실제 시스템 시각(Instant.now())을 잘못 사용하면
        // 절대 이 값과 일치할 수 없으므로 Clock 주입 누락 회귀를 확실히 잡는다.
        val historicalNextFireAt = Instant.parse("2019-01-01T00:00:00Z")
        val historicalNow = Instant.parse("2019-01-01T00:15:00Z")
        val rule = scheduledRule(nextFireAt = historicalNextFireAt, cron = HOURLY_CRON)
        repository.save(rule)

        worker(fixedNow = historicalNow).pollAndFire()

        val found = repository.findById(rule.id)
        assertThat(found?.nextFireAt).isEqualTo(Instant.parse("2019-01-01T01:00:00Z"))
    }

    private companion object {
        const val DAILY_9AM_CRON = "0 0 9 * * *"
        const val HOURLY_CRON = "0 0 * * * *"
    }

    /**
     * [AutomationTestBootApplication] 전체 컴포넌트 스캔이 요구하는 [AutomationPermissionResolver] 스텁
     * 등록 전용 설정([AutomationRuleControllerTest] 동형, 클래스 KDoc 참조).
     */
    @TestConfiguration
    class TestSupportConfig {
        @Bean
        fun automationPermissionResolver(): AutomationPermissionResolver = StubAutomationPermissionResolver()
    }
}
