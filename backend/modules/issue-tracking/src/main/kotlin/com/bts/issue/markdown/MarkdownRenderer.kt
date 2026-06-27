// 이슈 설명 필드의 Markdown을 HTML로 변환하고 XSS 페이로드를 제거하는 서버 측 렌더러
package com.bts.issue.markdown

import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.util.data.MutableDataSet
import org.owasp.html.HtmlPolicyBuilder
import org.owasp.html.PolicyFactory

/**
 * Markdown → HTML 변환 후 OWASP HTML Sanitizer allowlist로 2차 정화하는 렌더러.
 *
 * ## 보안 계층 설계
 * 1. **flexmark 1차 방어** — `ESCAPE_HTML_BLOCKS=true` + `HTML_BLOCK_PARSER=false` 설정.
 *    블록 수준 raw HTML을 엔티티로 escape하여 2차 sanitizer 도달 전에 무력화.
 * 2. **OWASP Java HTML Sanitizer 2차 방어** — allowlist에 없는 태그·속성 전부 제거.
 *    on* 이벤트 핸들러, style, script, iframe, svg 등 공격 태그 차단.
 *    `a[href]`는 `allowUrlProtocols("http","https","mailto")` — javascript:/data: 거부.
 *    `code[class]`는 `language-*` 패턴만 허용 (코드 하이라이팅 보존).
 *
 * CSRF ADR `docs/decisions/2026-05-20-csrf-cookie-mode.md` §서버 측 입력 sanitization 규정 준수.
 */
object MarkdownRenderer {
    // ── flexmark 파서/렌더러 (스레드 안전, 싱글턴 재사용) ──────────────────────

    private val FLEXMARK_OPTIONS: MutableDataSet =
        MutableDataSet().apply {
            // raw HTML 입력 비활성 — <svg onload> 등 사용자 HTML 태그를 1차 방어한다.
            //
            // 전략: flexmark 1차 escape + OWASP 2차 allowlist 가 최종 방어선이다.
            //
            // ESCAPE_HTML_BLOCKS: 블록 수준(단락 전체) raw HTML → escape 처리.
            // HTML_BLOCK_PARSER: HTML 블록 파서 비활성 → raw HTML 블록을 단락 텍스트로 처리.
            // SUPPRESS_INLINE_HTML: 인라인 raw HTML 주석(HtmlInlineComment) 억제용으로 유지.
            //   인라인 raw HTML 태그(HtmlInline)는 RawInlineHtmlNodeRenderer 가 전담 처리하므로
            //   이 옵션이 아니라 해당 렌더러가 권위 있는 정책이다(core 핸들러를 override).
            //   ESCAPE_INLINE_HTML 대신 SUPPRESS 를 쓰는 이유:
            //     ESCAPE_INLINE_HTML 은 삽입된 태그를 텍스트로 출력하므로 태그 내 문자열
            //     (예: JaVaScRiPt:) 이 escape 된 형태로 결과에 남아 기존 XSS 차단 테스트를 깬다.
            //   ── RawInlineHtmlNodeRenderer 정책 요약 ──
            //   script/style(내용 자체가 코드인 raw-text 태그)는 raw 로 통과시켜 OWASP 가 태그+내용을
            //     통째로 제거하게 위임한다(SUPPRESS 단독은 태그만 지우고 alert(1) 텍스트를 남기는 회귀).
            //   그 외 raw HTML 태그(span/img/svg 등)는 억제 — raw span 주입(EC7)·속성 기반 공격 차단.
            //   MentionNodeRenderer 가 HtmlWriter 로 생성한 span.mention 은 HtmlInline 이 아니라 무영향.
            set(HtmlRenderer.ESCAPE_HTML_BLOCKS as com.vladsch.flexmark.util.data.DataKey<Boolean>, true)
            set(Parser.HTML_BLOCK_PARSER as com.vladsch.flexmark.util.data.DataKey<Boolean>, false)
            set(HtmlRenderer.SUPPRESS_INLINE_HTML as com.vladsch.flexmark.util.data.DataKey<Boolean>, true)
        }

    private val MENTION_EXT: MentionExtension = MentionExtension.create()

