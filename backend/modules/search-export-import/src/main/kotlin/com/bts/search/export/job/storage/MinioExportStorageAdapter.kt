// MinIO SDK로 ExportObjectStoragePort를 구현하는 outbound 어댑터 — put/openStream/remove

package com.bts.search.export.job.storage

import io.minio.BucketExistsArgs
import io.minio.GetObjectArgs
import io.minio.MakeBucketArgs
import io.minio.MinioClient
import io.minio.PutObjectArgs
import io.minio.RemoveObjectArgs
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.InputStream

/**
 * [ExportObjectStoragePort] MinIO 구현체.
 *
 * [MinioClient] 를 통해 Export 결과 파일을 `bts-exports` 버킷에 저장/조회/삭제한다.
 * 모든 MinIO SDK 예외는 [MinioExportStorageException] 으로 변환해 상위 레이어에 전달한다.
 *
 * ## BC 격리
 *
 * issue-tracking 의 `MinioStorageAdapter` 와 완전히 별개로 동작한다.
 * 서로 다른 [MinioClient] 빈과 버킷을 사용하므로 첨부파일(`bts-attachments`)과
 * Export 결과(`bts-exports`)가 분리된다 (ADR `docs/decisions/2026-06-29-fr-ex-02-async-export-jobs.md §D4`).
 *
 * ## 스트리밍
 *
 * [put] 은 [InputStream] 과 size 를 그대로 MinIO SDK 에 전달한다.
 * 대용량 파일도 메모리에 전부 적재하지 않는다.
 *
 * @param minioClient MinIO 접속 클라이언트.
 * @param properties MinIO 접속 설정 (bucket 이름 포함).
 */
@Component
// MinIO SDK는 MinioException/IOException/NoSuchAlgorithmException 등 다양한 체크 예외를 던지므로
// 모두 MinioExportStorageException 으로 변환하기 위해 generic catch 를 사용한다 (issue-tracking 동일 관례).
@Suppress("TooGenericExceptionCaught")
class MinioExportStorageAdapter(
    private val minioClient: MinioClient,
    private val properties: MinioExportStorageConfig.Properties,
) : ExportObjectStoragePort {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Export 결과 파일을 MinIO 버킷에 업로드한다.
     *
     * @param key 저장소 내 오브젝트 경로 (패턴: `{projectKey}/{jobId}.{ext}`).
     * @param inputStream 업로드할 바이트 스트림. 호출자가 close 책임.
     * @param size 스트림 바이트 수.
     * @param contentType MIME 타입.
     * @throws MinioExportStorageException 업로드 실패 시.
     */
    override fun put(
        key: String,
        inputStream: InputStream,
        size: Long,
        contentType: String,
    ) {
        log.debug("put key={} size={} contentType={}", key, size, contentType)
        try {
            minioClient.putObject(
                PutObjectArgs.builder()
                    .bucket(properties.bucket)
                    .`object`(key)
                    .stream(inputStream, size, PART_SIZE)
                    .contentType(contentType)
                    .build(),
            )
        } catch (ex: Exception) {
            log.error("MinIO put 실패 key={}", key, ex)
            throw MinioExportStorageException("Export 오브젝트 업로드 실패: $key", ex)
        }
    }

    /**
     * MinIO 버킷에서 오브젝트를 스트림으로 열어 반환한다.
     *
     * 반환된 [InputStream] 은 호출자가 close 해야 한다.
     *
     * @param key 저장소 내 오브젝트 경로.
     * @return 오브젝트 바이트 스트림.
     * @throws MinioExportStorageException 오브젝트 없음 또는 다운로드 실패 시.
     */
    override fun openStream(key: String): InputStream {
        log.debug("openStream key={}", key)
        try {
            return minioClient.getObject(
                GetObjectArgs.builder()
                    .bucket(properties.bucket)
                    .`object`(key)
                    .build(),
            )
        } catch (ex: Exception) {
            log.error("MinIO openStream 실패 key={}", key, ex)
            throw MinioExportStorageException("Export 오브젝트 다운로드 실패: $key", ex)
        }
    }

    /**
     * MinIO 버킷에서 오브젝트를 삭제한다.
     *
     * 오브젝트가 존재하지 않아도 예외를 던지지 않는다(멱등성).
     *
     * @param key 저장소 내 오브젝트 경로.
     * @throws MinioExportStorageException 네트워크/권한 오류 등 삭제 실패 시.
     */
    override fun remove(key: String) {
        log.debug("remove key={}", key)
        try {
            minioClient.removeObject(
                RemoveObjectArgs.builder()
                    .bucket(properties.bucket)
                    .`object`(key)
                    .build(),
            )
        } catch (ex: Exception) {
            log.error("MinIO remove 실패 key={}", key, ex)
            throw MinioExportStorageException("Export 오브젝트 삭제 실패: $key", ex)
        }
    }

    /**
     * 버킷이 존재하지 않으면 자동으로 생성한다.
     *
     * [MinioExportStorageConfig] 의 ApplicationRunner 가 기동 시 호출한다.
     *
     * @throws MinioExportStorageException 버킷 생성 실패 시.
     */
    fun ensureBucket() {
        val bucket = properties.bucket
        try {
            val exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())
            if (!exists) {
                log.info("MinIO export 버킷 미존재 — 자동 생성 bucket={}", bucket)
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build())
                log.info("MinIO export 버킷 생성 완료 bucket={}", bucket)
            } else {
                log.debug("MinIO export 버킷 이미 존재 bucket={}", bucket)
            }
        } catch (ex: Exception) {
            log.error("MinIO export 버킷 보장 실패 bucket={}", bucket, ex)
            throw MinioExportStorageException("export 버킷 보장 실패: $bucket", ex)
        }
    }

    companion object {
        /** MinIO multipart 업로드 파트 크기. -1 이면 SDK 가 자동 결정. */
        private const val PART_SIZE = -1L
    }
}
