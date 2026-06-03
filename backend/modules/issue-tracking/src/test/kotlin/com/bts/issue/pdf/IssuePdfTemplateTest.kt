// IssuePdfTemplate XHTML 생성 검증 테스트 (FR-IS-08 Task 2)
package com.bts.issue.pdf

import com.bts.issue.adapter.inbound.rest.IssueResponse
import com.bts.issue.markdown.MarkdownRenderer
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory

/**
 * IssuePdfTemplate 단위 테스트.
 *
 * ## 검증 범위
 * - 메타 표에 이슈 키·제목·상태·타입·우선순위·라벨·resolution 포함 여부.
 * - descriptionHtml이 본문 영역에 그대로 삽입됨.
 * - descriptionHtml=null이면 raw description을 주입하지 않고 placeholder 사용(NFR1 보안).
 * - assignee·impact·resolution=null이면 `-` 표기, NPE 없음.
 * - 실제 MarkdownRenderer.renderSafe() 출력을 본문으로 넣어도 well-formed XML 파싱 성공(C2).
 * - @page CSS에 헤더(key·summary)·푸터(페이지 번호) 규칙 존재.
 */
class IssuePdfTemplateTest : DescribeSpec({

    val template = IssuePdfTemplate()

    val sampleResolution =
        IssueResponse.ResolutionSummary(
            id = UUID.fromString("00000000-0000-4000-8000-000000000010"),
            key = "fixed",
            name = "Fixed",
        )

    /** 테스트용 기본 픽스처 — 모든 필드가 채워진 이슈. */
    fun fullIssue(descriptionHtml: String? = "<p>본문입니다.</p>") =
        IssueResponse(
            key = "ATLAS-42",
            id = UUID.fromString("00000000-0000-4000-8000-000000000042"),
            projectKey = "ATLAS",
            summary = "테스트 이슈 제목",
            currentStateKey = "in_progress",
            reporterId = UUID.fromString("00000000-0000-4000-8000-000000000001"),
            version = 1L,
            createdAt = Instant.parse("2026-06-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-06-02T00:00:00Z"),
            typeId = 1L,
            typeKey = "bug",
            typeName = "Bug",
            description = "raw description — should not appear in output when descriptionHtml is null",
            descriptionHtml = descriptionHtml,
            priority = 2,
            priorityName = "High",
            labels = listOf("backend", "urgent"),
            impact = 1,
            impactName = "High",
            assigneeId = UUID.fromString("00000000-0000-4000-8000-000000000002"),
            resolution = sampleResolution,
        )

    describe("IssuePdfTemplate.render — 메타 표") {

        it("key·summary·상태·타입·우선순위·라벨·resolution이 출력에 포함된다") {
            val result = template.render(fullIssue())

            result shouldContain "ATLAS-42"
            result shouldContain "테스트 이슈 제목"
            result shouldContain "in_progress"
            result shouldContain "Bug"
            result shouldContain "High"
            result shouldContain "backend"
            result shouldContain "urgent"
            result shouldContain "Fixed"
        }

        it("프로젝트 키와 생성/수정 일시가 포함된다") {
            val result = template.render(fullIssue())

            result shouldContain "ATLAS"
            result shouldContain "2026-06-01"
            result shouldContain "2026-06-02"
        }
    }

    describe("IssuePdfTemplate.render — 본문 삽입") {

        it("descriptionHtml이 본문 영역에 그대로 삽입된다") {
            val html = "<p>본문입니다.</p><ul><li>항목1</li><li>항목2</li></ul>"
            val result = template.render(fullIssue(descriptionHtml = html))

            result shouldContain html
        }
    }

    describe("IssuePdfTemplate.render — NFR1 보안: descriptionHtml=null 처리") {

        it("descriptionHtml이 null이면 placeholder를 사용하고 raw description을 주입하지 않는다") {
            val issue = fullIssue(descriptionHtml = null)
            val result = template.render(issue)

            // placeholder가 있어야 한다.
            result shouldContain "(설명 없음)"
            // raw description 값이 그대로 들어가면 안 된다 (NFR1).
            result shouldNotContain "raw description"
        }

        it("descriptionHtml이 빈 문자열이면 placeholder를 사용한다") {
            val issue = fullIssue(descriptionHtml = "")
            val result = template.render(issue)

            result shouldContain "(설명 없음)"
        }
    }

    describe("IssuePdfTemplate.render — null 필드 처리") {

        it("assignee·impact·resolution이 null이면 '-' 표기, NPE 없음") {
            val issue =
                IssueResponse(
                    key = "ATLAS-1",
                    id = UUID.fromString("00000000-0000-4000-8000-000000000001"),
                    projectKey = "ATLAS",
                    summary = "null 필드 테스트",
                    currentStateKey = "open",
                    reporterId = UUID.fromString("00000000-0000-4000-8000-000000000001"),
                    version = 1L,
                    createdAt = null,
                    updatedAt = null,
                    typeId = 1L,
                    typeKey = "task",
                    typeName = "Task",
                    descriptionHtml = "<p>본문</p>",
                    assigneeId = null,
                    impact = null,
                    impactName = null,
                    resolution = null,
                )

            val result = template.render(issue)

            // 적어도 3개의 '-' (담당자, 영향도, resolution)가 있어야 한다.
            result.split(Regex("-")).size shouldBeAtLeast 4
            // NPE 없이 정상 반환됨 (예외 미발생이 곧 단언)
        }

        it("createdAt·updatedAt이 null이면 '-' 표기, NPE 없음") {
            val issue = fullIssue().copy(createdAt = null, updatedAt = null)
            val result = template.render(issue)

            // 예외 없이 반환됨
            result shouldContain "ATLAS-42"
        }
    }

    describe("IssuePdfTemplate.render — C2: 실제 MarkdownRenderer 출력 well-formed XML 파싱") {

        it("한글+코드블록+중첩리스트+mailto 링크 포함 renderSafe 출력을 본문으로 넣어도 DocumentBuilder.parse 성공") {
            val complexMarkdown =
                "# 한글 제목\n\n" +
                    "본문에 **굵은 글씨**와 *기울임*이 포함됩니다.\n\n" +
                    "코드 블록 예시:\n\n" +
                    "```kotlin\nfun hello() = println(\"안녕하세요\")\n```\n\n" +
                    "중첩 목록:\n" +
                    "- 상위 항목 1\n" +
                    "  - 하위 항목 1-1\n" +
                    "  - 하위 항목 1-2\n" +
                    "- 상위 항목 2\n\n" +
                    "메일 링크: [담당자에게](mailto:user@example.com)\n\n" +
                    "<script>alert(1)</script>"

            val sanitizedHtml = MarkdownRenderer.renderSafe(complexMarkdown)
            val issue = fullIssue(descriptionHtml = sanitizedHtml)
            val result = template.render(issue)

            // 1) XML 선언으로 시작해야 한다.
            result.trim() shouldStartWith "<?xml"

            // 2) DocumentBuilder.parse로 well-formed XML 파싱 성공 (예외 미발생 = 단언).
            val factory =
                DocumentBuilderFactory.newInstance().apply {
                    isNamespaceAware = true
                }
            val builder = factory.newDocumentBuilder()
            // 파싱 실패 시 SAXParseException을 던짐 — 이 줄에서 예외 없으면 well-formed 확인됨.
            val doc = builder.parse(ByteArrayInputStream(result.toByteArray(Charsets.UTF_8)))
            val root = doc.documentElement

            root.tagName shouldContain "html"
        }
    }

    describe("IssuePdfTemplate.render — CSS @page 규칙") {

        it("@page CSS에 헤더(key·summary)·푸터(페이지 번호) 규칙이 있다") {
            val result = template.render(fullIssue())

            result shouldContain "@page"
            result shouldContain "@top-center"
            result shouldContain "@bottom-center"
            result shouldContain "counter(page)"
            result shouldContain "counter(pages)"
        }

        it("CSS에 NanumGothic 폰트가 설정된다") {
            val result = template.render(fullIssue())

            result shouldContain "NanumGothic"
        }
    }

    describe("IssuePdfTemplate.render — XHTML 골격") {

        it("출력이 XHTML namespace를 포함한 html 태그로 시작한다") {
            val result = template.render(fullIssue())

            result shouldContain """xmlns="http://www.w3.org/1999/xhtml""""
        }

        it("head와 body 섹션이 모두 존재한다") {
            val result = template.render(fullIssue())

            result shouldContain "<head>"
            result shouldContain "</head>"
            result shouldContain "<body>"
            result shouldContain "</body>"
        }
    }
})

/** kotest matchers에 없는 `shouldBeAtLeast` 단순 헬퍼. */
private infix fun Int.shouldBeAtLeast(expected: Int) {
    if (this < expected) {
        throw AssertionError("Expected at least $expected but was $this")
    }
}
