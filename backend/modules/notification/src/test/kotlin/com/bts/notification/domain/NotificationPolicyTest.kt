// NotificationPolicy 도메인 Aggregate 단위 테스트 — 생성·isGlobal·toggle 불변식 검증

package com.bts.notification.domain

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.Instant
import java.util.UUID

class NotificationPolicyTest : DescribeSpec({

    val fixedNow: Instant = Instant.parse("2026-06-11T00:00:00Z")
    val laterNow: Instant = Instant.parse("2026-06-11T01:00:00Z")

    val sampleId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    val sampleProjectId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000002")
    val sampleCreatedBy: UUID = UUID.fromString("00000000-0000-0000-0000-000000000003")

    fun buildPolicy(
        projectId: UUID? = sampleProjectId,
        enabled: Boolean = true,
    ): NotificationPolicy =
        NotificationPolicy(
            id = sampleId,
            projectId = projectId,
            eventType = NotificationEventType.ISSUE_CREATED,
            recipientRole = RecipientRole.ASSIGNEE,
            channel = Channel.IN_APP,
            enabled = enabled,
            createdBy = sampleCreatedBy,
            createdAt = fixedNow,
            updatedAt = fixedNow,
        )

    describe("NotificationPolicy 생성") {
        it("모든 필드가 주어진 값으로 초기화된다") {
            val policy = buildPolicy()
            policy.id shouldBe sampleId
            policy.projectId shouldBe sampleProjectId
            policy.eventType shouldBe NotificationEventType.ISSUE_CREATED
            policy.recipientRole shouldBe RecipientRole.ASSIGNEE
            policy.channel shouldBe Channel.IN_APP
            policy.enabled shouldBe true
            policy.createdBy shouldBe sampleCreatedBy
            policy.createdAt shouldBe fixedNow
            policy.updatedAt shouldBe fixedNow
        }
    }

    describe("isGlobal()") {
        it("projectId 가 null 이면 전역 정책이다") {
            val global = buildPolicy(projectId = null)
            global.isGlobal() shouldBe true
        }

        it("projectId 가 non-null 이면 프로젝트 전용 정책이다") {
            val projectScoped = buildPolicy(projectId = sampleProjectId)
            projectScoped.isGlobal() shouldBe false
        }
    }

    describe("toggle()") {
        it("enabled=true → toggle(false) 시 enabled 가 false 로 변경된다") {
            val policy = buildPolicy(enabled = true)
            val toggled = policy.toggle(newEnabled = false, now = laterNow)
            toggled.enabled shouldBe false
        }

        it("enabled=false → toggle(true) 시 enabled 가 true 로 변경된다") {
            val policy = buildPolicy(enabled = false)
            val toggled = policy.toggle(newEnabled = true, now = laterNow)
            toggled.enabled shouldBe true
        }

        it("toggle 후 updatedAt 이 now 파라미터로 갱신된다") {
            val policy = buildPolicy()
            val toggled = policy.toggle(newEnabled = false, now = laterNow)
            toggled.updatedAt shouldBe laterNow
        }

        it("toggle 은 원본 policy 를 변경하지 않는다 — 불변 copy 반환") {
            val policy = buildPolicy(enabled = true)
            val toggled = policy.toggle(newEnabled = false, now = laterNow)
            // 원본 불변 확인
            policy.enabled shouldBe true
            policy.updatedAt shouldBe fixedNow
            // copy 는 별개 객체
            toggled shouldNotBe policy
        }

        it("toggle 후 enabled·updatedAt 외 나머지 필드는 보존된다") {
            val policy = buildPolicy()
            val toggled = policy.toggle(newEnabled = false, now = laterNow)
            toggled.id shouldBe policy.id
            toggled.projectId shouldBe policy.projectId
            toggled.eventType shouldBe policy.eventType
            toggled.recipientRole shouldBe policy.recipientRole
            toggled.channel shouldBe policy.channel
            toggled.createdBy shouldBe policy.createdBy
            toggled.createdAt shouldBe policy.createdAt
        }
    }
})
