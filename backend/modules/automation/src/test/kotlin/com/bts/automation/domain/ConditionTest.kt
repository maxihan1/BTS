// Condition.fromJson/toJson 파싱·화이트리스트·상한 검증 단위 테스트 (FR-AT-03 Task 1)

package com.bts.automation.domain

import com.fasterxml.jackson.databind.node.IntNode
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.TextNode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class ConditionTest : DescribeSpec({

    describe("Condition.fromJson — 비교 연산자") {
        it("== 를 var-first 형태로 파싱한다") {
            val condition = Condition.fromJson("""{"==": [{"var": "issue.status"}, "Done"]}""")

            condition shouldBe
                Condition.Comparison(
                    field = "issue.status",
                    operator = ComparisonOperator.EQUALS,
                    value = TextNode("Done"),
                )
        }

        it("NOT_EQUALS(!=) 를 파싱한다") {
            val condition = Condition.fromJson("""{"!=": [{"var": "issue.type"}, "Bug"]}""")

            condition shouldBe
                Condition.Comparison(
                    field = "issue.type",
                    operator = ComparisonOperator.NOT_EQUALS,
                    value = TextNode("Bug"),
                )
        }

        it(">= 를 숫자 리터럴과 함께 파싱한다") {
            val condition = Condition.fromJson("""{">=": [{"var": "issue.priority"}, 3]}""")

            condition shouldBe
                Condition.Comparison(
                    field = "issue.priority",
                    operator = ComparisonOperator.GREATER_THAN_OR_EQUAL,
                    value = IntNode(3),
                )
        }

        it("> / < / <= 도 동일 형태로 파싱한다") {
            (Condition.fromJson("""{">": [{"var": "issue.priority"}, 1]}""") as Condition.Comparison).operator shouldBe
                ComparisonOperator.GREATER_THAN
            (Condition.fromJson("""{"<": [{"var": "issue.priority"}, 5]}""") as Condition.Comparison).operator shouldBe
                ComparisonOperator.LESS_THAN
            (Condition.fromJson("""{"<=": [{"var": "issue.priority"}, 5]}""") as Condition.Comparison).operator shouldBe
                ComparisonOperator.LESS_THAN_OR_EQUAL
        }
    }

    describe("Condition.fromJson — in (멤버십)") {
        it("var 가 첫 번째, 배열 리터럴이 두 번째면 배열 멤버십으로 파싱한다") {
            val condition =
                Condition.fromJson("""{"in": [{"var": "issue.status"}, ["Done", "Closed"]]}""")

            (condition as Condition.Comparison).field shouldBe "issue.status"
            condition.operator shouldBe ComparisonOperator.IN
            condition.value.isArray shouldBe true
        }

        it("리터럴이 첫 번째, var 가 두 번째여도 파싱한다 (labels 멤버십, EC8)") {
            val condition = Condition.fromJson("""{"in": ["urgent", {"var": "issue.labels"}]}""")

            condition shouldBe
                Condition.Comparison(
                    field = "issue.labels",
                    operator = ComparisonOperator.IN,
                    value = TextNode("urgent"),
                )
        }
    }

    describe("Condition.fromJson — 존재 !/!!") {
        it("EMPTY(!) 를 var 단일 피연산자로 파싱한다") {
            val condition = Condition.fromJson("""{"!": {"var": "issue.assignee"}}""")

            condition shouldBe
                Condition.Comparison(
                    field = "issue.assignee",
                    operator = ComparisonOperator.EMPTY,
                    value = NullNode.instance,
                )
        }

        it("EXISTS(!!) 를 var 단일 피연산자로 파싱한다") {
            val condition = Condition.fromJson("""{"!!": {"var": "issue.assignee"}}""")

            (condition as Condition.Comparison).operator shouldBe ComparisonOperator.EXISTS
            condition.field shouldBe "issue.assignee"
        }
    }

    describe("Condition.fromJson — 조합 and/or/not") {
        it("and 를 리스트로 파싱한다") {
            val condition =
                Condition.fromJson(
                    """{"and": [{"==": [{"var": "issue.type"}, "Bug"]}, {">=": [{"var": "issue.priority"}, 3]}]}""",
                )

            condition shouldBe
                Condition.And(
                    listOf(
                        Condition.Comparison("issue.type", ComparisonOperator.EQUALS, TextNode("Bug")),
                        Condition.Comparison("issue.priority", ComparisonOperator.GREATER_THAN_OR_EQUAL, IntNode(3)),
                    ),
                )
        }

        it("or 를 리스트로 파싱한다") {
            val condition =
                Condition.fromJson("""{"or": [{"==": [{"var": "issue.status"}, "Done"]}]}""")

            condition shouldBe
                Condition.Or(listOf(Condition.Comparison("issue.status", ComparisonOperator.EQUALS, TextNode("Done"))))
        }

        it("not 을 단일 하위 조건으로 파싱한다") {
            val condition = Condition.fromJson("""{"not": {"==": [{"var": "issue.status"}, "Done"]}}""")

            condition shouldBe
                Condition.Not(Condition.Comparison("issue.status", ComparisonOperator.EQUALS, TextNode("Done")))
        }

        it("빈 and/or 도 파싱한다 (항등)") {
            Condition.fromJson("""{"and": []}""") shouldBe Condition.And(emptyList())
            Condition.fromJson("""{"or": []}""") shouldBe Condition.Or(emptyList())
        }

        it("2단 중첩(and 안에 or)을 파싱한다") {
            val condition =
                Condition.fromJson(
                    """{"and": [{"or": [{"==": [{"var": "issue.status"}, "Done"]}, """ +
                        """{"==": [{"var": "issue.status"}, "Closed"]}]}]}""",
                )

            condition shouldBe
                Condition.And(
                    listOf(
                        Condition.Or(
                            listOf(
                                Condition.Comparison("issue.status", ComparisonOperator.EQUALS, TextNode("Done")),
                                Condition.Comparison("issue.status", ComparisonOperator.EQUALS, TextNode("Closed")),
                            ),
                        ),
                    ),
                )
        }
    }

    describe("Condition.toJson — round-trip") {
        it("단순 비교가 round-trip 된다") {
            val original = Condition.fromJson("""{"==": [{"var": "issue.status"}, "Done"]}""")

            Condition.fromJson(original.toJson()) shouldBe original
        }

        it("리터럴이 var 보다 앞에 오는 in 표현식도 round-trip 된다 (위치 정보 소실 없이 의미 보존)") {
            val original = Condition.fromJson("""{"in": ["urgent", {"var": "issue.labels"}]}""")

            Condition.fromJson(original.toJson()) shouldBe original
        }

        it("존재 연산자(!/!!)가 round-trip 된다") {
            val original = Condition.fromJson("""{"!!": {"var": "issue.assignee"}}""")

            Condition.fromJson(original.toJson()) shouldBe original
        }

        it("and/or/not 중첩 트리가 round-trip 된다") {
            val original =
                Condition.fromJson(
                    """{"and": [{"==": [{"var": "issue.type"}, "Bug"]}, """ +
                        """{"not": {"==": [{"var": "issue.status"}, "Done"]}}]}""",
                )

            Condition.fromJson(original.toJson()) shouldBe original
        }
    }

    describe("Condition.fromJson — 검증 거부") {
        it("미지원 연산자는 거부한다") {
            shouldThrow<InvalidConditionExpressionException> {
                Condition.fromJson("""{"xor": [{"var": "issue.status"}, "Done"]}""")
            }
        }

        it("화이트리스트 밖 필드는 거부한다") {
            shouldThrow<InvalidConditionExpressionException> {
                Condition.fromJson("""{"==": [{"var": "issue.secretField"}, "x"]}""")
            }
        }

        it("깊이가 10을 초과하면 거부한다 (11단 not 중첩)") {
            val deepJson =
                (1..11).fold("""{"==": [{"var": "issue.status"}, "Done"]}""") { acc, _ ->
                    """{"not": $acc}"""
                }

            shouldThrow<InvalidConditionExpressionException> {
                Condition.fromJson(deepJson)
            }
        }

        it("깊이 10 이하는 통과한다 (9단 not 중첩 + 비교)") {
            val okJson =
                (1..9).fold("""{"==": [{"var": "issue.status"}, "Done"]}""") { acc, _ ->
                    """{"not": $acc}"""
                }

            Condition.fromJson(okJson)
        }

        it("노드 수가 100을 초과하면 거부한다 (or 하위 101개 비교)") {
            val comparisons =
                (1..101).joinToString(",") { """{"==": [{"var": "issue.status"}, "v$it"]}""" }
            val bigJson = """{"or": [$comparisons]}"""

            shouldThrow<InvalidConditionExpressionException> {
                Condition.fromJson(bigJson)
            }
        }

        it("빈 문자열/파싱 불가능한 JSON은 거부한다") {
            shouldThrow<InvalidConditionExpressionException> { Condition.fromJson("") }
            shouldThrow<InvalidConditionExpressionException> { Condition.fromJson("{not-json") }
        }

        it("최상위가 JSON 객체가 아니면 거부한다") {
            shouldThrow<InvalidConditionExpressionException> { Condition.fromJson("[]") }
        }

        it("연산자 키가 2개 이상이면 거부한다") {
            shouldThrow<InvalidConditionExpressionException> {
                Condition.fromJson("""{"and": [], "or": []}""")
            }
        }

        it("비교 연산자의 피연산자가 2개 원소 배열이 아니면 거부한다") {
            shouldThrow<InvalidConditionExpressionException> {
                Condition.fromJson("""{"==": [{"var": "issue.status"}]}""")
            }
        }

        it("비교 연산자 양쪽 모두 var 이면 거부한다") {
            shouldThrow<InvalidConditionExpressionException> {
                Condition.fromJson("""{"==": [{"var": "issue.status"}, {"var": "issue.type"}]}""")
            }
        }

        it("비교 연산자 양쪽 모두 리터럴이면 거부한다 (var 참조 없음)") {
            shouldThrow<InvalidConditionExpressionException> {
                Condition.fromJson("""{"==": ["Done", "Closed"]}""")
            }
        }

        it("var 값이 문자열이 아니면 거부한다") {
            shouldThrow<InvalidConditionExpressionException> {
                Condition.fromJson("""{"==": [{"var": 123}, "Done"]}""")
            }
        }

        it("EMPTY(!) 연산자의 피연산자가 var 가 아니면 거부한다") {
            shouldThrow<InvalidConditionExpressionException> {
                Condition.fromJson("""{"!": "issue.assignee"}""")
            }
        }

        it("리터럴이 중첩 객체이면 거부한다") {
            shouldThrow<InvalidConditionExpressionException> {
                Condition.fromJson("""{"==": [{"var": "issue.status"}, {"nested": "object"}]}""")
            }
        }

        it("리터럴 배열 원소가 객체/배열이면 거부한다") {
            shouldThrow<InvalidConditionExpressionException> {
                Condition.fromJson("""{"in": [{"var": "issue.status"}, [{"nested": true}]]}""")
            }
        }

        it("리터럴 배열 원소가 100개를 초과하면 거부한다 (101개, DoS 방지 gate-2 P2-c)") {
            val elements = (1..101).joinToString(",") { "\"v$it\"" }

            shouldThrow<InvalidConditionExpressionException> {
                Condition.fromJson("""{"in": [{"var": "issue.labels"}, [$elements]]}""")
            }
        }

        it("리터럴 배열 원소가 100개면 통과한다 (경계값)") {
            val elements = (1..100).joinToString(",") { "\"v$it\"" }

            Condition.fromJson("""{"in": [{"var": "issue.labels"}, [$elements]]}""")
        }

        it("and/or 의 값이 배열이 아니면 거부한다") {
            shouldThrow<InvalidConditionExpressionException> {
                Condition.fromJson("""{"and": "not-an-array"}""")
            }
        }
    }

    describe("Condition — 필드 화이트리스트") {
        it("스펙에 명시된 9개 필드를 정확히 포함한다") {
            Condition.FIELD_WHITELIST shouldBe
                setOf(
                    "issue.key",
                    "issue.type",
                    "issue.status",
                    "issue.priority",
                    "issue.assignee",
                    "issue.reporter",
                    "issue.labels",
                    "issue.summary",
                    "issue.projectKey",
                )
        }
    }
})
