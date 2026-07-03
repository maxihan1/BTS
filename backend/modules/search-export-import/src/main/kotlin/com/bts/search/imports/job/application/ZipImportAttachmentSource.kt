// Import 첨부 zip 아카이브에서 파일명 기준 InputStream 을 반환하는 ImportAttachmentSource 구현체 (FR-IM-01 PR4)
package com.bts.search.imports.job.application

import com.bts.search.imports.job.storage.ImportObjectStoragePort
import com.bts.shared.issue.ImportAttachmentSource
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Import 첨부 zip 아카이브를 열어 [ImportAttachmentSource] 계약으로 노출하는 어댑터(FR-IM-01 PR4 Task 8).
 *
 * ## 매칭 책임 (BC 격리)
 *
 * zip 아카이브 내부 경로 구성(`{sourceKey}/{filename}` vs 평면 `{filename}`) 매칭은 이 클래스가
 * 소유한다 — [ImportAttachmentSource] 인터페이스 자체는 매칭 규칙을 규정하지 않는다(해당 인터페이스
 * KDoc "filename → sourceKey 매칭 책임" 참조). zip 은 search-export-import BC 가 업로드받아 소유한
 * 리소스이므로 매칭 로직도 이 BC 안에 둔다 — issue-tracking 어댑터는 매칭 결과(스트림)만 소비한다.
 *
 * ## 생명주기
 *
 * 생성 시점에 [storage] 에서 [objectKey] 오브젝트를 임시파일로 내려받은 뒤 [ZipFile] 로 엔트리를
 * 인덱싱한다. [close] 를 호출하면 [ZipFile] 을 닫고 임시파일을 삭제한다. [Closeable] 을 구현하므로
 * (`AutoCloseable` 의 하위 타입) `nullableSource.use { source -> ... }` 처럼 nullable 수신자에도
 * Kotlin stdlib `use` 를 안전하게 사용할 수 있다(`this == null` 이면 close 를 호출하지 않고 그대로
 * block 을 실행하는 `Closeable?.use` 오버로드).
 *
 * ## 반환 스트림 close 책임
 *
 * [open] 이 반환하는 [InputStream] 은 [ZipFile.getInputStream] 결과를 그대로 반환한다.
 * **호출자(소비 어댑터, issue-tracking `IssueImportAdapter`)가 close 해야 한다** — 이 클래스는
 * 파일명 매칭·검증까지만 책임진다(FR-AC-01 MinIO 스트림 누수 회귀 방지 원칙과 동형).
 *
 * ## 손상된 zip
 *
 * [ZipFile] 생성이 실패하면(손상된 아카이브) 예외를 전파하지 않고 경고 로그만 남긴다. 이후 모든
 * [open] 호출은 null 을 반환한다 — 호출자는 첨부 전체를 스킵으로 처리한다.
 *
 * @param storage zip 오브젝트를 내려받을 스토리지 포트.
 * @param objectKey 내려받을 zip 오브젝트 키(예: `import_jobs.attachments_object_key`).
 */
