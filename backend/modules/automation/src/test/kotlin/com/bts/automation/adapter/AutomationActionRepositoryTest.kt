// AutomationActionRepository 통합 테스트 — replaceForRule/findByRuleId position 순·actor_user_id 저장·조회 (FR-AT-02 Task 6)

package com.bts.automation.adapter

import com.bts.automation.AutomationTestBootApplication
import com.bts.automation.AutomationTestcontainersBase
import com.bts.automation.StubAutomationPermissionResolver
import com.bts.automation.StubIssueMutationPort
import com.bts.automation.StubIssuePermissionResolver
import com.bts.automation.StubIssueSnapshotPort
import com.bts.automation.domain.Action
import com.bts.automation.domain.AutomationRule
import com.bts.automation.domain.TriggerType
import com.bts.shared.issue.IssueMutationPort
import com.bts.shared.issue.IssueSnapshotPort
import com.bts.shared.permission.AutomationPermissionResolver
import com.bts.shared.permission.IssuePermissionResolver
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
    /**
     * 컨텍스트 로드용 [AutomationPermissionResolver]/[IssueMutationPort]/[IssueSnapshotPort](FR-AT-03
     * Task 7 추가분)/[IssuePermissionResolver](FR-AT-04 Task 4 추가분) 스텁 등록
     * ([AutomationRuleRepositoryTest] 동형).
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

    /**
     * ★N+1 을 없애는 배치 조회 (FR-AT-04 성능 · 2026-09-09).
     *
     * 종전 `analyzeProjectConflicts` 는 규칙마다 [AutomationActionRepository.findByRuleId] 를 불렀다.
     * 스펙(`2026-07-13-fr-at-04-conflict-analysis.md:73`)이 「N+1은 허용하되 100규칙 1s 임계 내」라고
     * **조건부 허용**했는데, 2코어 VM 실측에서 그 조건이 깨졌다 —
     *
     *   n=100  find 19ms · hydrate(N+1) **2,331ms** · analyze(CPU) 7ms
     *   n=200  find 17ms · hydrate(N+1) **4,152ms** · analyze(CPU) 16ms
     *
     * 왕복 1회당 약 11.6ms 이고 **전체의 98.9%** 가 여기다. 쿼리가 느린 게 아니라 왕복이 많다.
     * 배치 1회로 바꾸면 같은 일이 `find` 와 같은 자릿수(수십 ms)로 떨어진다.
     */
    @Test
    fun `findByRuleIds — 여러 룰의 액션을 한 번에 룰별로 묶어 돌려준다`() {
        val ruleA = savedRule()
        val ruleB = savedRule()
        val ruleC = savedRule()
        actionRepository.replaceForRule(ruleA.id, fourActionsOneOfEach())
        actionRepository.replaceForRule(ruleB.id, listOf(Action.AddCommentAction(body = "B 코멘트")))
        // ruleC 는 액션 0건 — 키가 아예 없는지(빈 리스트가 아니라) 호출자 계약을 고정한다.

        val byRule = actionRepository.findByRuleIds(listOf(ruleA.id, ruleB.id, ruleC.id))

        assertThat(byRule[ruleA.id]).hasSize(fourActionsOneOfEach().size)
        assertThat(byRule[ruleB.id]).containsExactly(Action.AddCommentAction(body = "B 코멘트"))
        assertThat(byRule[ruleC.id]).isNull()
    }

    @Test
    fun `findByRuleIds — position 순서가 findByRuleId 와 같다`() {
        val rule = savedRule()
        val actions = fourActionsOneOfEach()
        actionRepository.replaceForRule(rule.id, actions)

        val batch = actionRepository.findByRuleIds(listOf(rule.id))[rule.id]

        assertThat(batch).isEqualTo(actionRepository.findByRuleId(rule.id))
    }

    @Test
    fun `findByRuleIds — 빈 입력은 DB 를 치지 않고 빈 맵이다`() {
        assertThat(actionRepository.findByRuleIds(emptyList())).isEmpty()
    }

    @Test
    fun `findByRuleId — 액션이 없는 룰은 빈 리스트를 반환한다`() {
        val rule = savedRule()

        assertThat(actionRepository.findByRuleId(rule.id)).isEmpty()
    }

    @Test
    fun `replaceForRule — SET_FIX_VERSIONS 액션은 findByRuleId 왕복에서 versionIds 가 보존된다`() {
        // actionConfigJson(직렬화)이 fromJson(역직렬화)의 정확한 역함수여야 한다 — 어긋나면 이 룰의
        // 모든 액션이 로드 불가능해진다(poison message, [AutomationActionRepository] KDoc 참고).
        val rule = savedRule()
        val versionIds = listOf(UUID.randomUUID(), UUID.randomUUID())
        val actions = listOf(Action.SetFixVersionsAction(versionIds = versionIds))

        actionRepository.replaceForRule(rule.id, actions)
        val found = actionRepository.findByRuleId(rule.id)

        assertThat(found).containsExactly(Action.SetFixVersionsAction(versionIds = versionIds))
    }

    @Test
    fun `replaceForRule — SET_FIX_VERSIONS 빈 배열도 findByRuleId 왕복에서 보존된다`() {
        val rule = savedRule()
        val actions = listOf(Action.SetFixVersionsAction(versionIds = emptyList()))

        actionRepository.replaceForRule(rule.id, actions)
        val found = actionRepository.findByRuleId(rule.id)

        assertThat(found).containsExactly(Action.SetFixVersionsAction(versionIds = emptyList()))
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
