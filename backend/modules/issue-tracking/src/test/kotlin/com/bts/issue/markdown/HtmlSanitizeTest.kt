// 에디터가 보낸 HTML 을 저장 전에 정화하는 sanitizeHtml + 확장된 allowlist 검증
package com.bts.issue.markdown

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * [MarkdownRenderer.sanitizeHtml] 및 확장된 allowlist 에 대한 단위 테스트.
 *
 * ## 왜 진입점이 둘인가
 * `renderSafe` 는 **markdown 을 받아** flexmark 로 렌더한 뒤 정화한다 — CSV import 와 기존 데이터
 * 읽기 fallback 이 쓴다. `sanitizeHtml` 은 **이미 HTML 인 입력**(TipTap 에디터 출력)을 정화만 한다.
 * 정책(allowlist)은 하나를 공유한다 — 둘이 갈라지면 한쪽으로 들어온 XSS 가 다른 쪽 테스트를
 * 통과한 채 살아남는다.
 *
 * ## 이미지 정책이 특별한 이유
 * 첨부 다운로드는 `Authorization: Bearer` 헤더 인증이라 `<img src="/api/...">` 가 애초에 뜨지 않는다.
 * 그래서 본문 이미지는 `attachment:<uuid>` 라는 **참조**로 저장하고 프론트가 렌더 시점에 blob 으로
 * 바꾼다. allowlist 는 그 형태만 통과시킨다 — `http(s)` 외부 이미지를 열어 주면 트래킹 픽셀과
 * 혼합 콘텐츠가 함께 들어온다.
 */
