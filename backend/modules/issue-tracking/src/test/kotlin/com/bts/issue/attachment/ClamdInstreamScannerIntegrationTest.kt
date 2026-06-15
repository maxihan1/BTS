// 실 ClamAV clamd 컨테이너를 대상으로 ClamdInstreamScanner 를 검증하는 통합 테스트

package com.bts.issue.attachment

import com.bts.issue.attachment.adapter.ClamdInstreamScanner
import com.bts.issue.attachment.application.ScanVerdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * 실 Testcontainers clamd 대상 [ClamdInstreamScanner] 통합 테스트.
 *
 * Task 1 단위 테스트(가짜 서버)가 가정한 프로토콜/응답형식이 실 clamd 와 일치하는지 백스톱한다.
 *
 * ## 컨테이너 기동
 * [AttachmentClamavTestcontainersBase.clamavContainer] 를 singleton 으로 재사용한다.
 * clamd 는 DB 로드로 인해 포트 listen 까지 수십 초가 걸릴 수 있다(로컬 게이트, CI 제외).
 * 단독 재실행 가능: `./gradlew :modules:issue-tracking:test --tests '*ClamdInstreamScannerIntegrationTest'`
 *
 * ## 검증 시나리오
 * - (a) clean 바이트 → [ScanVerdict.CLEAN]
 * - (b) EICAR 표준 테스트 문자열 → [ScanVerdict.INFECTED] (시그니처명 단언 없이 판정만)
 *
 * ## EICAR 테스트 파일
 * EICAR (European Institute for Computer Antivirus Research) 표준 테스트 파일은
 * 실제 악성코드가 아닌 안티바이러스 탐지 검증용 공개 문자열이다.
 * 소스에 raw 제어바이트 없이 평문 그대로 포함한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClamdInstreamScannerIntegrationTest : AttachmentClamavTestcontainersBase() {
    private val connectTimeoutMs = 5_000
    private val readTimeoutMs = 30_000

    /**
     * 스캐너 인스턴스를 생성한다.
     *
     * Testcontainers 가 동적으로 할당한 host/port 를 사용한다.
     */
    private fun scanner(): ClamdInstreamScanner =
        ClamdInstreamScanner(
            host = clamavContainer.host,
            port = clamavContainer.getMappedPort(3310),
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs,
        )

    /**
     * (a) clean 바이트 — [ScanVerdict.CLEAN] 판정.
     *
     * Given  "hello world" 평문 바이트 (악성코드 없음)
     * When   [ClamdInstreamScanner.scan] 호출
     * Then   [ScanVerdict.CLEAN] 반환
     */
    @Test
    fun `(a) clean 바이트는 CLEAN 판정`() {
        val input = "hello world".byteInputStream()
        val verdict = scanner().scan(input)
        assertEquals(ScanVerdict.CLEAN, verdict, "clean 바이트는 CLEAN 이어야 한다.")
    }

    /**
     * (b) EICAR 표준 테스트 문자열 — [ScanVerdict.INFECTED] 판정.
     *
     * Given  EICAR 공식 안티바이러스 테스트 파일 문자열
     * When   [ClamdInstreamScanner.scan] 호출
     * Then   [ScanVerdict.INFECTED] 반환 (시그니처명 단언 없음 — clamd 버전별 상이)
     *
     * EICAR 문자열은 소스에 평문 그대로 포함한다. raw 제어바이트 없음.
     */
    @Test
    fun `(b) EICAR 테스트 문자열은 INFECTED 판정`() {
        // EICAR 표준 안티바이러스 테스트 파일 (공개 도메인, 실제 악성코드 아님)
        val eicarString =
            "X5O!P%@AP[4\\PZX54(P^)7CC)7}" +
                "\$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!\$H+H*"
        val input = eicarString.byteInputStream(Charsets.UTF_8)
        val verdict = scanner().scan(input)
        assertEquals(ScanVerdict.INFECTED, verdict, "EICAR 테스트 문자열은 INFECTED 이어야 한다.")
    }
}
