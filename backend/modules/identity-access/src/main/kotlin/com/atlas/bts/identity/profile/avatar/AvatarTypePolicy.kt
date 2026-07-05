// 아바타 업로드 허용 MIME 화이트리스트 + 크기 상한 판정 정책 — 단일 출처 (FR-PR-01)
package com.atlas.bts.identity.profile.avatar

/**
 * 아바타 업로드 시 허용 MIME/크기를 판정하는 정책.
 *
 * issue-tracking `AttachmentTypePolicy.ALLOWED_MIME` 의 **이미지 서브셋**만 허용한다.
 * 아바타는 화면에 `<img>` 로 렌더링되므로 실행 가능한 active-content(svg/html 등)는 전면 배제한다.
 *
 * ## 판정
 * - MIME: essence(파라미터 제거·소문자) 가 [ALLOWED_MIME] 에 있어야 한다.
 * - 크기: [MAX_BYTES] 이하여야 한다.
 * 위반 시 [AvatarValidationException] 을 던진다(컨트롤러 Task 5 가 400 매핑).
 *
 * ## 보안 경계
 * 내용 위조(png 바이트에 스크립트 삽입)는 내용기반 탐지 미도입으로 미탐지한다.
 * 인라인 실행 차단은 다운로드 응답의 `X-Content-Type-Options: nosniff` +
 * `Content-Disposition` 헤더(컨트롤러 Task 5 책임)가 담당한다.
 */
object AvatarTypePolicy {
    /** 아바타 업로드를 허용하는 essence MIME(소문자). issue-tracking 이미지 화이트리스트의 서브셋. */
    val ALLOWED_MIME: Set<String> =
        setOf("image/jpeg", "image/png", "image/gif", "image/webp")

    /** 아바타 최대 크기 — 5 MiB. */
    const val MAX_BYTES: Long = 5L * 1024 * 1024

    /** MIME → 저장 파일 확장자 매핑(`.` 제외 소문자). */
    private val MIME_TO_EXT: Map<String, String> =
        mapOf(
            "image/jpeg" to "jpg",
            "image/png" to "png",
            "image/gif" to "gif",
            "image/webp" to "webp",
        )

    /**
     * 아바타 MIME/크기가 허용 대상인지 검증한다.
     *
     * @param contentType 클라이언트가 보낸 MIME(파라미터·대문자 포함 가능).
     * @param sizeBytes 업로드 바이트 수.
     * @throws AvatarValidationException MIME 이 화이트리스트 밖이거나 크기가 [MAX_BYTES] 초과 시.
     *   메시지는 내부정보 노출 없이 일반화한다.
     */
    fun validate(
        contentType: String,
        sizeBytes: Long,
    ) {
        if (normalize(contentType) !in ALLOWED_MIME) {
            throw AvatarValidationException("지원하지 않는 이미지 형식입니다.")
        }
        if (sizeBytes > MAX_BYTES) {
            throw AvatarValidationException("이미지 크기는 5MB 이하여야 합니다.")
        }
    }

    /**
     * 허용 MIME 에 대응하는 저장 파일 확장자를 반환한다.
     *
     * @param contentType 허용 목록에 속한 MIME.
     * @return 확장자(`.` 제외 소문자, 예: "png").
     * @throws AvatarValidationException 허용 목록 밖 MIME 인 경우(fail-closed).
     */
    fun extensionFor(contentType: String): String =
        MIME_TO_EXT[normalize(contentType)]
            ?: throw AvatarValidationException("지원하지 않는 이미지 형식입니다.")

    /** MIME 을 essence(소문자, `;` 이후 파라미터 제거) 로 정규화한다. */
    private fun normalize(contentType: String): String = contentType.substringBefore(';').trim().lowercase()
}

/**
 * 아바타 MIME/크기 검증 실패 예외.
 *
 * 컨트롤러(Task 5)가 400 으로 매핑한다. 메시지는 내부정보를 담지 않는 일반 문구만 사용한다.
 *
 * @param message 사용자 노출용 일반화 메시지.
 */
class AvatarValidationException(message: String) : RuntimeException(message)
