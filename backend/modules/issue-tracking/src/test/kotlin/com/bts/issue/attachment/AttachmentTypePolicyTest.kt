// 첨부 업로드 MIME/확장자 화이트리스트 정책 단위 테스트
package com.bts.issue.attachment

import com.bts.issue.attachment.application.AttachmentTypePolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class AttachmentTypePolicyTest {
    @ParameterizedTest
    @CsvSource(
        // contentType, filename — 허용되어야 하는 케이스
        "image/png, photo.png",
        "image/jpeg, photo.jpeg",
        "application/pdf, report.pdf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document, doc.docx",
        // docx가 zip/octet-stream 으로 보고되는 오탐 회피
        "application/zip, report.docx",
        "application/octet-stream, report.docx",
        "application/zip, archive.zip",
        "video/mp4, clip.mp4",
        "text/csv, data.csv",
        // 대문자 + 파라미터 정규화
        "IMAGE/PNG; q=1, A.PNG",
    )
    fun `허용 타입과 확장자는 isAllowed true`(
        contentType: String,
        filename: String,
    ) {
        assertThat(AttachmentTypePolicy.isAllowed(contentType, filename)).isTrue()
    }

    @ParameterizedTest
    @CsvSource(
        // 위험 active-content
        "text/html, evil.html",
        "image/svg+xml, x.svg",
        "application/xhtml+xml, x.xhtml",
        "application/javascript, a.js",
        "application/x-msdownload, a.exe",
        "application/x-sh, run.sh",
        // 확장자 위장 — MIME 은 허용이나 확장자가 차단 대상
        "image/png, evil.html",
        // octet-stream + 차단 확장자
        "application/octet-stream, evil.svg",
        // 확장자 없음
        "application/pdf, noext",
        // MIME 차단 + 허용 확장자 위장
        "text/html, fake.png",
    )
    fun `차단 타입 또는 확장자는 isAllowed false`(
        contentType: String,
        filename: String,
    ) {
        assertThat(AttachmentTypePolicy.isAllowed(contentType, filename)).isFalse()
    }

    @Test
    fun `빈 contentType은 확장자에 위임한다`() {
        assertThat(AttachmentTypePolicy.isAllowed("", "photo.png")).isTrue()
        assertThat(AttachmentTypePolicy.isAllowed("", "evil.html")).isFalse()
    }
}
