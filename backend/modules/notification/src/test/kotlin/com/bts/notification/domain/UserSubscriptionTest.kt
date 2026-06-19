// UserSubscription 도메인 단위 테스트 — 생성·withEnabled·CONFIGURABLE_CHANNELS·isConfigurable 불변식 검증

package com.bts.notification.domain

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.Instant
import java.util.UUID

class UserSubscriptionTest : DescribeSpec({

    val fixedNow: Instant = Instant.parse("2026-06-19T00:00:00Z")
    val laterNow: Instant = Instant.parse("2026-06-19T01:00:00Z")

    val sampleUserId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

    fun buildSubscription(
        channel: Channel = Channel.IN_APP,
        enabled: Boolean = true,
    ): UserSubscription =
        UserSubscription(
            userId = sampleUserId,
            eventType = NotificationEventType.ISSUE_CREATED,
            channel = channel,
            enabled = enabled,
            createdAt = fixedNow,
            updatedAt = fixedNow,
        )

    describe("UserSubscription 생성") {
        it("모든 필드가 주어진 값으로 초기화된다") {
            val sub = buildSubscription()
            sub.userId shouldBe sampleUserId
            sub.eventType shouldBe NotificationEventType.ISSUE_CREATED
            sub.channel shouldBe Channel.IN_APP
            sub.enabled shouldBe true
            sub.createdAt shouldBe fixedNow
            sub.updatedAt shouldBe fixedNow
        }
    }

    describe("withEnabled()") {
        it("enabled=true → withEnabled(false) 시 enabled 가 false 로 변경된다") {
            val sub = buildSubscription(enabled = true)
            val updated = sub.withEnabled(newEnabled = false, now = laterNow)
            updated.enabled shouldBe false
        }

        it("enabled=false → withEnabled(true) 시 enabled 가 true 로 변경된다") {
            val sub = buildSubscription(enabled = false)
            val updated = sub.withEnabled(newEnabled = true, now = laterNow)
            updated.enabled shouldBe true
        }

        it("withEnabled 후 updatedAt 이 now 파라미터로 갱신된다") {
            val sub = buildSubscription()
            val updated = sub.withEnabled(newEnabled = false, now = laterNow)
            updated.updatedAt shouldBe laterNow
        }

        it("withEnabled 는 원본 인스턴스를 변경하지 않는다 — 불변 copy 반환") {
            val sub = buildSubscription(enabled = true)
            val updated = sub.withEnabled(newEnabled = false, now = laterNow)
            sub.enabled shouldBe true
            sub.updatedAt shouldBe fixedNow
            updated shouldNotBe sub
        }

        it("withEnabled 후 enabled·updatedAt 외 나머지 필드는 보존된다") {
            val sub = buildSubscription()
            val updated = sub.withEnabled(newEnabled = false, now = laterNow)
            updated.userId shouldBe sub.userId
            updated.eventType shouldBe sub.eventType
            updated.channel shouldBe sub.channel
            updated.createdAt shouldBe sub.createdAt
        }
    }

    describe("CONFIGURABLE_CHANNELS") {
        it("IN_APP 과 EMAIL 두 채널만 포함한다") {
            UserSubscription.CONFIGURABLE_CHANNELS shouldBe setOf(Channel.IN_APP, Channel.EMAIL)
        }

        it("SLACK 은 포함하지 않는다") {
            (Channel.SLACK in UserSubscription.CONFIGURABLE_CHANNELS) shouldBe false
        }

        it("TEAMS 는 포함하지 않는다") {
            (Channel.TEAMS in UserSubscription.CONFIGURABLE_CHANNELS) shouldBe false
        }

        it("WEBHOOK 은 포함하지 않는다") {
            (Channel.WEBHOOK in UserSubscription.CONFIGURABLE_CHANNELS) shouldBe false
        }
    }

    describe("isConfigurable()") {
        it("IN_APP 채널은 설정 가능하다") {
            UserSubscription.isConfigurable(Channel.IN_APP) shouldBe true
        }

        it("EMAIL 채널은 설정 가능하다") {
            UserSubscription.isConfigurable(Channel.EMAIL) shouldBe true
        }

        it("SLACK 채널은 설정 불가하다") {
            UserSubscription.isConfigurable(Channel.SLACK) shouldBe false
        }

        it("TEAMS 채널은 설정 불가하다") {
            UserSubscription.isConfigurable(Channel.TEAMS) shouldBe false
        }

        it("WEBHOOK 채널은 설정 불가하다") {
            UserSubscription.isConfigurable(Channel.WEBHOOK) shouldBe false
        }
    }
})