class ZipImportAttachmentSource(
    storage: ImportObjectStoragePort,
    objectKey: String,
) : ImportAttachmentSource, Closeable {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tempFile: Path = downloadToTempFile(storage, objectKey)
    private val zipFile: ZipFile? = openZipFileOrNull(tempFile, objectKey)
    private var closed = false

    /**
     * [filename]/[sourceKey] 로 zip 안에서 후보 엔트리를 찾아 스트림을 반환한다.
     *
     * `<sourceKey>/<filename>` → 평면 `<filename>` 순으로 시도한다. 미매칭, 크기 상한 초과,
     * zip-slip 이름, 손상된 아카이브, 이미 [close] 된 상태면 모두 null 을 반환한다 — 예외를
     * 던지지 않는다([ImportAttachmentSource.open] 계약).
     */
    override fun open(
        filename: String,
        sourceKey: String?,
    ): InputStream? =
        activeZipFileOrNull()?.let { zip ->
            findSafeEntry(zip, filename, sourceKey)?.let { entry -> zip.getInputStream(entry) }
        }

    /** [close] 되지 않았을 때만 [zipFile] 을 반환한다 — close 이후 [open] 호출을 null 로 방어한다. */
    private fun activeZipFileOrNull(): ZipFile? = if (closed) null else zipFile

    /** [ZipFile] 을 닫고 임시파일을 삭제한다. 반복 호출해도 안전하다(멱등). */
    @Suppress("TooGenericExceptionCaught")
    override fun close() {
        if (closed) return
        closed = true
        try {
            zipFile?.close()
        } catch (e: Exception) {
            log.warn("Import 첨부 zip 닫기 실패 — 무시하고 계속. cause={}", e.message)
        }
        try {
            Files.deleteIfExists(tempFile)
        } catch (e: Exception) {
            log.warn("Import 첨부 임시파일 삭제 실패 — path={} cause={}", tempFile, e.message)
        }
    }

    /**
     * `<sourceKey>/<filename>` → 평면 `<filename>` 순으로 처음 매칭된 엔트리를 찾아 안전성 검증까지
     * 통과했을 때만 반환한다. 매칭된 엔트리가 안전성 검증에 실패하면(크기 초과·zip-slip) 다음 후보로
     * 넘어가지 않고 즉시 null 을 반환한다(fail-closed — "일단 찾았으니 다른 후보도 더 시도" 하지 않는다).
     */
    private fun findSafeEntry(
        zip: ZipFile,
        filename: String,
        sourceKey: String?,
    ): ZipEntry? {
        val matched = candidateEntryNames(filename, sourceKey).firstNotNullOfOrNull { name -> zip.getEntry(name) }
        val safe = matched != null && isSafeEntry(matched)
        if (matched != null && !safe) {
            log.warn("Import 첨부 엔트리 검증 실패 — name={} size={}", matched.name, matched.size)
        }
        return if (safe) matched else null
    }

    /** `<sourceKey>/<filename>`(sourceKey 있을 때만) → 평면 `<filename>` 순의 후보 엔트리 이름 목록. */
    private fun candidateEntryNames(
        filename: String,
        sourceKey: String?,
    ): List<String> = listOfNotNull(sourceKey?.let { "$it/$filename" }, filename)

    /** 엔트리 비압축 크기가 [MAX_ENTRY_SIZE_BYTES] 이하이고 zip-slip 이름이 아닌지 검증한다. */
    private fun isSafeEntry(entry: ZipEntry): Boolean {
        return entry.size <= MAX_ENTRY_SIZE_BYTES && !isPathTraversal(entry.name)
    }

    /** 엔트리 이름이 절대경로이거나 `..` 세그먼트(상위 디렉터리 탈출)를 포함하는지 검사한다. */
    private fun isPathTraversal(name: String): Boolean = name.startsWith("/") || name.split("/").any { it == ".." }

    /** [storage] 에서 [objectKey] 를 시스템 임시 디렉터리 파일로 내려받는다. */
    private fun downloadToTempFile(
        storage: ImportObjectStoragePort,
        objectKey: String,
    ): Path {
        val temp = Files.createTempFile(TEMP_FILE_PREFIX, ".zip")
        storage.get(objectKey).use { input -> Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING) }
        return temp
    }

    /** [tempFile] 을 [ZipFile] 로 연다. 손상된 아카이브면 경고 로그만 남기고 null 을 반환한다(예외 미전파). */
    @Suppress("TooGenericExceptionCaught")
    private fun openZipFileOrNull(
        tempFile: Path,
        objectKey: String,
    ): ZipFile? =
        try {
            ZipFile(tempFile.toFile())
        } catch (e: Exception) {
            log.warn("Import 첨부 zip 열기 실패 — objectKey={} 첨부 전체 스킵. cause={}", objectKey, e.message)
            null
        }

    companion object {
        /** 첨부 zip 임시파일 접두어. */
        private const val TEMP_FILE_PREFIX = "bts-import-attachments-"

        /** 첨부 엔트리 비압축 크기 상한(100MB) — 초과 시 거부. */
        @Suppress("MagicNumber")
        const val MAX_ENTRY_SIZE_BYTES: Long = 100 * 1024 * 1024L
    }
}
