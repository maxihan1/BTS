// Import 원본/에러로그 오브젝트 스토리지 포트 인터페이스 — put/get/delete 추상화

package com.bts.search.imports.job.storage

import java.io.InputStream

/**
 * Import 원본 파일 및 실패행 에러로그를 오브젝트 스토리지에 저장/조회/삭제하는 포트 인터페이스.
 *
 * MinIO 구현체([MinioImportStorageAdapter])가 이 인터페이스를 구현한다.
 * 상위 서비스(ImportJobProcessor 등)는 구현체에 직접 의존하지 않고 이 포트를 통해 스토리지를 사용한다.
 *
 * ## BC 격리
 *
 * `export` BC 의 `ExportObjectStoragePort`, issue-tracking 의 `AttachmentStoragePort` 와 동형이지만
 * 패키지와 구현을 search-export-import BC 의 import 하위 도메인에 독립적으로 정의한다 (NEVER-1).
 *
 * ## 오브젝트 키 패턴
 *
 * `{projectKey}/{jobId}.{ext}` (원본), `{projectKey}/{jobId}-errors.csv` (에러로그) 형태를 사용한다.
 */
interface ImportObjectStoragePort {
    /**
     * 오브젝트를 버킷에 업로드한다.
     *
     * @param objectKey 저장소 내 오브젝트 경로.
     * @param inputStream 업로드할 바이트 스트림. 호출자가 close 책임.
     * @param size 스트림 바이트 수.
     * @param contentType MIME 타입 (예: `"text/csv; charset=UTF-8"`).
     * @throws MinioImportStorageException 업로드 실패 시.
     */
    fun put(
        objectKey: String,
        inputStream: InputStream,
        size: Long,
        contentType: String,
    )

    /**
     * 오브젝트를 스트림으로 열어 반환한다.
     *
     * 반환된 [InputStream] 은 호출자가 close 해야 한다.
     *
     * @param objectKey 저장소 내 오브젝트 경로.
     * @return 오브젝트 바이트 스트림.
     * @throws MinioImportStorageException 오브젝트 없음 또는 다운로드 실패 시.
     */
    fun get(objectKey: String): InputStream

    /**
     * 오브젝트를 삭제한다. 오브젝트가 없어도 예외를 던지지 않는다(멱등성).
     *
     * @param objectKey 저장소 내 오브젝트 경로.
     * @throws MinioImportStorageException 네트워크/권한 오류 등 삭제 실패 시.
     */
    fun delete(objectKey: String)
}
