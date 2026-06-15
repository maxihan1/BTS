// ClamAV clamd 연동 설정 — bts.clamav.* 바인딩 + VirusScanPort 빈 등록

package com.bts.issue.attachment.adapter

import com.bts.issue.attachment.application.VirusScanPort
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.util.StringUtils

/**
 * ClamAV clamd 데몬 연동 Spring 설정 클래스.
 *
 * [Properties] 를 통해 `bts.clamav.*` 설정값을 바인딩하고,
 * [VirusScanPort] 빈([ClamdInstreamScanner])을 등록한다.
 *
 * ## host fail-fast (설정 누락 = 부팅 중단)
 *
 * `bts.clamav.host` 가 빈 문자열이면 기동 시점에 [IllegalStateException] 을 던진다.
 * 스캔 설정이 누락된 채 서비스가 기동되면 첨부 업로드 보안 게이트가 무력화된다.
 *
 * MinIO 의 best-effort bucket 보장 패턴(미가용 시 warn 후 기동 계속)과 의도적으로 다르다.
 * clamd 는 fail-closed 정책이므로 설정 누락 자체가 보안 갭이며 기동을 허용하지 않는다.
 *
 * ## 런타임 데몬 미가용
 *
 * 기동 후 데몬이 다운된 경우는 별개 경로다. [ClamdInstreamScanner] 가
 * [com.bts.issue.attachment.application.AttachmentScanUnavailableException] 을 던지고
 * ExceptionHandler 가 503 으로 응답한다(Task 4 처리).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ClamavConfig.Properties::class)
class ClamavConfig {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * ClamAV clamd 접속 설정 바인딩 데이터 클래스.
     *
     * 환경변수 우선순위 (BTS_* prefix, DEVELOPMENT.md §6).
     * - `BTS_CLAMAV_HOST` → host (필수, 미설정 시 부팅 fail-fast)
     * - `BTS_CLAMAV_PORT` → port (선택, 기본 3310)
     * - `BTS_CLAMAV_CONNECT_TIMEOUT_MS` → connectTimeoutMs
     * - `BTS_CLAMAV_READ_TIMEOUT_MS` → readTimeoutMs
     *
     * @param host clamd 데몬 호스트 주소 (예: "127.0.0.1" 또는 "clamav").
     * @param port clamd 데몬 포트 (기본 3310).
     * @param connectTimeoutMs 소켓 연결 타임아웃 밀리초 (기본 5000).
     * @param readTimeoutMs 소켓 읽기 타임아웃 밀리초 (기본 60000).
     */
    @ConfigurationProperties(prefix = "bts.clamav")
    data class Properties(
        val host: String = "",
        val port: Int = 3310,
        val connectTimeoutMs: Int = 5_000,
        val readTimeoutMs: Int = 60_000,
    )

    /**
     * [VirusScanPort] Spring 빈 — [ClamdInstreamScanner] 구현체를 등록한다.
     *
     * `host` 가 비어 있으면 설정 오류로 간주해 기동을 중단한다.
     *
     * @param properties ClamAV 접속 설정.
     * @return 초기화된 [ClamdInstreamScanner].
     * @throws IllegalStateException host 가 빈 문자열인 경우 (부팅 fail-fast).
     */
    @Bean
    fun virusScanPort(properties: Properties): VirusScanPort {
        check(StringUtils.hasText(properties.host)) {
            "bts.clamav.host 가 설정되지 않았습니다. BTS_CLAMAV_HOST 환경변수를 확인하세요."
        }
        log.info("ClamdInstreamScanner 초기화 — host={} port={}", properties.host, properties.port)
        return ClamdInstreamScanner(
            host = properties.host,
            port = properties.port,
            connectTimeoutMs = properties.connectTimeoutMs,
            readTimeoutMs = properties.readTimeoutMs,
        )
    }
}
