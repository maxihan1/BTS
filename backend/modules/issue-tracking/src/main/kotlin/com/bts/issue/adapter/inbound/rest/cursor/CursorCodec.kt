// cursor 페이지네이션 토큰 인코딩/디코딩 유틸

package com.bts.issue.adapter.inbound.rest.cursor

import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.UUID

private const val VERSION_PREFIX = "v1:"
private const val PAYLOAD_DELIMITER = "|"

/**
 * cursor 페이지네이션 위치를 나타내는 값 객체.
 *
 * keyset seek 기준 `(created_at DESC, id DESC)` 의 마지막 행 위치를 보유한다.
 *
 * @property createdAt 마지막 행의 생성 시각. 나노초 정밀도 포함.
 * @property id 마지막 행의 UUID.
 */
data class CursorPosition(
    val createdAt: OffsetDateTime,
    val id: UUID,
)

/**
 * cursor 토큰 디코딩 실패를 나타내는 예외.
 *
 * 위변조, 형식 오류, 지원하지 않는 버전 prefix 등 모든 디코딩 실패에 사용한다.
 * HTTP 계층에서 400 Bad Request 로 변환된다 (IssueExceptionHandler 매핑).
 *
 * @param message 실패 사유.
 */
class CursorDecodeException(message: String) : RuntimeException(message)

/**
 * cursor 토큰 인코딩/디코딩 유틸.
 *
 * 토큰 형식: `v1:<Base64URL("$createdAt|$id")>`.
 *
 * 설계 원칙.
 * - 클라이언트는 토큰 내부 구조에 의존하지 않는다 (opaque).
 * - 나노초 정밀도를 ISO 8601 문자열로 보존하여 keyset seek 경계를 정확히 복원한다.
 * - HMAC 서명 불필요 — BROWSE 권한 + visibility 술어가 실제 데이터 노출을 차단한다 (NFR-3).
 * - padding 미포함 Base64URL — URL 쿼리 파라미터 전달 시 `=` 인코딩 문제를 회피한다.
 */
object CursorCodec {

    /**
     * [CursorPosition] 을 opaque cursor 토큰으로 인코딩한다.
     *
     * 페이로드: `"$createdAt|$id"` (ISO 8601 + `|` 구분자 + UUID 문자열).
     * OffsetDateTime.toString() 은 나노초가 있으면 `.NNNNNNNNN` 까지 포함하는 ISO 8601 을 생성한다.
     *
     * @param createdAt 마지막 행의 생성 시각.
     * @param id 마지막 행의 UUID.
     * @return `v1:<Base64URL>` 형식의 opaque 토큰.
     */
    fun encode(
        createdAt: OffsetDateTime,
        id: UUID,
    ): String {
        val payload = "$createdAt$PAYLOAD_DELIMITER$id"
        val encoded = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(payload.toByteArray(Charsets.UTF_8))
        return "$VERSION_PREFIX$encoded"
    }

    /**
     * opaque cursor 토큰을 [CursorPosition] 으로 디코딩한다.
     *
     * @param token cursor 토큰. 빈 문자열이면 첫 페이지로 해석해 null 을 반환한다 (예외 아님).
     * @return 디코딩된 [CursorPosition]. 빈 문자열이면 null.
     * @throws CursorDecodeException 토큰 형식 오류, 지원하지 않는 버전 prefix, 파싱 실패 시.
     */
    fun decode(token: String): CursorPosition? {
        if (token.isEmpty()) return null

        if (!token.startsWith(VERSION_PREFIX)) {
            throw CursorDecodeException("지원하지 않는 cursor 버전 prefix: ${token.take(10)}")
        }

        val encoded = token.removePrefix(VERSION_PREFIX)

        val payload = try {
            Base64.getUrlDecoder()
                .decode(encoded)
                .toString(Charsets.UTF_8)
        } catch (ex: IllegalArgumentException) {
            throw CursorDecodeException("cursor 토큰 Base64URL 디코딩 실패: ${ex.message}")
        }

        val delimiterIndex = payload.indexOf(PAYLOAD_DELIMITER)
        if (delimiterIndex < 0) {
            throw CursorDecodeException("cursor 토큰 형식 오류: 구분자(|)가 없습니다.")
        }

        val rawDate = payload.substring(0, delimiterIndex)
        val rawId = payload.substring(delimiterIndex + 1)

        val createdAt = try {
            OffsetDateTime.parse(rawDate)
        } catch (ex: DateTimeParseException) {
            throw CursorDecodeException("cursor 토큰 날짜 파싱 실패: ${ex.message}")
        }

        val id = try {
            UUID.fromString(rawId)
        } catch (ex: IllegalArgumentException) {
            throw CursorDecodeException("cursor 토큰 UUID 파싱 실패: ${ex.message}")
        }

        return CursorPosition(createdAt, id)
    }
}
