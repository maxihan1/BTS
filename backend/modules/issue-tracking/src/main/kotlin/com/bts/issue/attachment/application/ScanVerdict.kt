// 바이러스 스캔 결과를 나타내는 열거형

package com.bts.issue.attachment.application

/**
 * ClamAV 스캔 결과 판정.
 *
 * - [CLEAN]: 악성코드 미탐지 — 업로드 허용.
 * - [INFECTED]: 악성코드 탐지 — 업로드 거부([com.bts.issue.attachment.application.AttachmentInfectedException]).
 *
 * 스캐너 미가용·타임아웃·미상 응답은 [CLEAN]/[INFECTED] 가 아닌
 * [AttachmentScanUnavailableException] 을 던진다 (fail-closed).
 */
enum class ScanVerdict {
    /** 스캔 통과 — 파일에서 악성코드가 발견되지 않았다. */
    CLEAN,

    /** 악성코드 탐지 — 파일을 저장소에 저장하지 않는다. */
    INFECTED,
}
