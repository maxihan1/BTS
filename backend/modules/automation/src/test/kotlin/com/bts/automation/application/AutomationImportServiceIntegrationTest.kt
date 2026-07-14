// AutomationRuleService.importRules 통합 테스트 — id 기준 upsert 원자성 + 검증 재사용 + EC3/EC4/EC6 (FR-AT-06 GitOps Task 4)

package com.bts.automation.application

import com.bts.automation.AutomationTestBootApplication
import com.bts.automation.AutomationTestcontainersBase
import com.bts.automation.StubAutomationPermissionResolver
import com.bts.automation.StubIssueMutationPort
import com.bts.automation.StubIssuePermissionResolver
import com.bts.automation.StubIssueSnapshotPort
import com.bts.automation.domain.ActionConfigInvalidException
import com.bts.automation.domain.ActionType
import com.bts.automation.domain.AutomationRuleInvalidException
import com.bts.automation.domain.InvalidConditionExpressionException
import com.bts.automation.domain.TriggerConfigInvalidException
import com.bts.automation.domain.TriggerType
import com.bts.automation.gitops.ImportActionCommand
import com.bts.automation.gitops.ImportRuleCommand
import com.bts.shared.permission.IssuePermissionResolver
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

private const val PROJECT_KEY = "GITIMP"
private const val OTHER_PROJECT_KEY = "GITIMP2"

/**
 * [AutomationRuleService.importRules] 통합 테스트 (FR-AT-06 GitOps Task 4).
 *
 * `webEnvironment = NONE` — 이 태스크는 서비스 메서드([AutomationRuleService.importRules]) 자체가
 * 대상이고, HTTP import 엔드포인트는 아직 없다(Task 5 scope). [AutomationRuleService] 를 직접
 * 오토와이어해 실 PostgreSQL(Testcontainers) round-trip 으로 검증한다 — PK UNIQUE 제약 위반
 * ([org.springframework.dao.DuplicateKeyException]) 감지·OCC·실 트랜잭션 롤백은 MockK 로는 관측할 수
 * 없다([ModuleBootTest][com.bts.automation.ModuleBootTest] 동형 최소 구성).
 *
 * ## 커버 시나리오 (plan Task 4 RED)
 * 1. id 보존 생성 — 프로젝트에 미존재하는 id 로 CREATE, `enabled=false` 보존.
 * 2. 멱등(S3) — 같은 커맨드 2회 import 시 2회차는 전량 UPDATE, 규칙 수 불변.
 * 3. 원자성(S4) — 5커맨드 중 1개(조건 MAX_DEPTH 초과) 실패 시 전량 롤백 + 실패 인덱스.
 * 4. triggerType 변경(EC3)/id 귀속 충돌(EC4, 타 프로젝트·소프트삭제) → 예외.
 * 5. 검증 재사용(FR5) — 비화이트리스트 var·잘못된 cron·잘못된 url·name 초과.
 * 6. MANAGE_AUTOMATION 없음 → 루프 이전에 거부(저장 0건).
 * 7. WEBHOOK 규칙 생성 → `webhookTokens` 1건 노출.
 */
