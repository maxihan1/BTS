// TemplateRenderer.render — {{ path.to.var }} 치환/공백 변형/중첩경로/미정의/미닫힘 문법오류 단위 테스트

package com.bts.automation.application

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.slf4j.LoggerFactory

class TemplateRendererTest : DescribeSpec({

    val context =
        mapOf(
            "issue" to
                mapOf(
                    "key" to "PROJ-123",
                    "status" to null,
                    "assignee" to mapOf("name" to "홍길동"),
                ),
            "count" to 5,
        )

    describe("render — 단일 변수 치환") {
        it("{{ issue.key }} 를 context 값으로 치환한다") {
            TemplateRenderer.render("{{ issue.key }}", context) shouldBe "PROJ-123"
        }

        it("문자열이 아닌 값(Int)도 toString 으로 치환한다") {
            TemplateRenderer.render("{{ count }}", context) shouldBe "5"
        }

        it("변수가 없는 문자열은 그대로 반환한다") {
            TemplateRenderer.render("변수 없는 문자열", context) shouldBe "변수 없는 문자열"
        }
    }

    describe("render — 공백 변형") {
        it("공백 없는 {{issue.key}} 와 공백 있는 {{ issue.key }} 는 동일하게 치환된다") {
            TemplateRenderer.render("{{issue.key}}", context) shouldBe "PROJ-123"
            TemplateRenderer.render("{{ issue.key }}", context) shouldBe "PROJ-123"
        }

        it("중괄호 안 여러 공백도 허용한다") {
            TemplateRenderer.render("{{   issue.key   }}", context) shouldBe "PROJ-123"
        }
    }

    describe("render — 중첩 경로") {
        it("issue.assignee.name 처럼 2단계 이상 중첩된 Map 을 재귀적으로 해석한다") {
            TemplateRenderer.render("{{ issue.assignee.name }}", context) shouldBe "홍길동"
        }
    }

    describe("render — 여러 변수 한 문자열") {
        it("한 문자열에 등장하는 모든 변수를 각각 치환한다") {
            val result =
                TemplateRenderer.render(
                    "이슈 {{ issue.key }} 담당자 {{ issue.assignee.name }}",
                    context,
                )

            result shouldBe "이슈 PROJ-123 담당자 홍길동"
        }
    }

    describe("render — 미정의 변수는 빈 문자열 + 경고 로그") {
        it("context 에 키 자체가 없으면 빈 문자열로 치환하고 경고 로그를 남긴다") {
            val warns = captureWarnLogs { TemplateRenderer.render("{{ issue.reporter }}", context) shouldBe "" }

            warns.size shouldBe 1
            warns.first() shouldBe "automation_template_variable_undefined path=issue.reporter"
        }

        it("값이 명시적으로 null 이어도 빈 문자열로 치환하고 경고 로그를 남긴다") {
            val warns = captureWarnLogs { TemplateRenderer.render("{{ issue.status }}", context) shouldBe "" }

            warns.size shouldBe 1
            warns.first() shouldBe "automation_template_variable_undefined path=issue.status"
        }

        it("중간 경로가 Map 이 아니면(예: issue.key.nested) 빈 문자열로 치환하고 경고 로그를 남긴다") {
            val warns =
                captureWarnLogs {
                    TemplateRenderer.render("{{ issue.key.nested }}", context) shouldBe ""
                }

            warns.size shouldBe 1
            warns.first() shouldBe "automation_template_variable_undefined path=issue.key.nested"
        }

        it("context 가 빈 맵이면 모든 변수가 미정의로 처리된다") {
            val warns =
                captureWarnLogs {
                    TemplateRenderer.render("{{ issue.key }}", emptyMap()) shouldBe ""
                }

            warns.size shouldBe 1
            warns.first() shouldBe "automation_template_variable_undefined path=issue.key"
        }

        it("경고 로그는 변수 경로만 남기고 실제 값(PII 가능성)은 로깅하지 않는다") {
            val piiContext = mapOf("user" to mapOf("email" to null))

            val warns =
                captureWarnLogs {
                    TemplateRenderer.render("{{ user.email }}", piiContext) shouldBe ""
                }

            warns.first() shouldBe "automation_template_variable_undefined path=user.email"
        }
    }

    describe("render — 미닫힘 {{ (문법 오류) 는 리터럴 그대로 유지") {
        it("닫히지 않은 {{ 는 예외 없이 원본 그대로 유지된다") {
            TemplateRenderer.render("Hello {{ issue.key", context) shouldBe "Hello {{ issue.key"
        }

        it("정상 변수 뒤에 미닫힘 변수가 와도 정상 변수만 치환되고 미닫힘은 리터럴 유지된다") {
            val result = TemplateRenderer.render("{{ issue.key }} and {{ broken", context)

            result shouldBe "PROJ-123 and {{ broken"
        }
    }

    describe("render — context 빈 맵") {
        it("변수가 없는 템플릿은 빈 맵이어도 그대로 반환한다") {
            TemplateRenderer.render("정적 문자열", emptyMap()) shouldBe "정적 문자열"
        }
    }
})

/**
 * [block] 실행 중 [TemplateRenderer] 로거에 쌓인 WARN 레벨 로그의 포맷 메시지 목록을 반환한다
 * ([ListAppender] 부착 후 실행, [com.atlas.bts.identity.calendar.CalendarFeedServiceTest] 동형 패턴).
 */
private fun captureWarnLogs(block: () -> Unit): List<String> {
    val logger = LoggerFactory.getLogger(TemplateRenderer::class.java) as Logger
    val appender = ListAppender<ILoggingEvent>().also { it.start() }
    logger.addAppender(appender)
    try {
        block()
    } finally {
        logger.detachAppender(appender)
    }
    return appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
}
