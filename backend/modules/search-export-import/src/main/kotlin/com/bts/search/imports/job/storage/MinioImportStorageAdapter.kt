// MinIO SDK로 ImportObjectStoragePort를 구현하는 outbound 어댑터 — put/get/delete

package com.bts.search.imports.job.storage

import io.minio.BucketExistsArgs
import io.minio.GetObjectArgs
import io.minio.MakeBucketArgs
import io.minio.MinioClient
import io.minio.PutObjectArgs
import io.minio.RemoveObjectArgs
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.io.InputStream

/**
 * [ImportObjectStoragePort] MinIO 구현체.
 *
 * [MinioClient] 를 통해 Import 원본 파일과 실패행 에러로그를 `bts-imports` 버킷에 저장/조회/삭제한다.
 * 모든 MinIO SDK 예외는 [MinioImportStorageException] 으로 변환해 상위 레이어에 전달한다.
 *
 * ## BC 격리
 *
 * `export` BC 의 `MinioExportStorageAdapter`, issue-tracking 의 `MinioStorageAdapter` 와 완전히 별개로
 * 동작한다. 서로 다른 [MinioClient] 빈과 버킷을 사용하므로 첨부파일(`bts-attachments`), Export 결과
 * (`bts-exports`), Import 원본/에러로그(`bts-imports`)가 물리적으로 분리된다.
 *
 * ## 스트리밍
 *
 * [put] 은 [InputStream] 과 size 를 그대로 MinIO SDK 에 전달한다.
 * 대용량 파일도 메모리에 전부 적재하지 않는다.
 *
 * @param minioClient Import 전용 MinIO 접속 클라이언트 (`importMinioClient` 빈).
 * @param properties MinIO 접속 설정 (bucket 이름 포함).
 */
@Component
// MinIO SDK는 MinioException/IOException/NoSuchAlgorithmException 등 다양한 체크 예외를 던지므로
// 모두 MinioImportStorageException 으로 변환하기 위해 generic catch 를 사용한다 (export/issue-tracking 동일 관례).
@Suppress("TooGenericExceptionCaught")
class MinioImportStorageAdapter(
    @Qualifier("importMinioClient")
    private val minioClient: MinioClient,
    private val properties: MinioImportStorageConfig.Properties,
) : ImportObjectStoragePort {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Import 원본/에러로그 파일을 MinIO 버킷에 업로드한다.
     *
     * @param objectKey 저장소 내 오브젝트 경로.
     * @param inputStream 업로드할 바이트 스트림. 호출자가 close 책임.
     * @param size 스트림 바이트 수.
     * @param contentType MIME 타입.
     * @throws MinioImportStorageException 업로드 실패 시.
     */
    override fun put(
        objectKey: String,
        inputStream: InputStream,
        size: Long,
        contentType: String,
    ) {
        log.debug("put objectKey={} size={} contentType={}", objectKey, size, contentType)
        try {
            minioClient.putObject(
                PutObjectArgs.builder()
                    .bucket(properties.importBucket)
                    .`object`(objectKey)
                    .stream(inputStream, size, PART_SIZE)
                    .contentType(contentType)
                    .build(),
            )
        } catch (ex: Exception) {
            log.error("MinIO put 실패 objectKey={}", objectKey, ex)
            throw MinioImportStorageException("Import 오브젝트 업로드 실패: $objectKey", ex)
        }
    }

    /**
     * MinIO 버킷에서 오브젝트를 스트림으로 열어 반환한다.
     *
     * 반환된 [InputStream] 은 호출자가 close 해야 한다.
     *
     * @param objectKey 저장소 내 오브젝트 경로.
     * @return 오브젝트 바이트 스트림.
     * @throws MinioImportStorageException 오브젝트 없음 또는 다운로드 실패 시.
     */
    override fun get(objectKey: String): InputStream {
        log.debug("get objectKey={}", objectKey)
        try {
            return minioClient.getObject(
                GetObjectArgs.builder()
                    .bucket(properties.importBucket)
                    .`object`(objectKey)
                    .build(),
            )
        } catch (ex: Exception) {
            log.error("MinIO get 실패 objectKey={}", objectKey, ex)
            throw MinioImportStorageException("Import 오브젝트 다운로드 실패: $objectKey", ex)
        }
    }

    /**
     * MinIO 버킷에서 오브젝트를 삭제한다.
     *
     * 오브젝트가 존재하지 않아도 예외를 던지지 않는다(멱등성).
     *
     * @param objectKey 저장소 내 오브젝트 경로.
     * @throws MinioImportStorageException 네트워크/권한 오류 등 삭제 실패 시.
     */
    override fun delete(objectKey: String) {
        log.debug("delete objectKey={}", objectKey)
        try {
            minioClient.removeObject(
                RemoveObjectArgs.builder()
                    .bucket(properties.importBucket)
                    .`object`(objectKey)
                    .build(),
            )
        } catch (ex: Exception) {
            log.error("MinIO delete 실패 objectKey={}", objectKey, ex)
            throw MinioImportStorageException("Import 오브젝트 삭제 실패: $objectKey", ex)
        }
    }

    /**
     * 버킷이 존재하지 않으면 자동으로 생성한다.
     *
     * [MinioImportStorageConfig] 의 ApplicationRunner 가 기동 시 호출한다.
     *
     * @throws MinioImportStorageException 버킷 생성 실패 시.
     */
    fun ensureBucket() {
        val bucket = properties.importBucket
        try {
            val exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())
            if (!exists) {
                log.info("MinIO import 버킷 미존재 — 자동 생성 bucket={}", bucket)
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build())
                log.info("MinIO import 버킷 생성 완료 bucket={}", bucket)
            } else {
                log.debug("MinIO import 버킷 이미 존재 bucket={}", bucket)
            }
        } catch (ex: Exception) {
            log.error("MinIO import 버킷 보장 실패 bucket={}", bucket, ex)
            throw MinioImportStorageException("import 버킷 보장 실패: $bucket", ex)
        }
    }

    companion object {
        /** MinIO multipart 업로드 파트 크기. -1 이면 SDK 가 자동 결정. */
        private const val PART_SIZE = -1L
    }
}
