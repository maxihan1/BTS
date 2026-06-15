// MinIO 오브젝트 스토리지 접근 실패 시 발생하는 도메인 예외

package com.bts.issue.attachment

/**
 * MinIO 오브젝트 스토리지 작업 실패 시 발생하는 예외.
 *
 * put/get/remove 과정에서 발생한 [cause] 를 래핑해 상위 레이어에 전달한다.
 *
 * @param message 오류 설명.
 * @param cause 원인 예외.
 */
class MinioStorageException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
