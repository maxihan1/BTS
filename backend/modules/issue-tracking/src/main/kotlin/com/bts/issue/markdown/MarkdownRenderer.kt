// 이슈 설명 필드의 Markdown을 HTML로 변환하고 XSS 페이로드를 제거하는 서버 측 렌더러
package com.bts.issue.markdown

import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListExtension
import com.vladsch.flexmark.ext.tables.TablesExtension
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

    /**
     * flexmark 확장 전량 — 파서와 렌더러가 **같은 목록**을 본다.
     *
     * 목록을 두 벌로 두면 한쪽에만 확장이 붙어 「파싱은 되는데 렌더가 안 되는」(또는 그 반대)
     * 상태가 조용히 생긴다. 상수 하나를 양쪽이 참조해 그 갈라짐을 원천 차단한다.
     *
     * 취소선·표·체크박스는 GFM 확장이라 코어 파서가 모른다 — Jira Cloud 에디터 서식 패리티(J8).
     */
    private val EXTENSIONS =
        listOf(
            MENTION_EXT,
            StrikethroughExtension.create(),
            TablesExtension.create(),
            TaskListExtension.create(),
        )

    private val PARSER: Parser =
        Parser.builder(FLEXMARK_OPTIONS)
            .extensions(EXTENSIONS)
            .build()

    private val RENDERER: HtmlRenderer =
        HtmlRenderer.builder(FLEXMARK_OPTIONS)
            .extensions(EXTENSIONS)
            // 인라인 raw HTML 전담 렌더러 — core HtmlInline 핸들러를 override 한다.
            .nodeRendererFactory(RawInlineHtmlNodeRendererFactory)
            .build()

    // ── OWASP HTML Sanitizer allowlist 정책 ────────────────────────────────────

    /**
     * 본문 이미지의 유일한 허용 `src` 형태 — `attachment:<uuid>`.
     *
     * 첨부 식별자(UUID v4 형태)까지 정확히 되잰다. 스킴만 검사하면 `attachment:../../etc/passwd`
     * 같은 경로가 통과해 프론트의 blob 치환 로직에 임의 문자열이 흘러든다.
     */
    private const val ATTACHMENT_SCHEME = "attachment:"

    private val ATTACHMENT_SRC =
        Regex("^attachment:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

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
            // 밑줄·취소선 — Jira 에디터 ⌘U · ⌘⇧S 에 대응(J8). 밑줄은 마크다운 문법이 없어
            // 에디터 경로로만 들어온다(편차 X2). `s` 는 flexmark Strikethrough 가, `del` 은
            // 에디터가 만든다 — 둘 다 열어야 두 입구가 같은 결과를 낸다.
            .allowElements("u", "del", "s")
            .allowElements("ul", "ol", "li")
            .allowElements("p", "br")
            // 구분선 — `---` 및 툴바 구분선 버튼(J8).
            .allowElements("hr")
            .allowElements("pre", "blockquote")
            .allowElements("code")
            // 표 — GFM 표 문법과 툴바 표 삽입(J8). 속성 없는 구조 태그만 연다. `colspan`/`rowspan`
            // 은 열지 않는다 — 지금 만들 수단이 없고, 여는 순간 값 검증이 새 표면이 된다.
            .allowElements("table", "thead", "tbody", "tfoot", "tr", "th", "td")
            // 체크박스 목록 — `- [ ]` 및 툴바 액션 아이템(J8). `type` 을 checkbox 로 못 박아
            // text/password 입력창 주입을 막는다. `disabled`/`checked` 는 값 없는 표시용이다.
            .allowElements("input")
            .allowAttributes("type")
            .matching { v: String -> v.equals("checkbox", ignoreCase = true) }
            .onElements("input")
            .allowAttributes("checked", "disabled")
            .onElements("input")
            // 이미지 — 첨부 참조(`attachment:<uuid>`)만 허용한다(J7). http(s) 를 열면 외부
            // 트래킹 픽셀과 혼합 콘텐츠가 함께 들어온다. 첨부 다운로드가 Bearer 헤더 인증이라
            // 어차피 `<img src="/api/...">` 는 뜨지 않고, 프론트가 렌더 시 blob 으로 바꾼다.
            //
            // ★두 겹이 **둘 다** 필요하다. OWASP 는 `src` 를 URL 속성으로 특별 취급해
            // `allowUrlProtocols` 에 없는 스킴을 `matching` 술어보다 **먼저** 잘라낸다 —
            // 스킴만 등록하면 `attachment:../../etc/passwd` 가 통과하고, 술어만 두면
            // `src` 자체가 스킴 단계에서 사라져 `<img alt="…" />` 만 남는다(실측).
            .allowUrlProtocols("attachment")
            .allowElements("img")
            .allowAttributes("src")
            .matching { v: String -> ATTACHMENT_SRC.matches(v) }
            .onElements("img")
            .allowAttributes("alt")
            .onElements("img")
            // language-* 패턴만 허용 — 임의 class 값 거부 (코드 하이라이팅 보존)
            .allowAttributes("class")
            .matching { v: String -> v.startsWith("language-") }
            .onElements("code")
            .allowElements("a")
            // allowUrlProtocols: OWASP 내장 스킴 필터 — http/https/mailto 외 javascript:/data: 등 거부
            .allowUrlProtocols("http", "https", "mailto")
            // ★`attachment` 는 위에서 `img` 를 위해 열었지만 `allowUrlProtocols` 는 **정책 전역**이다 —
            //   요소별이 아니라 정책 하나에 허용 스킴이 모인다. 그래서 img 를 위해 연 스킴이 `a[href]`
            //   에도 그대로 열렸고, UUID 술어는 `img[src]` 에만 걸려 있어 `a` 로 들어온 임의 경로를
            //   아무도 막지 않았다 — `<a href="attachment:../../etc/passwd">` 가 통과했다(실측).
            //   href 는 첨부 참조를 쓸 일이 없으므로 그 스킴만 되잰다. 상대 경로·앵커·http(s)·mailto 는
            //   종전대로 통과한다(실측 대조).
            .allowAttributes("href")
            .matching { v: String -> !v.startsWith(ATTACHMENT_SCHEME, ignoreCase = true) }
            .onElements("a")
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
     * 이미 HTML 인 입력을 allowlist 로 정화한다 (렌더 없음).
     *
     * 리치 에디터(TipTap)가 보낸 본문·댓글이 이 경로로 들어온다. [renderSafe] 와 **같은
     * [SANITIZE_POLICY]** 를 쓴다 — 정책이 갈라지면 한 입구로 들어온 페이로드가 다른 입구의
     * 테스트를 통과한 채 살아남는다.
     *
     * @param html 사용자 입력 HTML 문자열 (신뢰하지 않는 입력)
     * @return allowlist 밖 태그·속성이 제거된 안전한 HTML 문자열
     */
    fun sanitizeHtml(html: String): String = SANITIZE_POLICY.sanitize(html)

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