@SpringBootTest(
    classes = [AutomationTestBootApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
)
@Import(
    AutomationTestcontainersBase::class,
    AutomationImportServiceIntegrationTest.TestSupportConfig::class,
)
class AutomationImportServiceIntegrationTest {
    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var service: AutomationRuleService

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var permissionResolver: StubAutomationPermissionResolver

    private val actorId: UUID = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        jdbcTemplate.update("DELETE FROM automation_rules")
        permissionResolver.reset()
        permissionResolver.allow(PROJECT_KEY)
        permissionResolver.allow(OTHER_PROJECT_KEY)
    }

    // ── 1. id 보존 생성 ──────────────────────────────────────────────────────

    @Test
    fun `id 있는 커맨드가 프로젝트에 미존재하면 그 id로 CREATE하고 enabled를 보존한다`() {
        val presetId = UUID.randomUUID()
        val command = basicCommand(id = presetId, name = "미리 정한 id 규칙", enabled = false)

        val outcome = service.importRules(actorId, PROJECT_KEY, listOf(command))

        assertThat(outcome.created).isEqualTo(1)
        assertThat(outcome.updated).isEqualTo(0)
        assertThat(outcome.ruleIds).containsExactly(presetId)
        val row =
            jdbcTemplate.queryForMap(
                "SELECT enabled, project_key FROM automation_rules WHERE id = ?::uuid",
                presetId.toString(),
            )
        assertThat(row["enabled"]).isEqualTo(false)
        assertThat(row["project_key"]).isEqualTo(PROJECT_KEY)
    }

    // ── 2. 멱등(S3) ──────────────────────────────────────────────────────────

    @Test
    fun `같은 커맨드를 2회 import하면 2회차는 전량 UPDATE고 규칙 수는 불변이다`() {
        val presetId = UUID.randomUUID()
        val command = basicCommand(id = presetId, name = "멱등 규칙")

        val first = service.importRules(actorId, PROJECT_KEY, listOf(command))
        val second = service.importRules(actorId, PROJECT_KEY, listOf(command))

        assertThat(first.created).isEqualTo(1)
        assertThat(second.created).isEqualTo(0)
        assertThat(second.updated).isEqualTo(1)
        assertThat(ruleCount(PROJECT_KEY)).isEqualTo(1)
    }

    // ── 3. 원자성(S4) ────────────────────────────────────────────────────────

    @Test
    fun `5커맨드 중 1개가 조건 MAX_DEPTH를 초과하면 전량 롤백되고 예외에 실패 인덱스가 담긴다`() {
        val failingIndex = 2
        val commands =
            (0 until 5).map { i ->
                if (i == failingIndex) {
                    basicCommand(name = "실패 규칙", condition = deeplyNestedCondition(depth = 12))
                } else {
                    basicCommand(name = "규칙-$i")
                }
            }

        assertThatThrownBy { service.importRules(actorId, PROJECT_KEY, commands) }
            .isInstanceOf(AutomationImportCommandException::class.java)
            .satisfies({ e -> assertThat((e as AutomationImportCommandException).index).isEqualTo(failingIndex) })
            .hasCauseInstanceOf(InvalidConditionExpressionException::class.java)

        assertThat(ruleCount(PROJECT_KEY)).isEqualTo(0)
    }

    // ── 4. EC3 triggerType 변경 / EC4 id 귀속 충돌 ──────────────────────────────

    @Test
    fun `UPDATE 대상 규칙과 triggerType이 다르면 예외를 던진다(EC3)`() {
        val presetId = UUID.randomUUID()
        service.importRules(
            actorId,
            PROJECT_KEY,
            listOf(basicCommand(id = presetId, name = "원본", triggerType = TriggerType.ISSUE_CREATED)),
        )

        val changedTypeCommand =
            basicCommand(id = presetId, name = "원본", triggerType = TriggerType.ISSUE_COMMENTED)

        assertThatThrownBy { service.importRules(actorId, PROJECT_KEY, listOf(changedTypeCommand)) }
            .isInstanceOf(AutomationImportCommandException::class.java)
            .hasCauseInstanceOf(AutomationImportTriggerTypeChangedException::class.java)
    }

    @Test
    fun `id가 다른 프로젝트 소유면 id 귀속 충돌 예외를 던진다(EC4)`() {
        val presetId = UUID.randomUUID()
        service.importRules(actorId, PROJECT_KEY, listOf(basicCommand(id = presetId, name = "PROJ 소유 규칙")))

        val conflictingCommand = basicCommand(id = presetId, name = "다른 프로젝트에서 재사용 시도")

        assertThatThrownBy { service.importRules(actorId, OTHER_PROJECT_KEY, listOf(conflictingCommand)) }
            .isInstanceOf(AutomationImportCommandException::class.java)
            .hasCauseInstanceOf(AutomationImportIdConflictException::class.java)
        assertThat(ruleCount(OTHER_PROJECT_KEY)).isEqualTo(0)
    }

    @Test
    fun `id가 소프트삭제된 규칙을 가리키면 id 귀속 충돌 예외를 던진다(EC4)`() {
        val presetId = UUID.randomUUID()
        service.importRules(actorId, PROJECT_KEY, listOf(basicCommand(id = presetId, name = "삭제될 규칙")))
        jdbcTemplate.update(
            "UPDATE automation_rules SET deleted_at = ? WHERE id = ?::uuid",
            Instant.now().atOffset(ZoneOffset.UTC),
            presetId.toString(),
        )

        val reuseCommand = basicCommand(id = presetId, name = "삭제된 id 재사용 시도")

        assertThatThrownBy { service.importRules(actorId, PROJECT_KEY, listOf(reuseCommand)) }
            .isInstanceOf(AutomationImportCommandException::class.java)
            .hasCauseInstanceOf(AutomationImportIdConflictException::class.java)
    }

    // ── 5. 검증 재사용(FR5) ──────────────────────────────────────────────────

    @Test
    fun `조건의 var가 화이트리스트 밖이면 예외를 던진다`() {
        val command = basicCommand(condition = """{"==":[{"var":"issue.notAllowed"},"x"]}""")

        assertThatThrownBy { service.importRules(actorId, PROJECT_KEY, listOf(command)) }
            .isInstanceOf(AutomationImportCommandException::class.java)
            .hasCauseInstanceOf(InvalidConditionExpressionException::class.java)
        assertThat(ruleCount(PROJECT_KEY)).isEqualTo(0)
    }

    @Test
    fun `SCHEDULED triggerConfig의 cron이 잘못되면 예외를 던진다`() {
        val command =
            basicCommand(triggerType = TriggerType.SCHEDULED, triggerConfig = """{"cron":"not-a-cron"}""")

        assertThatThrownBy { service.importRules(actorId, PROJECT_KEY, listOf(command)) }
            .isInstanceOf(AutomationImportCommandException::class.java)
            .hasCauseInstanceOf(TriggerConfigInvalidException::class.java)
        assertThat(ruleCount(PROJECT_KEY)).isEqualTo(0)
    }

    @Test
    fun `CALL_WEBHOOK 액션의 url이 http-https가 아니면 예외를 던진다`() {
        val webhookAction = ImportActionCommand(type = ActionType.CALL_WEBHOOK, config = """{"url":"ftp://bad"}""")
        val command = basicCommand(actions = listOf(webhookAction))

        assertThatThrownBy { service.importRules(actorId, PROJECT_KEY, listOf(command)) }
            .isInstanceOf(AutomationImportCommandException::class.java)
            .hasCauseInstanceOf(ActionConfigInvalidException::class.java)
        assertThat(ruleCount(PROJECT_KEY)).isEqualTo(0)
    }

    @Test
    fun `name이 200자를 초과하면 예외를 던진다`() {
        val command = basicCommand(name = "x".repeat(201))

        assertThatThrownBy { service.importRules(actorId, PROJECT_KEY, listOf(command)) }
            .isInstanceOf(AutomationImportCommandException::class.java)
            .hasCauseInstanceOf(AutomationRuleInvalidException::class.java)
        assertThat(ruleCount(PROJECT_KEY)).isEqualTo(0)
    }

    // ── 6. MANAGE_AUTOMATION 없음(루프 이전) ────────────────────────────────────

    @Test
    fun `MANAGE_AUTOMATION 권한이 없으면 루프 시작 전에 거부되고 아무것도 저장되지 않는다`() {
        permissionResolver.deny(PROJECT_KEY)
        val commands = listOf(basicCommand(name = "규칙 A"), basicCommand(name = "규칙 B"))

        assertThatThrownBy { service.importRules(actorId, PROJECT_KEY, commands) }
            .isInstanceOf(AutomationForbiddenException::class.java)
        assertThat(ruleCount(PROJECT_KEY)).isEqualTo(0)
    }

    // ── 7. WEBHOOK 토큰 1회 노출 ─────────────────────────────────────────────

    @Test
    fun `WEBHOOK 규칙을 생성하면 webhookTokens에 원문 토큰이 1건 담긴다`() {
        val command = basicCommand(name = "웹훅 규칙", triggerType = TriggerType.WEBHOOK)

        val outcome = service.importRules(actorId, PROJECT_KEY, listOf(command))

        assertThat(outcome.webhookTokens).hasSize(1)
        val token = outcome.webhookTokens.single()
        assertThat(token.ruleId).isEqualTo(outcome.ruleIds.single())
        assertThat(token.name).isEqualTo("웹훅 규칙")
        assertThat(token.token).isNotBlank()

        val storedHash =
            jdbcTemplate.queryForObject(
                "SELECT webhook_token_hash FROM automation_rules WHERE id = ?::uuid",
                String::class.java,
                token.ruleId.toString(),
            )
        assertThat(storedHash).isNotBlank().isNotEqualTo(token.token)
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private fun ruleCount(projectKey: String): Int =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM automation_rules WHERE project_key = ?",
            Int::class.java,
            projectKey,
        ) ?: 0

    @Suppress("LongParameterList")
    private fun basicCommand(
        id: UUID? = null,
        name: String = "규칙",
        enabled: Boolean = true,
        actorUserId: UUID? = null,
        triggerType: TriggerType = TriggerType.ISSUE_CREATED,
        triggerConfig: String = "{}",
        condition: String? = null,
        actions: List<ImportActionCommand> = emptyList(),
    ): ImportRuleCommand =
        ImportRuleCommand(
            id = id,
            name = name,
            enabled = enabled,
            actorUserId = actorUserId,
            triggerType = triggerType,
            triggerConfig = triggerConfig,
            condition = condition,
            actions = actions,
        )

    /** `not` 을 [depth] 번 중첩해 [com.bts.automation.domain.Condition.MAX_DEPTH](=10)를 초과시킨다. */
    private fun deeplyNestedCondition(depth: Int): String {
        var expression = """{"==":[{"var":"issue.status"},"open"]}"""
        repeat(depth) { expression = """{"not":$expression}""" }
        return expression
    }

    /**
     * 테스트 전용 협력자 stub 빈 등록. `AutomationTestcontainersBase`(다른 Task 산출물)는 이 Task 의 파일
     * 범위 밖이라 `@Bean` 을 추가할 수 없어 이 파일 자체의 nested `@TestConfiguration` 에서 등록한다
     * ([com.bts.automation.web.AutomationRuleExportIntegrationTest.TestSupportConfig] 동형).
     * `webEnvironment = NONE` 이라 HTTP 보안 필터체인은 필요 없다
     * ([com.bts.automation.ModuleBootTest] 동형 최소 구성).
     */
    @TestConfiguration
    class TestSupportConfig {
        @Bean
        fun stubAutomationPermissionResolver(): StubAutomationPermissionResolver = StubAutomationPermissionResolver()

        @Bean
        fun stubIssueMutationPort(): StubIssueMutationPort = StubIssueMutationPort()

        @Bean
        fun stubIssueSnapshotPort(): StubIssueSnapshotPort = StubIssueSnapshotPort()

        @Bean
        fun issuePermissionResolver(): IssuePermissionResolver = StubIssuePermissionResolver()
    }
}
