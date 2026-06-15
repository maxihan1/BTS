// ClamAV clamd 데몬에 INSTREAM 프로토콜로 바이러스 스캔을 요청하는 outbound 어댑터

package com.bts.issue.attachment.adapter

import com.bts.issue.attachment.application.AttachmentScanUnavailableException
import com.bts.issue.attachment.application.ScanVerdict
import com.bts.issue.attachment.application.VirusScanPort
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * [VirusScanPort] clamd INSTREAM 구현체.
 *
 * raw TCP 소켓으로 clamd 데몬과 직접 통신한다. 외부 라이브러리 의존성이 없다(java.net/java.io 표준).
 *
 * ## INSTREAM 프로토콜
 *
 * 1. `zINSTREAM\u0000` 명령(NUL 종결, 10 바이트)을 송신한다.
 * 2. 파일 내용을 [CHUNK_SIZE] 단위로 `<4바이트 big-endian 길이><데이터>` 프레임으로 송신한다.
 * 3. 길이 0([TERMINATOR], 4 바이트) 을 송신해 스트림 종료를 알린다.
 * 4. clamd 응답을 읽는다. 실 clamd 응답은 NUL(`\u0000`) 로 종결된다.
 *
 * ## 응답 정규화 (fail-closed)
 *
 * trailing NUL(`\u0000`) · 개행(`\n`, `\r`) · 공백을 제거한 뒤 비교한다.
 * - 정규화 결과 == `"stream: OK"` → [ScanVerdict.CLEAN]
 * - 정규화 결과가 `"FOUND"` 로 끝남 → [ScanVerdict.INFECTED]
 * - 그 외 전부(미상 응답 / 빈 응답 / `INSTREAM size limit exceeded` / IOException / 타임아웃)
 *   → [AttachmentScanUnavailableException] (fail-closed)
 *
 * ## 보안 주의
 *
 * 로그에 파일 내용이나 탐지된 바이러스 시그니처명 전체를 출력하지 않는다.
 * 진단 메시지는 일반화된 수준으로만 남긴다.
 *
 * @param host clamd 데몬 호스트 (예: "127.0.0.1").
 * @param port clamd 데몬 포트 (기본 3310).
 * @param connectTimeoutMs 소켓 연결 타임아웃 (밀리초).
 * @param readTimeoutMs 소켓 읽기 타임아웃 (밀리초).
 */
