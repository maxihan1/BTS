// 아바타 바이너리를 오브젝트 스토리지에 저장/조회/삭제하는 outbound port + 관련 도메인 타입
package com.atlas.bts.identity.profile.avatar

import java.io.InputStream

/**
 * 아바타 바이너리 저장소 outbound port.
 *
 * application 레이어가 infrastructure(MinIO) 에 의존하지 않도록 추상화한다.
 * 구현체는 [MinioAvatarStorageAdapter].
 *
 * ## objectKey 규칙
 * objectKey 는 저장소 내 경로이며 호출자(application service)가 결정한다.
 * 예: `"avatars/<userId>/<uuid>.png"`.
 */
interface AvatarStoragePort {
    /**
     * 아바타 오브젝트를 업로드한다(동일 key 재업로드 시 덮어쓴다).
     *
     * @param objectKey 저장소 내 오브젝트 경로.
     * @param bytes 업로드할 아바타 바이트(검증 완료 — [AvatarTypePolicy]).
     * @param contentType MIME(예: "image/png").
     * @throws AvatarStorageException 업로드 실패 시.
     */
    fun put(
        objectKey: String,
        bytes: ByteArray,
        contentType: String,
    )

    /**
     * 아바타 오브젝트를 조회한다.
     *
     * @param objectKey 저장소 내 오브젝트 경로.
     * @return 스트림 + contentType 을 담은 [AvatarObject]. 스트림은 호출자가 close 한다.
     * @throws AvatarObjectNotFoundException 오브젝트가 없을 때(컨트롤러가 404 매핑).
     * @throws AvatarStorageException 그 외 저장소 접근 실패 시.
     */
    fun get(objectKey: String): AvatarObject

    /**
     * 아바타 오브젝트를 삭제한다(존재하지 않아도 예외 없음 — 멱등).
     *
     * @param objectKey 저장소 내 오브젝트 경로.
     * @throws AvatarStorageException 네트워크/권한 등 삭제 실패 시.
     */
    fun delete(objectKey: String)
}

/**
 * 조회한 아바타 오브젝트 — 바이트 스트림 + 저장 시 기록된 contentType.
 *
 * contentType 은 MinIO `statObject` 로 회수한 값이다(컨트롤러가 응답 Content-Type 에 사용).
 *
 * @param content 아바타 바이트 스트림. 호출자가 close 책임.
 * @param contentType 저장 시 기록된 MIME.
 */
data class AvatarObject(
    val content: InputStream,
    val contentType: String,
)

/**
 * 아바타 오브젝트가 저장소에 없을 때 발생하는 예외.
 *
 * 컨트롤러(Task 5)가 404 로 매핑한다. 메시지는 존재 여부 이외 내부정보를 담지 않는다.
 *
 * @param message 일반화 메시지.
 */
class AvatarObjectNotFoundException(message: String) : RuntimeException(message)

/**
 * 아바타 오브젝트 스토리지 접근 실패(not-found 제외) 예외.
 *
 * MinIO SDK 예외를 래핑한다. 메시지에는 objectKey/버킷 등 내부정보를 담지 않는다(HTTP 누출 방지).
 *
 * @param message 일반화 메시지.
 * @param cause 원인 예외.
 */
class AvatarStorageException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
