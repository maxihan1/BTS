// NotificationEventType / RecipientRole / Channel enum 카탈로그 단위 테스트

package com.bts.notification.domain

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class NotificationEventTypeTest : DescribeSpec({

    describe("NotificationEventType — 10종 존재 및 메타 검증") {

        it("enum 상수가 정확히 10종이어야 한다") {
            NotificationEventType.entries.size shouldBe 10
        }

        context("wireValue 검증") {
            it("ISSUE_CREATED.wireValue == \"issue.created\"") {
                NotificationEventType.ISSUE_CREATED.wireValue shouldBe "issue.created"
            }
            it("ISSUE_ASSIGNED.wireValue == \"issue.assigned\"") {
                NotificationEventType.ISSUE_ASSIGNED.wireValue shouldBe "issue.assigned"
            }
            it("ISSUE_TRANSITIONED.wireValue == \"issue.transitioned\"") {
                NotificationEventType.ISSUE_TRANSITIONED.wireValue shouldBe "issue.transitioned"
            }
            it("ISSUE_COMMENTED.wireValue == \"issue.commented\"") {
                NotificationEventType.ISSUE_COMMENTED.wireValue shouldBe "issue.commented"
            }
            it("ISSUE_DUE_SOON.wireValue == \"issue.due_soon\"") {
                NotificationEventType.ISSUE_DUE_SOON.wireValue shouldBe "issue.due_soon"
            }
            it("ISSUE_OVERDUE.wireValue == \"issue.overdue\"") {
                NotificationEventType.ISSUE_OVERDUE.wireValue shouldBe "issue.overdue"
            }
            it("SPRINT_STARTED.wireValue == \"sprint.started\"") {
                NotificationEventType.SPRINT_STARTED.wireValue shouldBe "sprint.started"
            }
            it("SPRINT_ENDED.wireValue == \"sprint.ended\"") {
                NotificationEventType.SPRINT_ENDED.wireValue shouldBe "sprint.ended"
            }
            it("AUTOMATION_FAILED.wireValue == \"automation.failed\"") {
                NotificationEventType.AUTOMATION_FAILED.wireValue shouldBe "automation.failed"
            }
            it("ISSUE_MENTIONED.wireValue == \"issue.mentioned\"") {
                NotificationEventType.ISSUE_MENTIONED.wireValue shouldBe "issue.mentioned"
            }
        }

        context("publishable 메타 — ISSUE_CREATED / ISSUE_TRANSITIONED 만 true") {
            it("ISSUE_CREATED.publishable == true") {
                NotificationEventType.ISSUE_CREATED.publishable shouldBe true
            }
            it("ISSUE_TRANSITIONED.publishable == true") {
                NotificationEventType.ISSUE_TRANSITIONED.publishable shouldBe true
            }
            it("ISSUE_MENTIONED.publishable == false") {
                NotificationEventType.ISSUE_MENTIONED.publishable shouldBe false
            }
            it("나머지 8종은 publishable == false") {
                val nonPublishable =
                    NotificationEventType.entries.filter {
                        it != NotificationEventType.ISSUE_CREATED && it != NotificationEventType.ISSUE_TRANSITIONED
                    }
                nonPublishable.size shouldBe 8
                nonPublishable.forEach { eventType ->
                    eventType.publishable shouldBe false
                }
            }
        }

        context("fromWire — 역매핑") {
            it("fromWire(\"issue.created\") == ISSUE_CREATED") {
                NotificationEventType.fromWire("issue.created") shouldBe NotificationEventType.ISSUE_CREATED
            }
            it("fromWire(\"automation.failed\") == AUTOMATION_FAILED") {
                NotificationEventType.fromWire("automation.failed") shouldBe NotificationEventType.AUTOMATION_FAILED
            }
            it("fromWire(\"issue.mentioned\") == ISSUE_MENTIONED") {
                NotificationEventType.fromWire("issue.mentioned") shouldBe NotificationEventType.ISSUE_MENTIONED
            }
            it("fromWire(\"nonexistent\") == null (예외 아님)") {
                NotificationEventType.fromWire("nonexistent").shouldBeNull()
            }
        }
    }

    describe("RecipientRole — 9종 존재 및 fromWire") {

        it("enum 상수가 정확히 9종이어야 한다") {
            RecipientRole.entries.size shouldBe 9
        }

        it("모든 enum명이 entries에 포함된다") {
            val expected =
                setOf(
                    "REPORTER", "ASSIGNEE", "PREVIOUS_ASSIGNEE", "WATCHER",
                    "COMPONENT_LEAD", "MENTIONED", "PROJECT_MEMBER", "RULE_OWNER", "PROJECT_ADMIN",
                )
            RecipientRole.entries.map { it.name }.toSet() shouldBe expected
        }

        context("fromWire — 역매핑") {
            it("fromWire(\"REPORTER\") == REPORTER") {
                RecipientRole.fromWire("REPORTER") shouldBe RecipientRole.REPORTER
            }
            it("fromWire(\"PROJECT_ADMIN\") == PROJECT_ADMIN") {
                RecipientRole.fromWire("PROJECT_ADMIN") shouldBe RecipientRole.PROJECT_ADMIN
            }
            it("fromWire(\"UNKNOWN\") == null (예외 아님)") {
                RecipientRole.fromWire("UNKNOWN").shouldBeNull()
            }
        }
    }

    describe("Channel — 5종 존재 및 fromWire") {

        it("enum 상수가 정확히 5종이어야 한다") {
            Channel.entries.size shouldBe 5
        }

        it("모든 enum명이 entries에 포함된다") {
            val expected = setOf("EMAIL", "IN_APP", "SLACK", "TEAMS", "WEBHOOK")
            Channel.entries.map { it.name }.toSet() shouldBe expected
        }

        context("fromWire — 역매핑") {
            it("fromWire(\"EMAIL\") == EMAIL") {
                Channel.fromWire("EMAIL") shouldBe Channel.EMAIL
            }
            it("fromWire(\"WEBHOOK\") == WEBHOOK") {
                Channel.fromWire("WEBHOOK") shouldBe Channel.WEBHOOK
            }
            it("fromWire(\"UNKNOWN\") == null (예외 아님)") {
                Channel.fromWire("UNKNOWN").shouldBeNull()
            }
        }
    }
})