class HtmlSanitizeTest : DescribeSpec({

    describe("MarkdownRenderer.sanitizeHtml — 에디터 HTML 정화") {

        context("XSS 차단 — renderSafe 와 같은 방어선") {

            it("script 태그와 내용을 통째로 제거한다") {
                val result = MarkdownRenderer.sanitizeHtml("<p>ok</p><script>alert(1)</script>")
                result shouldNotContain "<script"
                result shouldNotContain "alert(1)"
                result shouldContain "ok"
            }

            it("이벤트 핸들러 속성을 제거한다") {
                val result = MarkdownRenderer.sanitizeHtml("""<p onclick="alert(1)">본문</p>""")
                result shouldNotContain "onclick"
                result shouldContain "본문"
            }

            it("iframe 을 제거한다") {
                val result = MarkdownRenderer.sanitizeHtml("""<iframe src="https://evil"></iframe>""")
                result shouldNotContain "<iframe"
            }

            it("javascript: 링크를 제거한다") {
                val result = MarkdownRenderer.sanitizeHtml("""<a href="javascript:alert(1)">클릭</a>""")
                result shouldNotContain "javascript:"
            }

            it("style 속성을 제거한다") {
                val result = MarkdownRenderer.sanitizeHtml("""<p style="position:fixed">본문</p>""")
                result shouldNotContain "style="
            }
        }

        context("서식 태그 통과 — Jira 전수 (J8)") {

            it("굵게·기울임을 통과시킨다") {
                val result = MarkdownRenderer.sanitizeHtml("<p><strong>굵게</strong><em>기울임</em></p>")
                result shouldContain "<strong>굵게</strong>"
                result shouldContain "<em>기울임</em>"
            }

            it("밑줄(u)을 통과시킨다 — 편차 X2") {
                MarkdownRenderer.sanitizeHtml("<p><u>밑줄</u></p>") shouldContain "<u>밑줄</u>"
            }

            it("취소선(del·s)을 통과시킨다") {
                MarkdownRenderer.sanitizeHtml("<p><del>지움</del></p>") shouldContain "<del>지움</del>"
                MarkdownRenderer.sanitizeHtml("<p><s>지움</s></p>") shouldContain "<s>지움</s>"
            }

            it("구분선(hr)을 통과시킨다") {
                MarkdownRenderer.sanitizeHtml("<p>위</p><hr /><p>아래</p>") shouldContain "<hr"
            }

            it("표 전체 구조를 통과시킨다") {
                val html =
                    "<table><thead><tr><th>머리</th></tr></thead>" +
                        "<tbody><tr><td>값</td></tr></tbody></table>"
                val result = MarkdownRenderer.sanitizeHtml(html)
                result shouldContain "<table>"
                result shouldContain "<thead>"
                result shouldContain "<th>머리</th>"
                result shouldContain "<td>값</td>"
            }

            it("체크박스 목록을 통과시킨다") {
                val html = """<ul><li><input type="checkbox" disabled="disabled" checked="checked" />할 일</li></ul>"""
                val result = MarkdownRenderer.sanitizeHtml(html)
                result shouldContain "<input"
                result shouldContain "checkbox"
            }

            it("체크박스 input 은 type=checkbox 만 허용한다 — text 입력창 주입 차단") {
                val result = MarkdownRenderer.sanitizeHtml("""<input type="text" name="pw" />""")
                result shouldNotContain "<input"
            }

            it("제목 h1~h6 을 통과시킨다") {
                val result = MarkdownRenderer.sanitizeHtml("<h1>일</h1><h6>육</h6>")
                result shouldContain "<h1>일</h1>"
                result shouldContain "<h6>육</h6>"
            }
        }

        context("이미지 — attachment 참조만 허용 (J7)") {

            it("attachment:<uuid> src 를 통과시킨다") {
                val uuid = "3f8a1c2e-5b6d-4e7f-9a0b-1c2d3e4f5a6b"
                val result = MarkdownRenderer.sanitizeHtml("""<img src="attachment:$uuid" alt="화면" />""")
                result shouldContain "attachment:$uuid"
                result shouldContain "화면"
            }

            it("http 외부 이미지 src 를 제거한다") {
                val result = MarkdownRenderer.sanitizeHtml("""<img src="http://evil.example/x.png" />""")
                result shouldNotContain "evil.example"
            }

            it("https 외부 이미지 src 도 제거한다 — 트래킹 픽셀 차단") {
                val result = MarkdownRenderer.sanitizeHtml("""<img src="https://tracker.example/p.gif" />""")
                result shouldNotContain "tracker.example"
            }

            it("data: URI src 를 제거한다") {
                val result =
                    MarkdownRenderer.sanitizeHtml(
                        """<img src="data:image/svg+xml;base64,PHN2Zz48L3N2Zz4=" />""",
                    )
                result shouldNotContain "data:image"
            }

            it("attachment 스킴이되 UUID 형태가 아니면 제거한다") {
                val result = MarkdownRenderer.sanitizeHtml("""<img src="attachment:../../etc/passwd" />""")
                result shouldNotContain "etc/passwd"
            }

            it("img onerror 를 제거한다") {
                val uuid = "3f8a1c2e-5b6d-4e7f-9a0b-1c2d3e4f5a6b"
                val result =
                    MarkdownRenderer.sanitizeHtml("""<img src="attachment:$uuid" onerror="alert(1)" />""")
                result shouldNotContain "onerror"
            }
        }

        context("멘션 마크업 보존") {

            it("span.mention 을 통과시킨다") {
                // OWASP 는 `@` 를 `&#64;` 엔티티로 인코딩한다 — 브라우저는 `@` 로 렌더하므로 안전하고,
                // `MarkdownRendererMentionTest` 도 같은 형식으로 단언한다.
                val result = MarkdownRenderer.sanitizeHtml("""<p><span class="mention">@홍길동</span></p>""")
                result shouldContain """<span class="mention">&#64;홍길동</span>"""
            }

            it("복합 class span 은 class 를 제거한다 — EC8 회귀 가드") {
                val result = MarkdownRenderer.sanitizeHtml("""<p><span class="mention evil">x</span></p>""")
                result shouldNotContain "mention evil"
            }
        }

        context("빈 입력") {

            it("빈 문자열은 빈 문자열을 낸다") {
                MarkdownRenderer.sanitizeHtml("") shouldNotContain "<"
            }
        }
    }

    describe("MarkdownRenderer.renderSafe — flexmark 확장 (J8)") {

        it("~~취소선~~ 을 del 로 렌더한다") {
            MarkdownRenderer.renderSafe("~~지움~~") shouldContain "<del>지움</del>"
        }

        it("표 문법을 table 로 렌더한다") {
            val md =
                """
                | 머리 | 둘 |
                |---|---|
                | 값 | 2 |
                """.trimIndent()
            val result = MarkdownRenderer.renderSafe(md)
            result shouldContain "<table>"
            result shouldContain "머리"
            result shouldContain "값"
        }

        it("- [ ] 체크박스 목록을 렌더한다") {
            val result = MarkdownRenderer.renderSafe("- [ ] 할 일\n- [x] 한 일")
            result shouldContain "할 일"
            result shouldContain "한 일"
        }

        it("--- 구분선을 hr 로 렌더한다") {
            MarkdownRenderer.renderSafe("위\n\n---\n\n아래") shouldContain "<hr"
        }

        it("![alt](attachment:uuid) 를 img 로 렌더한다") {
            val uuid = "3f8a1c2e-5b6d-4e7f-9a0b-1c2d3e4f5a6b"
            val result = MarkdownRenderer.renderSafe("![화면](attachment:$uuid)")
            result shouldContain "attachment:$uuid"
        }

        it("![alt](http://evil/x.png) 의 src 는 제거된다") {
            MarkdownRenderer.renderSafe("![x](http://evil.example/x.png)") shouldNotContain "evil.example"
        }
    }
})
