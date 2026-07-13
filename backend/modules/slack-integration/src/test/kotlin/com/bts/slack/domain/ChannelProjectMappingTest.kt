// ChannelProjectMapping 도메인 VO 검증 단위 테스트 — eventTypes 빈 집합/미지 wire 거부, 정상값 보존 (FR-SL-06 Task 2)

package com.bts.slack.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.UUID

/**
 * [ChannelProjectMapping] 도메인 VO 단위 테스트.
 *
 * 검증 범위.
 * - eventTypes 가 빈 집합이면 `IllegalArgumentException`.
 * - eventTypes 에 [SlackChannelEventType] 카탈로그에 없는 wire 문자열이 섞이면 `IllegalArgumentException`.
 * - 정상 입력이면 모든 필드가 그대로 보존된다.
 */
class ChannelProjectMappingTest : DescribeSpec({

    val id = UUID.randomUUID()
    val now = Instant.parse("2026-07-13T00:00:00Z")

    fun mapping(eventTypes: Set<String>) =
        ChannelProjectMapping(
            id = id,
            teamId = "T0TEAM",
            projectKey = "PROJ",
            channelId = "C123",
            channelName = "general",
            eventTypes = eventTypes,
            createdAt = now,
            updatedAt = now,
        )

    describe("ChannelProjectMapping — eventTypes 빈 집합 거부") {
        it("eventTypes 가 비어 있으면 IllegalArgumentException") {
            shouldThrow<IllegalArgumentException> {
                mapping(emptySet())
            }
        }
    }

    describe("ChannelProjectMapping — 미지 wire 문자열 거부") {
        it("SlackChannelEventType 카탈로그에 없는 값이 섞이면 IllegalArgumentException") {
            shouldThrow<IllegalArgumentException> {
                mapping(setOf("issue.created", "foo.bar"))
            }
        }
    }

    describe("ChannelProjectMapping — 정상값 보존") {
        it("유효한 eventTypes 로 생성하면 모든 필드가 그대로 보존된다") {
            val result = mapping(setOf("issue.created", "issue.transitioned"))
            result.id shouldBe id
            result.teamId shouldBe "T0TEAM"
            result.projectKey shouldBe "PROJ"
            result.channelId shouldBe "C123"
            result.channelName shouldBe "general"
            result.eventTypes shouldBe setOf("issue.created", "issue.transitioned")
            result.createdAt shouldBe now
            result.updatedAt shouldBe now
        }
    }
})
