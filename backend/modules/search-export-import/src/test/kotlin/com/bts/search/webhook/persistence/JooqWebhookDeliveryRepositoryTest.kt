// JooqWebhookDeliveryRepository 통합테스트 — Testcontainers + Flyway V603 + append-only 기록 + 페이지네이션 (FR-API-03 PR3 Task 3)

package com.bts.search.webhook.persistence

import com.bts.search.savedfilter.persistence.SearchPersistenceTestBase
import com.bts.search.webhook.domain.DeliveryStatus
import com.bts.search.webhook.domain.OutboundWebhook
import com.bts.search.webhook.domain.WebhookEventCatalog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * JooqWebhookDeliveryRepository 통합테스트.
 *
 * Testcontainers (테스트용 DB 를 도커로 자동 실행하는 라이브러리) PG16-alpine(pgmq 포함) 위에서
 * Flyway V600~V603 마이그레이션 체인 적용 후 Repository 동작을 검증한다.
 * `SearchPersistenceTestBase`(savedfilter 패키지 소유, 같은 모듈/BC 내 공용 JVM-singleton 컨테이너
 * 기반 클래스)를 그대로 재사용한다 — 별도 컨테이너 기동 없이 V603 까지 포함된 마이그레이션 체인을 공유한다.
 *
 * `webhook_deliveries.webhook_id` 는 `outbound_webhooks` FK(NOT NULL)이므로, 매 테스트마다
 * `JooqOutboundWebhookRepository` 로 부모 구독을 먼저 저장한다.
 *
 * 검증 범위 (plan Task 3).
 * - record — SUCCEEDED 는 delivered_at 채워짐, FAILED 는 delivered_at null(발송 자체가 안 됐으므로).
 * - record — 매 호출마다 새 id 로 append-only 기록(수정 없음).
 * - listByWebhook — created_at DESC 최신순, 다른 webhookId 이력 제외(negative control), 페이지네이션.
 */
class JooqWebhookDeliveryRepositoryTest : SearchPersistenceTestBase() {
    private val outboundWebhookRepo get() = JooqOutboundWebhookRepository(dsl)

    /**
     * 테스트에서 시간을 전진시키기 위한 가변 [Clock].
     *
     * 같은 인스턴스를 [JooqWebhookDeliveryRepository]에 주입한 뒤 [advance]로 instant를 밀어,
     * 벽시계 sleep 없이 created_at 순서를 결정적으로 검증한다
     * (메모리 authcontroller-revokesession-timebomb 회귀 방지, WebhookCircuitBreakerTest 동일 패턴).
     */
    private class MutableClock(private var current: Instant) : Clock() {
        override fun instant(): Instant = current

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        fun advance(by: Duration) {
            current = current.plus(by)
        }
    }

    private val baseInstant = Instant.parse("2026-07-01T00:00:00Z")

    @AfterEach
    fun cleanUp() {
        dsl.execute("DELETE FROM webhook_deliveries")
        dsl.execute("DELETE FROM outbound_webhooks")
    }

    private fun createWebhook(): UUID =
        outboundWebhookRepo.save(
            OutboundWebhook.create(
                createdBy = UUID.randomUUID(),
                name = "테스트 웹훅",
                url = "https://example.com/hooks/atlas",
                eventFilter = listOf(WebhookEventCatalog.ISSUE_CREATED),
            ),
        ).id!!

    // ── record ────────────────────────────────────────────────────────────────

    @Test
    fun `record — SUCCEEDED 상태는 id, created_at, delivered_at 이 채워져 반환된다`() {
        val webhookId = createWebhook()
        val repo = JooqWebhookDeliveryRepository(dsl, MutableClock(baseInstant))

        val delivery =
            repo.record(
                webhookId = webhookId,
                eventType = WebhookEventCatalog.ISSUE_CREATED,
                status = DeliveryStatus.SUCCEEDED,
                responseCode = 200,
                attemptCount = 1,
                errorDetail = null,
            )

        assertThat(delivery.id).isNotNull()
        assertThat(delivery.webhookId).isEqualTo(webhookId)
        assertThat(delivery.eventType).isEqualTo(WebhookEventCatalog.ISSUE_CREATED)
        assertThat(delivery.status).isEqualTo(DeliveryStatus.SUCCEEDED)
        assertThat(delivery.responseCode).isEqualTo(200)
        assertThat(delivery.attemptCount).isEqualTo(1)
        assertThat(delivery.errorDetail).isNull()
        assertThat(delivery.createdAt).isNotNull()
        assertThat(delivery.deliveredAt).isNotNull()
    }

