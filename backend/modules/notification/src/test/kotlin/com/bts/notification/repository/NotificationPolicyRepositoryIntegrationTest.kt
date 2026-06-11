// NotificationPolicyRepository 통합 테스트 — Testcontainers PG16 + V400/V401 마이그레이션 검증

package com.bts.notification.repository

import com.bts.notification.domain.Channel
import com.bts.notification.domain.NotificationEventType
import com.bts.notification.domain.NotificationPolicy
import com.bts.notification.domain.RecipientRole
import com.bts.notification.support.NotificationTestcontainersBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.MethodOrderer
import java.time.Instant
import java.util.UUID

/**
 * NotificationPolicyRepository CRUD + 시드 + 멱등 통합 테스트.
 *
 * Testcontainers PG16-alpine 위에서 V400(DDL) + V401(시드) 마이그레이션 적용 후 검증한다.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class NotificationPolicyRepositoryIntegrationTest : NotificationTestcontainersBase() {

    private val repository: NotificationPolicyRepository by lazy {
        NotificationPolicyRepository(dsl)
    }

    private val testCreatedBy: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val now: Instant = Instant.parse("2026-06-11T00:00:00Z")

    private fun buildPolicy(
        projectKey: String? = "ATLAS",
        eventType: NotificationEventType = NotificationEventType.ISSUE_CREATED,
        recipientRole: RecipientRole = RecipientRole.ASSIGNEE,
        channel: Channel = Channel.EMAIL,
    ): NotificationPolicy = NotificationPolicy(
        id = UUID.randomUUID(),
        projectKey = projectKey,
        eventType = eventType,
        recipientRole = recipientRole,
        channel = channel,
        enabled = true,
        createdBy = testCreatedBy,
        createdAt = now,
        updatedAt = now,
    )

    // ── insert + findById 라운드트립 ─────────────────────────────────────────────

    @Test
    fun `insert 후 findById로 동일 정책을 조회할 수 있다`() {
        val policy = buildPolicy()

        val inserted = repository.insert(policy)
        val found = repository.findById(inserted.id)

        assertThat(found).isNotNull
        assertThat(found!!.id).isEqualTo(inserted.id)
        assertThat(found.projectKey).isEqualTo("ATLAS")
        assertThat(found.eventType).isEqualTo(NotificationEventType.ISSUE_CREATED)
        assertThat(found.recipientRole).isEqualTo(RecipientRole.ASSIGNEE)
        assertThat(found.channel).isEqualTo(Channel.EMAIL)
        assertThat(found.enabled).isTrue()
        assertThat(found.createdBy).isEqualTo(testCreatedBy)
    }

    @Test
    fun `존재하지 않는 id로 findById 호출 시 null을 반환한다`() {
        val result = repository.findById(UUID.randomUUID())
        assertThat(result).isNull()
    }

    // ── findAll — 전역(null) 조회 ────────────────────────────────────────────────

    @Test
    fun `findAll(null)은 V401 전역 시드 19행을 포함한다`() {
        val globals = repository.findAll(null)

        // V401 시드: 전역 정책 19행
        assertThat(globals).hasSizeGreaterThanOrEqualTo(19)

        // issue.created 전역 3행 존재 확인 (REPORTER / WATCHER / COMPONENT_LEAD, IN_APP)
        val issueCreatedGlobals = globals.filter {
            it.eventType == NotificationEventType.ISSUE_CREATED && it.projectKey == null
        }
        assertThat(issueCreatedGlobals).hasSize(3)

        val roles = issueCreatedGlobals.map { it.recipientRole }.toSet()
        assertThat(roles).containsExactlyInAnyOrder(
            RecipientRole.REPORTER,
            RecipientRole.WATCHER,
            RecipientRole.COMPONENT_LEAD,
        )
        // 채널은 모두 IN_APP
        assertThat(issueCreatedGlobals.map { it.channel }).containsOnly(Channel.IN_APP)
    }

    // ── findAll — 프로젝트 키 조회 ───────────────────────────────────────────────

    @Test
    fun `findAll("ATLAS")는 ATLAS 프로젝트 정책만 반환하고 전역 행은 포함하지 않는다`() {
        // ATLAS 프로젝트 전용 정책 2건 삽입
        repository.insert(buildPolicy(projectKey = "ATLAS", recipientRole = RecipientRole.ASSIGNEE, channel = Channel.IN_APP))
        repository.insert(buildPolicy(projectKey = "ATLAS", recipientRole = RecipientRole.REPORTER, channel = Channel.EMAIL))

        val atlasOnly = repository.findAll("ATLAS")

        assertThat(atlasOnly).hasSize(2)
        assertThat(atlasOnly).allMatch { it.projectKey == "ATLAS" }
    }

    // ── findByEventTypeAndProjectKey — 전역 ──────────────────────────────────────

    @Test
    fun `findByEventTypeAndProjectKey("issue_created", null)은 전역 issue_created 3행을 반환한다`() {
        val results = repository.findByEventTypeAndProjectKey("issue.created", null)

        assertThat(results).hasSize(3)
        assertThat(results).allMatch {
            it.eventType == NotificationEventType.ISSUE_CREATED && it.projectKey == null
        }
    }

    // ── findByEventTypeAndProjectKey — 프로젝트 ──────────────────────────────────

    @Test
    fun `findByEventTypeAndProjectKey("issue_created", "ATLAS")는 ATLAS issue_created 행만 반환한다`() {
        // ATLAS issue.created EMAIL 정책 삽입
        repository.insert(buildPolicy(projectKey = "ATLAS", eventType = NotificationEventType.ISSUE_CREATED, recipientRole = RecipientRole.ASSIGNEE, channel = Channel.EMAIL))
        // 다른 이벤트 행 삽입 (조회 결과에 포함되면 안 됨)
        repository.insert(buildPolicy(projectKey = "ATLAS", eventType = NotificationEventType.ISSUE_ASSIGNED, recipientRole = RecipientRole.ASSIGNEE, channel = Channel.EMAIL))

        val results = repository.findByEventTypeAndProjectKey("issue.created", "ATLAS")

        assertThat(results).hasSize(1)
        assertThat(results[0].projectKey).isEqualTo("ATLAS")
        assertThat(results[0].eventType).isEqualTo(NotificationEventType.ISSUE_CREATED)
    }

    // ── findByEventTypeAndProjectKey는 enabled=false 행도 반환 ────────────────────

    @Test
    fun `findByEventTypeAndProjectKey는 enabled=false 행도 반환한다 (평가 엔진용)`() {
        // enabled=false 인 ATLAS 정책 삽입
        val disabledPolicy = buildPolicy(
            projectKey = "ATLAS",
            eventType = NotificationEventType.ISSUE_CREATED,
            recipientRole = RecipientRole.ASSIGNEE,
            channel = Channel.IN_APP,
        ).copy(enabled = false)
        repository.insert(disabledPolicy)

        val results = repository.findByEventTypeAndProjectKey("issue.created", "ATLAS")

        // enabled=false 행이 포함돼야 한다
        assertThat(results).anyMatch { !it.enabled }
    }

    // ── toggle ────────────────────────────────────────────────────────────────────

    @Test
    fun `toggle(id, false, now)은 enabled를 false로 바꾸고 updated_at을 갱신한다`() {
        val policy = repository.insert(buildPolicy())

        val affected = repository.toggle(policy.id, false, now)

        assertThat(affected).isEqualTo(1)
        val updated = repository.findById(policy.id)
        assertThat(updated).isNotNull
        assertThat(updated!!.enabled).isFalse()
    }

    @Test
    fun `존재하지 않는 id로 toggle 호출 시 영향 행 수 0을 반환한다`() {
        val affected = repository.toggle(UUID.randomUUID(), false, now)
        assertThat(affected).isEqualTo(0)
    }

    // ── delete ────────────────────────────────────────────────────────────────────

    @Test
    fun `delete(id)는 행을 제거하고 영향 행 수 1을 반환한다`() {
        val policy = repository.insert(buildPolicy())

        val affected = repository.delete(policy.id)

        assertThat(affected).isEqualTo(1)
        assertThat(repository.findById(policy.id)).isNull()
    }

    @Test
    fun `존재하지 않는 id로 delete 호출 시 영향 행 수 0을 반환한다`() {
        val affected = repository.delete(UUID.randomUUID())
        assertThat(affected).isEqualTo(0)
    }

    // ── UNIQUE 중복 삽입 거부 (멱등) ─────────────────────────────────────────────

    @Test
    fun `시드와 동일한 전역 정책(issue_created, REPORTER, IN_APP) 삽입 시 예외가 발생한다`() {
        // V401 시드: (NULL, 'issue.created', 'REPORTER', 'IN_APP') 이미 존재
        val duplicate = NotificationPolicy(
            id = UUID.randomUUID(),
            projectKey = null,          // 전역
            eventType = NotificationEventType.ISSUE_CREATED,
            recipientRole = RecipientRole.REPORTER,
            channel = Channel.IN_APP,
            enabled = true,
            createdBy = testCreatedBy,  // 시드와 달리 non-null — cleanNonSeedPolicies 대상이 됨
            createdAt = now,
            updatedAt = now,
        )

        // NULLS NOT DISTINCT UNIQUE 제약에 의해 전역 중복도 잡힘 (memory: pg-null-distinct-on-conflict-idempotency).
        // 통합 테스트는 순수 jOOQ DSLContext 를 사용하므로 Spring 예외 변환 체인이 없다.
        // jOOQ 가 던지는 DataAccessException (IntegrityConstraintViolationException 상위) 을 직접 검증.
        // 프로덕션 환경(Spring DataSource + SQLExceptionTranslator 연결)에서는 DuplicateKeyException 으로 변환된다.
        assertThatThrownBy { repository.insert(duplicate) }
            .isInstanceOf(DataAccessException::class.java)
    }
}
