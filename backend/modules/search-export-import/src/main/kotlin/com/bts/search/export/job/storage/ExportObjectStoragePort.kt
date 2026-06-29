// Export 결과 오브젝트 스토리지 포트 인터페이스 — put/openStream/remove 추상화

package com.bts.search.export.job.storage

import java.io.InputStream

/**
 * Export 결과 파일을 오브젝트 스토리지에 저장/조회/삭제하는 포트 인터페이스.
 *
 * MinIO 구현체([MinioExportStorageAdapter])가 이 인터페이스를 구현한다.
 * 상위 서비스(ExportJobWorker 등)는 구현체에 직접 의존하지 않고 이 포트를 통해 스토리지를 사용한다.
 *
 * ## BC 격리
 *
 * issue-tracking 의 `AttachmentStoragePort` 와 동형이지만 패키지와 메서드 시그니처를
 * search-export-import BC 에 맞게 독립 정의한다 (NEVER-1).
 *
 * ## 오브젝트 키 패턴
 *
 * `{projectKey}/{jobId}.{ext}` (예: `ATLAS/550e8400-e29b-41d4-a716-446655440000.csv`)
 */
interface ExportObjectStoragePort {
    /**
     * 오브젝트를 버킷에 업로드한다.
     *
     * @param key 저장소 내 오브젝트 경로. 패턴: `{projectKey}/{jobId}.{ext}`.
     * @param inputStream 업로드할 바이트 스트림. 호출자가 close 책임.
     * @param size 스트림 바이트 수.
     * @param contentType MIME 타입 (예: `"text/csv; charset=UTF-8"`).
     * @throws MinioExportStorageException 업로드 실패 시.
     */
    fun put(
        key: String,
        inputStream: InputStream,
        size: Long,
        contentType: String,
    )

    /**
     * 오브젝트를 스트림으로 열어 반환한다.
     *
     * 반환된 [InputStream] 은 호출자가 close 해야 한다.
     *
     * @param key 저장소 내 오브젝트 경로.
     * @return 오브젝트 바이트 스트림.
     * @throws MinioExportStorageException 오브젝트 없음 또는 다운로드 실패 시.
     */
    fun openStream(key: String): InputStream

    /**
     * 오브젝트를 삭제한다. 오브젝트가 없어도 예외를 던지지 않는다(멱등성).
     *
     * @param key 저장소 내 오브젝트 경로.
     * @throws MinioExportStorageException 네트워크/권한 오류 등 삭제 실패 시.
     */
    fun remove(key: String)
}
