// ZipImportAttachmentSource 단위 테스트 — 파일명 매칭/크기 상한/zip-slip 거부/손상 zip/close 정리 (FR-IM-01 PR4 Task 8)
package com.bts.search.imports.job.application

import com.bts.search.imports.job.storage.ImportObjectStoragePort
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * [ZipImportAttachmentSource] 단위 테스트.
 *
 * 실제 [ZipOutputStream] 으로 zip 바이트를 생성하고, [ImportObjectStoragePort] 는 MockK 로 그 바이트를
 * 반환하도록 스텁한다(임시 storage stub 주입) — production 코드가 [ImportObjectStoragePort.get] 결과를
 * 임시파일로 내려받아 [java.util.zip.ZipFile] 로 여는 전체 경로를 실제로 검증한다.
 *
 * 검증 항목.
 * - (i) `<sourceKey>/<filename>` 우선 매칭, 없으면 평면 `<filename>` 매칭, 둘 다 없으면 null.
 * - (ii) 비압축 크기가 [ZipImportAttachmentSource.MAX_ENTRY_SIZE_BYTES] 를 초과하는 엔트리는 거부(null).
 * - (iii) zip-slip(`..` 세그먼트/절대경로) 이름의 엔트리는 거부(null).
 * - (iv) 손상된 zip(매직바이트 불일치)은 모든 [ZipImportAttachmentSource.open] 호출이 예외 대신 null.
 * - (v) [ZipImportAttachmentSource.close] 가 ZipFile 을 닫고 임시파일을 삭제하며, 이후 open 은 null.
 */
class ZipImportAttachmentSourceTest {
    private val storage: ImportObjectStoragePort = mockk()

    // ── (i) 파일명 매칭 ──────────────────────────────────────────────────────────

    @Test
    fun `open matches sourceKey-prefixed entry before flat filename`() {
        val bytes =
            buildZip(
                "JIRA-1/a.png" to "prefixed".toByteArray(),
                "a.png" to "flat".toByteArray(),
            )
        every { storage.get(any()) } returns ByteArrayInputStream(bytes)
        val source = ZipImportAttachmentSource(storage, "PROJ/job-1-attachments.zip")

        val stream = source.open("a.png", "JIRA-1")

        assertThat(stream).isNotNull()
        assertThat(stream!!.readBytes()).isEqualTo("prefixed".toByteArray())
        source.close()
    }

    @Test
    fun `open falls back to flat filename when sourceKey-prefixed entry is absent`() {
        val bytes = buildZip("a.png" to "flat".toByteArray())
        every { storage.get(any()) } returns ByteArrayInputStream(bytes)
        val source = ZipImportAttachmentSource(storage, "PROJ/job-1-attachments.zip")

        val stream = source.open("a.png", "JIRA-1")

        assertThat(stream).isNotNull()
        assertThat(stream!!.readBytes()).isEqualTo("flat".toByteArray())
        source.close()
    }

    @Test
    fun `open returns null when no candidate entry matches`() {
        val bytes = buildZip("other.png" to "x".toByteArray())
        every { storage.get(any()) } returns ByteArrayInputStream(bytes)
        val source = ZipImportAttachmentSource(storage, "PROJ/job-1-attachments.zip")

        val stream = source.open("a.png", "JIRA-1")

        assertThat(stream).isNull()
        source.close()
    }

    // ── (ii) 크기 상한 ───────────────────────────────────────────────────────────

    @Test
    fun `open rejects entry exceeding max uncompressed size`() {
        val oversized = ByteArray((ZipImportAttachmentSource.MAX_ENTRY_SIZE_BYTES + 1).toInt())
        val bytes = buildZip("big.bin" to oversized)
        every { storage.get(any()) } returns ByteArrayInputStream(bytes)
        val source = ZipImportAttachmentSource(storage, "PROJ/job-1-attachments.zip")

        val stream = source.open("big.bin", null)

        assertThat(stream).isNull()
        source.close()
    }

    // ── (iii) zip-slip 거부 ──────────────────────────────────────────────────────

    @Test
    fun `open rejects entry name containing parent directory traversal segment`() {
        val bytes = buildZip("../evil.txt" to "x".toByteArray())
        every { storage.get(any()) } returns ByteArrayInputStream(bytes)
        val source = ZipImportAttachmentSource(storage, "PROJ/job-1-attachments.zip")

        val stream = source.open("../evil.txt", null)

        assertThat(stream).isNull()
        source.close()
    }

    @Test
    fun `open rejects entry name with absolute path`() {
        val bytes = buildZip("/etc/passwd" to "x".toByteArray())
        every { storage.get(any()) } returns ByteArrayInputStream(bytes)
        val source = ZipImportAttachmentSource(storage, "PROJ/job-1-attachments.zip")

        val stream = source.open("/etc/passwd", null)

        assertThat(stream).isNull()
        source.close()
    }

    // ── (iv) 손상된 zip ──────────────────────────────────────────────────────────

    @Test
    fun `open returns null for every call when the archive itself is corrupted`() {
        every { storage.get(any()) } returns ByteArrayInputStream("not a zip file".toByteArray())
        val source = ZipImportAttachmentSource(storage, "PROJ/job-1-attachments.zip")

        assertThat(source.open("a.png", "JIRA-1")).isNull()
        assertThat(source.open("b.png", null)).isNull()
        source.close()
    }

    // ── (v) close() 정리 ─────────────────────────────────────────────────────────

    @Test
    fun `close closes the zip file and deletes the temp file, making subsequent open calls return null`() {
        val bytes = buildZip("a.png" to "flat".toByteArray())
        every { storage.get(any()) } returns ByteArrayInputStream(bytes)
        val source = ZipImportAttachmentSource(storage, "PROJ/job-1-attachments.zip")
        // sanity: 정상 동작 확인 후 close
        assertThat(source.open("a.png", null)).isNotNull()
        val tempFile = tempFileOf(source)
        assertThat(Files.exists(tempFile)).isTrue()

        source.close()

        assertThat(Files.exists(tempFile)).isFalse()
        assertThat(source.open("a.png", null)).isNull()
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────────

    /** [entries] 로 구성된 zip 아카이브 바이트를 생성한다. */
    private fun buildZip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** private [ZipImportAttachmentSource.tempFile] 필드를 리플렉션으로 읽는다 — close() 정리 검증 전용. */
    private fun tempFileOf(source: ZipImportAttachmentSource): Path {
        val field = ZipImportAttachmentSource::class.java.getDeclaredField("tempFile")
        field.isAccessible = true
        return field.get(source) as Path
    }
}
