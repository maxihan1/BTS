// UserSubscriptionRepository 통합 테스트 — Testcontainers PG16 + upsert/findByUser/fetchDisabled 검증

package com.bts.notification.repository

import com.bts.notification.domain.Channel
import com.bts.notification.domain.NotificationEventType
import com.bts.notification.domain.UserSubscription
import com.bts.notification.support.NotificationTestcontainersBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.time.Instant
import java.util.UUID

/**
 * UserSubscriptionRepository 통합 테스트.
 *
 * Testcontainers PG16-alpine 위에서 마이그레이션을 적용한 뒤
 * upsert / findByUser / fetchDisabled 의 정확성을 검증한다.
 *
 * - upsert 멱등: 같은 (user, eventType, channel) 재삽입 시 ON CONFLICT DO UPDATE 로 갱신
 * - findByUser: 해당 사용자 행만 반환, 타 사용자 격리
 * - fetchDisabled: enabled=false 행의 userId 만 반환, 배치 필터용
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class UserSubscriptionRepositoryTest : NotificationTestcontainersBase() {
    private val repository: UserSubscriptionRepository by lazy {
        UserSubscriptionRepository(dsl)
    }

    private val userId1: UUID = UUID.fromString("00000000-0000-0000-0001-000000000001")
    private val userId2: UUID = UUID.fromString("00000000-0000-0000-0001-000000000002")
    private val t0: Instant = Instant.parse("2026-06-19T10:00:00Z")
    private val t1: Instant = Instant.parse("2026-06-19T11:00:00Z")

    @BeforeEach
    fun cleanSubs() {
        dsl.execute("DELETE FROM user_notification_subs")
    }

    private fun sub(
        userId: UUID = userId1,
        eventType: NotificationEventType = NotificationEventType.ISSUE_CREATED,
        channel: Channel = Channel.IN_APP,
        enabled: Boolean = true,
        createdAt: Instant = t0,
        updatedAt: Instant = t0,
    ) = UserSubscription(
        userId = userId,
        eventType = eventType,
        channel = channel,
        enabled = enabled,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    // ── upsert — 신규 INSERT ─────────────────────────────────────────────────────

    @Test
    fun `upsert 신규 — 저장 후 findByUser로 1행이 조회되고 enabled 값이 일치한다`() {
        repository.upsert(sub(enabled = true))

        val results = repository.findByUser(userId1)
        assertThat(results).hasSize(1)
        assertThat(results[0].userId).isEqualTo(userId1)
        assertThat(results[0].eventType).isEqualTo(NotificationEventType.ISSUE_CREATED)
        assertThat(results[0].channel).isEqualTo(Channel.IN_APP)
        assertThat(results[0].enabled).isTrue()
    }

    // ── upsert — 멱등(ON CONFLICT DO UPDATE) ─────────────────────────────────────

    @Test
    fun `upsert 멱등 — 같은 (user,event,channel) 재호출 시 enabled와 updated_at만 갱신되고 행 수는 1이다`() {
        repository.upsert(sub(enabled = true, updatedAt = t0))
        repository.upsert(sub(enabled = false, updatedAt = t1))

        val results = repository.findByUser(userId1)
        assertThat(results).hasSize(1)
        assertThat(results[0].enabled).isFalse()
        assertThat(results[0].updatedAt).isEqualTo(t1)
    }

    // ── findByUser — 타 사용자 격리 ──────────────────────────────────────────────

    @Test
    fun `findByUser — 해당 사용자의 행만 반환하고 타 사용자 행은 포함하지 않는다`() {
        repository.upsert(sub(userId = userId1, channel = Channel.IN_APP))
        repository.upsert(sub(userId = userId2, channel = Channel.EMAIL))

        val results1 = repository.findByUser(userId1)
        val results2 = repository.findByUser(userId2)

        assertThat(results1).hasSize(1)
        assertThat(results1[0].userId).isEqualTo(userId1)
        assertThat(results1[0].channel).isEqualTo(Channel.IN_APP)

        assertThat(results2).hasSize(1)
        assertThat(results2[0].userId).isEqualTo(userId2)
        assertThat(results2[0].channel).isEqualTo(Channel.EMAIL)
    }

    @Test
    fun `findByUser — 행이 없는 사용자는 빈 리스트를 반환한다`() {
        val results = repository.findByUser(UUID.randomUUID())
        assertThat(results).hasSize(0)
    }

    // ── fetchDisabled ─────────────────────────────────────────────────────────────

    @Test
    fun `fetchDisabled — enabled=false 행의 userId만 Set으로 반환한다`() {
        // userId1: IN_APP ISSUE_CREATED → disabled
        repository.upsert(sub(userId = userId1, enabled = false))
        // userId2: IN_APP ISSUE_CREATED → enabled (반환되면 안 됨)
        repository.upsert(sub(userId = userId2, enabled = true))

        val disabled =
            repository.fetchDisabled(
                eventType = NotificationEventType.ISSUE_CREATED,
                channel = Channel.IN_APP,
                userIds = setOf(userId1, userId2),
            )

        assertThat(disabled).containsExactly(userId1)
    }

    @Test
    fun `fetchDisabled — 행이 없는 userId는 결과에 포함되지 않는다`() {
        val unknown = UUID.randomUUID()
        val disabled =
            repository.fetchDisabled(
                eventType = NotificationEventType.ISSUE_CREATED,
                channel = Channel.IN_APP,
                userIds = setOf(unknown),
            )
        assertThat(disabled).hasSize(0)
    }

    @Test
    fun `fetchDisabled — userIds가 비면 쿼리 없이 빈 Set을 반환한다`() {
        repository.upsert(sub(userId = userId1, enabled = false))

        val disabled =
            repository.fetchDisabled(
                eventType = NotificationEventType.ISSUE_CREATED,
                channel = Channel.IN_APP,
                userIds = emptySet(),
            )
        assertThat(disabled).hasSize(0)
    }

    @Test
    fun `fetchDisabled — 다른 (event,channel) 조합의 disabled 행은 포함하지 않는다`() {
        // userId1: EMAIL ISSUE_CREATED → disabled (다른 channel)
        repository.upsert(sub(userId = userId1, channel = Channel.EMAIL, enabled = false))
        // userId2: IN_APP ISSUE_ASSIGNED → disabled (다른 eventType)
        repository.upsert(
            sub(
                userId = userId2,
                eventType = NotificationEventType.ISSUE_ASSIGNED,
                channel = Channel.IN_APP,
                enabled = false,
            ),
        )

        val disabled =
            repository.fetchDisabled(
                eventType = NotificationEventType.ISSUE_CREATED,
                channel = Channel.IN_APP,
                userIds = setOf(userId1, userId2),
            )
        assertThat(disabled).hasSize(0)
    }
}
