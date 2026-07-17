// TriggerType/TriggerConfig 형식 검증 + AutomationRule 팩토리·동작(enable/disable/rename/updateConfig) 단위 테스트

package com.bts.automation.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.UUID

class TriggerConfigTest : DescribeSpec({

    val fixedNow = Instant.parse("2026-07-10T00:00:00Z")

    describe("TriggerType — enum 6종") {
        it("6종 모두 정의된다") {
            TriggerType.entries.size shouldBe 6
        }

        it("이슈 이벤트 3종 + SCHEDULED + WEBHOOK + PR_MERGED 를 포함한다") {
            TriggerType.entries.toSet() shouldBe
                setOf(
                    TriggerType.ISSUE_CREATED,
                    TriggerType.ISSUE_UPDATED,
                    TriggerType.ISSUE_COMMENTED,
                    TriggerType.SCHEDULED,
                    TriggerType.WEBHOOK,
                    TriggerType.PR_MERGED,
                )
        }
    }

    describe("TriggerConfig.validate — SCHEDULED") {
        it("cron 필드가 있고 파싱 가능하면 통과한다") {
            // Spring CronExpression은 초 필드를 포함한 6필드 형식(초 분 시 일 월 요일)을 요구한다.
            // 표준 5필드 유닉스 cron(스펙 예시의 "0 9 * * *")과 다르다 — 매일 09:00:00을 6필드로 표현.
            TriggerConfig.validate(TriggerType.SCHEDULED, """{"cron":"0 0 9 * * *"}""")
        }

        it("cron 필드가 없으면 거부한다") {
            shouldThrow<TriggerConfigInvalidException> {
                TriggerConfig.validate(TriggerType.SCHEDULED, "{}")
            }
        }

        it("cron 필드가 빈 문자열이면 거부한다") {
            shouldThrow<TriggerConfigInvalidException> {
                TriggerConfig.validate(TriggerType.SCHEDULED, """{"cron":""}""")
            }
        }

        it("cron 필드가 파싱 불가능한 표현식이면 거부한다") {
            shouldThrow<TriggerConfigInvalidException> {
                TriggerConfig.validate(TriggerType.SCHEDULED, """{"cron":"not-a-cron"}""")
            }
        }

        it("cron 필드가 문자열이 아니면 거부한다") {
            shouldThrow<TriggerConfigInvalidException> {
                TriggerConfig.validate(TriggerType.SCHEDULED, """{"cron":123}""")
            }
        }
    }

    describe("TriggerConfig.validate — ISSUE_UPDATED") {
        it("fields 가 없으면(전체 update 발화 의미) 통과한다") {
            TriggerConfig.validate(TriggerType.ISSUE_UPDATED, "{}")
        }

        it("fields 가 문자열 배열이면 통과한다") {
            TriggerConfig.validate(TriggerType.ISSUE_UPDATED, """{"fields":["priority","summary"]}""")
        }

        it("fields 가 빈 배열이면 통과한다") {
            TriggerConfig.validate(TriggerType.ISSUE_UPDATED, """{"fields":[]}""")
        }

        it("fields 가 배열이 아니면 거부한다") {
            shouldThrow<TriggerConfigInvalidException> {
                TriggerConfig.validate(TriggerType.ISSUE_UPDATED, """{"fields":"priority"}""")
            }
        }

        it("fields 항목 중 빈 문자열이 있으면 거부한다") {
            shouldThrow<TriggerConfigInvalidException> {
                TriggerConfig.validate(TriggerType.ISSUE_UPDATED, """{"fields":["priority",""]}""")
            }
        }

        it("fields 항목 중 문자열이 아닌 값이 있으면 거부한다") {
            shouldThrow<TriggerConfigInvalidException> {
                TriggerConfig.validate(TriggerType.ISSUE_UPDATED, """{"fields":[1,2]}""")
            }
        }
    }

    describe("TriggerConfig.validate — PR_MERGED") {
        it("targetBranch 가 없으면(전체 브랜치 발화 의미) 통과한다") {
            TriggerConfig.validate(TriggerType.PR_MERGED, "{}")
        }

        it("targetBranch 가 문자열이면 통과한다") {
            TriggerConfig.validate(TriggerType.PR_MERGED, """{"targetBranch":"release/1.2"}""")
        }

        it("targetBranch 가 숫자면 거부한다") {
            shouldThrow<TriggerConfigInvalidException> {
                TriggerConfig.validate(TriggerType.PR_MERGED, """{"targetBranch":123}""")
            }
        }

        it("targetBranch 가 빈 문자열이면 거부한다") {
            shouldThrow<TriggerConfigInvalidException> {
                TriggerConfig.validate(TriggerType.PR_MERGED, """{"targetBranch":""}""")
            }
        }
    }

    describe("TriggerConfig.validate — 빈 config 허용 타입(ISSUE_CREATED/ISSUE_COMMENTED/WEBHOOK)") {
        it("ISSUE_CREATED 는 빈 config 를 허용한다") {
            TriggerConfig.validate(TriggerType.ISSUE_CREATED, "{}")
        }

        it("ISSUE_COMMENTED 는 빈 config 를 허용한다") {
            TriggerConfig.validate(TriggerType.ISSUE_COMMENTED, "{}")
        }

        it("WEBHOOK 은 빈 config 를 허용한다") {
            TriggerConfig.validate(TriggerType.WEBHOOK, "{}")
        }
    }

    describe("TriggerConfig.validate — 공통 형식 오류") {
        it("빈 문자열은 모든 타입에서 거부한다") {
            shouldThrow<TriggerConfigInvalidException> {
                TriggerConfig.validate(TriggerType.ISSUE_CREATED, "")
            }
        }

        it("파싱 불가능한 JSON 은 거부한다") {
            shouldThrow<TriggerConfigInvalidException> {
                TriggerConfig.validate(TriggerType.ISSUE_CREATED, "{not-json")
            }
        }

        it("JSON 객체가 아니면(배열 등) 거부한다") {
            shouldThrow<TriggerConfigInvalidException> {
                TriggerConfig.validate(TriggerType.ISSUE_CREATED, "[]")
            }
        }
    }

    describe("AutomationRule.create — triggerConfig 검증 위임") {
        it("유효한 config 로 생성하면 enabled=true, version=0 이다") {
            val rule =
                AutomationRule.create(
                    projectKey = "PROJ",
                    name = "이슈 생성 알림",
                    triggerType = TriggerType.ISSUE_CREATED,
                    createdBy = UUID.randomUUID(),
                    now = fixedNow,
                )

            rule.enabled shouldBe true
            rule.version shouldBe 0L
            rule.projectKey shouldBe "PROJ"
            rule.triggerType shouldBe TriggerType.ISSUE_CREATED
            rule.triggerConfig shouldBe TriggerConfig.EMPTY
        }

        it("잘못된 triggerConfig 로 생성하면 거부한다(SCHEDULED cron 없음)") {
            shouldThrow<TriggerConfigInvalidException> {
                AutomationRule.create(
                    projectKey = "PROJ",
                    name = "cron 없는 SCHEDULED",
                    triggerType = TriggerType.SCHEDULED,
                    triggerConfig = "{}",
                    createdBy = UUID.randomUUID(),
                    now = fixedNow,
                )
            }
        }

        it("이름이 빈 문자열이면 거부한다") {
            shouldThrow<AutomationRuleInvalidException> {
                AutomationRule.create(
                    projectKey = "PROJ",
                    name = "  ",
                    triggerType = TriggerType.ISSUE_CREATED,
                    createdBy = UUID.randomUUID(),
                    now = fixedNow,
                )
            }
        }

        it("projectKey 가 빈 문자열이면 거부한다") {
            shouldThrow<AutomationRuleInvalidException> {
                AutomationRule.create(
                    projectKey = "",
                    name = "이름",
                    triggerType = TriggerType.ISSUE_CREATED,
                    createdBy = UUID.randomUUID(),
                    now = fixedNow,
                )
            }
        }
    }

    describe("AutomationRule — enable/disable/rename/updateConfig 는 OCC 버전을 +1 한다") {
        fun newRule(): AutomationRule =
            AutomationRule.create(
                projectKey = "PROJ",
                name = "원본 이름",
                triggerType = TriggerType.ISSUE_UPDATED,
                triggerConfig = """{"fields":["priority"]}""",
                createdBy = UUID.randomUUID(),
                now = fixedNow,
            )

        it("disable 은 enabled=false, version+1") {
            val rule = newRule()
            val later = fixedNow.plusSeconds(60)

            val disabled = rule.disable(later)

            disabled.enabled shouldBe false
            disabled.version shouldBe rule.version + 1
            disabled.updatedAt shouldBe later
        }

        it("enable 은 enabled=true, version+1") {
            val rule = newRule().disable(fixedNow.plusSeconds(60))
            val later = fixedNow.plusSeconds(120)

            val enabled = rule.enable(later)

            enabled.enabled shouldBe true
            enabled.version shouldBe rule.version + 1
        }

        it("rename 은 name 교체, version+1") {
            val rule = newRule()
            val later = fixedNow.plusSeconds(60)

            val renamed = rule.rename("새 이름", later)

            renamed.name shouldBe "새 이름"
            renamed.version shouldBe rule.version + 1
        }

        it("rename 은 빈 이름을 거부한다") {
            val rule = newRule()
            shouldThrow<AutomationRuleInvalidException> {
                rule.rename("   ", fixedNow.plusSeconds(60))
            }
        }

        it("updateConfig 는 triggerConfig 교체, version+1") {
            val rule = newRule()
            val later = fixedNow.plusSeconds(60)

            val updated = rule.updateConfig("""{"fields":["summary","priority"]}""", later)

            updated.triggerConfig shouldBe """{"fields":["summary","priority"]}"""
            updated.version shouldBe rule.version + 1
        }

        it("updateConfig 는 triggerType 형식에 맞지 않으면 거부한다") {
            val rule = newRule()
            shouldThrow<TriggerConfigInvalidException> {
                rule.updateConfig("""{"fields":"not-an-array"}""", fixedNow.plusSeconds(60))
            }
        }
    }
})
