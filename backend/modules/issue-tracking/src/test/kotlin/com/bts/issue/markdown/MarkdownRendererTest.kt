// Markdown 렌더러 XSS 차단 및 정상 변환 검증 테스트
package com.bts.issue.markdown

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * MarkdownRenderer.renderSafe 에 대한 단위 테스트.
 *
 * XSS (Cross-Site Scripting) 페이로드 10종 차단과 정상 Markdown 보존을 검증한다.
 * CSRF ADR `docs/decisions/2026-05-20-csrf-cookie-mode.md` §서버 측 입력 sanitization 규정 준수.
 */
class MarkdownRendererTest : DescribeSpec({

    describe("MarkdownRenderer.renderSafe") {

        // ── XSS 차단 10종 ──────────────────────────────────────────────────────

        context("XSS 페이로드 차단") {

            it("1. script 태그를 제거한다") {
                val input = "<script>alert(1)</script>"
                val result = MarkdownRenderer.renderSafe(input)
                result shouldNotContain "<script"
                result shouldNotContain "alert(1)"
            }

            it("2. img onerror 이벤트 핸들러를 제거한다") {
                val input = "<img src=x onerror=alert(1)>"
                val result = MarkdownRenderer.renderSafe(input)
                result shouldNotContain "onerror"
                result shouldNotContain "alert(1)"
            }

            it("3. javascript: 스킴 링크를 제거한다") {
                val input = "[클릭](javascript:alert(1))"
                val result = MarkdownRenderer.renderSafe(input)
                result shouldNotContain "javascript:"
                result shouldNotContain "alert(1)"
            }

            it("4. iframe 태그를 제거한다") {
                val input = """<iframe src="evil"></iframe>"""
                val result = MarkdownRenderer.renderSafe(input)
                result shouldNotContain "<iframe"
                result shouldNotContain "evil"
            }

            it("5. data: URI 이미지를 제거한다") {
                val input = "![x](data:text/html,<script>alert(1)</script>)"
                val result = MarkdownRenderer.renderSafe(input)
                result shouldNotContain "data:text/html"
                result shouldNotContain "<script"
            }

            it("6. a href javascript: 스킴을 제거한다") {
                val input = """<a href="javascript:alert(1)">x</a>"""
                val result = MarkdownRenderer.renderSafe(input)
                result shouldNotContain "javascript:"
            }

            it("7. on* 이벤트 핸들러(onclick 등)를 제거한다") {
                val input = """<div onclick="alert(1)">x</div>"""
                val result = MarkdownRenderer.renderSafe(input)
                result shouldNotContain "onclick"
                result shouldNotContain "alert(1)"
            }

            it("8. style 태그의 javascript: 참조를 제거한다") {
                val input = "<style>body{background:url(javascript:alert(1))}</style>"
                val result = MarkdownRenderer.renderSafe(input)
                result shouldNotContain "<style"
                result shouldNotContain "javascript:"
            }

            it("9. HTML 엔티티 우회 및 중첩 태그를 무력화한다") {
                val entityInput = "&lt;script&gt;alert(1)&lt;/script&gt;"
                val nestedInput = "<scr<script>ipt>alert(1)</scr</script>ipt>"

                val entityResult = MarkdownRenderer.renderSafe(entityInput)
                // 엔티티 자체는 텍스트로 표시될 수 있지만 실행 가능한 스크립트가 되어선 안 된다.
                entityResult shouldNotContain "<script>"

                val nestedResult = MarkdownRenderer.renderSafe(nestedInput)
                nestedResult shouldNotContain "<script"
                nestedResult shouldNotContain "alert(1)"
            }

            it("10. svg onload 이벤트 핸들러를 제거한다") {
                val input = "<svg onload=alert(1)>"
                val result = MarkdownRenderer.renderSafe(input)
                result shouldNotContain "<svg"
                result shouldNotContain "onload"
                result shouldNotContain "alert(1)"
            }
        }

        // ── 정상 Markdown 보존 ─────────────────────────────────────────────────

        context("정상 Markdown 보존") {

            it("헤더(## x)를 h2 태그로 변환한다") {
                val input = "## 제목"
                val result = MarkdownRenderer.renderSafe(input)
                result shouldContain "<h2>"
                result shouldContain "제목"
            }

            it("순서 없는 리스트를 ul/li 태그로 변환한다") {
                val input = "- 항목1\n- 항목2"
                val result = MarkdownRenderer.renderSafe(input)
                result shouldContain "<ul>"
                result shouldContain "<li>"
                result shouldContain "항목1"
            }

            it("코드블록을 pre/code 태그로 변환하고 language 클래스를 보존한다") {
                val input = "```js\nconsole.log('hello');\n```"
                val result = MarkdownRenderer.renderSafe(input)
                result shouldContain "<pre>"
                result shouldContain "<code"
                result shouldContain "language-js"
                result shouldContain "console.log"
            }

            it("https 링크를 a 태그로 변환한다") {
                val input = "[방문](https://example.com)"
                val result = MarkdownRenderer.renderSafe(input)
                result shouldContain "<a"
                result shouldContain "https://example.com"
                result shouldContain "방문"
            }

            it("mailto 링크를 a 태그로 변환한다") {
                val input = "[메일](mailto:user@example.com)"
                val result = MarkdownRenderer.renderSafe(input)
                result shouldContain "<a"
                result shouldContain "mailto:user@example.com"
            }

            it("굵게(강조)를 strong 태그로 변환한다") {
                val input = "**중요**"
                val result = MarkdownRenderer.renderSafe(input)
                result shouldContain "<strong>"
                result shouldContain "중요"
            }

            it("깨진 Markdown도 예외 없이 결과를 반환한다") {
                val input = "```unclosed code block"
                val result = MarkdownRenderer.renderSafe(input)
                // 예외가 발생하지 않고 결과가 반환되어야 한다.
                result shouldContain "unclosed code block"
            }
        }
    }
})
