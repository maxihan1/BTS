// ClamAV 스캔에서 악성코드가 탐지됐을 때 발생하는 예외

package com.bts.issue.attachment.application

/**
 * 바이러스 스캔 결과 악성코드가 탐지된 첨부 파일 업로드 시도에 대해 발생한다.
 *
 * ## 응답 매핑
 *
 * HTTP 응답에 파일 시그니처·바이러스명을 노출하지 않는다.
 * [com.bts.issue.attachment.web.AttachmentExceptionHandler] 가
 * 422 UNPROCESSABLE_ENTITY + `ISSUE_ATTACHMENT_INFECTED` 로 매핑한다.
 *
 * ## 상속 구조
 *
 * [RuntimeException] 을 직접 상속한다. [org.springframework.web.server.ResponseStatusException]
 * 상속을 금지한다 — catch-all [org.springframework.web.bind.annotation.ExceptionHandler] 가
 * ResponseStatusException 을 삼켜 상태 코드가 500 으로 변질되는 사고를 방지한다.
 *
 * ## 진단 정보
 *
 * [filename] 은 서버 로그 진단용으로만 사용한다. HTTP 응답·에러 메시지에 포함하지 않는다.
 *
 * @param filename 업로드를 시도한 원본 파일명 (진단 전용, 응답 비노출).
 */
class AttachmentInfectedException(
    val filename: String,
) : RuntimeException("바이러스 스캔에서 악성코드가 탐지되었습니다: filename=$filename")
