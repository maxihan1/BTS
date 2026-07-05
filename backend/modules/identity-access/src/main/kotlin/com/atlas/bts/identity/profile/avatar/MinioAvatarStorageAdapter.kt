// AvatarStoragePort 를 MinIO SDK 로 구현하는 outbound 어댑터 (put/statObject+getObject/removeObject)
package com.atlas.bts.identity.profile.avatar

import io.minio.BucketExistsArgs
import io.minio.GetObjectArgs
import io.minio.MakeBucketArgs
import io.minio.MinioClient
import io.minio.PutObjectArgs
import io.minio.RemoveObjectArgs
import io.minio.StatObjectArgs
import io.minio.errors.ErrorResponseException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

/**
 * [AvatarStoragePort] 의 MinIO 구현체.
 *
 * issue-tracking `MinioStorageAdapter` 를 참조 복제하되 아바타 전용 배선이다(모듈 격리, 타 모듈 import 없음).
 * MinIO SDK 예외는 [AvatarStorageException] 으로, not-found(NoSuchKey 등)는 [AvatarObjectNotFoundException]
 * 으로 변환한다. 예외 메시지에는 objectKey/버킷 등 내부정보를 담지 않는다(HTTP 누출 방지).
 *
 * ## contentType 회수
 * [get] 은 `statObject` 로 저장 시 기록된 contentType 을 회수한 뒤 `getObject` 스트림과 함께 반환한다.
 * (다운로드 응답의 `X-Content-Type-Options: nosniff` + Content-Type 헤더는 컨트롤러 Task 5 책임.)
 *
 * @param minioClient 아바타 전용 MinIO 클라이언트([MinioAvatarStorageConfig.avatarMinioClient]).
 * @param properties 아바타 버킷 이름 포함 접속 설정.
 */
@Component
// MinIO SDK 는 다양한 체크 예외(MinioException/IOException/NoSuchAlgorithmException 등)를 던지므로
// 모두 도메인 예외로 변환하기 위해 generic catch 를 사용한다(issue-tracking MinioStorageAdapter 동일 관례).
@Suppress("TooGenericExceptionCaught")
class MinioAvatarStorageAdapter(
    @Qualifier("avatarMinioClient") private val minioClient: MinioClient,
    private val properties: MinioAvatarStorageConfig.Properties,
) : AvatarStoragePort {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun put(
        objectKey: String,
        bytes: ByteArray,
        contentType: String,
    ) {
        try {
            bytes.inputStream().use { stream ->
                minioClient.putObject(
                    PutObjectArgs.builder()
                        .bucket(properties.avatarBucket)
                        .`object`(objectKey)
                        .stream(stream, bytes.size.toLong(), PART_SIZE)
                        .contentType(contentType)
                        .build(),
                )
            }
        } catch (ex: Exception) {
            log.error("아바타 put 실패 objectKey={}", objectKey, ex)
            throw AvatarStorageException("아바타 저장에 실패했습니다.", ex)
        }
    }

    override fun get(objectKey: String): AvatarObject {
        val contentType = statContentType(objectKey)
        val stream =
            try {
                minioClient.getObject(
                    GetObjectArgs.builder()
                        .bucket(properties.avatarBucket)
                        .`object`(objectKey)
                        .build(),
                )
            } catch (ex: ErrorResponseException) {
                throw notFoundOr(ex, objectKey)
            } catch (ex: Exception) {
                log.error("아바타 get 실패 objectKey={}", objectKey, ex)
                throw AvatarStorageException("아바타 조회에 실패했습니다.", ex)
            }
        return AvatarObject(stream, contentType)
    }

    override fun delete(objectKey: String) {
        try {
            minioClient.removeObject(
                RemoveObjectArgs.builder()
                    .bucket(properties.avatarBucket)
                    .`object`(objectKey)
                    .build(),
            )
        } catch (ex: Exception) {
            log.error("아바타 delete 실패 objectKey={}", objectKey, ex)
            throw AvatarStorageException("아바타 삭제에 실패했습니다.", ex)
        }
    }

    /**
     * 버킷이 없으면 생성한다(idempotent).
     *
     * [MinioAvatarStorageConfig] 의 ApplicationRunner 가 기동 시 호출한다.
     *
     * @throws AvatarStorageException 버킷 존재 확인/생성 실패 시.
     */
    fun ensureBucket() {
        val bucket = properties.avatarBucket
        try {
            val exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build())
                log.info("아바타 버킷 생성 완료 bucket={}", bucket)
            }
        } catch (ex: Exception) {
            log.error("아바타 버킷 보장 실패 bucket={}", bucket, ex)
            throw AvatarStorageException("아바타 버킷 보장에 실패했습니다.", ex)
        }
    }

    /** statObject 로 저장 시 기록된 contentType 을 회수한다. not-found 는 [AvatarObjectNotFoundException]. */
    private fun statContentType(objectKey: String): String =
        try {
            minioClient.statObject(
                StatObjectArgs.builder()
                    .bucket(properties.avatarBucket)
                    .`object`(objectKey)
                    .build(),
            ).contentType() ?: DEFAULT_CONTENT_TYPE
        } catch (ex: ErrorResponseException) {
            throw notFoundOr(ex, objectKey)
        } catch (ex: Exception) {
            log.error("아바타 stat 실패 objectKey={}", objectKey, ex)
            throw AvatarStorageException("아바타 조회에 실패했습니다.", ex)
        }

    /** MinIO 에러 코드가 not-found 계열이면 [AvatarObjectNotFoundException], 아니면 [AvatarStorageException]. */
    private fun notFoundOr(
        ex: ErrorResponseException,
        objectKey: String,
    ): RuntimeException {
        val code = ex.errorResponse()?.code()
        if (code in NOT_FOUND_CODES) {
            return AvatarObjectNotFoundException("아바타를 찾을 수 없습니다.")
        }
        log.error("아바타 접근 실패 objectKey={} code={}", objectKey, code, ex)
        return AvatarStorageException("아바타 조회에 실패했습니다.", ex)
    }

    companion object {
        /** MinIO multipart 업로드 최소 파트 크기. -1 이면 SDK 가 자동 결정. */
        private const val PART_SIZE = -1L

        /** statObject 에 contentType 이 없을 때 폴백 MIME. */
        private const val DEFAULT_CONTENT_TYPE = "application/octet-stream"

        /** not-found 로 간주할 MinIO 에러 코드. */
        private val NOT_FOUND_CODES = setOf("NoSuchKey", "NoSuchObject", "NoSuchBucket")
    }
}
