// 인라인 raw HTML 정화 flexmark 렌더러 — script/style 위험 태그만 OWASP로 통과(내용까지 제거), 그 외 태그는 억제
package com.bts.issue.markdown

import com.vladsch.flexmark.ast.HtmlInline
import com.vladsch.flexmark.html.HtmlWriter
import com.vladsch.flexmark.html.renderer.NodeRenderer
import com.vladsch.flexmark.html.renderer.NodeRendererContext
import com.vladsch.flexmark.html.renderer.NodeRendererFactory
import com.vladsch.flexmark.html.renderer.NodeRenderingHandler
import com.vladsch.flexmark.util.data.DataHolder

/**
 * 사용자가 마크다운 소스에 직접 입력한 인라인 raw HTML(HtmlInline AST 노드)을 정화하는 렌더러.
 *
 * ## 배경 — SUPPRESS_INLINE_HTML 단독의 한계
 * flexmark SUPPRESS_INLINE_HTML 은 인라인 raw HTML "태그"만 제거하고 태그 사이 텍스트는 보존한다.
 * 그래서 `<script>alert(1)</script>` 입력 시 `<script>`/`</script>` 태그는 사라지지만 코드 본문
 * `alert(1)` 이 단락 텍스트로 남아 결과 HTML 에 노출된다(텍스트라 실행은 안 되나 XSS 차단 테스트 회귀).
 *
 * ## 정책
 * - script/style 처럼 "내용 자체가 코드"인 raw-text 위험 태그는 태그를 raw 로 통과시켜
 *   OWASP HTML Sanitizer 가 태그 + CDATA 내용을 통째로 제거하도록 위임한다.
 *   OWASP 는 실제 HTML 파서라 대소문자/속성/중첩 우회에 견고하다(정규식 strip 대비 안전).
 * - 그 외 인라인 raw HTML 태그(span/img/svg/div 등)는 출력하지 않는다(억제).
 *   사용자 raw `<span class="mention">` 주입 차단(EC7) + 속성 기반 공격(onerror/onload) 차단.
 * - 렌더러가 생성한 정상 태그(MentionNodeRenderer 의 span.mention 등)는 HtmlInline 이 아니므로 무영향.
 *
 * 코드 펜스/인라인 코드 내 `<script>` 는 FencedCode/Code 노드(텍스트)라 HtmlInline 이 아니므로
 * 이 렌더러가 건드리지 않는다 — 코드 예시는 보존된다.
 */
internal object RawInlineHtmlNodeRenderer : NodeRenderer {
    // script/style 의 여는/닫는 태그 판별 — 대소문자 무시 + 태그명 경계 확인(scriptable 등 오탐 방지).
    private val DANGEROUS_RAW_TEXT_TAG: Regex =
        Regex("^</?(?:script|style)(?:\\s|>|/|$)", RegexOption.IGNORE_CASE)

    override fun getNodeRenderingHandlers(): Set<NodeRenderingHandler<*>> =
        setOf(
            NodeRenderingHandler(
                HtmlInline::class.java,
                object : NodeRenderingHandler.CustomNodeRenderer<HtmlInline> {
                    override fun render(
                        node: HtmlInline,
                        context: NodeRendererContext,
                        html: HtmlWriter,
                    ) {
                        if (DANGEROUS_RAW_TEXT_TAG.containsMatchIn(node.chars)) {
                            // OWASP 가 script/style 요소를 내용까지 제거하도록 raw 태그를 통과시킨다.
                            html.raw(node.chars)
                        }
                        // 그 외 raw HTML 태그는 출력하지 않음(억제) — EC7/속성 기반 XSS 차단.
                    }
                },
            ),
        )
}

/** [RawInlineHtmlNodeRenderer] 를 HtmlRenderer 에 등록하는 팩토리. */
internal object RawInlineHtmlNodeRendererFactory : NodeRendererFactory {
    override fun apply(options: DataHolder): NodeRenderer = RawInlineHtmlNodeRenderer
}
