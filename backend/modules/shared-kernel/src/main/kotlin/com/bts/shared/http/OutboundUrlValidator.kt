// Webhook URL을 검증해 SSRF 내부망 차단 및 스킴 검사를 수행하는 컴포넌트

package com.bts.shared.http

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.InetAddress
import java.net.URI

/**
 * Webhook URL 의 SSRF(Server-Side Request Forgery) 위험을 평가하는 검증기.
 *
 * ## 검증 순서
 * 1. blank 또는 URI 파싱 실패 → [UrlCheck.Malformed]
 * 2. 스킴이 http/https 가 아닌 경우 → [UrlCheck.Blocked]
 * 3. 호스트 DNS 해석 → 각 IP 가 [isInternal] 이면 → [UrlCheck.Blocked]
 * 4. 모두 통과 → [UrlCheck.Allowed]
 *
 * ## 한계 (G2 — DNS rebinding / TOCTOU)
 * 이 검증기는 DNS 조회 시점과 실제 HTTP 연결 시점이 다른 TOCTOU 구조를 가진다.
 * DNS rebinding 공격자가 검증 시점과 연결 시점 사이에 DNS 응답을 바꿀 수 있다.
 * 이 위험은 관리자 전용 URL 설정 + 현재 PR2 범위에서 수용한다.
 * 완전 차단(연결 IP 핀잉)은 후속 작업.
 *
 * ## IPv6 ULA / IPv4-mapped IPv6
 * [InetAddress] 내장 플래그가 IPv6 ULA(fc00::/7)와 IPv4-mapped IPv6(::ffff:x.x.x.x)를
 * 사설망으로 분류하지 않으므로 [isInternal] 에 명시적 검사를 추가한다.
 */
@Component
class OutboundUrlValidator {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * [url] 을 검사해 [UrlCheck] 결과를 반환한다.
     *
     * 네트워크 요청을 보내지 않는다 — DNS 조회만 수행 (리터럴 IP 는 DNS 조회 없음).
     *
     * @param url 검사할 Webhook URL 문자열
     * @return [UrlCheck.Allowed], [UrlCheck.Blocked], 또는 [UrlCheck.Malformed]
     */
    @Suppress("ReturnCount") // 보안 검증 함수 특성상 단계별 early return이 로직을 명확하게 함
    fun check(url: String): UrlCheck {
        if (url.isBlank()) return UrlCheck.Malformed("URL이 비어 있습니다")

        val uri =
            runCatching { URI(url) }.getOrElse {
                return UrlCheck.Malformed("URL 파싱 실패: ${it.message}")
            }

        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return UrlCheck.Blocked("허용되지 않는 스킴: $scheme")
        }

        val host = uri.host ?: return UrlCheck.Malformed("호스트가 없는 URL")

        val addresses =
            runCatching { InetAddress.getAllByName(host).toList() }.getOrElse {
                log.warn("webhook_url_validator_dns_failed host={} error={}", host, it.message)
                return UrlCheck.Blocked("DNS 조회 실패: ${it.message}")
            }

        for (addr in addresses) {
            if (isInternal(addr)) {
                log.warn("webhook_url_blocked host={} reason=internal_address", host)
                return UrlCheck.Blocked("내부망 주소 차단: ${addr.hostAddress}")
            }
        }

        return UrlCheck.Allowed
    }

    /**
     * [addr] 이 내부망 주소인지 검사한다.
     *
     * 내장 플래그([InetAddress.isLoopbackAddress] 등) + IPv6 ULA(fc00::/7) +
     * IPv4-mapped IPv6(::ffff:x.x.x.x) 언래핑 후 재검사를 포함한다.
     *
     * @param addr 검사할 [InetAddress]
     * @return 내부망 주소이면 true
     */
    @Suppress("ReturnCount", "MagicNumber") // IPv6 플래그 검사 특성상 early return + bitmask 필수
    private fun isInternal(addr: InetAddress): Boolean {
        if (addr.isLoopbackAddress) return true
        if (addr.isLinkLocalAddress) return true
        if (addr.isSiteLocalAddress) return true
        if (addr.isAnyLocalAddress) return true
        if (addr.isMulticastAddress) return true

        val raw = addr.address

        // IPv6 ULA 검사: fc00::/7 — 첫 바이트의 상위 7비트가 0b1111110
        if (raw.size == 16 && (raw[0].toInt() and 0xFE) == 0xFC) return true

        // IPv4-mapped IPv6 언래핑: ::ffff:x.x.x.x
        val mapped = extractMappedIpv4(raw)
        if (mapped != null && isInternal(mapped)) return true

        return false
    }

    /**
     * IPv4-mapped IPv6 주소(::ffff:x.x.x.x)에서 IPv4 부분을 추출한다.
     *
     * RFC 4291 §2.5.5 고정 포맷: 10바이트 0x00 + 2바이트 0xFF + 4바이트 IPv4.
     * 해당 형식이 아니면 null 반환.
     *
     * @param raw IPv6 주소 바이트 배열 (16바이트)
     * @return 추출된 IPv4 [InetAddress] 또는 null
     */
    @Suppress("MagicNumber", "ReturnCount") // 바이트 인덱스는 RFC 4291 §2.5.5 고정값 / 검증 단계별 early return 필수
    private fun extractMappedIpv4(raw: ByteArray): InetAddress? {
        if (raw.size != 16) return null
        val isZeroPrefix =
            raw[0] == 0.toByte() && raw[1] == 0.toByte() &&
                raw[2] == 0.toByte() && raw[3] == 0.toByte() &&
                raw[4] == 0.toByte() && raw[5] == 0.toByte() &&
                raw[6] == 0.toByte() && raw[7] == 0.toByte() &&
                raw[8] == 0.toByte() && raw[9] == 0.toByte()
        val isFFFF = raw[10] == 0xFF.toByte() && raw[11] == 0xFF.toByte()
        if (!isZeroPrefix || !isFFFF) return null
        val ipv4Bytes = raw.copyOfRange(12, 16)
        return runCatching { InetAddress.getByAddress(ipv4Bytes) }.getOrNull()
    }
}
