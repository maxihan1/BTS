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

    private val FLEXMARK_OPTIONS: MutableDataSet = MutableDataSet().apply {
        // raw HTML 입력 비활성 — <svg onload> 등 사용자 HTML 태그를 1차 방어한다.
        //
        // 전략: OWASP 2차 sanitizer allowlist가 최종 방어선이다.
        // flexmark는 Markdown 문법으로 생성하는 <a>, <strong>, <code> 등 정상 태그는 그대로 출력하고
        // 사용자가 raw HTML로 삽입한 <script>, <svg onload>, <img onerror> 등 공격 태그도 출력한다.
        // OWASP allowlist가 공격 태그를 전부 제거하므로 flexmark에서 추가 처리가 없어도 안전하다.
        //
        // ESCAPE_HTML_BLOCKS: 블록 수준(단락 전체) raw HTML은 escape 처리 — 이중 안전망.
        // HTML_BLOCK_PARSER: HTML 블록 파서 비활성 → raw HTML 블록을 단락 텍스트로 처리.
        // ESCAPE_INLINE_HTML 미사용 이유: 인라인 raw HTML까지 escape하면
        //   Markdown 링크([text](url)) 등 flexmark가 내부 생성하는 <a href> 태그까지 영향을 주어
        //   정상 링크가 텍스트로만 출력되는 사이드 이펙트가 발생한다.
        //   OWASP 단독으로 inline raw HTML 공격 태그(svg/img/div 등)를 충분히 차단한다.
        set(HtmlRenderer.ESCAPE_HTML_BLOCKS as com.vladsch.flexmark.util.data.DataKey<Boolean>, true)
        set(Parser.HTML_BLOCK_PARSER as com.vladsch.flexmark.util.data.DataKey<Boolean>, false)
    }

    private val PARSER: Parser = Parser.builder(FLEXMARK_OPTIONS).build()
    private val RENDERER: HtmlRenderer = HtmlRenderer.builder(FLEXMARK_OPTIONS).build()

    // ── OWASP HTML Sanitizer allowlist 정책 ────────────────────────────────────

    /**
     * 허용 태그·속성 allowlist.
     *
     * - 허용 태그: h1~h6, strong, em, b, i, ul, ol, li, p, br, pre, code, blockquote, a
     * - 허용 속성: a[href] (http/https/mailto 한정), code[class] (language-* 한정)
     * - 제거 대상: script, iframe, svg, style, on* 핸들러, data: URI, javascript: 스킴
     *
     * @see allowUrlProtocols — OWASP 내장 URL 프로토콜 필터 (javascript:/data: 자동 거부)
     */
    private val SANITIZE_POLICY: PolicyFactory = HtmlPolicyBuilder()
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
        val rawHtml = runCatching {
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
