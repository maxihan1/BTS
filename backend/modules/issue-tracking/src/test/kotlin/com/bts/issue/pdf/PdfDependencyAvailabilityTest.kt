// openhtmltopdf 의존성 및 한글 폰트 리소스 클래스패스 가용성 검증 테스트 (FR-IS-08 Task 1)
package com.bts.issue.pdf

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldNotBe

/**
 * Task 1 — 의존성 + 폰트 번들 가용성 가드 테스트.
 *
 * ## 목적
 * - `openhtmltopdf-pdfbox` 의존성이 클래스패스에 존재하는지 컴파일/실행 시점에 확인한다.
 * - 번들된 한글 폰트(`NanumGothic-Regular.ttf`)가 런타임 리소스로 접근 가능한지 확인한다.
 *
 * ## 실패 기준 (RED)
 * - `openhtmltopdf-pdfbox` 의존성 미존재 시 컴파일 실패.
 * - 폰트 리소스가 classpath 에 없으면 `shouldNotBe null` 단언 실패.
 */
class PdfDependencyAvailabilityTest : DescribeSpec({

    describe("openhtmltopdf 의존성 가용성") {

        it("PdfRendererBuilder 인스턴스화가 클래스패스에서 성공한다") {
            // openhtmltopdf-pdfbox:1.0.10 이 의존성에 없으면 컴파일 시점에 이 줄에서 실패한다.
            val builder = com.openhtmltopdf.pdfboxout.PdfRendererBuilder()
            builder shouldNotBe null
        }
    }

    describe("한글 폰트 리소스 가용성") {

        /** 번들 폰트 파일 경로 — src/main/resources/fonts/ 에 위치. */
        val fontResourcePath = "/fonts/NanumGothic-Regular.ttf"

        it("NanumGothic-Regular.ttf 가 클래스패스 리소스로 존재한다") {
            // classpath 에 폰트 파일이 없으면 getResourceAsStream 이 null 을 반환한다.
            val stream = javaClass.getResourceAsStream(fontResourcePath)
            stream shouldNotBe null
            stream?.close()
        }
    }
})
