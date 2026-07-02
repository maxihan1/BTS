// Import 원본/에러로그용 MinIO 오브젝트 스토리지 설정 — Properties 바인딩 + MinioClient 빈 + 버킷 보장

package com.bts.search.imports.job.storage

import io.minio.MinioClient
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.util.StringUtils

/**
 * Import 원본 파일 및 실패행 에러로그 저장용 MinIO 오브젝트 스토리지 Spring 설정.
 *
 * [Properties] 를 통해 `bts.minio.*` 설정값을 바인딩하고 [MinioClient] 빈을 등록한다.
 * issue-tracking 의 `MinioStorageConfig`, `export` BC 의 `MinioExportStorageConfig` 와 동일한
 * 접속 프로퍼티(`bts.minio.endpoint` 등)를 재사용하지만, 빈 이름과 버킷은 별도로 분리한다 (BC 격리 — NEVER-1).
 *
 * ## endpoint fail-fast
 *
 * endpoint 가 빈 문자열이면 기동 시점에 [IllegalStateException] 을 던져 환경 설정 오류를 즉시 감지한다.
 *
 * ## 버킷 자동 보장 (best-effort)
 *
 * [MinioImportStorageAdapter.ensureBucket] 을 ApplicationRunner 에서 호출한다.
 * MinIO 미가용 시에도 앱 기동을 막지 않는다. 버킷 보장 실패는 warn 으로 로그하고 진행한다.
 *
 * 이 catch 는 [MinioImportStorageAdapter.ensureBucket] 이 던지는 [MinioImportStorageException] 만
 * 좁게 잡는다(제네릭 `Exception` catch 아님). 인증/권한 예외는 이 타입에 해당하지 않으므로 여기서
 * 삼켜지지 않고 그대로 전파된다 — best-effort catch 가 권한 예외까지 가려 조용히 삼키고
 * non-prod 에서 마스킹된 채 prod 로 누출되는 함정을 회피한다.
 *
 * ## BC 격리
 *
 * issue-tracking 버킷(`bts-attachments`), Export 버킷(`bts-exports`), Import 버킷(`bts-imports`)은
 * 물리적으로 분리된다. 이 설정 클래스는 `importMinioClient` 라는 별도 빈 이름을 사용해
 * issue-tracking `minioClient`, export `exportMinioClient` 빈과 충돌하지 않는다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MinioImportStorageConfig.Properties::class)
class MinioImportStorageConfig {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Import MinIO 접속 설정 바인딩 데이터 클래스.
     *
     * 환경변수 우선순위 (`bts.minio.*` 공유 접속 프로퍼티 — issue-tracking/export 와 동일 MinIO 인스턴스).
     * - `BTS_MINIO_ENDPOINT` → endpoint
     * - `BTS_MINIO_ACCESS_KEY` → accessKey
     * - `BTS_MINIO_SECRET_KEY` → secretKey
     * - `BTS_MINIO_IMPORT_BUCKET` → importBucket (기본값: `bts-imports`) — issue-tracking
     *   `BTS_MINIO_BUCKET`, export `BTS_MINIO_EXPORT_BUCKET` 과 독립
     *
     * @param endpoint MinIO 서버 주소 (예: `"http://localhost:9000"`).
     * @param accessKey MinIO 액세스 키.
     * @param secretKey MinIO 시크릿 키.
     * @param importBucket Import 원본/에러로그를 저장할 버킷 이름. 기본값: `"bts-imports"`.
     */
    @ConfigurationProperties(prefix = "bts.minio")
    data class Properties(
        val endpoint: String = "",
        val accessKey: String = "",
        val secretKey: String = "",
        val importBucket: String = "bts-imports",
    )

    /**
     * Import 전용 [MinioClient] Spring 빈.
     *
     * issue-tracking 의 `minioClient`, export 의 `exportMinioClient` 빈과 충돌 방지를 위해
     * `importMinioClient` 이름을 사용한다 (여러 BC 가 같은 메서드명으로 `@Bean` 등록 시
     * 빈 이름이 충돌하는 함정 회피 — shared-util 선례 동일 관례).
     * endpoint 가 비어 있으면 설정 오류로 간주해 기동을 중단한다.
     *
     * @param properties MinIO 접속 설정.
     * @return 초기화된 [MinioClient].
     * @throws IllegalStateException endpoint 가 빈 문자열인 경우.
     */
    @Bean("importMinioClient")
    fun importMinioClient(properties: Properties): MinioClient {
        check(StringUtils.hasText(properties.endpoint)) {
            "bts.minio.endpoint 가 설정되지 않았습니다. BTS_MINIO_ENDPOINT 환경변수를 확인하세요."
        }
        log.info("Import MinioClient 초기화 — endpoint={} bucket={}", properties.endpoint, properties.importBucket)
        return MinioClient.builder()
            .endpoint(properties.endpoint)
            .credentials(properties.accessKey, properties.secretKey)
            .build()
    }

    /**
     * Import 버킷 존재 여부를 확인하고 없으면 생성하는 초기화 작업.
     *
     * [MinioImportStorageAdapter.ensureBucket] 을 ApplicationRunner 에서 호출한다.
     * MinIO 미가용 시에도 앱 기동을 막지 않는다 (best-effort, issue-tracking/export 동일 관례).
     *
     * @param adapter [MinioImportStorageAdapter] 인스턴스.
     * @return [ApplicationRunner] — 버킷 보장 로직 실행.
     */
    @Bean
    fun importBucketEnsurer(adapter: MinioImportStorageAdapter): ApplicationRunner =
        ApplicationRunner {
            try {
                adapter.ensureBucket()
            } catch (e: MinioImportStorageException) {
                log.warn(
                    "MinIO import 버킷 보장 실패 — MinIO 미가용 가능. 앱 기동은 계속하며 업로드 시점에 재확인된다. cause={}",
                    e.message,
                )
            }
        }
}
