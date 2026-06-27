// 이슈 본문 @멘션을 span으로 마크업하는 flexmark 인라인 확장
package com.bts.issue.markdown

import com.bts.issue.mention.MentionParser
import com.vladsch.flexmark.html.HtmlRenderer
import com.vladsch.flexmark.html.HtmlWriter
import com.vladsch.flexmark.html.renderer.NodeRenderer
import com.vladsch.flexmark.html.renderer.NodeRendererContext
import com.vladsch.flexmark.html.renderer.NodeRendererFactory
import com.vladsch.flexmark.html.renderer.NodeRenderingHandler
import com.vladsch.flexmark.parser.InlineParser
import com.vladsch.flexmark.parser.InlineParserExtension
import com.vladsch.flexmark.parser.InlineParserExtensionFactory
import com.vladsch.flexmark.parser.LightInlineParser
import com.vladsch.flexmark.parser.Parser
import com.vladsch.flexmark.util.ast.Node
import com.vladsch.flexmark.util.data.DataHolder
import com.vladsch.flexmark.util.data.MutableDataHolder
import com.vladsch.flexmark.util.sequence.BasedSequence

// ── AST 노드 ──────────────────────────────────────────────────────────────────

/**
 * flexmark AST 멘션 노드 — @username 인라인 요소를 표현한다.
 *
 * username 필드는 MentionParser.MENTION_PATTERN 기준 캡처 그룹 결과이며
 * MentionNodeRenderer 가 span.mention 으로 렌더한다.
 * username 은 렌더 시 html.text() 로 HTML escape 처리된다(CONCERN-S2).
 */
class Mention(chars: BasedSequence, val username: String) : Node(chars) {
    override fun getSegments(): Array<BasedSequence> = EMPTY_SEGMENTS
}

// ── 인라인 파서 확장 ───────────────────────────────────────────────────────────

/**
 * @ 트리거로 Mention 노드를 생성하는 flexmark 인라인 파서 확장.
 *
 * MentionParser.MENTION_PATTERN(lookbehind 포함)을 Java Pattern 으로 변환해
 * matchWithGroups() 에 전달한다. 단, flexmark 의 matchWithGroups 는 현재 위치에서
 * 시작하는 부분 입력 상에서 매칭해 lookbehind 가 @ 이전 문자를 볼 수 없다.
 * 따라서 parse() 진입 시 이전 문자를 수동으로 확인해 경계를 검사한다.
 *
 * - 수동 경계 확인으로 이메일(@) 과 @@bob 차단 (EC3, EC4).
 * - 코드스팬/코드블록/링크 내부는 flexmark 가 별도 노드로 분리하므로
 *   인라인 파서가 작동하지 않아 EC1, EC2, EC6 가 구조적으로 보장된다.
 * - 매칭 실패 시 false 를 반환해 @ 를 일반 텍스트로 흘린다(CONCERN-E1).
 */
private class MentionInlineParserExtension : InlineParserExtension {
    override fun finalizeDocument(inlineParser: InlineParser) = Unit

    override fun finalizeBlock(inlineParser: InlineParser) = Unit

    @Suppress("ReturnCount")
    override fun parse(inlineParser: LightInlineParser): Boolean {
        val input = inlineParser.getInput()
        val atIndex = inlineParser.getIndex()

        // 이전 문자 경계 수동 확인 — MENTION_PATTERN lookbehind 와 동일 문자 집합
        // matchWithGroups 가 부분 문자열 상에서 lookbehind 를 평가하지 못하는 것을 보완.
        // atIndex > 0 인 경우에만 확인 (문자열 시작에서는 이전 문자 없음 → 멘션 허용)
        if (atIndex > 0 && input[atIndex - 1].isMentionBoundary()) {
            return false
        }

        val groups = inlineParser.matchWithGroups(MENTION_JAVA_PATTERN) ?: return false
        // groups[0] = 전체 매칭(@alice), groups[1] = username 캡처 그룹(alice)
        // Java 배열 원소는 Kotlin 에서 nullable 로 추론되므로 명시적 null 체크
        val fullMatch = groups.getOrNull(0) ?: return false
        val username = groups.getOrNull(1)?.toString() ?: return false
        inlineParser.flushTextNode()
        inlineParser.appendNode(Mention(fullMatch, username))
        return true
    }

