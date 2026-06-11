// NotificationPolicyService 단위 테스트 — 권한 게이트, CRUD 위임, 예외 변환 시나리오 전체 커버

package com.bts.notification.application

import com.bts.notification.domain.Channel
import com.bts.notification.domain.NotificationEventType
import com.bts.notification.domain.NotificationPolicy
import com.bts.notification.domain.RecipientRole
import com.bts.notification.repository.NotificationPolicyRepository
import com.bts.shared.permission.SystemPermissionResolver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.dao.DuplicateKeyException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class NotificationPolicyServiceTest : DescribeSpec({

    val repository: NotificationPolicyRepository = mockk()
    val permissionResolver: SystemPermissionResolver = mockk()

    // 고정 시각 주입 — AuthController revokeSession time-bomb 교훈 (Clock 의존성 주입)
    val fixedInstant: Instant =
        Instant.parse("2026-06-11T12:00:00Z")
    val fixedClock: Clock =
        Clock.fixed(fixedInstant, ZoneOffset.UTC)

    val service =
        NotificationPolicyService(
            repository = repository,
            systemPermissionResolver = permissionResolver,
            clock = fixedClock,
        )

    val adminActorId: UUID = UUID.randomUUID()
    val nonAdminActorId: UUID = UUID.randomUUID()

    /** 테스트용 NotificationPolicy 빌더 — 모든 필드를 선택적으로 커스터마이즈할 수 있도록 다인자, 기본값 제공 */
    @Suppress("LongParameterList") // 테스트 빌더: 각 파라미터가 독립 시나리오 축, 기본값 제공으로 호출부 간결
    fun buildPolicy(
        id: UUID = UUID.randomUUID(),
        projectKey: String? = null,
        eventType: NotificationEventType = NotificationEventType.ISSUE_CREATED,
        recipientRole: RecipientRole = RecipientRole.REPORTER,
        channel: Channel = Channel.IN_APP,
        enabled: Boolean = true,
        createdBy: UUID? = adminActorId,
    ): NotificationPolicy =
        NotificationPolicy(
            id = id,
            projectKey = projectKey,
            eventType = eventType,
            recipientRole = recipientRole,
            channel = channel,
            enabled = enabled,
            createdBy = createdBy,
            createdAt = fixedInstant,
            updatedAt = fixedInstant,
        )

    beforeEach {
        every { permissionResolver.isSystemAdmin(adminActorId) } returns true
        every { permissionResolver.isSystemAdmin(nonAdminActorId) } returns false
    }

    describe("권한 게이트 — 비-admin 이면 모든 메서드에서 NotificationPolicyForbiddenException") {

        it("create: 비-admin → ForbiddenException, repository 호출 없음") {
            shouldThrow<NotificationPolicyForbiddenException> {
                service.create(
                    actorId = nonAdminActorId,
                    projectKey = null,
                    eventType = NotificationEventType.ISSUE_CREATED,
                    recipientRole = RecipientRole.REPORTER,
                    channel = Channel.IN_APP,
                    enabled = true,
                )
            }
            verify(exactly = 0) { repository.insert(any()) }
        }

        it("list: 비-admin → ForbiddenException, repository 호출 없음") {
            shouldThrow<NotificationPolicyForbiddenException> {
                service.list(actorId = nonAdminActorId, projectKey = null)
            }
            verify(exactly = 0) { repository.findAll(any()) }
        }

        it("toggle: 비-admin → ForbiddenException, repository 호출 없음") {
            shouldThrow<NotificationPolicyForbiddenException> {
                service.toggle(actorId = nonAdminActorId, id = UUID.randomUUID(), enabled = false)
            }
            verify(exactly = 0) { repository.toggle(any(), any(), any()) }
        }

        it("delete: 비-admin → ForbiddenException, repository 호출 없음") {
            shouldThrow<NotificationPolicyForbiddenException> {
                service.delete(actorId = nonAdminActorId, id = UUID.randomUUID())
            }
            verify(exactly = 0) { repository.delete(any()) }
        }
    }

    describe("create — admin 성공 경로") {

        it("repository.insert 호출, createdBy=actorId, 시각=고정 Clock 확인") {
            val savedPolicy = buildPolicy()
            every { repository.insert(any()) } returns savedPolicy

            val result =
                service.create(
                    actorId = adminActorId,
                    projectKey = null,
                    eventType = NotificationEventType.ISSUE_CREATED,
                    recipientRole = RecipientRole.REPORTER,
                    channel = Channel.IN_APP,
                    enabled = true,
                )

            result shouldBe savedPolicy
            verify(exactly = 1) {
                repository.insert(
                    match { policy ->
                        policy.createdBy == adminActorId &&
                            policy.createdAt == fixedInstant &&
                            policy.updatedAt == fixedInstant &&
                            policy.eventType == NotificationEventType.ISSUE_CREATED &&
                            policy.recipientRole == RecipientRole.REPORTER &&
                            policy.channel == Channel.IN_APP &&
                            policy.enabled
                    },
                )
            }
        }

        it("프로젝트 전용 정책 생성 — projectKey 전달 확인") {
            val savedPolicy = buildPolicy(projectKey = "PROJ-A")
            every { repository.insert(any()) } returns savedPolicy

            val result =
                service.create(
                    actorId = adminActorId,
                    projectKey = "PROJ-A",
                    eventType = NotificationEventType.ISSUE_ASSIGNED,
                    recipientRole = RecipientRole.ASSIGNEE,
                    channel = Channel.EMAIL,
                    enabled = false,
                )

            result shouldBe savedPolicy
            verify(exactly = 1) {
                repository.insert(match { it.projectKey == "PROJ-A" })
            }
        }
    }

    describe("create — DuplicateKeyException → NotificationPolicyDuplicateException 변환") {

        it("repository 가 DuplicateKeyException 를 던지면 NotificationPolicyDuplicateException 로 변환") {
            every { repository.insert(any()) } throws DuplicateKeyException("unique constraint violation")

            shouldThrow<NotificationPolicyDuplicateException> {
                service.create(
                    actorId = adminActorId,
                    projectKey = null,
                    eventType = NotificationEventType.ISSUE_CREATED,
                    recipientRole = RecipientRole.REPORTER,
                    channel = Channel.IN_APP,
                    enabled = true,
                )
            }
        }
    }

    describe("create — jOOQ native DataAccessException (Spring 변환기 비활성 컨텍스트) 경로") {

        it("SQLState 23505(unique_violation) → NotificationPolicyDuplicateException 변환") {
            val sqlEx = java.sql.SQLException("duplicate key value", "23505")
            every { repository.insert(any()) } throws
                org.jooq.exception.DataAccessException("unique violation", sqlEx)

            shouldThrow<NotificationPolicyDuplicateException> {
                service.create(
                    actorId = adminActorId,
                    projectKey = null,
                    eventType = NotificationEventType.ISSUE_CREATED,
                    recipientRole = RecipientRole.REPORTER,
                    channel = Channel.IN_APP,
                    enabled = true,
                )
            }
        }

        it("SQLState 23505 아님 → 원본 DataAccessException 그대로 전파(중복으로 오판하지 않음)") {
            val sqlEx = java.sql.SQLException("connection failure", "08006")
            every { repository.insert(any()) } throws
                org.jooq.exception.DataAccessException("connection", sqlEx)

            shouldThrow<org.jooq.exception.DataAccessException> {
                service.create(
                    actorId = adminActorId,
                    projectKey = null,
                    eventType = NotificationEventType.ISSUE_CREATED,
                    recipientRole = RecipientRole.REPORTER,
                    channel = Channel.IN_APP,
                    enabled = true,
                )
            }
        }
    }

    describe("list — admin 위임") {

        it("repository.findAll(projectKey) 결과를 그대로 반환") {
            val policies = listOf(buildPolicy(), buildPolicy(projectKey = null))
            every { repository.findAll(null) } returns policies

            val result = service.list(actorId = adminActorId, projectKey = null)

            result shouldBe policies
            verify(exactly = 1) { repository.findAll(null) }
        }

        it("projectKey 를 그대로 위임") {
            val policies = listOf(buildPolicy(projectKey = "PROJ-B"))
            every { repository.findAll("PROJ-B") } returns policies

            val result = service.list(actorId = adminActorId, projectKey = "PROJ-B")

            result shouldBe policies
            verify(exactly = 1) { repository.findAll("PROJ-B") }
        }
    }

    describe("toggle — 영향 행 0 이면 NotificationPolicyNotFoundException") {

        it("repository.toggle 가 0 반환 → NotFoundException") {
            val targetId = UUID.randomUUID()
            every { repository.toggle(targetId, false, fixedInstant) } returns 0

            shouldThrow<NotificationPolicyNotFoundException> {
                service.toggle(actorId = adminActorId, id = targetId, enabled = false)
            }
        }

        it("영향 행 1 이상 → 정상 완료") {
            val targetId = UUID.randomUUID()
            every { repository.toggle(targetId, true, fixedInstant) } returns 1

            service.toggle(actorId = adminActorId, id = targetId, enabled = true)

            verify(exactly = 1) { repository.toggle(targetId, true, fixedInstant) }
        }
    }

    describe("delete — 영향 행 0 이면 NotificationPolicyNotFoundException") {

        it("repository.delete 가 0 반환 → NotFoundException") {
            val targetId = UUID.randomUUID()
            every { repository.delete(targetId) } returns 0

            shouldThrow<NotificationPolicyNotFoundException> {
                service.delete(actorId = adminActorId, id = targetId)
            }
        }

        it("영향 행 1 이상 → 정상 완료") {
            val targetId = UUID.randomUUID()
            every { repository.delete(targetId) } returns 1

            service.delete(actorId = adminActorId, id = targetId)

            verify(exactly = 1) { repository.delete(targetId) }
        }
    }
})
