// ClamdInstreamScanner 단위 테스트 — in-JVM 가짜 clamd ServerSocket 으로 응답을 제어한다.

package com.bts.issue.attachment.adapter

import com.bts.issue.attachment.application.AttachmentScanUnavailableException
import com.bts.issue.attachment.application.ScanVerdict
import com.bts.issue.attachment.application.VirusScanPort
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/**
 * [ClamdInstreamScanner] 단위 테스트.
 *
 * in-JVM [ServerSocket] 을 스레드풀에서 기동해 각 시나리오별 clamd 응답을 반환하고,
 * 스캐너가 INSTREAM 프로토콜을 올바르게 구현했는지 검증한다.
 *
 * ## 가짜 clamd 서버 동작
 * 실 clamd 는 `zINSTREAM` + NUL 수신 후 청크 스트림을 소비하고, 응답을 NUL(`\x00`) 로 종결한다.
 * 가짜 서버도 동일하게 NUL 종결 응답을 돌려줘, GREEN 구현이 trailing NUL 정규화를 반드시
 * 포함해야 테스트를 통과하도록 설계한다.
 *
 * ## 검증 목록
 * - `stream: OK\x00`(NUL 종결) → [ScanVerdict.CLEAN]
 * - `stream: OK\n`(개행 종결) → [ScanVerdict.CLEAN]
 * - `stream: OK` + 추가 공백 → [ScanVerdict.CLEAN]
 * - `stream: Eicar-Test-Signature FOUND\x00` → [ScanVerdict.INFECTED]
 * - `stream: Win.Test.EICAR_NDB-1 FOUND\n` → [ScanVerdict.INFECTED]
 * - `stream: ERROR` → [AttachmentScanUnavailableException] (fail-closed)
 * - 빈 응답 → [AttachmentScanUnavailableException] (fail-closed)
 * - `INSTREAM size limit exceeded` → [AttachmentScanUnavailableException] (fail-closed)
 * - 연결 거부(닫힌 포트) → [AttachmentScanUnavailableException]
 * - read 타임아웃(응답 없음) → [AttachmentScanUnavailableException]
 * - INSTREAM 프레이밍 단언: `zINSTREAM` + NUL + 청크 + `\x00\x00\x00\x00` 수신 확인
 */
