// MinIO SDK를 사용해 AttachmentStoragePort를 구현하는 outbound 어댑터

package com.bts.issue.attachment.adapter

import com.bts.issue.attachment.MinioStorageException
import com.bts.issue.attachment.application.AttachmentStoragePort
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
 * [AttachmentStoragePort] MinIO 구현체.
 *
 * [MinioClient] 를 통해 오브젝트 스토리지에 put/get/remove 를 수행한다.
 * 모든 MinIO SDK 예외는 [MinioStorageException] 으로 변환해 상위 레이어에 전달한다.
 *
 * ## 스트리밍
 *
 * [put] 은 [InputStream] 과 size 를 그대로 MinIO SDK 에 전달한다.
 * 대용량 파일도 메모리에 전부 적재하지 않는다.
 *
 * ## ensureBucket
 *
 * [ensureBucket] 은 기동 시 한 번 호출되어 bucket 이 없으면 생성한다.
 * [MinioStorageConfig] 의 ApplicationRunner 가 호출한다.
 *
 * @param minioClient MinIO 접속 클라이언트.
 * @param properties MinIO 접속 설정 (bucket 이름 포함).
 */
@Component
class MinioStorageAdapter(
    private val minioClient: MinioClient,
    private val properties: MinioStorageConfig.Properties,
) : AttachmentStoragePort {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 오브젝트를 MinIO bucket 에 업로드한다.
     *
     * @param storageKey 저장소 내 오브젝트 경로.
     * @param input 업로드할 바이트 스트림. 호출자가 close 책임.
     * @param size 스트림 바이트 수.
     * @param contentType MIME 타입.
     * @throws MinioStorageException 업로드 실패 시.
     */
    override fun put(
        storageKey: String,
        input: InputStream,
        size: Long,
        contentType: String,
    ) {
        log.debug("put storageKey={} size={} contentType={}", storageKey, size, contentType)
        try {
            minioClient.putObject(
                PutObjectArgs.builder()
                    .bucket(properties.bucket)
                    .`object`(storageKey)
                    .stream(input, size, PART_SIZE)
                    .contentType(contentType)
                    .build(),
            )
        } catch (ex: Exception) {
            log.error("MinIO put 실패 storageKey={}", storageKey, ex)
            throw MinioStorageException("오브젝트 업로드 실패: $storageKey", ex)
        }
    }

    /**
     * MinIO bucket 에서 오브젝트를 다운로드한다.
     *
     * 반환된 [InputStream] 은 호출자가 close 해야 한다.
     *
     * @param storageKey 저장소 내 오브젝트 경로.
     * @return 오브젝트 바이트 스트림.
     * @throws MinioStorageException 오브젝트가 없거나 다운로드 실패 시.
     */
    override fun get(storageKey: String): InputStream {
        log.debug("get storageKey={}", storageKey)
        try {
            return minioClient.getObject(
                GetObjectArgs.builder()
                    .bucket(properties.bucket)
                    .`object`(storageKey)
                    .build(),
            )
        } catch (ex: Exception) {
            log.error("MinIO get 실패 storageKey={}", storageKey, ex)
            throw MinioStorageException("오브젝트 다운로드 실패: $storageKey", ex)
        }
    }

    /**
     * MinIO bucket 에서 오브젝트를 삭제한다.
     *
     * 오브젝트가 존재하지 않아도 예외를 던지지 않는다 (멱등성).
     *
     * @param storageKey 저장소 내 오브젝트 경로.
     * @throws MinioStorageException 네트워크/권한 오류 등 삭제 실패 시.
     */
    override fun remove(storageKey: String) {
        log.debug("remove storageKey={}", storageKey)
        try {
            minioClient.removeObject(
                RemoveObjectArgs.builder()
                    .bucket(properties.bucket)
                    .`object`(storageKey)
                    .build(),
            )
        } catch (ex: Exception) {
            log.error("MinIO remove 실패 storageKey={}", storageKey, ex)
            throw MinioStorageException("오브젝트 삭제 실패: $storageKey", ex)
        }
    }

    /**
     * bucket 이 존재하지 않으면 자동으로 생성한다. (NFR-6 bucket 보장)
     *
     * [MinioStorageConfig] 의 ApplicationRunner 가 기동 시 호출한다.
     *
     * @throws MinioStorageException bucket 생성 실패 시.
     */
    fun ensureBucket() {
        val bucket = properties.bucket
        try {
            val exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())
            if (!exists) {
                log.info("MinIO bucket 미존재 — 자동 생성 bucket={}", bucket)
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build())
                log.info("MinIO bucket 생성 완료 bucket={}", bucket)
            } else {
                log.debug("MinIO bucket 이미 존재 bucket={}", bucket)
            }
        } catch (ex: Exception) {
            log.error("MinIO bucket 보장 실패 bucket={}", bucket, ex)
            throw MinioStorageException("bucket 보장 실패: $bucket", ex)
        }
    }

    companion object {
        /** MinIO multipart 업로드 최소 파트 크기 (5 MiB). -1 이면 SDK가 자동 결정. */
        private const val PART_SIZE = -1L
    }
}
