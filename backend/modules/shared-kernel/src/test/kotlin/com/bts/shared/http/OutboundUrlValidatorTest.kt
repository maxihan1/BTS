// OutboundUrlValidator 단위 테스트 — SSRF 내부망 차단, 스킴 검사, IPv6 ULA/매핑 IP 차단 검증

package com.bts.shared.http

import io.kotest.core.spec.style.DescribeSpec
import org.assertj.core.api.Assertions.assertThat

/**
 * [OutboundUrlValidator] 단위 테스트.
 *
 * ### 검증 항목
 * - S1. 공인 IP 리터럴 → Allowed
 * - S2. loopback(127.0.0.1) → Blocked
 * - S3. link-local/metadata(169.254.169.254) → Blocked
 * - S4. 사설망(10.x, 192.168.x, 172.16.x) → Blocked
 * - S5. 비-http 스킴(ftp, file) → Blocked/Malformed
 * - S6. blank URL → Malformed
 * - S7. 형식 불량 URL → Malformed
 * - S3(리뷰 보강). IPv6 ULA(fc00::1) → Blocked
 * - S3(리뷰 보강). IPv6 loopback(::1) → Blocked
 * - S3(리뷰 보강). IPv4-mapped IPv6(::ffff:127.0.0.1) → Blocked
 * - S3(리뷰 보강). 정수형 IP(2130706433 = 127.0.0.1) → Blocked
 */
class OutboundUrlValidatorTest : DescribeSpec({

    val validator = OutboundUrlValidator()

    describe("허용 케이스") {
        it("S1 공인 IP 리터럴(93.184.216.34)은 Allowed") {
            val result = validator.check("http://93.184.216.34/")
            assertThat(result).isInstanceOf(UrlCheck.Allowed::class.java)
        }

        it("https 스킴 공인 호스트도 Allowed") {
            val result = validator.check("https://example.com/hook")
            assertThat(result).isInstanceOf(UrlCheck.Allowed::class.java)
        }
    }

    describe("loopback 차단") {
        it("S2 127.0.0.1은 Blocked") {
            val result = validator.check("http://127.0.0.1/x")
            assertThat(result).isInstanceOf(UrlCheck.Blocked::class.java)
        }

        it("S3(리뷰) IPv6 loopback [::1]은 Blocked") {
            val result = validator.check("http://[::1]/")
            assertThat(result).isInstanceOf(UrlCheck.Blocked::class.java)
        }
    }

    describe("link-local/metadata 차단") {
        it("S3 169.254.169.254 메타데이터 서버는 Blocked") {
            val result = validator.check("http://169.254.169.254/latest/meta-data")
            assertThat(result).isInstanceOf(UrlCheck.Blocked::class.java)
        }
    }

    describe("사설망 차단") {
        it("S4 10.0.0.5는 Blocked") {
            val result = validator.check("http://10.0.0.5/")
            assertThat(result).isInstanceOf(UrlCheck.Blocked::class.java)
        }

        it("S4 192.168.1.1은 Blocked") {
            val result = validator.check("http://192.168.1.1/")
            assertThat(result).isInstanceOf(UrlCheck.Blocked::class.java)
        }

        it("S4 172.16.0.1은 Blocked") {
            val result = validator.check("http://172.16.0.1/")
            assertThat(result).isInstanceOf(UrlCheck.Blocked::class.java)
        }
    }

    describe("IPv6 ULA / IPv4-mapped IPv6 차단 (리뷰 보강 S3)") {
        it("IPv6 ULA fc00::1은 Blocked") {
            val result = validator.check("http://[fc00::1]/")
            assertThat(result).isInstanceOf(UrlCheck.Blocked::class.java)
        }

        it("IPv4-mapped IPv6 [::ffff:127.0.0.1]은 Blocked") {
            val result = validator.check("http://[::ffff:127.0.0.1]/")
            assertThat(result).isInstanceOf(UrlCheck.Blocked::class.java)
        }

        it("정수형 IP 2130706433 (= 127.0.0.1)은 Blocked") {
            val result = validator.check("http://2130706433/")
            assertThat(result).isInstanceOf(UrlCheck.Blocked::class.java)
        }
    }

    describe("비-http 스킴 차단") {
        it("S5 ftp://host/는 Blocked 또는 Malformed") {
            val result = validator.check("ftp://host/")
            assertThat(result).isNotInstanceOf(UrlCheck.Allowed::class.java)
        }

        it("S5 file:///etc/passwd는 Blocked 또는 Malformed") {
            val result = validator.check("file:///etc/passwd")
            assertThat(result).isNotInstanceOf(UrlCheck.Allowed::class.java)
        }
    }

    describe("형식 불량 URL") {
        it("S6 blank URL은 Malformed") {
            val result = validator.check("")
            assertThat(result).isInstanceOf(UrlCheck.Malformed::class.java)
        }

        it("S7 'not a url'은 Malformed") {
            val result = validator.check("not a url")
            assertThat(result).isInstanceOf(UrlCheck.Malformed::class.java)
        }
    }
})
