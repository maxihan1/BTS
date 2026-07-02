// Import 오브젝트 스토리지 오류 — put/get/delete 실패 시 래핑

package com.bts.search.imports.job.storage

/**
 * [MinioImportStorageAdapter] 가 MinIO SDK 예외를 래핑해 던지는 도메인 예외.
 *
 * MinIO SDK 는 [io.minio.errors.MinioException], [java.io.IOException],
 * [java.security.NoSuchAlgorithmException] 등 다양한 체크 예외를 던진다.
 * 호출자가 MinIO SDK 를 직접 처리하지 않도록 이 예외로 일괄 변환한다.
 *
 * ## BC 격리
 *
 * `export` BC 의 `MinioExportStorageException`, issue-tracking 의 `MinioStorageException` 과
 * 별개 클래스로 정의한다. search-export-import BC 는 issue-tracking 내부 클래스를 import 하지 않는다 (NEVER-1).
 *
 * @param message 오류 메시지. 대상 오브젝트 키를 포함한다.
 * @param cause 원인 예외. MinIO SDK 가 던진 원본 예외.
 */
class MinioImportStorageException(
    message: String,
    cause: Throwable,
) : RuntimeException(message, cause)
