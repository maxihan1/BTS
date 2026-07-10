// AutomationActionRepository 통합 테스트 — replaceForRule/findByRuleId position 순·actor_user_id 저장·조회 (FR-AT-02 Task 6)

package com.bts.automation.adapter

import com.bts.automation.AutomationTestBootApplication
import com.bts.automation.AutomationTestcontainersBase
import com.bts.automation.StubAutomationPermissionResolver
import com.bts.automation.domain.Action
import com.bts.automation.domain.AutomationRule
import com.bts.automation.domain.TriggerType
import com.bts.shared.permission.AutomationPermissionResolver
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
 * [AutomationActionRepository] 통합 테스트 (FR-AT-02 Task 6).
 *
 * [AutomationTestBootApplication] 컴포넌트 스캔으로 실 [AutomationActionRepository]/[AutomationRuleRepository]
 * 를 로드하고, [AutomationTestcontainersBase] 가 배선한 Testcontainers DataSource 위에서 실제 SQL 을
 * 검증한다([[new-bc-first-repository-testboot-context-regression]] 선례 동형).
 *
 * `automation_actions.rule_id` 는 `automation_rules.id` 를 참조하는 FK 라 액션은 항상 먼저 저장된
 * 룰(parent row)에 대해서만 삽입 가능하다 — 이 테스트의 모든 케이스가 [ruleRepository] 로 룰을 먼저
 * 저장한 뒤 그 id 로 액션을 조작한다.
 *
 * ## 검증 시나리오
 * - replaceForRule → findByRuleId 가 position 순으로 그대로 복원(4종 액션 round-trip)
 * - replaceForRule 재호출은 기존 액션을 전량 교체(부분 유지 아님)
 * - 빈 리스트로 replaceForRule 하면 기존 액션이 전량 삭제됨
 * - findByRuleId — 액션이 없는 룰은 빈 리스트
 * - AutomationRuleRepository.save 가 룰+액션을 원자적으로 저장(같은 트랜잭션)
 * - AutomationRuleRepository.findById 의 actions 는 항상 빈 리스트(자체 로드 안 함, KDoc 계약)
 * - actor_user_id — save/findById 로 actorUserId 가 저장·조회됨(createdBy 와 다른 값 포함)
 */
