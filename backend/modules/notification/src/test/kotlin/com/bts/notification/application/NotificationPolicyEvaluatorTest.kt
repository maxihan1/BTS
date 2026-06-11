// NotificationPolicyEvaluator 단위 테스트 — projectKey 우선·replace 방식 평가 분기 전체 커버

package com.bts.notification.application

import com.bts.notification.domain.Channel
import com.bts.notification.domain.NotificationEventType
import com.bts.notification.domain.NotificationPolicy
import com.bts.notification.domain.RecipientRole
import com.bts.notification.repository.NotificationPolicyRepository
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.util.UUID

class NotificationPolicyEvaluatorTest : DescribeSpec({

    val repository: NotificationPolicyRepository = mockk()
    val evaluator = NotificationPolicyEvaluator(repository)

    // 각 테스트 전에 mock 호출 기록과 stub을 초기화해 이전 테스트 오염을 방지한다.
    beforeEach { clearMocks(repository) }

    val fixedNow: Instant = Instant.parse("2026-06-11T00:00:00Z")
    val eventType = NotificationEventType.ISSUE_CREATED
    val wireValue = eventType.wireValue

    /**
     * 테스트용 NotificationPolicy 빌더.
     * projectKey·recipientRole·channel·enabled 만 변경해 사용한다.
     */
    fun buildPolicy(
        projectKey: String?,
        recipientRole: RecipientRole = RecipientRole.ASSIGNEE,
        channel: Channel = Channel.IN_APP,
        enabled: Boolean = true,
    ): NotificationPolicy = NotificationPolicy(
        id = UUID.randomUUID(),
        projectKey = projectKey,
        eventType = eventType,
        recipientRole = recipientRole,
        channel = channel,
        enabled = enabled,
        createdBy = null,
        createdAt = fixedNow,
        updatedAt = fixedNow,
    )

    describe("projectKey == null 일 때") {
        it("전역 활성 정책만 PolicyMatch 로 반환한다") {
            val globalActive = buildPolicy(projectKey = null, recipientRole = RecipientRole.REPORTER, channel = Channel.EMAIL)
            val globalDisabled = buildPolicy(projectKey = null, recipientRole = RecipientRole.ASSIGNEE, channel = Channel.SLACK, enabled = false)

            every { repository.findByEventTypeAndProjectKey(wireValue, null) } returns listOf(globalActive, globalDisabled)

            val result = evaluator.evaluate(eventType, null)

            result shouldHaveSize 1
            result[0].recipientRole shouldBe RecipientRole.REPORTER
            result[0].channel shouldBe Channel.EMAIL
        }

        it("전역 정책이 전혀 없으면 빈 목록을 반환한다") {
            every { repository.findByEventTypeAndProjectKey(wireValue, null) } returns emptyList()

            val result = evaluator.evaluate(eventType, null)

            result.shouldBeEmpty()
        }
    }

    describe("projectKey != null 이고 프로젝트가 해당 event_type 정책을 보유할 때") {
        it("프로젝트 활성 정책만 반환하고, 전역 조회는 하지 않는다") {
            val projectActive = buildPolicy(projectKey = "ATLAS", recipientRole = RecipientRole.WATCHER, channel = Channel.IN_APP)
            val projectDisabled = buildPolicy(projectKey = "ATLAS", recipientRole = RecipientRole.PROJECT_MEMBER, channel = Channel.EMAIL, enabled = false)

            every { repository.findByEventTypeAndProjectKey(wireValue, "ATLAS") } returns listOf(projectActive, projectDisabled)

            val result = evaluator.evaluate(eventType, "ATLAS")

            result shouldHaveSize 1
            result[0].recipientRole shouldBe RecipientRole.WATCHER
            result[0].channel shouldBe Channel.IN_APP

            // 전역 조회가 호출되지 않았음을 검증 (replace — 전역 무시)
            verify(exactly = 0) { repository.findByEventTypeAndProjectKey(wireValue, null) }
        }

        it("프로젝트 정책이 존재하나 전부 enabled=false 면 빈 목록을 반환한다 — 전역 fallback 없음") {
            val projectDisabled1 = buildPolicy(projectKey = "ATLAS", recipientRole = RecipientRole.REPORTER, channel = Channel.EMAIL, enabled = false)
            val projectDisabled2 = buildPolicy(projectKey = "ATLAS", recipientRole = RecipientRole.ASSIGNEE, channel = Channel.SLACK, enabled = false)

            every { repository.findByEventTypeAndProjectKey(wireValue, "ATLAS") } returns listOf(projectDisabled1, projectDisabled2)

            val result = evaluator.evaluate(eventType, "ATLAS")

            result.shouldBeEmpty()

            // 전부 disabled여도 전역 fallback 조회 금지
            verify(exactly = 0) { repository.findByEventTypeAndProjectKey(wireValue, null) }
        }
    }

    describe("projectKey != null 이고 프로젝트가 해당 event_type 행이 0개일 때") {
        it("전역 활성 정책으로 폴백한다") {
            val globalActive = buildPolicy(projectKey = null, recipientRole = RecipientRole.PROJECT_ADMIN, channel = Channel.WEBHOOK)

            every { repository.findByEventTypeAndProjectKey(wireValue, "ATLAS") } returns emptyList()
            every { repository.findByEventTypeAndProjectKey(wireValue, null) } returns listOf(globalActive)

            val result = evaluator.evaluate(eventType, "ATLAS")

            result shouldHaveSize 1
            result[0].recipientRole shouldBe RecipientRole.PROJECT_ADMIN
            result[0].channel shouldBe Channel.WEBHOOK
        }

        it("전역 폴백에도 정책이 없으면 빈 목록을 반환한다") {
            every { repository.findByEventTypeAndProjectKey(wireValue, "ATLAS") } returns emptyList()
            every { repository.findByEventTypeAndProjectKey(wireValue, null) } returns emptyList()

            val result = evaluator.evaluate(eventType, "ATLAS")

            result.shouldBeEmpty()
        }
    }
})
