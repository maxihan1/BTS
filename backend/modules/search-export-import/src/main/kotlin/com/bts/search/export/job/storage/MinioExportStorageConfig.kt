// Export 결과용 MinIO 오브젝트 스토리지 설정 — Properties 바인딩 + MinioClient 빈 + 버킷 보장

package com.bts.search.export.job.storage

import io.minio.MinioClient
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.util.StringUtils

/**
 * Export 결과 파일 저장용 MinIO 오브젝트 스토리지 Spring 설정.
 *
 * [Properties] 를 통해 `bts.minio.*` 설정값을 바인딩하고 [MinioClient] 빈을 등록한다.
 * issue-tracking 의 `MinioStorageConfig` 와 동일한 프로퍼티를 사용하지만
 * 빈 이름과 버킷 기본값이 달라 별도 설정 클래스로 분리한다 (BC 격리 — NEVER-1).
 *
 * ## endpoint fail-fast
 *
 * endpoint 가 빈 문자열이면 기동 시점에 [IllegalStateException] 을 던져 환경 설정 오류를 즉시 감지한다.
 *
 * ## 버킷 자동 보장 (best-effort)
 *
 * [MinioExportStorageAdapter.ensureBucket] 을 ApplicationRunner 에서 호출한다.
 * MinIO 미가용 시에도 앱 기동을 막지 않는다. 버킷 보장 실패는 warn 으로 로그하고 진행한다.
 *
 * ## BC 격리 (ADR §D4)
 *
 * issue-tracking 버킷(`bts-attachments`)과 Export 버킷(`bts-exports`)은 물리적으로 분리된다.
 * 이 설정 클래스는 `exportMinioClient` 라는 별도 빈 이름을 사용해
 * issue-tracking `minioClient` 빈과 충돌하지 않는다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MinioExportStorageConfig.Properties::class)
class MinioExportStorageConfig {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Export MinIO 접속 설정 바인딩 데이터 클래스.
     *
     * 환경변수 우선순위 (`bts.minio.*` 공유 접속 프로퍼티 — issue-tracking 과 동일 MinIO 인스턴스).
     * - `BTS_MINIO_ENDPOINT` → endpoint
     * - `BTS_MINIO_ACCESS_KEY` → accessKey
     * - `BTS_MINIO_SECRET_KEY` → secretKey
     * - `BTS_MINIO_EXPORT_BUCKET` → export.bucket (기본값: `bts-exports`) — issue-tracking `BTS_MINIO_BUCKET` 과 독립
     *
     * 버킷은 `bts.minio.export.bucket` 하위 키로 분리하여 issue-tracking 의 `bts.minio.bucket`
     * (`BTS_MINIO_BUCKET`) 과 충돌하지 않는다 (ADR §D4 BC 분리 의도).
     *
     * @param endpoint MinIO 서버 주소 (예: `"http://localhost:9000"`).
     * @param accessKey MinIO 액세스 키.
     * @param secretKey MinIO 시크릿 키.
     * @param export Export 버킷 독립 설정. [ExportBucket] 참조.
     */
    @ConfigurationProperties(prefix = "bts.minio")
    data class Properties(
        val endpoint: String = "",
        val accessKey: String = "",
        val secretKey: String = "",
        val export: ExportBucket = ExportBucket(),
    ) {
        /**
         * Export 버킷 독립 설정.
         *
         * `bts.minio.export.bucket` 프로퍼티 / `BTS_MINIO_EXPORT_BUCKET` 환경변수로 제어된다.
         * issue-tracking 의 `bts.minio.bucket`(`BTS_MINIO_BUCKET`) 과 독립적이다.
         *
         * @param bucket Export 결과를 저장할 버킷 이름. 기본값: `"bts-exports"`.
         */
        data class ExportBucket(val bucket: String = "bts-exports")
    }

    /**
     * Export 전용 [MinioClient] Spring 빈.
     *
     * issue-tracking 의 `minioClient` 빈과 충돌 방지를 위해 `exportMinioClient` 이름 사용.
     * endpoint 가 비어 있으면 설정 오류로 간주해 기동을 중단한다.
     *
     * @param properties MinIO 접속 설정.
     * @return 초기화된 [MinioClient].
     * @throws IllegalStateException endpoint 가 빈 문자열인 경우.
     */
    @Bean("exportMinioClient")
    fun exportMinioClient(properties: Properties): MinioClient {
        check(StringUtils.hasText(properties.endpoint)) {
            "bts.minio.endpoint 가 설정되지 않았습니다. BTS_MINIO_ENDPOINT 환경변수를 확인하세요."
        }
        log.info("Export MinioClient 초기화 — endpoint={} bucket={}", properties.endpoint, properties.export.bucket)
        return MinioClient.builder()
            .endpoint(properties.endpoint)
            .credentials(properties.accessKey, properties.secretKey)
            .build()
    }

    /**
     * Export 버킷 존재 여부를 확인하고 없으면 생성하는 초기화 작업.
     *
     * [MinioExportStorageAdapter.ensureBucket] 을 ApplicationRunner 에서 호출한다.
     * MinIO 미가용 시에도 앱 기동을 막지 않는다 (best-effort, issue-tracking 동일 관례).
     *
     * @param adapter [MinioExportStorageAdapter] 인스턴스.
     * @return [ApplicationRunner] — 버킷 보장 로직 실행.
     */
    @Bean
    fun exportBucketEnsurer(adapter: MinioExportStorageAdapter): ApplicationRunner =
        ApplicationRunner {
            try {
                adapter.ensureBucket()
            } catch (e: MinioExportStorageException) {
                log.warn(
                    "MinIO export 버킷 보장 실패 — MinIO 미가용 가능. 앱 기동은 계속하며 업로드 시점에 재확인된다. cause={}",
                    e.message,
                )
            }
        }
}