@SpringBootTest(
    classes = [AutomationTestBootApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
)
@Import(
    AutomationTestcontainersBase::class,
    AutomationActionRepositoryTest.PermissionResolverStubConfig::class,
)
class AutomationActionRepositoryTest {
    /** 컨텍스트 로드용 [AutomationPermissionResolver] 스텁 등록([AutomationRuleRepositoryTest] 동형). */
    @TestConfiguration
    class PermissionResolverStubConfig {
        @Bean
        fun automationPermissionResolver(): AutomationPermissionResolver = StubAutomationPermissionResolver()
    }

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var actionRepository: AutomationActionRepository

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var ruleRepository: AutomationRuleRepository

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var jdbcTemplate: JdbcTemplate

    private val now: Instant = Instant.parse("2026-07-11T00:00:00Z")

    @BeforeEach
    fun cleanUp() {
        // automation_actions 는 automation_rules FK ON DELETE CASCADE 로 함께 정리된다(V302).
        jdbcTemplate.update("DELETE FROM automation_rules")
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private fun savedRule(actorUserId: UUID? = null): AutomationRule {
        val createdBy = UUID.randomUUID()
        val rule =
            AutomationRule.create(
                projectKey = "ATLAS",
                name = "자동화 룰",
                triggerType = TriggerType.ISSUE_CREATED,
                createdBy = createdBy,
                actorUserId = actorUserId ?: createdBy,
                now = now,
            )
        ruleRepository.save(rule)
        return rule
    }

    private fun fourActionsOneOfEach(): List<Action> =
        listOf(
            Action.SetFieldAction(field = "priority", value = TextNode("High")),
            Action.AssignAction(assigneeId = UUID.randomUUID()),
            Action.AssignAction(assigneeId = null),
            Action.AddCommentAction(body = "자동 코멘트 {{issue.key}}"),
            Action.CallWebhookAction(
                url = "https://example.com/hook",
                method = "PUT",
                headers = mapOf("X-Token" to "secret"),
                body = """{"ok":true}""",
            ),
        )

    // ── replaceForRule / findByRuleId ────────────────────────────────────────────

    @Test
    fun `replaceForRule — 4종 액션을 position 순으로 저장하고 findByRuleId 로 그대로 복원된다`() {
        val rule = savedRule()
        val actions = fourActionsOneOfEach()

        actionRepository.replaceForRule(rule.id, actions)
        val found = actionRepository.findByRuleId(rule.id)

        assertThat(found).containsExactlyElementsOf(actions)
    }

    @Test
    fun `replaceForRule — 재호출 시 기존 액션을 전량 교체한다`() {
        val rule = savedRule()
        actionRepository.replaceForRule(
            rule.id,
            listOf(Action.AddCommentAction(body = "첫 번째"), Action.AddCommentAction(body = "두 번째")),
        )

        actionRepository.replaceForRule(rule.id, listOf(Action.AddCommentAction(body = "교체됨")))

        val found = actionRepository.findByRuleId(rule.id)
        assertThat(found).containsExactly(Action.AddCommentAction(body = "교체됨"))
    }

    @Test
    fun `replaceForRule — 빈 리스트로 교체하면 기존 액션이 전량 삭제된다`() {
        val rule = savedRule()
        actionRepository.replaceForRule(rule.id, listOf(Action.AddCommentAction(body = "삭제될 액션")))

        actionRepository.replaceForRule(rule.id, emptyList())

        assertThat(actionRepository.findByRuleId(rule.id)).isEmpty()
    }

    @Test
    fun `findByRuleId — 액션이 없는 룰은 빈 리스트를 반환한다`() {
        val rule = savedRule()

        assertThat(actionRepository.findByRuleId(rule.id)).isEmpty()
    }

    // ── AutomationRuleRepository.save 연동 ────────────────────────────────────────

    @Test
    fun `save — 룰과 액션을 같은 트랜잭션에서 원자적으로 저장한다`() {
        val createdBy = UUID.randomUUID()
        val actions = listOf(Action.AddCommentAction(body = "생성 시 함께 저장"))
        val rule =
            AutomationRule.create(
                projectKey = "ATLAS",
                name = "액션 보유 룰",
                triggerType = TriggerType.ISSUE_CREATED,
                createdBy = createdBy,
                actions = actions,
                now = now,
            )

        ruleRepository.save(rule)

        assertThat(actionRepository.findByRuleId(rule.id)).isEqualTo(actions)
    }

    @Test
    fun `findById — actions 는 항상 빈 리스트로 매핑된다(자체 로드 안 함)`() {
        val actions = listOf(Action.AddCommentAction(body = "실행 큐 로드 아님"))
        val rule =
            AutomationRule.create(
                projectKey = "ATLAS",
                name = "룰",
                triggerType = TriggerType.ISSUE_CREATED,
                createdBy = UUID.randomUUID(),
                actions = actions,
                now = now,
            )
        ruleRepository.save(rule)

        val found = ruleRepository.findById(rule.id)

        assertThat(found?.actions).isEmpty()
        // 실제 액션은 별도 로드로만 확인 가능함을 함께 단언 — findById 가 액션을 숨기는 게 아니라
        // 애초에 로드하지 않을 뿐임을 명확히 한다.
        assertThat(actionRepository.findByRuleId(rule.id)).isEqualTo(actions)
    }

    // ── actor_user_id ─────────────────────────────────────────────────────────────

    @Test
    fun `save·findById — actorUserId 가 createdBy 와 달라도 그대로 저장·조회된다`() {
        val actor = UUID.randomUUID()

        val rule = savedRule(actorUserId = actor)
        val found = ruleRepository.findById(rule.id)

        assertThat(found?.actorUserId).isEqualTo(actor)
    }

    @Test
    fun `save·findById — actorUserId 미지정 시 createdBy 로 저장·조회된다`() {
        val rule = savedRule()
        val found = ruleRepository.findById(rule.id)

        assertThat(found?.actorUserId).isEqualTo(rule.createdBy)
    }
}
