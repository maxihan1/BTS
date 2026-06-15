// ClamAV 데몬 미가용·타임아웃·미상 응답 시 발생하는 예외 (fail-closed)

package com.bts.issue.attachment.application

/**
 * ClamAV 바이러스 스캔을 수행할 수 없을 때 발생한다.
 *
 * 다음 상황에서 발생한다.
 * - ClamAV 데몬에 연결할 수 없을 때 (연결 거부, 타임아웃)
 * - 스캔 응답을 읽는 도중 IOException 이 발생했을 때
 * - clamd 가 알 수 없는 응답 또는 오류 응답을 반환했을 때
 * - `INSTREAM size limit exceeded` 등 스캔 한도 초과 응답을 받았을 때
 *
 * ## fail-closed 정책
 *
 * 이 예외가 발생하면 파일 업로드를 거부해야 한다. "스캔 불가 = 우회 허용" 같은 경로는
 * 보안 갭을 만드므로 [com.bts.issue.attachment.web.AttachmentExceptionHandler] 가
 * 503 으로 매핑한다.
 *
 * ## 상속 구조
 *
 * [RuntimeException] 을 직접 상속한다. [org.springframework.web.server.ResponseStatusException]
 * 상속을 금지한다 — catch-all [org.springframework.web.bind.annotation.ExceptionHandler] 가
 * ResponseStatusException 을 삼켜 상태 코드가 500 으로 변질되는 사고를 방지한다.
 *
 * @param message 진단 메시지. HTTP 응답에는 노출하지 않는다.
 * @param cause 원인 예외.
 */
class AttachmentScanUnavailableException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