    @Test
    fun `record — FAILED 상태는 delivered_at 이 null 로 기록된다 (발송 자체가 안 됨)`() {
        val webhookId = createWebhook()
        val repo = JooqWebhookDeliveryRepository(dsl, MutableClock(baseInstant))

        val delivery =
            repo.record(
                webhookId = webhookId,
                eventType = WebhookEventCatalog.ISSUE_CREATED,
                status = DeliveryStatus.FAILED,
                responseCode = null,
                attemptCount = 1,
                errorDetail = "connection timeout",
            )

        assertThat(delivery.status).isEqualTo(DeliveryStatus.FAILED)
        assertThat(delivery.responseCode).isNull()
        assertThat(delivery.errorDetail).isEqualTo("connection timeout")
        assertThat(delivery.deliveredAt).isNull()
        assertThat(delivery.createdAt).isNotNull()
    }

    @Test
    fun `record — 매 호출마다 새 id 로 append-only 기록된다`() {
        val webhookId = createWebhook()
        val repo = JooqWebhookDeliveryRepository(dsl, MutableClock(baseInstant))

        val first =
            repo.record(webhookId, WebhookEventCatalog.ISSUE_CREATED, DeliveryStatus.FAILED, null, 1, "timeout")
        val second =
            repo.record(webhookId, WebhookEventCatalog.ISSUE_CREATED, DeliveryStatus.SUCCEEDED, 200, 2, null)

        assertThat(first.id).isNotEqualTo(second.id)
    }

    // ── listByWebhook ─────────────────────────────────────────────────────────

    @Test
    fun `listByWebhook — created_at 최신순으로 반환한다`() {
        val webhookId = createWebhook()
        val clock = MutableClock(baseInstant)
        val repo = JooqWebhookDeliveryRepository(dsl, clock)

        val oldest =
            repo.record(webhookId, WebhookEventCatalog.ISSUE_CREATED, DeliveryStatus.SUCCEEDED, 200, 1, null)
        clock.advance(Duration.ofSeconds(1))
        val middle =
            repo.record(webhookId, WebhookEventCatalog.ISSUE_CREATED, DeliveryStatus.SUCCEEDED, 200, 1, null)
        clock.advance(Duration.ofSeconds(1))
        val newest =
            repo.record(webhookId, WebhookEventCatalog.ISSUE_CREATED, DeliveryStatus.SUCCEEDED, 200, 1, null)

        val result = repo.listByWebhook(webhookId, page = 0, size = 20)

        assertThat(result.map { it.id }).containsExactly(newest.id, middle.id, oldest.id)
    }

    @Test
    fun `listByWebhook — 다른 webhookId 의 이력은 제외한다 (negative control)`() {
        val webhookId1 = createWebhook()
        val webhookId2 = createWebhook()
        val repo = JooqWebhookDeliveryRepository(dsl, MutableClock(baseInstant))
        val target =
            repo.record(webhookId1, WebhookEventCatalog.ISSUE_CREATED, DeliveryStatus.SUCCEEDED, 200, 1, null)
        repo.record(webhookId2, WebhookEventCatalog.ISSUE_CREATED, DeliveryStatus.SUCCEEDED, 200, 1, null)

        val result = repo.listByWebhook(webhookId1, page = 0, size = 20)

        assertThat(result.map { it.id }).containsExactly(target.id)
    }

    @Test
    fun `listByWebhook — page 와 size 로 전체 목록을 중복 누락 없이 분할한다`() {
        val webhookId = createWebhook()
        val clock = MutableClock(baseInstant)
        val repo = JooqWebhookDeliveryRepository(dsl, clock)
        val saved =
            (1..3).map {
                val delivery =
                    repo.record(webhookId, WebhookEventCatalog.ISSUE_CREATED, DeliveryStatus.SUCCEEDED, 200, 1, null)
                clock.advance(Duration.ofSeconds(1))
                delivery
            }

        val page0 = repo.listByWebhook(webhookId, page = 0, size = 2)
        val page1 = repo.listByWebhook(webhookId, page = 1, size = 2)

        assertThat(page0).hasSize(2)
        assertThat(page1).hasSize(1)
        assertThat(page0.map { it.id } + page1.map { it.id })
            .containsExactlyInAnyOrderElementsOf(saved.map { it.id })
    }

    @Test
    fun `listByWebhook — 이력이 없으면 빈 목록을 반환한다`() {
        val webhookId = createWebhook()
        val repo = JooqWebhookDeliveryRepository(dsl, MutableClock(baseInstant))

        assertThat(repo.listByWebhook(webhookId, page = 0, size = 20)).isEmpty()
    }
}
