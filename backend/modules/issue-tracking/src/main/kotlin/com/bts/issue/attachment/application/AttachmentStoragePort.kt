// 첨부파일 바이너리를 오브젝트 스토리지에 저장/조회/삭제하는 outbound port 인터페이스

package com.bts.issue.attachment.application

import java.io.InputStream

/**
 * 첨부파일 바이너리 저장소 outbound port.
 *
 * application 레이어가 infrastructure(MinIO 등)에 의존하지 않도록
 * 인터페이스로 추상화한다. 구현체는 [com.bts.issue.attachment.adapter.MinioStorageAdapter].
 *
 * ## 스트리밍 기반
 *
 * 100MB 크기의 파일을 메모리에 전부 적재하지 않도록 [InputStream] 기반으로 설계한다.
 * [put] 은 size 를 별도로 받아 MinIO `putObject` 에 전달한다.
 *
 * ## storageKey 규칙
 *
 * storageKey 는 오브젝트 스토리지의 경로이며, 호출자(application service)가 결정한다.
 * 예: `"issues/PROJ-1/attachments/<uuid>.pdf"`
 */
interface AttachmentStoragePort {
    /**
     * 오브젝트를 업로드한다.
     *
     * @param storageKey 저장소 내 오브젝트 경로 (슬래시 포함 가능).
     * @param input 업로드할 바이트 스트림. 호출자가 close 책임을 갖는다.
     * @param size 스트림의 바이트 수. MinIO multipart 계산에 사용된다.
     * @param contentType MIME 타입 (예: "application/pdf", "image/png").
     */
    fun put(
        storageKey: String,
        input: InputStream,
        size: Long,
        contentType: String,
    )

    /**
     * 오브젝트를 다운로드한다.
     *
     * 반환된 [InputStream] 은 호출자가 close 해야 한다.
     *
     * @param storageKey 저장소 내 오브젝트 경로.
     * @return 오브젝트 바이트 스트림.
     * @throws com.bts.issue.attachment.MinioStorageException 오브젝트가 없거나 접근 실패 시.
     */
    fun get(storageKey: String): InputStream

    /**
     * 오브젝트를 삭제한다.
     *
     * 오브젝트가 존재하지 않아도 예외를 던지지 않는다 (멱등성).
     *
     * @param storageKey 저장소 내 오브젝트 경로.
     */
    fun remove(storageKey: String)
}
