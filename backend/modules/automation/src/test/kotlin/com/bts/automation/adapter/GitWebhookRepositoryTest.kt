// GitWebhookRepository 통합 테스트 — 등록/조회/소프트삭제/배달 dedup/FK CASCADE (FR-AT-07 PR-C Task 6)

package com.bts.automation.adapter

import com.bts.automation.AutomationTestBootApplication
import com.bts.automation.AutomationTestcontainersBase
import com.bts.automation.StubAutomationPermissionResolver
import com.bts.automation.StubIssueMutationPort
import com.bts.automation.StubIssuePermissionResolver
import com.bts.automation.StubIssueSnapshotPort
import com.bts.automation.domain.GitProvider
import com.bts.automation.domain.GitWebhook
import com.bts.shared.issue.IssueMutationPort
import com.bts.shared.issue.IssueSnapshotPort
import com.bts.shared.permission.AutomationPermissionResolver
import com.bts.shared.permission.IssuePermissionResolver
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
 * [GitWebhookRepository] 통합 테스트 (FR-AT-07 PR-C Task 6).
 *
 * [AutomationTestBootApplication] 컴포넌트 스캔으로 실 [GitWebhookRepository] 를 로드하고,
 * [AutomationTestcontainersBase] 가 배선한 Testcontainers DataSource 위에서 `V307`/`V308` 실제
 * 스키마·SQL 을 검증한다([AutomationConditionRepositoryTest] 선례 동형).
 *
 * ## 검증 시나리오
 * - insert → findByTokenHash 로 원본이 복원된다
 * - findByTokenHash — 소프트 삭제된 웹훅은 조회에서 제외된다(V307 부분 UNIQUE 와 정합)
 * - findByProjectKey — 같은 프로젝트의 미삭제 웹훅만, 타 프로젝트/삭제됨은 제외
 * - insertDelivery — 같은 (webhook_id, delivery_id) 2회 삽입 시 1회차 true·2회차 false(dedup)
 * - insertDelivery — 다른 delivery_id 는 둘 다 true(중복 아님)
 * - webhook 하드삭제 시 그 배달 이력도 함께 삭제된다(V308 `ON DELETE CASCADE` 실증)
 */
@SpringBootTest(
    classes = [AutomationTestBootApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
)
@Import(
    AutomationTestcontainersBase::class,
    GitWebhookRepositoryTest.PermissionResolverStubConfig::class,
)
class GitWebhookRepositoryTest {
    /**
     * 컨텍스트 로드용 [AutomationPermissionResolver]/[IssueMutationPort]/[IssueSnapshotPort]/
     * [IssuePermissionResolver] 스텁 등록([AutomationConditionRepositoryTest] 동형 — 이 테스트 자체는
     * 이 포트들을 쓰지 않지만 `com.bts.automation` 전체 스캔 컨텍스트 로드에 필요).
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
    private lateinit var repository: GitWebhookRepository

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var jdbcTemplate: JdbcTemplate

    private val now: Instant = Instant.parse("2026-07-17T00:00:00Z")

    @BeforeEach
    fun cleanUp() {
        // git_webhook_deliveries 는 git_webhooks FK ON DELETE CASCADE 로 함께 정리된다(V308).
        jdbcTemplate.update("DELETE FROM git_webhooks")
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private fun newWebhook(
        projectKey: String = "ATLAS",
        provider: GitProvider = GitProvider.GITHUB,
        tokenHash: String = "a".repeat(64),
        secretEncrypted: String = "encrypted-secret",
        createdAt: Instant = now,
    ): GitWebhook =
        GitWebhook(
            id = UUID.randomUUID(),
            projectKey = projectKey,
            provider = provider,
            tokenHash = tokenHash,
            secretEncrypted = secretEncrypted,
            createdAt = createdAt,
            createdBy = UUID.randomUUID(),
            deletedAt = null,
        )

    // ── insert / findByTokenHash ─────────────────────────────────────────────

    @Test
    fun `insert 후 findByTokenHash 로 원본이 복원된다`() {
        val webhook = newWebhook()

        repository.insert(webhook)
        val found = repository.findByTokenHash(webhook.tokenHash)

        assertThat(found).isEqualTo(webhook)
    }

    @Test
    fun `findByTokenHash — 존재하지 않는 해시는 null 을 반환한다`() {
        assertThat(repository.findByTokenHash("z".repeat(64))).isNull()
    }

    @Test
    fun `findByTokenHash — 소프트 삭제된 웹훅은 조회에서 제외된다`() {
        val webhook = newWebhook()
        repository.insert(webhook)

        repository.softDelete(webhook.id, now)

        assertThat(repository.findByTokenHash(webhook.tokenHash)).isNull()
    }

    // ── findByProjectKey ─────────────────────────────────────────────────────

    @Test
    fun `findByProjectKey — 같은 프로젝트의 미삭제 웹훅만 반환한다`() {
        val a = newWebhook(tokenHash = "a".repeat(64))
        val b = newWebhook(tokenHash = "b".repeat(64))
        val deleted = newWebhook(tokenHash = "c".repeat(64))
        val otherProject = newWebhook(projectKey = "OTHER", tokenHash = "d".repeat(64))
        listOf(a, b, deleted, otherProject).forEach(repository::insert)
        repository.softDelete(deleted.id, now)

        val found = repository.findByProjectKey("ATLAS")

        assertThat(found.map { it.id }).containsExactlyInAnyOrder(a.id, b.id)
    }

    @Test
    fun `findByProjectKey — 등록된 웹훅이 없으면 빈 목록을 반환한다`() {
        assertThat(repository.findByProjectKey("NONE")).isEmpty()
    }

    // ── insertDelivery (dedup) ───────────────────────────────────────────────

    @Test
    fun `insertDelivery — 같은 webhook·delivery 조합은 1회차만 true 를 반환한다`() {
        val webhook = newWebhook()
        repository.insert(webhook)

        val first = repository.insertDelivery(webhook.id, "delivery-1", now)
        val second = repository.insertDelivery(webhook.id, "delivery-1", now.plusSeconds(5))

        assertThat(first).isTrue()
        assertThat(second).isFalse()
        val count =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM git_webhook_deliveries WHERE webhook_id = ? AND delivery_id = ?",
                Long::class.java,
                webhook.id,
                "delivery-1",
            )
        assertThat(count).isEqualTo(1L)
    }

    @Test
    fun `insertDelivery — 다른 delivery_id 는 둘 다 true 를 반환한다`() {
        val webhook = newWebhook()
        repository.insert(webhook)

        val first = repository.insertDelivery(webhook.id, "delivery-1", now)
        val second = repository.insertDelivery(webhook.id, "delivery-2", now)

        assertThat(first).isTrue()
        assertThat(second).isTrue()
    }

    // ── FK CASCADE ───────────────────────────────────────────────────────────

    @Test
    fun `webhook 을 하드삭제하면 그 배달 이력도 함께 삭제된다(ON DELETE CASCADE)`() {
        val webhook = newWebhook()
        repository.insert(webhook)
        repository.insertDelivery(webhook.id, "delivery-1", now)
        repository.insertDelivery(webhook.id, "delivery-2", now)

        jdbcTemplate.update("DELETE FROM git_webhooks WHERE id = ?", webhook.id)

        val remaining =
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM git_webhook_deliveries WHERE webhook_id = ?",
                Long::class.java,
                webhook.id,
            )
        assertThat(remaining).isEqualTo(0L)
    }
}
