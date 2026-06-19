// IssueDomainEvent Jackson 직렬화/역직렬화 round-trip + @JsonTypeInfo 타입 표기 검증

package com.bts.issue.event

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IssueKey
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.Instant
import java.util.UUID

class IssueDomainEventTest : DescribeSpec({

    val mapper =
        ObjectMapper()
            .registerKotlinModule()
            .registerModule(JavaTimeModule())

    val issueKey = IssueKey("ATLAS-1")
    val actorId = ActorId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
    val now = Instant.parse("2026-01-01T00:00:00Z")

    describe("IssueCreated") {
        val event =
            IssueCreated(
                issueKey = issueKey,
                projectKey = "ATLAS",
                summary = "첫 번째 이슈",
                reporterId = actorId,
                actorId = actorId,
                occurredAt = now,
            )

        it("직렬화 시 type 필드가 'issue.created' 로 포함된다") {
            val json = mapper.writeValueAsString(event)
            json shouldContain "\"type\":\"issue.created\""
        }

        it("round-trip: IssueDomainEvent 로 역직렬화하면 원본과 동일하다") {
            val json = mapper.writeValueAsString(event)
            val restored = mapper.readValue(json, IssueDomainEvent::class.java)
            restored shouldBe event
        }
    }

    describe("IssueUpdated") {
        val event =
            IssueUpdated(
                issueKey = issueKey,
                fields = setOf("summary", "description"),
                occurredAt = now,
            )

        it("직렬화 시 type 필드가 'issue.updated' 로 포함된다") {
            val json = mapper.writeValueAsString(event)
            json shouldContain "\"type\":\"issue.updated\""
        }

        it("round-trip: IssueDomainEvent 로 역직렬화하면 원본과 동일하다") {
            val json = mapper.writeValueAsString(event)
            val restored = mapper.readValue(json, IssueDomainEvent::class.java)
            restored shouldBe event
        }
    }

    describe("IssueTransitioned") {
        val event =
            IssueTransitioned(
                issueKey = issueKey,
                fromState = "open",
                toState = "IN_PROGRESS",
                actorId = actorId,
                occurredAt = now,
            )

        it("직렬화 시 type 필드가 'issue.transitioned' 로 포함된다") {
            val json = mapper.writeValueAsString(event)
            json shouldContain "\"type\":\"issue.transitioned\""
        }

        it("round-trip: IssueDomainEvent 로 역직렬화하면 원본과 동일하다") {
            val json = mapper.writeValueAsString(event)
            val restored = mapper.readValue(json, IssueDomainEvent::class.java)
            restored shouldBe event
        }
    }

    describe("IssueSoftDeleted") {
        val event =
            IssueSoftDeleted(
                issueKey = issueKey,
                occurredAt = now,
            )

        it("직렬화 시 type 필드가 'issue.soft_deleted' 로 포함된다") {
            val json = mapper.writeValueAsString(event)
            json shouldContain "\"type\":\"issue.soft_deleted\""
        }

        it("round-trip: IssueDomainEvent 로 역직렬화하면 원본과 동일하다") {
            val json = mapper.writeValueAsString(event)
            val restored = mapper.readValue(json, IssueDomainEvent::class.java)
            restored shouldBe event
        }
    }

    describe("IssueMentioned") {
        val bobId = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val carolId = UUID.fromString("33333333-3333-3333-3333-333333333333")
        val event =
            IssueMentioned(
                issueKey = issueKey,
                projectKey = "ATLAS",
                mentionedUserIds = listOf(bobId, carolId),
                actorId = actorId,
                sourceField = "description",
                occurredAt = now,
            )

        it("직렬화 시 type 필드가 'issue.mentioned' 로 포함된다") {
            val json = mapper.writeValueAsString(event)
            json shouldContain "\"type\":\"issue.mentioned\""
        }

        it("round-trip: IssueDomainEvent 로 역직렬화하면 원본과 동일하다") {
            val json = mapper.writeValueAsString(event)
            val restored = mapper.readValue(json, IssueDomainEvent::class.java)
            restored shouldBe event
        }

        it("mentionedUserIds 순서가 역직렬화 후에도 보존된다") {
            val json = mapper.writeValueAsString(event)
            val restored = mapper.readValue(json, IssueDomainEvent::class.java) as IssueMentioned
            restored.mentionedUserIds shouldBe listOf(bobId, carolId)
        }
    }

    describe("IssueDueSoon") {
        val event =
            IssueDueSoon(
                issueKey = "PROJ-1",
                projectKey = "PROJ",
                occurredAt = now,
            )

        it("직렬화 시 type 필드가 'issue.due_soon' 으로 포함된다") {
            val json = mapper.writeValueAsString(event)
            json shouldContain "\"type\":\"issue.due_soon\""
        }

        it("round-trip: IssueDomainEvent 로 역직렬화하면 원본과 동일하다") {
            val json = mapper.writeValueAsString(event)
            val restored = mapper.readValue(json, IssueDomainEvent::class.java)
            restored shouldBe event
        }
    }

    describe("IssueOverdue") {
        val event =
            IssueOverdue(
                issueKey = "PROJ-1",
                projectKey = "PROJ",
                occurredAt = now,
            )

        it("직렬화 시 type 필드가 'issue.overdue' 로 포함된다") {
            val json = mapper.writeValueAsString(event)
            json shouldContain "\"type\":\"issue.overdue\""
        }

        it("round-trip: IssueDomainEvent 로 역직렬화하면 원본과 동일하다") {
            val json = mapper.writeValueAsString(event)
            val restored = mapper.readValue(json, IssueDomainEvent::class.java)
            restored shouldBe event
        }
    }

    describe("다형성 역직렬화") {
        it("type=issue.created JSON 을 IssueDomainEvent 로 읽으면 IssueCreated 인스턴스다") {
            val json =
                """
                {
                  "type": "issue.created",
                  "issueKey": "ATLAS-1",
                  "projectKey": "ATLAS",
                  "summary": "첫 번째 이슈",
                  "reporterId": {"value": "11111111-1111-1111-1111-111111111111"},
                  "actorId": {"value": "11111111-1111-1111-1111-111111111111"},
                  "occurredAt": "2026-01-01T00:00:00Z"
                }
                """.trimIndent()
            val event = mapper.readValue(json, IssueDomainEvent::class.java)
            (event is IssueCreated) shouldBe true
        }

        it("type=issue.updated JSON 을 IssueDomainEvent 로 읽으면 IssueUpdated 인스턴스다") {
            val json =
                """
                {
                  "type": "issue.updated",
                  "issueKey": "ATLAS-1",
                  "fields": ["summary"],
                  "occurredAt": "2026-01-01T00:00:00Z"
                }
                """.trimIndent()
            val event = mapper.readValue(json, IssueDomainEvent::class.java)
            (event is IssueUpdated) shouldBe true
        }

        it("type=issue.transitioned JSON 을 IssueDomainEvent 로 읽으면 IssueTransitioned 인스턴스다") {
            val json =
                """
                {
                  "type": "issue.transitioned",
                  "issueKey": "ATLAS-1",
                  "fromState": "open",
                  "toState": "IN_PROGRESS",
                  "actorId": {"value": "11111111-1111-1111-1111-111111111111"},
                  "occurredAt": "2026-01-01T00:00:00Z"
                }
                """.trimIndent()
            val event = mapper.readValue(json, IssueDomainEvent::class.java)
            (event is IssueTransitioned) shouldBe true
        }

        it("type=issue.soft_deleted JSON 을 IssueDomainEvent 로 읽으면 IssueSoftDeleted 인스턴스다") {
            val json =
                """
                {
                  "type": "issue.soft_deleted",
                  "issueKey": "ATLAS-1",
                  "occurredAt": "2026-01-01T00:00:00Z"
                }
                """.trimIndent()
            val event = mapper.readValue(json, IssueDomainEvent::class.java)
            (event is IssueSoftDeleted) shouldBe true
        }
    }
})
