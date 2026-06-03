// IssueResponse를 openhtmltopdf로 렌더해 PDF ByteArray를 반환하는 컴포넌트
package com.bts.issue.pdf

import com.bts.issue.adapter.inbound.rest.IssueResponse
import com.openhtmltopdf.extend.FSSupplier
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream

/**
 * [IssueResponse]를 PDF 바이너리([ByteArray])로 변환한다.
 *
 * ## 처리 흐름
 * 1. [IssuePdfTemplate.render]로 XHTML 문자열 생성.
 * 2. [PdfRendererBuilder]에 NanumGothic 폰트를 등록하고 XHTML을 렌더.
 * 3. 렌더 결과 바이트를 반환.
 *
 * ## 외부 리소스 차단
 * [PdfRendererBuilder.withHtmlContent] 호출 시 `baseUri=null`을 전달해
 * 외부 URL 참조를 비활성화한다. 템플릿 allowlist에 `img` 태그가 없어 자연 차단된다.
 *
 * ## NFR1 보안
 * XHTML 본문은 [IssuePdfTemplate]이 descriptionHtml(OWASP sanitized) 경로만 사용하므로
 * 이 클래스는 별도 sanitize 없이 템플릿 출력을 그대로 렌더한다.
 *
 * @param template XHTML 문자열 생성 담당 컴포넌트.
 */
@Component
class IssuePdfRenderer(
    private val template: IssuePdfTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 이슈 응답 DTO를 PDF 바이너리로 변환한다.
     *
     * @param issue PDF로 출력할 이슈 응답 DTO. [IssueResponse.descriptionHtml]이 채워진 단건 응답 권장.
     * @return PDF 바이너리. 첫 5바이트는 `%PDF-` 시그니처.
     */
    fun render(issue: IssueResponse): ByteArray {
        log.debug("PDF 렌더 시작: issueKey={}", issue.key)
        val xhtml = template.render(issue)
        val baos =
            ByteArrayOutputStream().also { out ->
                PdfRendererBuilder()
                    // OFL 라이선스 NanumGothic — classpath 번들 폰트, 한글 임베딩 필수
                    .useFont(
                        FSSupplier { javaClass.getResourceAsStream(FONT_RESOURCE_PATH) },
                        FONT_FAMILY,
                    )
                    // baseUri=null — 외부 URL 리소스 fetch 비활성 (img allowlist 없음으로 자연 차단)
                    .withHtmlContent(xhtml, null)
                    .toStream(out)
                    .run()
            }
        log.debug("PDF 렌더 완료: issueKey={}, bytes={}", issue.key, baos.size())
        return baos.toByteArray()
    }

    companion object {
        /** 클래스패스 한글 폰트 리소스 경로. */
        private const val FONT_RESOURCE_PATH = "/fonts/NanumGothic-Regular.ttf"

        /** CSS `font-family`에 등록되는 폰트 패밀리명. IssuePdfTemplate의 CSS와 일치해야 한다. */
        private const val FONT_FAMILY = "NanumGothic"
    }
}
