// ActionType 4종 + Action.fromJson/ActionConfig.validate 형식 검증 단위 테스트

package com.bts.automation.domain

import com.fasterxml.jackson.databind.node.BooleanNode
import com.fasterxml.jackson.databind.node.IntNode
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.databind.node.TextNode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class ActionTest : DescribeSpec({

    describe("ActionType — enum 4종") {
        it("4종 모두 정의된다") {
            ActionType.entries.size shouldBe 4
        }

        it("SET_FIELD/ASSIGN/ADD_COMMENT/CALL_WEBHOOK 을 포함한다") {
            ActionType.entries.toSet() shouldBe
                setOf(
                    ActionType.SET_FIELD,
                    ActionType.ASSIGN,
                    ActionType.ADD_COMMENT,
                    ActionType.CALL_WEBHOOK,
                )
        }
    }

    describe("Action.fromJson — SET_FIELD") {
        it("field/value 가 유효하면 파싱된다") {
            val action = Action.fromJson(ActionType.SET_FIELD, """{"field":"priority","value":"High"}""")

            action shouldBe Action.SetFieldAction(field = "priority", value = TextNode("High"))
        }

        it("value 가 임의 JSON 타입(숫자/불리언/null)이어도 파싱된다") {
            val numberAction = Action.fromJson(ActionType.SET_FIELD, """{"field":"storyPoints","value":5}""")
            (numberAction as Action.SetFieldAction).value shouldBe IntNode(5)

            val boolAction = Action.fromJson(ActionType.SET_FIELD, """{"field":"flag","value":true}""")
            (boolAction as Action.SetFieldAction).value shouldBe BooleanNode.TRUE

            val nullAction = Action.fromJson(ActionType.SET_FIELD, """{"field":"assignee","value":null}""")
            (nullAction as Action.SetFieldAction).value shouldBe NullNode.instance
        }

        it("field 가 없으면 거부한다") {
            shouldThrow<ActionConfigInvalidException> {
                Action.fromJson(ActionType.SET_FIELD, """{"value":"High"}""")
            }
        }

        it("field 가 빈 문자열이면 거부한다") {
            shouldThrow<ActionConfigInvalidException> {
                Action.fromJson(ActionType.SET_FIELD, """{"field":"","value":"High"}""")
            }
        }

        it("field 가 문자열이 아니면 거부한다") {
            shouldThrow<ActionConfigInvalidException> {
                Action.fromJson(ActionType.SET_FIELD, """{"field":123,"value":"High"}""")
            }
        }

        it("value 키가 없으면 거부한다") {
            shouldThrow<ActionConfigInvalidException> {
                Action.fromJson(ActionType.SET_FIELD, """{"field":"priority"}""")
            }
        }
    }

    describe("Action.fromJson — ASSIGN") {
        it("assigneeId 가 유효한 uuid 이면 파싱된다") {
            val id = UUID.randomUUID()

            val action = Action.fromJson(ActionType.ASSIGN, """{"assigneeId":"$id"}""")

            action shouldBe Action.AssignAction(assigneeId = id)
        }

        it("assigneeId 가 null 이면 담당자 해제로 파싱된다") {
            val action = Action.fromJson(ActionType.ASSIGN, """{"assigneeId":null}""")

            action shouldBe Action.AssignAction(assigneeId = null)
        }

        it("assigneeId 키가 없으면 거부한다") {
            shouldThrow<ActionConfigInvalidException> {
                Action.fromJson(ActionType.ASSIGN, "{}")
            }
        }

        it("assigneeId 가 uuid 형식이 아니면 거부한다") {
            shouldThrow<ActionConfigInvalidException> {
                Action.fromJson(ActionType.ASSIGN, """{"assigneeId":"not-a-uuid"}""")
            }
        }

        it("assigneeId 가 문자열/null 이 아니면 거부한다") {
            shouldThrow<ActionConfigInvalidException> {
                Action.fromJson(ActionType.ASSIGN, """{"assigneeId":123}""")
            }
        }
    }

    describe("Action.fromJson — ADD_COMMENT") {
        it("body 가 있으면 파싱된다") {
            val action = Action.fromJson(ActionType.ADD_COMMENT, """{"body":"완료했습니다"}""")

            action shouldBe Action.AddCommentAction(body = "완료했습니다")
        }

        it("body 가 없으면 거부한다") {
            shouldThrow<ActionConfigInvalidException> {
                Action.fromJson(ActionType.ADD_COMMENT, "{}")
            }
        }

        it("body 가 빈 문자열이면 거부한다") {
            shouldThrow<ActionConfigInvalidException> {
                Action.fromJson(ActionType.ADD_COMMENT, """{"body":""}""")
            }
        }

        it("body 가 공백만이면 거부한다") {
            shouldThrow<ActionConfigInvalidException> {
                Action.fromJson(ActionType.ADD_COMMENT, """{"body":"   "}""")
            }
        }

        it("body 가 문자열이 아니면 거부한다") {
            shouldThrow<ActionConfigInvalidException> {
                Action.fromJson(ActionType.ADD_COMMENT, """{"body":123}""")
            }
        }
    }

    describe("Action.fromJson — CALL_WEBHOOK") {
        it("url 만 있으면 기본 method=POST, headers 빈 맵, body 빈 문자열로 파싱된다") {
            val action = Action.fromJson(ActionType.CALL_WEBHOOK, """{"url":"https://example.com/hook"}""")

            action shouldBe
                Action.CallWebhookAction(
                    url = "https://example.com/hook",
                    method = "POST",
                    headers = emptyMap(),
                    body = "",
                )
        }

        it("method/headers/body 를 명시하면 그대로 파싱된다") {
            val action =
                Action.fromJson(
                    ActionType.CALL_WEBHOOK,
                    """{"url":"https://example.com/hook","method":"GET",""" +
                        """"headers":{"X-Token":"abc"},"body":"payload"}""",
                )

            action shouldBe
                Action.CallWebhookAction(
                    url = "https://example.com/hook",
                    method = "GET",
                    headers = mapOf("X-Token" to "abc"),
                    body = "payload",
                )
        }

        it("url 이 없으면 거부한다") {
            shouldThrow<ActionConfigInvalidException> {
                Action.fromJson(ActionType.CALL_WEBHOOK, "{}")
            }
        }

        it("url 이 빈 문자열이면 거부한다") {
            shouldThrow<ActionConfigInvalidException> {
                Action.fromJson(ActionType.CALL_WEBHOOK, """{"url":""}""")
            }
        }

        it("url 스킴이 http/https 가 아니면 거부한다") {
            shouldThrow<ActionConfigInvalidException> {
                Action.fromJson(ActionType.CALL_WEBHOOK, """{"url":"ftp://example.com"}""")
            }
        }

        it("url 에 스킴이 없으면 거부한다") {
            shouldThrow<ActionConfigInvalidException> {
                Action.fromJson(ActionType.CALL_WEBHOOK, """{"url":"example.com/hook"}""")
            }
        }

        it("url 에 {{ 템플릿 }} 이 포함돼도 http(s) 로 시작하면 config 검증을 통과한다") {
            // CallWebhookAction.url 은 실행 시점 템플릿 치환을 지원한다(ADR D3b). 템플릿 값은
            // URI 로 파싱 불가능(`{{`/`}}` 는 유효 URI 문자가 아님)하므로 config 시점은 엄격 URI
            // 파싱 대신 스킴 prefix 문자열 검사만 한다 — 엄격 검증(URI 파싱+SSRF)은 템플릿이 렌더된
            // 뒤 실행 시점 OutboundUrlValidator 가 수행한다([WebhookActionClient] 참고).
            val action =
                Action.fromJson(ActionType.CALL_WEBHOOK, """{"url":"https://{{ issue.host }}/hook"}""")

            action shouldBe
                Action.CallWebhookAction(
                    url = "https://{{ issue.host }}/hook",
                    method = "POST",
                    headers = emptyMap(),
                    body = "",
                )
        }

        it("url 이 파싱 불가능한 문자열이어도 http(s) 로 시작하면 config 검증을 통과한다(엄격 검증은 실행 시점)") {
            val action =
                Action.fromJson(ActionType.CALL_WEBHOOK, """{"url":"https://example.com/ bad"}""")

            (action as Action.CallWebhookAction).url shouldBe "https://example.com/ bad"
        }

        it("headers 가 객체가 아니면 거부한다") {
            shouldThrow<ActionConfigInvalidException> {
                Action.fromJson(
                    ActionType.CALL_WEBHOOK,
                    """{"url":"https://example.com","headers":"not-an-object"}""",
                )
            }
        }

        it("headers 값이 문자열이 아니면 거부한다") {
            shouldThrow<ActionConfigInvalidException> {
                Action.fromJson(
                    ActionType.CALL_WEBHOOK,
                    """{"url":"https://example.com","headers":{"X-Count":1}}""",
                )
            }
        }
    }

    describe("ActionConfig.validate — 공통 형식 오류") {
        it("빈 문자열은 모든 타입에서 거부한다") {
            shouldThrow<ActionConfigInvalidException> {
                ActionConfig.validate(ActionType.ADD_COMMENT, "")
            }
        }

        it("파싱 불가능한 JSON 은 거부한다") {
            shouldThrow<ActionConfigInvalidException> {
                ActionConfig.validate(ActionType.ADD_COMMENT, "{not-json")
            }
        }

        it("JSON 객체가 아니면(배열 등) 거부한다") {
            shouldThrow<ActionConfigInvalidException> {
                ActionConfig.validate(ActionType.ADD_COMMENT, "[]")
            }
        }

        it("4종 모두 유효한 config 로 검증을 통과한다") {
            ActionConfig.validate(ActionType.SET_FIELD, """{"field":"priority","value":"High"}""")
            ActionConfig.validate(ActionType.ASSIGN, """{"assigneeId":null}""")
            ActionConfig.validate(ActionType.ADD_COMMENT, """{"body":"완료"}""")
            ActionConfig.validate(ActionType.CALL_WEBHOOK, """{"url":"https://example.com/hook"}""")
        }
    }
})
