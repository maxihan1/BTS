// ConditionEvaluator.evaluate — 연산자별 참/거짓 판정 + fail-safe 계약 단위 테스트 (FR-AT-03 Task 2, 50 케이스)

package com.bts.automation.application

import com.bts.automation.domain.Condition
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

/**
 * [ConditionEvaluator.evaluate] 단위 테스트 (FR-AT-03 Task 2, 스펙 정본 50 케이스).
 *
 * 실 [Condition.fromJson] 파서(Task 1)로 조건 트리를 만들고 합성 [ConditionContext.of] fixture 로
 * 평가한다. 카테고리 구성 — 1.비교연산자(12) 2.멤버십in(8) 3.존재!/!!(6) 4.조합and/or/not(8)
 * 5.null/누락필드(6) 6.타입불일치fail-safe(5) 7.항등(2) 8.중첩조합필러(3) = 50.
 */
class ConditionEvaluatorTest : DescribeSpec({

    describe("evaluate — 비교 연산자 (12)") {
        it("EQUALS 문자열 참 — status==Done 이고 실제 Done") {
            val ctx = ConditionContext.of(mapOf("issue.status" to "Done"))
            val condition = Condition.fromJson("""{"==": [{"var": "issue.status"}, "Done"]}""")

            ConditionEvaluator.evaluate(condition, ctx) shouldBe true
        }

        it("EQUALS 문자열 거짓 — status==Done 이지만 실제 Open") {
            val ctx = ConditionContext.of(mapOf("issue.status" to "Open"))
            val condition = Condition.fromJson("""{"==": [{"var": "issue.status"}, "Done"]}""")

            ConditionEvaluator.evaluate(condition, ctx) shouldBe false
        }

        it("NOT_EQUALS(!=) 문자열 참 — type!=Bug 이고 실제 Feature") {
            val ctx = ConditionContext.of(mapOf("issue.type" to "Feature"))
            val condition = Condition.fromJson("""{"!=": [{"var": "issue.type"}, "Bug"]}""")

            ConditionEvaluator.evaluate(condition, ctx) shouldBe true
        }

        it("NOT_EQUALS(!=) 문자열 거짓 — type!=Bug 이고 실제 Bug") {
            val ctx = ConditionContext.of(mapOf("issue.type" to "Bug"))
            val condition = Condition.fromJson("""{"!=": [{"var": "issue.type"}, "Bug"]}""")

            ConditionEvaluator.evaluate(condition, ctx) shouldBe false
        }

        it("GREATER_THAN 숫자 참 — priority>3 이고 실제 5") {
            val ctx = ConditionContext.of(mapOf("issue.priority" to 5))
            val condition = Condition.fromJson("""{">": [{"var": "issue.priority"}, 3]}""")

            ConditionEvaluator.evaluate(condition, ctx) shouldBe true
        }

        it("GREATER_THAN 숫자 거짓 — priority>3 이고 실제 2") {
            val ctx = ConditionContext.of(mapOf("issue.priority" to 2))
            val condition = Condition.fromJson("""{">": [{"var": "issue.priority"}, 3]}""")

            ConditionEvaluator.evaluate(condition, ctx) shouldBe false
        }

        it("GREATER_THAN_OR_EQUAL 숫자 참 — priority>=3 이고 실제 3(경계값)") {
            val ctx = ConditionContext.of(mapOf("issue.priority" to 3))
            val condition = Condition.fromJson("""{">=": [{"var": "issue.priority"}, 3]}""")

            ConditionEvaluator.evaluate(condition, ctx) shouldBe true
        }

        it("GREATER_THAN_OR_EQUAL 숫자 거짓 — priority>=3 이고 실제 2") {
            val ctx = ConditionContext.of(mapOf("issue.priority" to 2))
            val condition = Condition.fromJson("""{">=": [{"var": "issue.priority"}, 3]}""")

            ConditionEvaluator.evaluate(condition, ctx) shouldBe false
        }

        it("LESS_THAN 숫자 참 — priority<3 이고 실제 1") {
            val ctx = ConditionContext.of(mapOf("issue.priority" to 1))
            val condition = Condition.fromJson("""{"<": [{"var": "issue.priority"}, 3]}""")

            ConditionEvaluator.evaluate(condition, ctx) shouldBe true
        }

        it("LESS_THAN 숫자 거짓 — priority<3 이고 실제 5") {
            val ctx = ConditionContext.of(mapOf("issue.priority" to 5))
            val condition = Condition.fromJson("""{"<": [{"var": "issue.priority"}, 3]}""")

            ConditionEvaluator.evaluate(condition, ctx) shouldBe false
        }

        it("LESS_THAN_OR_EQUAL 숫자 참 — priority<=3 이고 실제 3(경계값)") {
            val ctx = ConditionContext.of(mapOf("issue.priority" to 3))
            val condition = Condition.fromJson("""{"<=": [{"var": "issue.priority"}, 3]}""")

            ConditionEvaluator.evaluate(condition, ctx) shouldBe true
        }

        it("LESS_THAN_OR_EQUAL 숫자 거짓 — priority<=3 이고 실제 4") {
            val ctx = ConditionContext.of(mapOf("issue.priority" to 4))
            val condition = Condition.fromJson("""{"<=": [{"var": "issue.priority"}, 3]}""")

            ConditionEvaluator.evaluate(condition, ctx) shouldBe false
        }
    }

    describe("evaluate — 멤버십 in (8)") {
        it("배열 멤버십 참 — status in [Done, Closed] 이고 실제 Done") {
            val ctx = ConditionContext.of(mapOf("issue.status" to "Done"))
            val condition = Condition.fromJson("""{"in": [{"var": "issue.status"}, ["Done", "Closed"]]}""")

            ConditionEvaluator.evaluate(condition, ctx) shouldBe true
        }

        it("배열 멤버십 거짓 — status in [Done, Closed] 이고 실제 Open") {
            val ctx = ConditionContext.of(mapOf("issue.status" to "Open"))
            val condition = Condition.fromJson("""{"in": [{"var": "issue.status"}, ["Done", "Closed"]]}""")

            ConditionEvaluator.evaluate(condition, ctx) shouldBe false
        }

        it("문자열 부분문자열 참 — summary 가 crashes 를 포함") {
            val ctx = ConditionContext.of(mapOf("issue.summary" to "Login page crashes on submit"))
            val condition = Condition.fromJson("""{"in": ["crashes", {"var": "issue.summary"}]}""")

            ConditionEvaluator.evaluate(condition, ctx) shouldBe true
        }

        it("문자열 부분문자열 거짓 — summary 가 timeout 을 포함하지 않음") {
            val ctx = ConditionContext.of(mapOf("issue.summary" to "Login page crashes on submit"))
            val condition = Condition.fromJson("""{"in": ["timeout", {"var": "issue.summary"}]}""")

            ConditionEvaluator.evaluate(condition, ctx) shouldBe false
        }

        it("labels 멤버십 참 — urgent 가 labels 에 포함") {
            val ctx = ConditionContext.of(mapOf("issue.labels" to listOf("urgent", "backend")))
            val condition = Condition.fromJson("""{"in": ["urgent", {"var": "issue.labels"}]}""")

            ConditionEvaluator.evaluate(condition, ctx) shouldBe true
        }

        it("labels 멤버십 거짓 — urgent 가 labels 에 없음") {
            val ctx = ConditionContext.of(mapOf("issue.labels" to listOf("backend")))
            val condition = Condition.fromJson("""{"in": ["urgent", {"var": "issue.labels"}]}""")

            ConditionEvaluator.evaluate(condition, ctx) shouldBe false
        }

        it("빈 배열 haystack 미포함 — status in [] 는 항상 거짓") {
            val ctx = ConditionContext.of(mapOf("issue.status" to "Done"))
            val condition = Condition.fromJson("""{"in": [{"var": "issue.status"}, []]}""")

            ConditionEvaluator.evaluate(condition, ctx) shouldBe false
        }

        it("labels 빈 배열 미포함 — urgent in (빈 labels) 는 거짓") {
            val ctx = ConditionContext.of(mapOf("issue.labels" to emptyList<String>()))
            val condition = Condition.fromJson("""{"in": ["urgent", {"var": "issue.labels"}]}""")

            ConditionEvaluator.evaluate(condition, ctx) shouldBe false
        }
    }

    describe("evaluate — 존재 !/!! (6)") {
        it("EXISTS(!!) 참 — assignee 있음") {
            val ctx = ConditionContext.of(mapOf("issue.assignee" to "user-1"))
            val condition = Condition.fromJson("""{"!!": {"var": "issue.assignee"}}""")

            ConditionEvaluator.evaluate(condition, ctx) shouldBe true
        }

        it("EXISTS(!!) 거짓 — assignee 없음") {
            val ctx = ConditionContext.of(emptyMap())
            val condition = Condition.fromJson("""{"!!": {"var": "issue.assignee"}}""")

            ConditionEvaluator.evaluate(condition, ctx) shouldBe false
        }

        it("EMPTY(!) 참 — labels 가 빈 배열") {
            val ctx = ConditionContext.of(mapOf("issue.labels" to emptyList<String>()))
            val condition = Condition.fromJson("""{"!": {"var": "issue.labels"}}""")

            ConditionEvaluator.evaluate(condition, ctx) shouldBe true
        }

        it("EMPTY(!) 거짓 — labels 가 비어있지 않음") {
            val ctx = ConditionContext.of(mapOf("issue.labels" to listOf("a")))
            val condition = Condition.fromJson("""{"!": {"var": "issue.labels"}}""")

            ConditionEvaluator.evaluate(condition, ctx) shouldBe false
        }

        it("EMPTY(!) 참 — summary 가 빈 문자열") {
            val ctx = ConditionContext.of(mapOf("issue.summary" to ""))
            val condition = Condition.fromJson("""{"!": {"var": "issue.summary"}}""")

            ConditionEvaluator.evaluate(condition, ctx) shouldBe true
        }

        it("EMPTY(!) 거짓 — summary 가 비어있지 않음") {
            val ctx = ConditionContext.of(mapOf("issue.summary" to "Something"))
            val condition = Condition.fromJson("""{"!": {"var": "issue.summary"}}""")

            ConditionEvaluator.evaluate(condition, ctx) shouldBe false
        }
    }

    describe("evaluate — 조합 and/or/not (8)") {
        it("AND 전부 참 — status==Done 그리고 priority>1") {
            val ctx = ConditionContext.of(mapOf("issue.status" to "Done", "issue.priority" to 5))
            val condition =
                Condition.fromJson(
                    """{"and": [{"==": [{"var": "issue.status"}, "Done"]}, {">": [{"var": "issue.priority"}, 1]}]}""",
                )

            ConditionEvaluator.evaluate(condition, ctx) shouldBe true
        }

        it("AND 일부 거짓 — status==Done 은 참이지만 priority>1 은 거짓") {
            val ctx = ConditionContext.of(mapOf("issue.status" to "Done", "issue.priority" to 1))
            val condition =
                Condition.fromJson(
                    """{"and": [{"==": [{"var": "issue.status"}, "Done"]}, {">": [{"var": "issue.priority"}, 1]}]}""",
                )

            ConditionEvaluator.evaluate(condition, ctx) shouldBe false
        }

        it("OR 하나 참 — status==Done 이 참") {
            val ctx = ConditionContext.of(mapOf("issue.status" to "Done"))
            val condition =
                Condition.fromJson(
                    """{"or": [{"==": [{"var": "issue.status"}, "Done"]}, """ +
                        """{"==": [{"var": "issue.status"}, "Closed"]}]}""",
                )

            ConditionEvaluator.evaluate(condition, ctx) shouldBe true
        }

        it("OR 전부 거짓 — status 가 Done/Closed 둘 다 아님") {
            val ctx = ConditionContext.of(mapOf("issue.status" to "Open"))
            val condition =
                Condition.fromJson(
                    """{"or": [{"==": [{"var": "issue.status"}, "Done"]}, """ +
                        """{"==": [{"var": "issue.status"}, "Closed"]}]}""",
                )

            ConditionEvaluator.evaluate(condition, ctx) shouldBe false
        }

        it("NOT 참을 부정하면 거짓 — status==Done 이 참인데 not 으로 뒤집음") {
            val ctx = ConditionContext.of(mapOf("issue.status" to "Done"))
            val condition = Condition.fromJson("""{"not": {"==": [{"var": "issue.status"}, "Done"]}}""")

            ConditionEvaluator.evaluate(condition, ctx) shouldBe false
        }

        it("NOT 거짓을 부정하면 참 — status==Done 이 거짓인데 not 으로 뒤집음") {
            val ctx = ConditionContext.of(mapOf("issue.status" to "Open"))
            val condition = Condition.fromJson("""{"not": {"==": [{"var": "issue.status"}, "Done"]}}""")

            ConditionEvaluator.evaluate(condition, ctx) shouldBe true
        }

        it("2단 중첩 AND(OR(...)) 참 — status 가 Done/Closed 중 하나이고 priority>=3") {
            val ctx = ConditionContext.of(mapOf("issue.status" to "Closed", "issue.priority" to 5))
            val condition =
                Condition.fromJson(
                    """{"and": [{"or": [{"==": [{"var": "issue.status"}, "Done"]}, """ +
                        """{"==": [{"var": "issue.status"}, "Closed"]}]}, {">=": [{"var": "issue.priority"}, 3]}]}""",
                )

            ConditionEvaluator.evaluate(condition, ctx) shouldBe true
        }

        it("2단 중첩 OR(AND(...)) 거짓 — 두 하위 조건 모두 불충족") {
            val ctx = ConditionContext.of(mapOf("issue.status" to "Open", "issue.priority" to 5))
            val condition =
                Condition.fromJson(
                    """{"or": [{"and": [{"==": [{"var": "issue.status"}, "Done"]}, """ +
                        """{">": [{"var": "issue.priority"}, 10]}]}, {"==": [{"var": "issue.status"}, "Closed"]}]}""",
                )

            ConditionEvaluator.evaluate(condition, ctx) shouldBe false
        }
    }

    describe("evaluate — null/누락 필드 (6)") {
        it("누락 필드 ==null 은 참 — assignee 가 컨텍스트에 없음") {
            val ctx = ConditionContext.of(emptyMap())
            val condition = Condition.fromJson("""{"==": [{"var": "issue.assignee"}, null]}""")

            ConditionEvaluator.evaluate(condition, ctx) shouldBe true
        }

        it("누락 필드 ==값 은 거짓 — assignee 가 컨텍스트에 없고 리터럴은 문자열") {
            val ctx = ConditionContext.of(emptyMap())
            val condition = Condition.fromJson("""{"==": [{"var": "issue.assignee"}, "user-1"]}""")

            ConditionEvaluator.evaluate(condition, ctx) shouldBe false
        }

        it("누락 필드 !=값 은 참 — assignee 가 컨텍스트에 없음") {
            val ctx = ConditionContext.of(emptyMap())
            val condition = Condition.fromJson("""{"!=": [{"var": "issue.assignee"}, "user-1"]}""")

            ConditionEvaluator.evaluate(condition, ctx) shouldBe true
        }

        it("누락 필드 서수비교(>) 는 거짓 — priority 가 컨텍스트에 없음") {
            val ctx = ConditionContext.of(emptyMap())
            val condition = Condition.fromJson("""{">": [{"var": "issue.priority"}, 3]}""")

            ConditionEvaluator.evaluate(condition, ctx) shouldBe false
        }

        it("누락 필드 서수비교(<=) 는 거짓 — priority 가 컨텍스트에 없음") {
            val ctx = ConditionContext.of(emptyMap())
            val condition = Condition.fromJson("""{"<=": [{"var": "issue.priority"}, 3]}""")

            ConditionEvaluator.evaluate(condition, ctx) shouldBe false
        }

        it("누락 필드 in 은 거짓 — status 가 컨텍스트에 없음") {
            val ctx = ConditionContext.of(emptyMap())
            val condition = Condition.fromJson("""{"in": [{"var": "issue.status"}, ["Done", "Closed"]]}""")

            ConditionEvaluator.evaluate(condition, ctx) shouldBe false
        }
    }

    describe("evaluate — 타입 불일치 fail-safe (5)") {
        it("EQUALS 문자열 필드 vs 숫자 리터럴은 거짓 — \"3\" 은 3 과 다름") {
            val ctx = ConditionContext.of(mapOf("issue.priority" to "3"))
            val condition = Condition.fromJson("""{"==": [{"var": "issue.priority"}, 3]}""")

            ConditionEvaluator.evaluate(condition, ctx) shouldBe false
        }

        it("EQUALS 숫자 필드 vs 문자열 리터럴은 거짓 — 3 은 \"3\" 과 다름") {
            val ctx = ConditionContext.of(mapOf("issue.priority" to 3))
            val condition = Condition.fromJson("""{"==": [{"var": "issue.priority"}, "3"]}""")

            ConditionEvaluator.evaluate(condition, ctx) shouldBe false
        }

        it("GREATER_THAN 문자열 필드는 서수 비교 불가 — 예외 대신 거짓") {
            val ctx = ConditionContext.of(mapOf("issue.status" to "5"))
            val condition = Condition.fromJson("""{">": [{"var": "issue.status"}, 3]}""")

            ConditionEvaluator.evaluate(condition, ctx) shouldBe false
        }

        it("GREATER_THAN 숫자 필드 vs 비숫자 문자열 리터럴은 거짓") {
            val ctx = ConditionContext.of(mapOf("issue.priority" to 5))
            val condition = Condition.fromJson("""{">": [{"var": "issue.priority"}, "abc"]}""")

            ConditionEvaluator.evaluate(condition, ctx) shouldBe false
        }

        it("IN 스칼라 haystack — 필드가 리스트도 문자열도 아니면 거짓") {
            val ctx = ConditionContext.of(mapOf("issue.priority" to 5))
            val condition = Condition.fromJson("""{"in": ["text", {"var": "issue.priority"}]}""")

            ConditionEvaluator.evaluate(condition, ctx) shouldBe false
        }
    }

    describe("evaluate — 항등 (2)") {
        it("빈 AND 는 항등원으로 참") {
            val ctx = ConditionContext.of(emptyMap())
            val condition = Condition.fromJson("""{"and": []}""")

            ConditionEvaluator.evaluate(condition, ctx) shouldBe true
        }

        it("빈 OR 는 항등원으로 거짓") {
            val ctx = ConditionContext.of(emptyMap())
            val condition = Condition.fromJson("""{"or": []}""")

            ConditionEvaluator.evaluate(condition, ctx) shouldBe false
        }
    }

    describe("evaluate — 중첩 조합 필러 (3)") {
        it("AND[NOT(status==Done), priority>3] 참") {
            val ctx = ConditionContext.of(mapOf("issue.status" to "Open", "issue.priority" to 5))
            val condition =
                Condition.fromJson(
                    """{"and": [{"not": {"==": [{"var": "issue.status"}, "Done"]}}, """ +
                        """{">": [{"var": "issue.priority"}, 3]}]}""",
                )

            ConditionEvaluator.evaluate(condition, ctx) shouldBe true
        }

        it("OR[AND[status==Done, priority==5], status==Closed] 거짓") {
            val ctx = ConditionContext.of(mapOf("issue.status" to "Open", "issue.priority" to 1))
            val condition =
                Condition.fromJson(
                    """{"or": [{"and": [{"==": [{"var": "issue.status"}, "Done"]}, """ +
                        """{"==": [{"var": "issue.priority"}, 5]}]}, {"==": [{"var": "issue.status"}, "Closed"]}]}""",
                )

            ConditionEvaluator.evaluate(condition, ctx) shouldBe false
        }

        it("3단 중첩 AND[OR[status==Done, status==Closed], NOT(priority<3)] 참") {
            val ctx = ConditionContext.of(mapOf("issue.status" to "Closed", "issue.priority" to 5))
            val condition =
                Condition.fromJson(
                    """{"and": [{"or": [{"==": [{"var": "issue.status"}, "Done"]}, """ +
                        """{"==": [{"var": "issue.status"}, "Closed"]}]}, """ +
                        """{"not": {"<": [{"var": "issue.priority"}, 3]}}]}""",
                )

            ConditionEvaluator.evaluate(condition, ctx) shouldBe true
        }
    }
})