    private val PARSER: Parser =
        Parser.builder(FLEXMARK_OPTIONS)
            .extensions(listOf(MENTION_EXT))
            .build()

    private val RENDERER: HtmlRenderer =
        HtmlRenderer.builder(FLEXMARK_OPTIONS)
            .extensions(listOf(MENTION_EXT))
            // 인라인 raw HTML 전담 렌더러 — core HtmlInline 핸들러를 override 한다.
            .nodeRendererFactory(RawInlineHtmlNodeRendererFactory)
            .build()

    // ── OWASP HTML Sanitizer allowlist 정책 ────────────────────────────────────

    /**
     * 허용 태그·속성 allowlist.
     *
     * - 허용 태그: h1~h6, strong, em, b, i, ul, ol, li, p, br, pre, code, blockquote, a, span
     * - 허용 속성: a[href] (http/https/mailto 한정), code[class] (language-* 한정),
     *             span[class] (정확히 "mention"만 — 복합 class 거부, EC8)
     * - 제거 대상: script, iframe, svg, style, on* 핸들러, data: URI, javascript: 스킴
     *
     * ## span 허용 보안 설계 (CONCERN-S1)
     * span[class=mention] 은 MentionNodeRenderer 가 생성하는 마크업 전용이다.
     * 사용자 raw span 주입은 SUPPRESS_INLINE_HTML 로 flexmark 단계에서 태그를 제거해
     * OWASP 에 HTML 태그로 도달하지 않는다(EC7 차단).
     * class 허용은 onElements("span") 으로 span 에만 한정 — code[class] 정책과 독립.
     *
     * @see allowUrlProtocols — OWASP 내장 URL 프로토콜 필터 (javascript:/data: 자동 거부)
     */
    private val SANITIZE_POLICY: PolicyFactory =
        HtmlPolicyBuilder()
            .allowElements("h1", "h2", "h3", "h4", "h5", "h6")
            .allowElements("strong", "em", "b", "i")
            .allowElements("ul", "ol", "li")
            .allowElements("p", "br")
            .allowElements("pre", "blockquote")
            .allowElements("code")
            // language-* 패턴만 허용 — 임의 class 값 거부 (코드 하이라이팅 보존)
            .allowAttributes("class")
            .matching { v: String -> v.startsWith("language-") }
            .onElements("code")
            .allowElements("a")
            // allowUrlProtocols: OWASP 내장 스킴 필터 — http/https/mailto 외 javascript:/data: 등 거부
            .allowUrlProtocols("http", "https", "mailto")
            .allowAttributes("href").onElements("a")
            // span[class=mention]: MentionNodeRenderer 생성 마크업 전용.
            // 정확히 "mention" 문자열만 허용 — "mention evil" 등 복합 class 거부(EC8).
            // class 허용을 onElements("span") 으로 span 에만 한정(CONCERN-S1).
            .allowElements("span")
            .allowAttributes("class")
            .matching { v: String -> v == "mention" }
            .onElements("span")
            .toFactory()

    // ── 공개 API ───────────────────────────────────────────────────────────────

    /**
     * Markdown 문자열을 안전한 HTML로 변환한다.
     *
     * 입력이 깨진 Markdown이거나 렌더 중 예외가 발생해도 sanitize된 결과를 반환하며
     * 절대 예외를 전파하지 않는다.
     *
     * @param md 사용자 입력 Markdown 문자열 (신뢰하지 않는 입력)
     * @return XSS 페이로드가 제거된 안전한 HTML 문자열
     */
    fun renderSafe(md: String): String {
        val rawHtml =
            runCatching {
                val document = PARSER.parse(md)
                RENDERER.render(document)
            }.getOrElse { _ ->
                // 렌더 실패 시 원문을 HTML 엔티티로 escape하여 안전하게 반환.
                // 예외를 silently swallow하지 않고, 안전한 fallback 결과를 제공한다.
                md.escapeHtmlEntities()
            }

        return SANITIZE_POLICY.sanitize(rawHtml)
    }

    // ── 내부 헬퍼 ─────────────────────────────────────────────────────────────

    private fun String.escapeHtmlEntities(): String =
        replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#x27;")
}