    companion object {
        // 단일 출처: MentionParser.MENTION_PATTERN → Java Pattern 변환 (drift 차단)
        private val MENTION_JAVA_PATTERN: java.util.regex.Pattern =
            MentionParser.MENTION_PATTERN.toPattern()

        /**
         * 멘션 경계 문자 판별 — MENTION_PATTERN lookbehind 의 forbidden character set 과 동일.
         * 이 문자가 @ 바로 앞에 오면 이메일 주소 또는 @@ 이중 트리거로 판단해 멘션 제외.
         *
         * 블록 바디 사용: ktlint function-signature(단일행) vs detekt MaxLineLength(120자) 충돌 해소.
         */
        private fun Char.isMentionBoundary(): Boolean {
            return isLetterOrDigit() || this == '.' || this == '_' || this == '-' || this == '@'
        }
    }
}

// ── 팩토리 ────────────────────────────────────────────────────────────────────

private object MentionInlineParserExtensionFactory : InlineParserExtensionFactory {
    /** @ 문자를 트리거로 등록한다. */
    override fun getCharacters(): CharSequence = "@"

    override fun getAfterDependents(): Set<Class<*>> = emptySet()

    override fun getBeforeDependents(): Set<Class<*>> = emptySet()

    override fun affectsGlobalScope(): Boolean = false

    override fun apply(lightInlineParser: LightInlineParser): InlineParserExtension = MentionInlineParserExtension()
}

// ── 노드 렌더러 ───────────────────────────────────────────────────────────────

/**
 * Mention 노드를 span.mention HTML 로 변환하는 렌더러.
 *
 * - html.attr("class", "mention").withAttr().tag("span") 으로 태그 + 속성 출력.
 * - html.text() 로 username 을 HTML escape 처리(CONCERN-S2 방어적 처리).
 * - @ 앞에 붙이는 문자열도 html.text() 경유(@ 는 &#64; 로 OWASP 인코딩됨).
 */
private object MentionNodeRenderer : NodeRenderer {
    override fun getNodeRenderingHandlers(): Set<NodeRenderingHandler<*>> =
        setOf(
            NodeRenderingHandler(
                Mention::class.java,
                object : NodeRenderingHandler.CustomNodeRenderer<Mention> {
                    override fun render(
                        node: Mention,
                        context: NodeRendererContext,
                        html: HtmlWriter,
                    ) {
                        html.attr("class", "mention")
                        html.withAttr()
                        html.tag("span")
                        // html.text() 는 HTML escape 처리 — username 의 특수문자 방어(CONCERN-S2)
                        html.text("@${node.username}")
                        html.tag("/span")
                    }
                },
            ),
        )
}

private object MentionNodeRendererFactory : NodeRendererFactory {
    override fun apply(options: DataHolder): NodeRenderer = MentionNodeRenderer
}

// ── 메인 확장 클래스 ──────────────────────────────────────────────────────────

/**
 * @멘션 마크업 flexmark 확장.
 *
 * Parser.ParserExtension 과 HtmlRenderer.HtmlRendererExtension 을 동시에 구현해
 * MarkdownRenderer 가 단일 인스턴스로 parser/renderer 양쪽에 등록할 수 있다.
 *
 * ## 보안 설계
 * - 렌더러가 부여한 span.mention 만 OWASP SANITIZE_POLICY 를 통과한다.
 * - 사용자 raw HTML `<span class="mention">evil</span>` 주입은
 *   MarkdownRenderer 의 SUPPRESS_INLINE_HTML 설정으로 flexmark 단계에서 태그를 제거해
 *   OWASP 에 HTML 태그로 도달하지 않으므로 span 허용 allowlist 와 무관하게 차단된다(EC7).
 * - span 이외 요소의 class 허용은 별도 onElements("span") 절로 독립 유지(CONCERN-S1).
 */
class MentionExtension private constructor() : Parser.ParserExtension, HtmlRenderer.HtmlRendererExtension {
    override fun parserOptions(options: MutableDataHolder) = Unit

    override fun rendererOptions(options: MutableDataHolder) = Unit

    override fun extend(parserBuilder: Parser.Builder) {
        parserBuilder.customInlineParserExtensionFactory(MentionInlineParserExtensionFactory)
    }

    override fun extend(
        rendererBuilder: HtmlRenderer.Builder,
        rendererType: String,
    ) {
        rendererBuilder.nodeRendererFactory(MentionNodeRendererFactory)
    }

    companion object {
        /** MentionExtension 인스턴스를 생성한다. */
        fun create(): MentionExtension = MentionExtension()
    }
}
