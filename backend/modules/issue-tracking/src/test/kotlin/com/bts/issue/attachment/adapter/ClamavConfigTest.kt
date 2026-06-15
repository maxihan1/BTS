// ClamavConfig 설정 빈 단위 테스트 — host 빈값 부팅 fail-fast 및 VirusScanPort 빈 생성 검증

package com.bts.issue.attachment.adapter

import com.bts.issue.attachment.application.VirusScanPort
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * [ClamavConfig] 단위 테스트.
 *
 * Spring 컨텍스트 없이 [ClamavConfig] 를 직접 조립해 설정 검증 로직을 테스트한다.
 *
 * ## 검증 목록
 * - `host` 빈 문자열 → `virusScanPort()` 빈 생성 시 [IllegalStateException] (부팅 fail-fast)
 * - 정상 host/port → [VirusScanPort] 빈이 [ClamdInstreamScanner] 인스턴스로 생성됨
 */
class ClamavConfigTest : DescribeSpec({

    describe("host 빈 문자열 — 부팅 fail-fast") {
        it("host 가 빈 문자열이면 IllegalStateException 을 던진다") {
            val config = ClamavConfig()
            val props = ClamavConfig.Properties(host = "")

            shouldThrow<IllegalStateException> {
                config.virusScanPort(props)
            }
        }

        it("host 가 공백 문자열이면 IllegalStateException 을 던진다") {
            val config = ClamavConfig()
            val props = ClamavConfig.Properties(host = "   ")

            shouldThrow<IllegalStateException> {
                config.virusScanPort(props)
            }
        }
    }

    describe("정상 host/port — VirusScanPort 빈 생성") {
        it("유효한 host/port 설정 시 ClamdInstreamScanner 인스턴스가 반환된다") {
            val config = ClamavConfig()
            val props =
                ClamavConfig.Properties(
                    host = "127.0.0.1",
                    port = 3310,
                    connectTimeoutMs = 5000,
                    readTimeoutMs = 60_000,
                )

            val port: VirusScanPort = config.virusScanPort(props)

            port.shouldBeInstanceOf<ClamdInstreamScanner>()
        }

        it("기본 포트(3310) 및 기본 타임아웃 값을 사용해도 빈이 정상 생성된다") {
            val config = ClamavConfig()
            val props = ClamavConfig.Properties(host = "clamav.internal")

            val port: VirusScanPort = config.virusScanPort(props)

            port.shouldBeInstanceOf<ClamdInstreamScanner>()
        }
    }
})
