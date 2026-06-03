// IssuePdfRenderer — XHTML→PDF 변환 결과 검증 테스트 (FR-IS-08 Task 3)
package com.bts.issue.pdf

import com.bts.issue.adapter.inbound.rest.IssueResponse
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import java.time.Instant
import java.util.UUID

/**
 * IssuePdfRenderer 단위 테스트.
 *
 * ## 검증 범위
 * - 렌더 결과가 PDF 바이너리 시그니처(`%PDF-`)로 시작한다.
 * - PDFTextStripper로 한글 텍스트가 실제 추출된다(tofu 아님 — 폰트 임베딩 검증).
 * - descriptionHtml=null 이슈도 예외 없이 유효 PDF를 반환한다.
 * - 템플릿 XHTML 파싱 실패 없이 렌더한다(예외 미발생).
 */
class IssuePdfRendererTest : DescribeSpec({

    val template = IssuePdfTemplate()
    val renderer = IssuePdfRenderer(template)

    val sampleResolution =
        IssueResponse.ResolutionSummary(
            id = UUID.fromString("00000000-0000-4000-8000-000000000010"),
            key = "fixed",
            name = "Fixed",
        )

    /** 한글 본문이 포함된 기본 픽스처. */
    fun koreanIssue(descriptionHtml: String? = "<p>한글 본문 내용입니다. 이슈 상세 설명.</p>") =
        IssueResponse(
            key = "ATLAS-99",
            id = UUID.fromString("00000000-0000-4000-8000-000000000099"),
            projectKey = "ATLAS",
            summary = "한글 제목 테스트 이슈",
            currentStateKey = "in_progress",
            reporterId = UUID.fromString("00000000-0000-4000-8000-000000000001"),
            version = 1L,
            createdAt = Instant.parse("2026-06-03T00:00:00Z"),
            updatedAt = Instant.parse("2026-06-03T01:00:00Z"),
            typeId = 1L,
            typeKey = "bug",
            typeName = "Bug",
            description = "raw description — must not appear",
            descriptionHtml = descriptionHtml,
            priority = 2,
            priorityName = "High",
            labels = listOf("backend"),
            impact = 1,
            impactName = "High",
            assigneeId = UUID.fromString("00000000-0000-4000-8000-000000000002"),
            resolution = sampleResolution,
        )

    describe("IssuePdfRenderer.render — PDF 시그니처") {

        it("한글 본문 이슈를 렌더하면 %PDF- 시그니처로 시작한다") {
            val bytes = renderer.render(koreanIssue())

            // PDF 바이너리는 반드시 %PDF- (0x25 0x50 0x44 0x46 0x2D) 로 시작해야 한다.
            bytes.size shouldBeAtLeast 5
            bytes[0] shouldBe 0x25.toByte() // '%'
            bytes[1] shouldBe 0x50.toByte() // 'P'
            bytes[2] shouldBe 0x44.toByte() // 'D'
            bytes[3] shouldBe 0x46.toByte() // 'F'
            bytes[4] shouldBe 0x2D.toByte() // '-'
        }
    }

    describe("IssuePdfRenderer.render — 한글 텍스트 추출 (폰트 임베딩 검증)") {

        it("생성된 PDF에서 PDFTextStripper로 이슈 키가 추출된다") {
            val bytes = renderer.render(koreanIssue())

            val extractedText = extractText(bytes)
            // 이슈 키는 ASCII이므로 폰트 임베딩과 무관하게 추출되어야 한다.
            extractedText shouldContain "ATLAS-99"
        }

        it("생성된 PDF에서 PDFTextStripper로 한글 본문이 추출된다 — tofu 아님") {
            val bytes = renderer.render(koreanIssue())

            val extractedText = extractText(bytes)
            // NanumGothic 임베딩이 성공해야 한글이 tofu(�) 없이 추출된다.
            extractedText shouldContain "한글"
        }
    }

    describe("IssuePdfRenderer.render — null 처리") {

        it("descriptionHtml이 null인 이슈도 예외 없이 유효 PDF를 반환한다") {
            val bytes = renderer.render(koreanIssue(descriptionHtml = null))

            // 예외 없이 반환되고, PDF 시그니처를 가져야 한다.
            bytes.size shouldBeAtLeast 5
            bytes[0] shouldBe 0x25.toByte()
        }
    }

    describe("IssuePdfRenderer.render — XHTML 파싱 무결성") {

        it("템플릿 XHTML을 파싱 실패 없이 렌더한다 — 예외 미발생") {
            // 예외 없이 완료되면 XHTML 파싱 성공으로 간주한다.
            renderer.render(koreanIssue())
        }
    }
})

/** PDFBox를 사용해 PDF 바이트에서 전체 텍스트를 추출한다. */
private fun extractText(bytes: ByteArray): String =
    PDDocument.load(bytes).use { doc ->
        PDFTextStripper().getText(doc)
    }

/** kotest 기본 matchers에 없는 `shouldBeAtLeast` 단순 헬퍼. */
private infix fun Int.shouldBeAtLeast(expected: Int) {
    if (this < expected) {
        throw AssertionError("Expected size at least $expected but was $this")
    }
}
