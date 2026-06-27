// @멘션 마크업 + 정화 검증 — MarkdownRenderer renderSafe 레벨 통합 테스트
package com.bts.issue.markdown

import com.bts.issue.mention.MentionParser
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * MarkdownRenderer.renderSafe 멘션 마크업 및 정화 검증 단위 테스트.
 *
 * 테스트 대상: S1(마크업), EC1~EC5(마크업 제외), EC7~EC8(정화 보안), NFR2(일관성).
 * DB/Testcontainers 의존 없음 — MarkdownRenderer, MentionParser 모두 stateless object.
 *
 * ## @ 인코딩 주의
 * OWASP sanitizer 는 @ 문자를 &#64; 엔티티로 인코딩한다(기존 MarkdownRendererTest 주석 확인됨).
 * span.mention 내 username 도 &#64;alice 형식으로 출력된다. 어설션은 이를 반영한다.
 *
 * ## 보안 계층 검증 원칙
 * - EC7: raw span 주입은 flexmark ESCAPE_INLINE_HTML + OWASP 2중 방어로 차단됨을 검증.
 * - EC8: OWASP matching { it == "mention" } 정확 일치로 복합 class 거부됨을 검증.
 */
class MarkdownRendererMentionTest : DescribeSpec({

    describe("MarkdownRenderer.renderSafe 멘션 마크업") {

        context("S1 — @username span 마크업") {
            it("@alice 를 span class=mention 으로 마크업한다") {
                // OWASP 가 @ → &#64; 인코딩하므로 span 내 username 도 &#64;alice 형식
                val result = MarkdownRenderer.renderSafe("@alice 확인")
                result shouldContain """<span class="mention">&#64;alice</span>"""
            }
        }

        context("EC1 — 인라인 코드 스팬 내부 제외") {
            it("인라인 코드 스팬 내 @mention 은 마크업하지 않는다") {
                val result = MarkdownRenderer.renderSafe("`@code` 본문")
                result shouldNotContain """<span class="mention">"""
                // 코드 스팬 자체는 code 요소로 렌더된다 (@ 는 &#64; 로 인코딩될 수 있음)
                result shouldContain "<code>"
            }
        }

        context("EC2 — 펜스 코드 블록 내부 제외") {
            it("펜스 코드 블록 내 @mention 은 마크업하지 않는다") {
                val result = MarkdownRenderer.renderSafe("```\n@x\n```")
                result shouldNotContain """<span class="mention">"""
            }
        }

        context("EC3 — 이메일 @ 제외") {
            it("이메일 주소의 @ 는 마크업하지 않는다") {
                val result = MarkdownRenderer.renderSafe("user@example.com")
                result shouldNotContain """<span class="mention">"""
            }
        }

        context("EC4 — @@ 제외") {
            it("@@bob 은 마크업하지 않는다") {
                val result = MarkdownRenderer.renderSafe("@@bob")
                result shouldNotContain """<span class="mention">"""
            }
        }

        context("EC5 — 문장부호 경계") {
            it("@alice. 에서 alice 만 마크업하고 마침표는 span 바깥에 남긴다") {
                val result = MarkdownRenderer.renderSafe("@alice.")
                // OWASP @ → &#64; 인코딩 반영
                result shouldContain """<span class="mention">&#64;alice</span>"""
                result shouldNotContain """<span class="mention">&#64;alice.</span>"""
            }
        }

        context("EC7 — 정화: raw span 주입 차단") {
            it("사용자가 입력한 raw span class=mention 태그는 텍스트화된다") {
                // flexmark ESCAPE_INLINE_HTML 로 인라인 raw HTML 을 escape 처리.
                // 렌더러가 부여한 span.mention 만 OWASP allowlist 를 통과한다.
                val result = MarkdownRenderer.renderSafe("""<span class="mention">evil</span>""")
                result shouldNotContain """<span class="mention">evil</span>"""
            }
        }

        context("EC8 — 복합 class 거부") {
            it("class=mention evil 복합 class 는 정화로 거부된다") {
                val result = MarkdownRenderer.renderSafe("""<span class="mention evil">x</span>""")
                result shouldNotContain """class="mention evil""""
                result shouldNotContain """<span class="mention evil">"""
            }
        }

        context("NFR2 — 렌더된 멘션 username 집합 subset of MentionParser.extract 결과") {
            it("렌더에서 강조된 username 은 MentionParser 추출 결과의 부분집합이다") {
                val text = "@alice @bob 확인"
                val rendered = MarkdownRenderer.renderSafe(text)
                val extracted = MentionParser.extract(text)

                // OWASP 가 @ → &#64; 인코딩하므로 span 내 username 앞의 @ 대신 &#64; 로 매칭
                val renderedMentions =
                    Regex("""<span class="mention">(?:@|&#64;)([A-Za-z0-9._\-]+)</span>""")
                        .findAll(rendered)
                        .map { it.groupValues[1] }
                        .toSet()

                renderedMentions.all { it in extracted }.shouldBeTrue()
            }
        }
    }
})
