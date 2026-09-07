// 리치 에디터가 보낸 HTML 이 「빈 본문」인지 판정하는 규칙의 판별식 (FR-TM-01 회귀 차단)

package com.bts.issue.markdown

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

/**
 * 빈 HTML 본문 판정 판별식.
 *
 * ## 왜 있나 — 가장 위험한 회귀
 *
 * FR-TM-01 은 「본문을 비운 채 이슈를 만들면 프로젝트 템플릿으로 채운다」다. 마크다운
 * textarea 시절에는 `description.isNotBlank()` 한 줄로 충분했다. **TipTap 은 빈 문서를
 * 빈 문자열이 아니라 `<p></p>` 로 직렬화한다.** 그대로 두면 리치 에디터로 바꾸는 순간
 * 「빈 본문」이 영영 오지 않아 **템플릿 기능이 조용히 죽는다.**
 *
 * 죽어도 아무 오류가 안 난다. 이슈는 만들어지고 본문만 비어 있을 뿐이다 — 그래서 이 판별식이
 * 필요하다.
 *
 * ## 왜 서버가 정본인가 (plan D-3)
 *
 * 프론트만 고치면 다른 클라이언트(모바일·API 사용자·자동화)가 같은 함정에 빠진다.
 * 프론트도 같은 판정을 하지만 그것은 **보조**다 — 네트워크를 건너온 값을 서버가 다시 잰다.
 *
 * ## `<img>` 예외
 *
 * 이미지 한 장만 붙인 본문은 **텍스트가 0자이지만 빈 본문이 아니다.** 이 조건을 빠뜨리면
 * 「이미지만 넣고 저장했더니 프로젝트 템플릿이 덮어썼다」가 된다.
 */
class HtmlBodyEmptinessTest : DescribeSpec({

    describe("빈 문서로 판정하는 것") {
        listOf(
            "null" to null,
            "빈 문자열" to "",
            "공백" to "   ",
            "TipTap 빈 문단" to "<p></p>",
            "빈 문단 + 줄바꿈" to "<p><br></p>",
            "빈 문단 여러 개" to "<p></p><p></p>",
            "&nbsp; 만 든 문단" to "<p>&nbsp;</p>",
            "U+00A0 만 든 문단" to "<p> </p>",
            "빈 강조" to "<p><strong></strong></p>",
        ).forEach { (name, html) ->
            it("$name 은 빈 본문이다") {
                MarkdownRenderer.isBlankHtml(html).shouldBe(true)
            }
        }
    }

    describe("빈 문서가 **아닌** 것") {
        listOf(
            "평문" to "<p>본문</p>",
            "공백에 둘러싸인 글자" to "<p>  가  </p>",
            "목록" to "<ul><li>항목</li></ul>",
            // ★가장 중요한 줄 — 텍스트가 0자여도 이미지는 내용이다.
            "이미지만" to "<p><img src=\"attachment:11111111-1111-1111-1111-111111111111\" alt=\"\"></p>",
            "이미지 + 공백" to "<p> <img src=\"attachment:11111111-1111-1111-1111-111111111111\"> </p>",
            "구분선만" to "<hr>",
            "빈 표" to "<table><tbody><tr><td></td></tr></tbody></table>",
        ).forEach { (name, html) ->
            it("$name 은 빈 본문이 아니다") {
                MarkdownRenderer.isBlankHtml(html).shouldBe(false)
            }
        }
    }

    describe("판정이 태그 이름을 텍스트로 세지 않는다") {
        it("태그 이름이 길어도 빈 본문이다 — 태그를 벗기지 않으면 여기서 걸린다") {
            // `<blockquote></blockquote>` 를 문자열 길이로 재면 24자라 「내용 있음」이 된다.
            MarkdownRenderer.isBlankHtml("<blockquote></blockquote>").shouldBe(true)
        }
    }
})
