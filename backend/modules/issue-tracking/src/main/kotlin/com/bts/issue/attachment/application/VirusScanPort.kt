// 바이러스 스캔 outbound port 인터페이스 — ClamAV 등 구현체를 추상화한다.

package com.bts.issue.attachment.application

import java.io.InputStream

/**
 * 첨부파일 바이러스 스캔 outbound port.
 *
 * application 레이어가 infrastructure(ClamAV 등)에 의존하지 않도록 인터페이스로 추상화한다.
 * 구현체는 [com.bts.issue.attachment.adapter.ClamdInstreamScanner].
 *
 * ## fail-closed
 *
 * 스캔 결과는 [ScanVerdict.CLEAN] 또는 [ScanVerdict.INFECTED] 두 가지뿐이다.
 * 스캐너 미가용 / 타임아웃 / 미상 응답은 [AttachmentScanUnavailableException] 을 던진다.
 * "예외 = 업로드 허용" 같은 경로는 보안 갭이므로 호출자는 예외를 그대로 전파해야 한다.
 *
 * ## 스트리밍
 *
 * [scan] 은 [InputStream] 기반으로 설계한다. 대용량 파일을 메모리에 전부 적재하지 않도록
 * 호출자는 임시파일 기반 스트림을 주입해야 한다.
 *
 * @see com.bts.issue.attachment.adapter.ClamdInstreamScanner
 */
interface VirusScanPort {
    /**
     * 파일 스트림을 스캔하고 판정 결과를 반환한다.
     *
     * @param input 스캔할 바이트 스트림. 호출자가 close 책임을 갖는다.
     * @return [ScanVerdict.CLEAN] 또는 [ScanVerdict.INFECTED].
     * @throws AttachmentScanUnavailableException 스캔을 수행할 수 없는 경우 (fail-closed).
     */
    fun scan(input: InputStream): ScanVerdict
}
