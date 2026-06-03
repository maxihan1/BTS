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
        return buildXhtml(issue, metaRows, bodyContent)
    }

    // ── 본문 결정 ─────────────────────────────────────────────────────────────

    /**
     * descriptionHtml이 null 또는 빈 문자열이면 placeholder를 반환한다.
     * raw description은 절대 반환하지 않는다(NFR1).
     */
    private fun resolveBodyContent(issue: IssueResponse): String {
        val html = issue.descriptionHtml
        return if (html.isNullOrBlank()) DESCRIPTION_PLACEHOLDER else html
    }

    // ── 메타 표 행 빌드 ────────────────────────────────────────────────────────

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

    /** 단일 메타 표 행 `<tr><th>…</th><td>…</td></tr>`을 추가한다. null 값은 `-`로 표기한다. */
    private fun StringBuilder.metaRow(label: String, value: String?) {
        val displayValue = value?.takeIf { it.isNotBlank() }?.xmlEscape() ?: EMPTY_VALUE
        append("<tr>")
        append("<th>").append(label.xmlEscape()).append("</th>")
        append("<td>").append(displayValue).append("</td>")
        append("</tr>\n")
    }

    // ── XHTML 골격 조합 ────────────────────────────────────────────────────────

    private fun buildXhtml(
        issue: IssueResponse,
        metaRows: String,
        bodyContent: String,
    ): String =
        """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Strict//EN"
  "http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd">
<html xmlns="http://www.w3.org/1999/xhtml">
<head>
  <meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
  <title>${issue.key.xmlEscape()} — ${issue.summary.xmlEscape()}</title>
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
        content: "${issue.key.xmlEscape()} — ${issue.summary.xmlEscape()}";
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
  <h1>${issue.key.xmlEscape()} — ${issue.summary.xmlEscape()}</h1>
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

    /** [Instant]를 `yyyy-MM-dd HH:mm` UTC 형식으로 포맷한다. */
    private fun Instant.formatDisplay(): String =
        DateTimeFormatter.ofPattern(DATE_PATTERN)
            .withZone(ZoneOffset.UTC)
            .format(this)

    companion object {
        private const val DESCRIPTION_PLACEHOLDER = "<p>(설명 없음)</p>"
        private const val EMPTY_VALUE = "-"
        private const val DATE_PATTERN = "yyyy-MM-dd HH:mm"
    }
}