class ClamdInstreamScannerTest : DescribeSpec({

    /**
     * 가짜 clamd 서버를 기동해 응답을 제어하는 헬퍼.
     *
     * @param response 가짜 서버가 돌려줄 바이트 배열 (NUL 종결 포함 가능).
     * @param consumeStream 청크 스트림을 끝까지 소비할지 여부 (기본 true).
     * @param readFramingInto 프레이밍 바이트를 캡처할 가변 리스트 (null 이면 캡처 안 함).
     * @param block 서버가 준비된 뒤 실행할 블록 (포트를 인자로 받음).
     */
    // in-JVM 가짜 clamd 소켓 — 청크 소비·캡처 분기가 복잡도를 높이므로 억제
    @Suppress("CyclomaticComplexMethod")
    fun withFakeClam(
        response: ByteArray,
        consumeStream: Boolean = true,
        readFramingInto: MutableList<Byte>? = null,
        block: (port: Int) -> Unit,
    ) {
        val executor = Executors.newSingleThreadExecutor()
        ServerSocket(0).use { server ->
            val port = server.localPort
            executor.submit {
                try {
                    server.accept().use { conn ->
                        val input = conn.getInputStream()
                        if (consumeStream) {
                            // `zINSTREAM` + NUL 헤더 소비 (10 바이트)
                            val headerBuf = ByteArray(10)
                            var headerRead = 0
                            while (headerRead < 10) {
                                val n = input.read(headerBuf, headerRead, 10 - headerRead)
                                if (n < 0) break
                                if (readFramingInto != null) {
                                    for (i in 0 until n) readFramingInto.add(headerBuf[headerRead + i])
                                }
                                headerRead += n
                            }
                            // 길이 0 종결자를 만날 때까지 청크 소비
                            while (true) {
                                val lenBytes = ByteArray(4)
                                var totalRead = 0
                                while (totalRead < 4) {
                                    val n = input.read(lenBytes, totalRead, 4 - totalRead)
                                    if (n < 0) break
                                    if (readFramingInto != null) {
                                        for (i in 0 until n) readFramingInto.add(lenBytes[totalRead + i])
                                    }
                                    totalRead += n
                                }
                                val chunkLen = ByteBuffer.wrap(lenBytes).order(ByteOrder.BIG_ENDIAN).int
                                if (chunkLen == 0) break
                                val chunk = ByteArray(chunkLen)
                                var chunkRead = 0
                                while (chunkRead < chunkLen) {
                                    val n = input.read(chunk, chunkRead, chunkLen - chunkRead)
                                    if (n < 0) break
                                    if (readFramingInto != null) {
                                        for (i in 0 until n) readFramingInto.add(chunk[chunkRead + i])
                                    }
                                    chunkRead += n
                                }
                            }
                        }
                        conn.getOutputStream().write(response)
                        conn.getOutputStream().flush()
                    }
                } catch (_: Exception) {
                    // 테스트 종료 시 소켓이 닫혀 발생하는 예외는 무시
                }
            }
            block(port)
        }
        executor.shutdown()
    }

    /**
     * NUL 바이트를 포함한 응답 바이트 배열 생성 헬퍼.
     */
    fun responseBytes(
        text: String,
        trailingNul: Boolean = false,
        trailingNewline: Boolean = false,
    ): ByteArray {
        val suffix =
            when {
                trailingNul -> byteArrayOf(0x00)
                trailingNewline -> byteArrayOf('\n'.code.toByte())
                else -> byteArrayOf()
            }
        return text.toByteArray(Charsets.UTF_8) + suffix
    }

    describe("CLEAN 판정") {
        it("stream: OK + trailing NUL → CLEAN (실 clamd 응답 형식)") {
            val response = responseBytes("stream: OK", trailingNul = true)
            withFakeClam(response) { port ->
                val scanner: VirusScanPort =
                    ClamdInstreamScanner(
                        host = "127.0.0.1",
                        port = port,
                        connectTimeoutMs = 2000,
                        readTimeoutMs = 2000,
                    )
                val payload = "안전한 파일 내용".toByteArray(Charsets.UTF_8)
                scanner.scan(payload.inputStream()) shouldBe ScanVerdict.CLEAN
            }
        }

        it("stream: OK + trailing 개행 → CLEAN") {
            val response = responseBytes("stream: OK", trailingNewline = true)
            withFakeClam(response) { port ->
                val scanner: VirusScanPort =
                    ClamdInstreamScanner(
                        host = "127.0.0.1",
                        port = port,
                        connectTimeoutMs = 2000,
                        readTimeoutMs = 2000,
                    )
                scanner.scan("clean".toByteArray().inputStream()) shouldBe ScanVerdict.CLEAN
            }
        }

        it("stream: OK + trailing 공백 → CLEAN") {
            val response = "stream: OK  ".toByteArray(Charsets.UTF_8)
            withFakeClam(response) { port ->
                val scanner: VirusScanPort =
                    ClamdInstreamScanner(
                        host = "127.0.0.1",
                        port = port,
                        connectTimeoutMs = 2000,
                        readTimeoutMs = 2000,
                    )
                scanner.scan("clean".toByteArray().inputStream()) shouldBe ScanVerdict.CLEAN
            }
        }
    }

    describe("INFECTED 판정") {
        it("stream: Eicar-Test-Signature FOUND + trailing NUL → INFECTED") {
            val response = responseBytes("stream: Eicar-Test-Signature FOUND", trailingNul = true)
            withFakeClam(response) { port ->
                val scanner: VirusScanPort =
                    ClamdInstreamScanner(
                        host = "127.0.0.1",
                        port = port,
                        connectTimeoutMs = 2000,
                        readTimeoutMs = 2000,
                    )
                scanner.scan("eicar".toByteArray().inputStream()) shouldBe ScanVerdict.INFECTED
            }
        }

        it("stream: Win.Test.EICAR_NDB-1 FOUND + trailing 개행 → INFECTED") {
            val response = responseBytes("stream: Win.Test.EICAR_NDB-1 FOUND", trailingNewline = true)
            withFakeClam(response) { port ->
                val scanner: VirusScanPort =
                    ClamdInstreamScanner(
                        host = "127.0.0.1",
                        port = port,
                        connectTimeoutMs = 2000,
                        readTimeoutMs = 2000,
                    )
                scanner.scan("malware".toByteArray().inputStream()) shouldBe ScanVerdict.INFECTED
            }
        }
    }

    describe("fail-closed — AttachmentScanUnavailableException") {
        it("stream: ERROR 응답 → 예외 (fail-closed)") {
            val response = responseBytes("stream: ERROR", trailingNul = true)
            withFakeClam(response) { port ->
                val scanner: VirusScanPort =
                    ClamdInstreamScanner(
                        host = "127.0.0.1",
                        port = port,
                        connectTimeoutMs = 2000,
                        readTimeoutMs = 2000,
                    )
                shouldThrow<AttachmentScanUnavailableException> {
                    scanner.scan("data".toByteArray().inputStream())
                }
            }
        }

        it("빈 응답 → 예외 (fail-closed)") {
            val response = byteArrayOf()
            withFakeClam(response) { port ->
                val scanner: VirusScanPort =
                    ClamdInstreamScanner(
                        host = "127.0.0.1",
                        port = port,
                        connectTimeoutMs = 2000,
                        readTimeoutMs = 2000,
                    )
                shouldThrow<AttachmentScanUnavailableException> {
                    scanner.scan("data".toByteArray().inputStream())
                }
            }
        }

        it("INSTREAM size limit exceeded → 예외 (fail-closed, StreamMaxLength 경계 가드)") {
            val response = responseBytes("INSTREAM size limit exceeded", trailingNul = true)
            withFakeClam(response) { port ->
                val scanner: VirusScanPort =
                    ClamdInstreamScanner(
                        host = "127.0.0.1",
                        port = port,
                        connectTimeoutMs = 2000,
                        readTimeoutMs = 2000,
                    )
                shouldThrow<AttachmentScanUnavailableException> {
                    scanner.scan("data".toByteArray().inputStream())
                }
            }
        }

        it("연결 거부(닫힌 포트) → 예외") {
            // 서버 없이 닫힌 포트로 접속 시도
            val closedPort = ServerSocket(0).use { it.localPort }
            val scanner: VirusScanPort =
                ClamdInstreamScanner(
                    host = "127.0.0.1",
                    port = closedPort,
                    connectTimeoutMs = 2000,
                    readTimeoutMs = 2000,
                )
            shouldThrow<AttachmentScanUnavailableException> {
                scanner.scan("data".toByteArray().inputStream())
            }
        }

        it("read 타임아웃 — 서버가 응답하지 않으면 예외") {
            // 스트림을 소비하지 않고 슬립만 하는 가짜 서버
            val executor = Executors.newSingleThreadExecutor()
            ServerSocket(0).use { server ->
                val port = server.localPort
                executor.submit {
                    try {
                        server.accept().use { _ ->
                            // 연결은 수락하지만 응답은 보내지 않은 채 슬립
                            Thread.sleep(10_000)
                        }
                    } catch (_: Exception) {
                        // 테스트 종료 시 소켓 닫힘 무시
                    }
                }
                // 타임아웃을 짧게 설정해 빠르게 실패 유도
                val scanner: VirusScanPort =
                    ClamdInstreamScanner(
                        host = "127.0.0.1",
                        port = port,
                        connectTimeoutMs = 2000,
                        readTimeoutMs = 300,
                    )
                shouldThrow<AttachmentScanUnavailableException> {
                    scanner.scan("data".toByteArray().inputStream())
                }
            }
            executor.shutdown()
        }
    }

    describe("INSTREAM 프레이밍 검증") {
        it("zINSTREAM + NUL 헤더 + 청크 + 0-길이 종결자를 올바르게 송신해야 한다") {
            val response = responseBytes("stream: OK", trailingNul = true)
            val capturedBytes = mutableListOf<Byte>()

            withFakeClam(response, readFramingInto = capturedBytes) { port ->
                val scanner: VirusScanPort =
                    ClamdInstreamScanner(
                        host = "127.0.0.1",
                        port = port,
                        connectTimeoutMs = 2000,
                        readTimeoutMs = 2000,
                    )
                val payload = "프레이밍 검증용 데이터".toByteArray(Charsets.UTF_8)
                scanner.scan(payload.inputStream())
            }

            val captured = capturedBytes.toByteArray()
            val payloadBytes = "프레이밍 검증용 데이터".toByteArray(Charsets.UTF_8)
            // 헤더(10) + 길이필드(4) + 페이로드 + 종결자(4)
            captured.size shouldBe (10 + 4 + payloadBytes.size + 4)

            // 헤더: "zINSTREAM" + NUL (10 바이트)
            val expectedHeader = "zINSTREAM".toByteArray(Charsets.UTF_8) + byteArrayOf(0)
            val header = captured.sliceArray(0 until 10)
            header shouldBe expectedHeader

            // 첫 청크 길이 필드 (big-endian 4바이트)
            val lenField = captured.sliceArray(10 until 14)
            val chunkLen = ByteBuffer.wrap(lenField).order(ByteOrder.BIG_ENDIAN).int
            chunkLen shouldBe payloadBytes.size

            // 종결자: 0 길이 4바이트 (마지막 4바이트)
            val terminator = captured.sliceArray(captured.size - 4 until captured.size)
            terminator shouldBe byteArrayOf(0, 0, 0, 0)
        }
    }

    describe("AttachmentScanUnavailableException 타입 검증") {
        it("예외는 RuntimeException 을 직접 상속해야 한다 (ResponseStatusException 상속 금지)") {
            val ex = AttachmentScanUnavailableException("테스트")
            ex.shouldBeInstanceOf<RuntimeException>()
            // ResponseStatusException 이 아닌지 확인 — Spring 예외가 아니어야 함
            ex::class.java.superclass.name shouldBe "java.lang.RuntimeException"
        }
    }
})
