// IssueResponse를 openhtmltopdf가 렌더할 수 있는 well-formed XHTML 문자열로 변환하는 템플릿
package com.bts.issue.pdf

import com.bts.issue.adapter.inbound.rest.IssueResponse
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * [IssueResponse]를 PDF 렌더에 사용할 well-formed XHTML 문자열로 변환한다.
 *
 * ## NFR1 보안 — descriptionHtml만 신뢰
 * 본문 영역에는 반드시 [IssueResponse.descriptionHtml](OWASP sanitized HTML)만 삽입한다.
 * [IssueResponse.description](raw Markdown)을 HTML로 직접 주입하는 것은 절대 금지한다.
 * descriptionHtml이 null 또는 빈 문자열이면 `(설명 없음)` placeholder를 사용한다.
 *
 * ## XHTML 호환성
 * openhtmltopdf는 well-formed XHTML을 요구한다.
 * OWASP HTML Sanitizer의 출력(자기종결 `<br />`, 균형 잡힌 태그)은 XHTML 호환이므로
 * XML 선언 + XHTML 문서 골격으로 감싸는 것만으로 well-formed XML 보장이 가능하다.
 */
@Component
class IssuePdfTemplate {
    /**
     * 이슈 응답 DTO를 PDF 렌더용 XHTML 문자열로 변환한다.
     *
     * @param issue PDF로 출력할 이슈 응답 DTO. [IssueResponse.descriptionHtml]이 채워진 단건 응답이어야 한다.
     * @return well-formed XHTML 문자열. openhtmltopdf `withHtmlContent`에 직접 전달 가능하다.
     */
    fun render(issue: IssueResponse): String {
        val bodyContent = resolveBodyContent(issue)
        val metaRows = buildMetaRows(issue)
        val escapedKey = issue.key.xmlEscape()
        val escapedSummary = issue.summary.xmlEscape()
        return buildXhtmlDocument(escapedKey, escapedSummary, metaRows, bodyContent)
    }

    // ── 본문 결정 ─────────────────────────────────────────────────────────────

    /**
     * 본문으로 삽입할 HTML을 결정한다.
     *
     * [IssueResponse.descriptionHtml]이 null 또는 빈 문자열이면 [DESCRIPTION_PLACEHOLDER]를 반환한다.
     * [IssueResponse.description](raw Markdown)은 절대 반환하지 않는다(NFR1 — XSS 방지).
     */
    private fun resolveBodyContent(issue: IssueResponse): String {
        val html = issue.descriptionHtml
        return if (html.isNullOrBlank()) DESCRIPTION_PLACEHOLDER else html
    }

    // ── 메타 표 행 빌드 ────────────────────────────────────────────────────────

    /**
     * 이슈 메타데이터를 XHTML 표 행(`tr`) 목록으로 빌드한다.
     *
     * 각 행은 [metaRow] 헬퍼로 생성되며 null 값은 [EMPTY_VALUE](`-`)로 표기한다.
     */
    private fun buildMetaRows(issue: IssueResponse): String =
        buildString {
            metaRow("키", issue.key)
            metaRow("제목", issue.summary)
            metaRow("프로젝트", issue.projectKey)
            metaRow("상태", issue.currentStateKey)
            metaRow("타입", issue.typeName)
            metaRow("우선순위", issue.priorityName)
            metaRow("담당자", issue.assigneeId?.toString())
            metaRow("라벨", issue.labels.takeIf { it.isNotEmpty() }?.joinToString(", "))
            metaRow("영향도", issue.impactName)
            metaRow("Resolution", issue.resolution?.name)
            metaRow("생성 일시", issue.createdAt?.formatDisplay())
            metaRow("수정 일시", issue.updatedAt?.formatDisplay())
        }

    /**
     * 단일 메타 표 행 `<tr><th>label</th><td>value</td></tr>`을 빌더에 추가한다.
     *
     * @param label 행 레이블(XML escape 적용).
     * @param value 셀 값. null 또는 빈 문자열이면 [EMPTY_VALUE](`-`)로 표기(XML escape 적용).
     */
    private fun StringBuilder.metaRow(
        label: String,
        value: String?,
    ) {
        val displayValue = value?.takeIf { it.isNotBlank() }?.xmlEscape() ?: EMPTY_VALUE
        append("<tr>")
        append("<th>").append(label.xmlEscape()).append("</th>")
        append("<td>").append(displayValue).append("</td>")
        append("</tr>\n")
    }

    // ── XHTML 골격 조합 ────────────────────────────────────────────────────────

    /**
     * XML 선언·DOCTYPE·head(CSS)·body(메타 표+본문)를 조합해 완전한 XHTML 문서를 반환한다.
     *
     * CSS `@page` 규칙에 헤더(key·summary)·푸터(페이지 번호) 규칙이 포함된다.
     *
     * @param escapedKey XML escape된 이슈 키.
     * @param escapedSummary XML escape된 이슈 제목.
     * @param metaRows `<tr>…</tr>` 행 목록 문자열(이미 XML escape 적용됨).
     * @param bodyContent 본문 HTML. sanitized HTML 또는 placeholder.
     */
    @Suppress("LongMethod")
    private fun buildXhtmlDocument(
        escapedKey: String,
        escapedSummary: String,
        metaRows: String,
        bodyContent: String,
    ): String =
        """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Strict//EN"
  "http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd">
<html xmlns="http://www.w3.org/1999/xhtml">
<head>
  <meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
  <title>$escapedKey — $escapedSummary</title>
  <style type="text/css">
    body { font-family: 'NanumGothic', sans-serif; font-size: 11pt; margin: 0; padding: 0; }
    h1 { font-size: 16pt; margin-bottom: 8pt; }
    table.meta { border-collapse: collapse; width: 100%; margin-bottom: 16pt; }
    table.meta th, table.meta td { border: 1pt solid #cccccc; padding: 4pt 8pt; text-align: left; }
    table.meta th { background-color: #f5f5f5; width: 25%; font-weight: bold; }
    div.body-content { margin-top: 16pt; }
    @page {
      margin: 20mm 15mm;
      @top-center {
        content: "$escapedKey — $escapedSummary";
        font-family: 'NanumGothic', sans-serif;
        font-size: 9pt;
        color: #666666;
      }
      @bottom-center {
        content: counter(page) " / " counter(pages);
        font-family: 'NanumGothic', sans-serif;
        font-size: 9pt;
        color: #666666;
      }
    }
  </style>
</head>
<body>
  <h1>$escapedKey — $escapedSummary</h1>
  <table class="meta">
    <tbody>
$metaRows    </tbody>
  </table>
  <div class="body-content">
$bodyContent
  </div>
</body>
</html>"""

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    /** XML 특수문자 5종(`&`, `<`, `>`, `"`, `'`)을 엔티티로 escape한다. */
    private fun String.xmlEscape(): String =
        replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    /** [Instant]를 [DISPLAY_FORMATTER] 포맷(`yyyy-MM-dd HH:mm` UTC)으로 변환한다. */
    private fun Instant.formatDisplay(): String = DISPLAY_FORMATTER.format(this)

    companion object {
        private const val DESCRIPTION_PLACEHOLDER = "<p>(설명 없음)</p>"
        private const val EMPTY_VALUE = "-"

        /** 표시용 일시 포맷터. 싱글턴으로 캐싱해 매 호출마다 생성 비용을 절감한다. */
        private val DISPLAY_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC)
    }
}
