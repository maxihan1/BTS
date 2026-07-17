// GitWebhookDeliveryCleanupWorker 통합 테스트 — 보존기간(7일) 경과 배달 기록 삭제/보존/무대상 정상종료 (FR-AT-07 PR-C Task 18)

package com.bts.automation.worker

import com.bts.automation.AutomationTestBootApplication
import com.bts.automation.AutomationTestcontainersBase
import com.bts.automation.StubAutomationPermissionResolver
import com.bts.automation.StubIssueMutationPort
import com.bts.automation.StubIssuePermissionResolver
import com.bts.automation.StubIssueSnapshotPort
import com.bts.automation.adapter.GitWebhookRepository
import com.bts.automation.domain.GitProvider
import com.bts.automation.domain.GitWebhook
import com.bts.shared.issue.IssueMutationPort
import com.bts.shared.issue.IssueSnapshotPort
import com.bts.shared.permission.AutomationPermissionResolver
import com.bts.shared.permission.IssuePermissionResolver
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
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
import java.time.ZoneOffset
import java.util.UUID

/**
 * [GitWebhookDeliveryCleanupWorker] 통합 테스트 (FR-AT-07 PR-C Task 18, 스펙 §3.8, V308 KDoc "T18").
 *
 * [AutomationTestBootApplication] 컴포넌트 스캔 위에서 실 [GitWebhookRepository]/[JdbcTemplate] 를 사용해
 * `git_webhook_deliveries` 실제 스키마(V308)에 대해 검증한다([GitWebhookRepositoryTest] 선례 동형). 스케줄
 * 결선(`@EnableScheduling`)은 이 테스트 컨텍스트에서 기본 OFF([AutomationSchedulingConfig] 참조)이므로
 * [GitWebhookDeliveryCleanupWorker.purgeExpiredDeliveries] 를 직접 호출한다(plan-eng-review E5 동형).
 *
 * ## 검증 시나리오
 * - 보존기간(7일) 경과 배달 기록은 삭제된다
 * - 보존기간 이내(경계값 포함 — 정확히 7일 전은 보존, 그보다 최근도 보존) 배달 기록은 보존된다
 * - 삭제 대상이 없어도 예외 없이 정상 종료한다
 *
 * ## [TestSupportConfig] — 컨텍스트 로드용 스텁
 * [AutomationTestBootApplication] 이 `com.bts.automation` 전체를 스캔하므로 [AutomationPermissionResolver]/
 * [IssueMutationPort]/[IssueSnapshotPort]/[IssuePermissionResolver] 스텁이 필요하다([GitWebhookRepositoryTest]
 * 동형 — 이 테스트 자체는 이 포트들을 쓰지 않지만 컨텍스트 로드에 필요).
 */
@SpringBootTest(
    classes = [AutomationTestBootApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
)
@Import(
    AutomationTestcontainersBase::class,
    GitWebhookDeliveryCleanupWorkerTest.TestSupportConfig::class,
)
class GitWebhookDeliveryCleanupWorkerTest {
    @TestConfiguration
    class TestSupportConfig {
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
    private lateinit var webhookRepository: GitWebhookRepository

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var jdbcTemplate: JdbcTemplate

    /** 폴링 기준 시각(now) — 보존기간 계산의 기준. */
    private val now: Instant = Instant.parse("2026-07-17T00:00:00Z")

    /** [now] 기준 정확히 보존기간(7일) 전 시각 — 경계값. */
    private val threshold: Instant = now.minus(Duration.ofDays(7))

    @BeforeEach
    fun cleanUp() {
        // git_webhook_deliveries 는 git_webhooks FK ON DELETE CASCADE 로 함께 정리된다(V308).
        jdbcTemplate.update("DELETE FROM git_webhooks")
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private fun worker(fixedNow: Instant = now): GitWebhookDeliveryCleanupWorker =
        GitWebhookDeliveryCleanupWorker(jdbcTemplate, Clock.fixed(fixedNow, ZoneOffset.UTC))

    private fun newWebhook(tokenHash: String): GitWebhook =
        GitWebhook(
            id = UUID.randomUUID(),
            projectKey = "ATLAS",
            provider = GitProvider.GITHUB,
            tokenHash = tokenHash,
            secretEncrypted = "encrypted-secret",
            createdAt = now,
            createdBy = UUID.randomUUID(),
            deletedAt = null,
        )

    private fun deliveryCount(
        webhookId: UUID,
        deliveryId: String,
    ): Long =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM git_webhook_deliveries WHERE webhook_id = ? AND delivery_id = ?",
            Long::class.java,
            webhookId,
            deliveryId,
        )

    // ── 보존기간 경과 → 삭제 ──────────────────────────────────────────────────

    @Test
    fun `보존기간 7일이 경과한 배달 기록은 삭제된다`() {
        val webhook = newWebhook(tokenHash = "a".repeat(64))
        webhookRepository.insert(webhook)
        webhookRepository.insertDelivery(webhook.id, "expired-delivery", threshold.minusSeconds(1))

        worker().purgeExpiredDeliveries()

        assertThat(deliveryCount(webhook.id, "expired-delivery")).isZero()
    }

    // ── 보존기간 이내(경계값 포함) → 보존 ─────────────────────────────────────

    @Test
    fun `보존기간 7일 이내의 배달 기록은 보존된다`() {
        val webhook = newWebhook(tokenHash = "b".repeat(64))
        webhookRepository.insert(webhook)
        // 정확히 경계(7일 전)는 WHERE received_at < threshold 조건에서 제외되지 않아야 한다(strictly less-than).
        webhookRepository.insertDelivery(webhook.id, "boundary-delivery", threshold)
        // 명백히 보존기간 이내.
        webhookRepository.insertDelivery(webhook.id, "recent-delivery", now.minusSeconds(60))

        worker().purgeExpiredDeliveries()

        assertThat(deliveryCount(webhook.id, "boundary-delivery")).isEqualTo(1L)
        assertThat(deliveryCount(webhook.id, "recent-delivery")).isEqualTo(1L)
    }

    // ── 삭제 대상 없음 → 정상 종료 ─────────────────────────────────────────────

    @Test
    fun `삭제 대상이 없어도 예외 없이 정상 종료한다`() {
        assertThatCode { worker().purgeExpiredDeliveries() }.doesNotThrowAnyException()
    }
}
