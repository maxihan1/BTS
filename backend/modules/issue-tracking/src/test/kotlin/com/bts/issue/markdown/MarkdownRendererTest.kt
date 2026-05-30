// Markdown 렌더러 XSS 차단 및 정상 변환 검증 테스트
package com.bts.issue.markdown

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * MarkdownRenderer.renderSafe 에 대한 단위 테스트.
 *
 * ## XSS 차단 기준
 * "결과 HTML에 실행 가능한 코드 0건"이 기준이다.
 *   - 실행 가능: `<script>`, `onerror=value`(이벤트 핸들러 어트리뷰트), `href="javascript:"` 등 브라우저가 평가하는 형태.
 *   - 실행 불가: `&lt;script&gt;`, `onerror&#61;value`(HTML 엔티티 escape) — 텍스트 노드로 표시만 됨.
 *   - 실행 불가: `<p>alert(1)</p>` — 텍스트 노드로 감싸진 것은 실행되지 않음.
 *
 * CSRF ADR `docs/decisions/2026-05-20-csrf-cookie-mode.md` §서버 측 입력 sanitization 규정 준수.
 */
class MarkdownRendererTest : DescribeSpec({

    describe("MarkdownRenderer.renderSafe") {

        // ── XSS 차단 10종 ──────────────────────────────────────────────────────

        context("XSS 페이로드 차단") {

            it("1. script 태그를 제거한다") {
                val input = "<script>alert(1)</script>"
                val result = MarkdownRenderer.renderSafe(input)
                // 실행 가능한 <script> 태그가 없어야 한다.
                result shouldNotContain "<script"
                result shouldNotContain "</script>"
            }

            it("2. img onerror 이벤트 핸들러를 제거한다") {
                val input = "<img src=x onerror=alert(1)>"
                val result = MarkdownRenderer.renderSafe(input)
                // 브라우저가 평가하는 이벤트 핸들러 어트리뷰트 형태(onerror=값)가 없어야 한다.
                // HTML 엔티티 escape된 onerror&#61; 형태는 실행 불가이므로 허용.
                result shouldNotContain "onerror="
                result shouldNotContain "<img"
            }

            it("3. javascript: 스킴 링크를 제거한다") {
                val input = "[클릭](javascript:alert(1))"
                val result = MarkdownRenderer.renderSafe(input)
                // href 어트리뷰트 안에 javascript: 가 없어야 한다.
                result shouldNotContain "href=\"javascript:"
                result shouldNotContain "href='javascript:"
            }

            it("4. iframe 태그를 제거한다") {
                val input = """<iframe src="evil"></iframe>"""
                val result = MarkdownRenderer.renderSafe(input)
                result shouldNotContain "<iframe"
            }

            it("5. data: URI 이미지를 제거한다") {
                val input = "![x](data:text/html,<script>alert(1)</script>)"
                val result = MarkdownRenderer.renderSafe(input)
                // src 어트리뷰트에 data:가 없어야 한다.
                result shouldNotContain "src=\"data:"
                result shouldNotContain "<script"
            }

            it("6. a href javascript: 스킴을 제거한다") {
                val input = """<a href="javascript:alert(1)">x</a>"""
                val result = MarkdownRenderer.renderSafe(input)
                // href 어트리뷰트에 javascript: 가 없어야 한다.
                result shouldNotContain "href=\"javascript:"
            }

            it("7. on* 이벤트 핸들러(onclick 등)를 제거한다") {
                val input = """<div onclick="alert(1)">x</div>"""
                val result = MarkdownRenderer.renderSafe(input)
                // 브라우저가 평가하는 어트리뷰트 형태(onclick="값")가 없어야 한다.
                result shouldNotContain "onclick="
                result shouldNotContain "<div"
            }

            it("8. style 태그를 제거한다") {
                val input = "<style>body{background:url(javascript:alert(1))}</style>"
                val result = MarkdownRenderer.renderSafe(input)
                result shouldNotContain "<style"
            }

            it("9. HTML 엔티티 우회 및 중첩 태그를 무력화한다") {
                // &lt;script&gt;는 브라우저가 텍스트로만 표시 — 실행 불가.
                val entityInput = "&lt;script&gt;alert(1)&lt;/script&gt;"
                val entityResult = MarkdownRenderer.renderSafe(entityInput)
                // 실행 가능한 <script> 태그 형태가 없어야 한다.
                entityResult shouldNotContain "<script>"

                // 중첩 태그 우회 시도 — 최종 결과에 실행 가능한 script 태그가 없어야 한다.
                val nestedInput = "<scr<script>ipt>alert(1)</scr</script>ipt>"
                val nestedResult = MarkdownRenderer.renderSafe(nestedInput)
                nestedResult shouldNotContain "<script"
            }

            it("10. svg onload 이벤트 핸들러를 제거한다") {
                val input = "<svg onload=alert(1)>"
                val result = MarkdownRenderer.renderSafe(input)
                result shouldNotContain "<svg"
                // 브라우저가 평가하는 어트리뷰트 형태 없어야 한다.
                result shouldNotContain "onload="
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
                result shouldContain "mailto:"
                // @ 문자는 OWASP sanitizer가 &#64; 엔티티로 인코딩 — 브라우저에서 동일하게 동작.
                result shouldContain "example.com"
                result shouldContain "메일"
            }

            it("굵게(강조)를 strong 태그로 변환한다") {
                val input = "**중요**"
                val result = MarkdownRenderer.renderSafe(input)
                result shouldContain "<strong>"
                result shouldContain "중요"
            }

            it("깨진 Markdown도 예외 없이 결과를 반환한다") {
                // 닫히지 않은 코드 펜스 — flexmark가 어떻게 처리하든 예외 없이 반환되어야 한다.
                val input = "```unclosed code block"
                val result = MarkdownRenderer.renderSafe(input)
                // XSS 공격 태그 없음만 보장하면 된다.
                result shouldNotContain "<script"
                result shouldNotContain "onerror="
            }
        }
    }
})
