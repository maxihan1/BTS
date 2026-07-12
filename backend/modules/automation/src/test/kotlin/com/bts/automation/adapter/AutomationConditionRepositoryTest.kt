// AutomationConditionRepository 통합 테스트 — 조건 트리 upsert/delete/조회 round-trip·룰 삭제 CASCADE (FR-AT-03 Task 4)

package com.bts.automation.adapter

import com.bts.automation.AutomationTestBootApplication
import com.bts.automation.AutomationTestcontainersBase
import com.bts.automation.StubAutomationPermissionResolver
import com.bts.automation.StubIssueMutationPort
import com.bts.automation.domain.AutomationRule
import com.bts.automation.domain.ComparisonOperator
import com.bts.automation.domain.Condition
import com.bts.automation.domain.TriggerType
import com.bts.shared.issue.IssueMutationPort
import com.bts.shared.permission.AutomationPermissionResolver
import com.fasterxml.jackson.databind.node.IntNode
import com.fasterxml.jackson.databind.node.TextNode
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
 * [AutomationConditionRepository] 통합 테스트 (FR-AT-03 Task 4).
 *
 * [AutomationTestBootApplication] 컴포넌트 스캔으로 실 [AutomationConditionRepository]/[AutomationRuleRepository]
 * 를 로드하고, [AutomationTestcontainersBase] 가 배선한 Testcontainers DataSource 위에서 실제 SQL 을
 * 검증한다([AutomationActionRepositoryTest] 선례 동형).
 *
 * `automation_conditions.rule_id` 는 `automation_rules.id` 를 참조하는 FK(겸 PK — 룰당 0..1 행)라
 * 조건은 항상 먼저 저장된 룰(parent row)에 대해서만 삽입 가능하다 — 모든 케이스가 [ruleRepository] 로
 * 룰을 먼저 저장한 뒤 그 id 로 조건을 조작한다.
 *
 * ## 검증 시나리오
 * - replace(신규) → findByRuleId 가 조건 트리(and/or/comparison)를 그대로 복원(round-trip)
 * - replace 재호출은 기존 조건을 새 조건으로 교체(upsert UPDATE 경로)
 * - replace(null) 은 기존 조건을 삭제 → findByRuleId 는 null
 * - findByRuleId — 조건이 없는 룰은 null
 * - 룰 삭제 시 automation_conditions 는 ON DELETE CASCADE 로 함께 삭제(고아 방지)
 */
@SpringBootTest(
    classes = [AutomationTestBootApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
)
@Import(
    AutomationTestcontainersBase::class,
    AutomationConditionRepositoryTest.PermissionResolverStubConfig::class,
)
class AutomationConditionRepositoryTest {
    /**
     * 컨텍스트 로드용 [AutomationPermissionResolver]/[IssueMutationPort] 스텁 등록
     * ([AutomationActionRepositoryTest] 동형).
     */
    @TestConfiguration
    class PermissionResolverStubConfig {
        @Bean
        fun automationPermissionResolver(): AutomationPermissionResolver = StubAutomationPermissionResolver()

        @Bean
        fun issueMutationPort(): IssueMutationPort = StubIssueMutationPort()
    }

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var conditionRepository: AutomationConditionRepository

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var ruleRepository: AutomationRuleRepository

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var jdbcTemplate: JdbcTemplate

    private val now: Instant = Instant.parse("2026-07-12T00:00:00Z")

    @BeforeEach
    fun cleanUp() {
        // automation_conditions 는 automation_rules FK ON DELETE CASCADE 로 함께 정리된다(V304).
        jdbcTemplate.update("DELETE FROM automation_rules")
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private fun savedRule(): AutomationRule {
        val createdBy = UUID.randomUUID()
        val rule =
            AutomationRule.create(
                projectKey = "ATLAS",
                name = "조건 보유 룰",
                triggerType = TriggerType.ISSUE_CREATED,
                createdBy = createdBy,
                actorUserId = createdBy,
                now = now,
            )
        ruleRepository.save(rule)
        return rule
    }

    // and/or/comparison 을 모두 포함하는 조건 트리(round-trip 커버리지 극대화).
    private fun sampleCondition(): Condition =
        Condition.And(
            listOf(
                Condition.Comparison("issue.priority", ComparisonOperator.EQUALS, TextNode("High")),
                Condition.Or(
                    listOf(
                        Condition.Comparison("issue.status", ComparisonOperator.NOT_EQUALS, TextNode("Done")),
                        Condition.Comparison("issue.type", ComparisonOperator.GREATER_THAN, IntNode(3)),
                    ),
                ),
            ),
        )

    private fun countConditionsForRule(ruleId: UUID): Int {
        val count =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM automation_conditions WHERE rule_id = ?",
                Int::class.java,
                ruleId,
            )
        return count ?: 0
    }

    // ── replace(신규) / findByRuleId round-trip ──────────────────────────────────

    @Test
    fun `replace — 조건 트리를 저장하고 findByRuleId 로 그대로 복원된다`() {
        val rule = savedRule()
        val condition = sampleCondition()

        conditionRepository.replace(rule.id, condition)
        val found = conditionRepository.findByRuleId(rule.id)

        assertThat(found).isEqualTo(condition)
    }

    // ── replace(기존) — upsert UPDATE 경로 ───────────────────────────────────────

    @Test
    fun `replace — 재호출 시 기존 조건을 새 조건으로 교체한다`() {
        val rule = savedRule()
        conditionRepository.replace(rule.id, sampleCondition())

        val replaced = Condition.Comparison("issue.status", ComparisonOperator.EQUALS, TextNode("Done"))
        conditionRepository.replace(rule.id, replaced)

        assertThat(conditionRepository.findByRuleId(rule.id)).isEqualTo(replaced)
        // 룰당 0..1 행 — 교체 후에도 조건 행은 정확히 1개.
        assertThat(countConditionsForRule(rule.id)).isEqualTo(1)
    }

    // ── replace(null) — 삭제 경로 ────────────────────────────────────────────────

    @Test
    fun `replace — null 이면 기존 조건을 삭제한다`() {
        val rule = savedRule()
        conditionRepository.replace(rule.id, sampleCondition())

        conditionRepository.replace(rule.id, null)

        assertThat(conditionRepository.findByRuleId(rule.id)).isNull()
        assertThat(countConditionsForRule(rule.id)).isEqualTo(0)
    }

    @Test
    fun `replace — 조건이 없는 룰에 null 을 replace 해도 안전하다(멱등)`() {
        val rule = savedRule()

        conditionRepository.replace(rule.id, null)

        assertThat(conditionRepository.findByRuleId(rule.id)).isNull()
    }

    // ── findByRuleId — 미존재 ────────────────────────────────────────────────────

    @Test
    fun `findByRuleId — 조건이 없는 룰은 null 을 반환한다`() {
        val rule = savedRule()

        assertThat(conditionRepository.findByRuleId(rule.id)).isNull()
    }

    // ── 룰 삭제 시 ON DELETE CASCADE ─────────────────────────────────────────────

    @Test
    fun `룰 삭제 시 automation_conditions 는 ON DELETE CASCADE 로 함께 삭제된다`() {
        val rule = savedRule()
        conditionRepository.replace(rule.id, sampleCondition())
        assertThat(countConditionsForRule(rule.id)).isEqualTo(1)

        jdbcTemplate.update("DELETE FROM automation_rules WHERE id = ?", rule.id)

        assertThat(countConditionsForRule(rule.id)).isEqualTo(0)
    }
}
