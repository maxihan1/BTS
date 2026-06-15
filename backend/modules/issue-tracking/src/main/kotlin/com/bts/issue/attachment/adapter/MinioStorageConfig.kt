// MinIO 오브젝트 스토리지 설정 — @ConfigurationProperties 바인딩 + MinioClient 빈 등록

package com.bts.issue.attachment.adapter

import com.bts.issue.attachment.MinioStorageException
import io.minio.MinioClient
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.util.StringUtils

/**
 * MinIO 오브젝트 스토리지 Spring 설정 클래스.
 *
 * [Properties] 를 통해 `bts.minio.*` 설정값을 바인딩하고,
 * [MinioClient] 빈을 등록한다.
 *
 * ## endpoint fail-fast
 *
 * endpoint 가 빈 문자열이면 기동 시점에 [IllegalStateException] 을 던져 환경 설정 오류를 즉시 감지한다.
 *
 * ## bucket 자동 보장 (NFR-6)
 *
 * [MinioStorageAdapter.ensureBucket] 을 ApplicationRunner 단계에서 호출해
 * 애플리케이션 기동 시 bucket 이 없으면 자동 생성한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MinioStorageConfig.Properties::class)
class MinioStorageConfig {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * MinIO 접속 설정 바인딩 데이터 클래스.
     *
     * 환경변수 우선순위 (BTS_* prefix, DEVELOPMENT.md §6).
     * - `BTS_MINIO_ENDPOINT` → endpoint
     * - `BTS_MINIO_ACCESS_KEY` → accessKey
     * - `BTS_MINIO_SECRET_KEY` → secretKey
     * - `BTS_MINIO_BUCKET` → bucket
     *
     * @param endpoint MinIO 서버 주소 (예: "http://localhost:9000").
     * @param accessKey MinIO 액세스 키.
     * @param secretKey MinIO 시크릿 키.
     * @param bucket 첨부파일을 저장할 bucket 이름.
     */
    @ConfigurationProperties(prefix = "bts.minio")
    data class Properties(
        val endpoint: String = "",
        val accessKey: String = "",
        val secretKey: String = "",
        val bucket: String = "bts-attachments",
    )

    /**
     * [MinioClient] Spring 빈.
     *
     * endpoint 가 비어 있으면 설정 오류로 간주해 기동을 중단한다.
     *
     * @param properties MinIO 접속 설정.
     * @return 초기화된 [MinioClient].
     * @throws IllegalStateException endpoint 가 빈 문자열인 경우.
     */
    @Bean
    fun minioClient(properties: Properties): MinioClient {
        check(StringUtils.hasText(properties.endpoint)) {
            "bts.minio.endpoint 가 설정되지 않았습니다. BTS_MINIO_ENDPOINT 환경변수를 확인하세요."
        }
        log.info("MinioClient 초기화 — endpoint={}", properties.endpoint)
        return MinioClient.builder()
            .endpoint(properties.endpoint)
            .credentials(properties.accessKey, properties.secretKey)
            .build()
    }

    /**
     * bucket 존재 여부를 확인하고 없으면 생성하는 초기화 작업.
     *
     * [MinioStorageAdapter] 가 의존 주입된 뒤 [MinioStorageAdapter.ensureBucket] 을 호출한다.
     * 이 빈은 기동 시점에 자동 실행된다.
     *
     * ## best-effort 기동 (회귀 방지)
     * MinIO 미가용 시에도 앱 기동을 막지 않는다. bucket 보장 실패는 warn 으로 로그하고 진행하며,
     * 실제 업로드 시점에 [MinioStorageException] 으로 드러난다. MinIO 를 사용하지 않는 다른 통합
     * 테스트/기능까지 MinIO 가용성에 묶어 부팅을 깨는 것을 방지한다(과결합 차단).
     *
     * @param adapter [MinioStorageAdapter] 인스턴스.
     * @return [org.springframework.boot.ApplicationRunner] — bucket 보장 로직 실행.
     */
    @Bean
    fun minioBucketEnsurer(adapter: MinioStorageAdapter): ApplicationRunner =
        ApplicationRunner {
            try {
                adapter.ensureBucket()
            } catch (e: MinioStorageException) {
                log.warn(
                    "MinIO bucket 보장 실패 — MinIO 미가용 가능. 앱 기동은 계속하며 업로드 시점에 재확인된다. cause={}",
                    e.message,
                )
            }
        }
}