class ClamdInstreamScanner(
    private val host: String,
    private val port: Int,
    private val connectTimeoutMs: Int,
    private val readTimeoutMs: Int,
) : VirusScanPort {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 입력 스트림을 clamd 로 전송하고 스캔 판정을 반환한다.
     *
     * 소켓은 `use {}` 블록으로 확실히 닫는다(connect/read 타임아웃 각각 적용).
     *
     * @param input 스캔할 바이트 스트림. 호출자가 close 책임을 갖는다.
     * @return [ScanVerdict.CLEAN] 또는 [ScanVerdict.INFECTED].
     * @throws AttachmentScanUnavailableException 스캔 불가 시 (fail-closed).
     */
    @Suppress("TooGenericExceptionCaught")
    override fun scan(input: InputStream): ScanVerdict {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                socket.soTimeout = readTimeoutMs

                val out = socket.getOutputStream()

                // 1. zINSTREAM + NUL 명령 송신
                out.write(COMMAND_INSTREAM)
                out.flush()

                // 2. 청크 단위로 파일 내용 전송
                streamChunks(input, out)

                // 3. 길이 0 종결자 송신
                out.write(TERMINATOR)
                out.flush()

                // 4. 응답 읽기 (read 타임아웃은 soTimeout 적용됨)
                val response = socket.getInputStream().readBytes().toString(Charsets.UTF_8)

                return parseResponse(response)
            }
        } catch (ex: AttachmentScanUnavailableException) {
            throw ex
        } catch (ex: IOException) {
            log.warn("clamd 소켓 통신 오류 — host={} port={} cause={}", host, port, ex.message)
            throw AttachmentScanUnavailableException("clamd 통신 오류: ${ex.message}", ex)
        } catch (ex: Exception) {
            log.warn("clamd 스캔 중 예상치 못한 오류 — host={} port={}", host, port, ex)
            throw AttachmentScanUnavailableException("clamd 스캔 오류: ${ex.message}", ex)
        }
    }

    /**
     * 입력 스트림을 INSTREAM 청크(`<4바이트 big-endian 길이><데이터>`)로 [out] 에 전송한다.
     *
     * EOF(-1)까지 읽는다. read()가 0 을 반환해도(비표준 스트림) 루프를 끝내지 않고 계속 읽어,
     * 스트림이 잘린 채 전송돼 뒷부분이 미스캔(fail-open)되는 것을 막는다.
     */
    private fun streamChunks(
        input: InputStream,
        out: OutputStream,
    ) {
        val buffer = ByteArray(CHUNK_SIZE)
        val lenBuf = ByteBuffer.allocate(LEN_FIELD_SIZE).order(ByteOrder.BIG_ENDIAN)
        var bytesRead = input.read(buffer)
        while (bytesRead != -1) {
            if (bytesRead > 0) {
                lenBuf.clear()
                lenBuf.putInt(bytesRead)
                out.write(lenBuf.array())
                out.write(buffer, 0, bytesRead)
            }
            bytesRead = input.read(buffer)
        }
    }

    /**
     * clamd 응답 문자열을 파싱해 [ScanVerdict] 를 반환한다.
     *
     * trailing NUL(`\u0000`) / 개행(`\n`, `\r`) / 공백을 제거한 뒤 비교한다.
     *
     * - `"stream: OK"` → [ScanVerdict.CLEAN]
     * - `"FOUND"` 로 끝남 → [ScanVerdict.INFECTED]
     * - 그 외 / 빈 응답 → [AttachmentScanUnavailableException] (fail-closed)
     */
    private fun parseResponse(raw: String): ScanVerdict {
        // trailing NUL · 개행 · 공백 정규화 (실 clamd 응답은 NUL 종결)
        val normalized = raw.trimEnd { it == '\u0000' || it == '\n' || it == '\r' || it == ' ' }

        if (normalized.isEmpty()) {
            log.warn("clamd 빈 응답 수신 — fail-closed")
            throw AttachmentScanUnavailableException("clamd 빈 응답")
        }

        return when {
            normalized == RESPONSE_CLEAN -> ScanVerdict.CLEAN
            normalized.endsWith(RESPONSE_FOUND_SUFFIX) -> {
                // 탐지 시그니처명 자체는 로그에 출력하지 않음 (보안)
                log.info("clamd 악성코드 탐지")
                ScanVerdict.INFECTED
            }
            else -> {
                log.warn("clamd 미상 응답 수신 — fail-closed")
                throw AttachmentScanUnavailableException("clamd 미상 응답")
            }
        }
    }

    companion object {
        /** INSTREAM 명령 — `zINSTREAM` + NUL 종결 (10 바이트). */
        private val COMMAND_INSTREAM: ByteArray = "zINSTREAM".toByteArray(Charsets.UTF_8) + byteArrayOf(0)

        /** 청크 길이 필드 크기 — big-endian 4바이트 정수. */
        private const val LEN_FIELD_SIZE: Int = 4

        /** 스트림 종료 신호 — 길이 0 을 나타내는 4바이트 big-endian 0. */
        private val TERMINATOR: ByteArray = byteArrayOf(0, 0, 0, 0)

        /** 청크 크기 — 64 KiB. StreamMaxLength(110M) 이하로 충분히 작다. */
        private const val CHUNK_SIZE: Int = 65_536

        /** clean 판정 기준 — 정규화 후 이 값과 일치해야 CLEAN. */
        private const val RESPONSE_CLEAN: String = "stream: OK"

        /** 악성코드 탐지 판정 기준 — 정규화 후 이 suffix 로 끝나면 INFECTED. */
        private const val RESPONSE_FOUND_SUFFIX: String = "FOUND"
    }
}
