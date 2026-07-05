// 아바타 전용 MinIO 배선 — @ConfigurationProperties(bts.minio) 재사용 + avatarMinioClient 빈 + 버킷 보장
package com.atlas.bts.identity.profile.avatar

import io.minio.MinioClient
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

/** identity-access 에 `bts.minio.endpoint` 미설정 시 폴백 endpoint(docker-compose dev 기준). */
private const val DEFAULT_MINIO_ENDPOINT = "http://localhost:9000"

/** 아바타 전용 기본 버킷 — 첨부 버킷과 분리. `bts.minio.avatar-bucket` 으로 override. */
private const val DEFAULT_AVATAR_BUCKET = "bts-avatars"

/**
 * `bts.minio.access-key`/`secret-key` 미설정 시 폴백 dev 자격증명(issue-tracking `application-test.yml`
 * 의 `minioadmin` 미러). MinIO SDK 는 빈 자격증명에 생성 시점 예외를 던지므로 빈 생성 성공을 위한 값이다.
 * prod 는 `BTS_MINIO_ACCESS_KEY`/`BTS_MINIO_SECRET_KEY` 로 실제 값을 주입하며 이 값을 쓰지 않는다.
 */
private const val DEFAULT_DEV_CREDENTIAL = "minioadmin"

/**
 * 아바타 전용 MinIO Spring 설정.
 *
 * issue-tracking `MinioStorageConfig` 를 참조 복제하되 **모듈 격리**를 위해 identity-access 자체 배선한다
 * (타 모듈 import 없음). 접속 설정(`bts.minio.*`)은 재사용하되 **버킷은 아바타 전용**([Properties.avatarBucket])
 * 으로 분리해 첨부 버킷과 섞이지 않게 한다.
 *
 * ## 빈 이름 분리
 * issue-tracking 의 `minioClient`/`minioBucketEnsurer` 와 잠재 충돌을 피하려 [avatarMinioClient],
 * [avatarMinioBucketEnsurer] 로 구분 명명한다.
 *
 * ## 부팅 안정성 (회귀 방지)
 * identity-access 는 `bts.minio.*` 설정이 없는 full-boot 통합 테스트가 다수이며, 그중 상당수가
 * `@ActiveProfiles("prod")` 로 부팅한다(prod randomport boot recipe). MinIO SDK 는 endpoint 뿐 아니라
 * **빈 자격증명에도 생성 시점 예외**(`AccessKey and SecretKey must not be empty`)를 던지므로, endpoint 와
 * 자격증명이 비어 있으면 dev 기본값([DEFAULT_MINIO_ENDPOINT]/[DEFAULT_DEV_CREDENTIAL])으로 폴백해
 * [avatarMinioClient] 빈이 항상 생성되게 한다(빌더는 생성 시 네트워크 연결을 하지 않아 무해).
 * 버킷 보장도 best-effort 로, MinIO 미가용 시 warn 만 남기고 부팅을 진행한다.
 *
 * ## prod 안전장치
 * fail-fast 로 던질 수 없으므로(위 prod-프로파일 부팅 테스트가 전부 깨진다), prod 프로파일에서 폴백이
 * 발동하면 **경고 로그**를 남긴다 — 기본값이 조용히 쓰이지 않게 한다. 실제 prod 는 반드시
 * `BTS_MINIO_ENDPOINT`/`BTS_MINIO_ACCESS_KEY`/`BTS_MINIO_SECRET_KEY` 를 주입해 이 경로를 타지 않는다.
 * (issue-tracking 은 boot 테스트가 `test` 프로파일 + `minioadmin` yml 을 써서 이 문제를 피한다 — 같은 의도.)
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MinioAvatarStorageConfig.Properties::class)
class MinioAvatarStorageConfig {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * MinIO 접속 + 아바타 버킷 설정 바인딩.
     *
     * @param endpoint MinIO 서버 주소(빈 값이면 로컬 기본값 폴백). `BTS_MINIO_ENDPOINT`.
     * @param accessKey MinIO 액세스 키. `BTS_MINIO_ACCESS_KEY`.
     * @param secretKey MinIO 시크릿 키. `BTS_MINIO_SECRET_KEY`.
     * @param avatarBucket 아바타 전용 버킷 이름. `BTS_MINIO_AVATAR_BUCKET`.
     */
    @ConfigurationProperties(prefix = "bts.minio")
    data class Properties(
        val endpoint: String = "",
        val accessKey: String = "",
        val secretKey: String = "",
        val avatarBucket: String = DEFAULT_AVATAR_BUCKET,
    )

    /**
     * 아바타 전용 [MinioClient] 빈.
     *
     * endpoint/자격증명이 빈 값이면 dev 기본값으로 폴백해 빈 생성이 항상 성공하게 한다(회귀 방지).
     * prod 프로파일에서 폴백이 발동하면 경고 로그를 남긴다(자격증명·비밀값은 로그에 남기지 않는다).
     *
     * @param properties MinIO 접속 설정.
     * @param environment prod 프로파일 감지용(폴백 경고 판정).
     * @return 초기화된 [MinioClient].
     */
    @Bean
    fun avatarMinioClient(
        properties: Properties,
        environment: Environment,
    ): MinioClient {
        val endpoint = properties.endpoint.ifBlank { DEFAULT_MINIO_ENDPOINT }
        val accessKey = properties.accessKey.ifBlank { DEFAULT_DEV_CREDENTIAL }
        val secretKey = properties.secretKey.ifBlank { DEFAULT_DEV_CREDENTIAL }
        warnIfProdFallback(properties, environment)
        log.info("avatarMinioClient 초기화 — endpoint={}", endpoint)
        return MinioClient.builder()
            .endpoint(endpoint)
            .credentials(accessKey, secretKey)
            .build()
    }

    /** prod 프로파일에서 endpoint/자격증명이 비어 dev 기본값 폴백이 발동하면 경고한다(비밀값 미기록). */
    private fun warnIfProdFallback(
        properties: Properties,
        environment: Environment,
    ) {
        val usingFallback =
            properties.endpoint.isBlank() ||
                properties.accessKey.isBlank() ||
                properties.secretKey.isBlank()
        if (usingFallback && environment.activeProfiles.contains("prod")) {
            log.warn(
                "bts.minio endpoint/자격증명이 prod 에서 비어 dev 기본값으로 폴백합니다. " +
                    "실제 배포는 BTS_MINIO_ENDPOINT/BTS_MINIO_ACCESS_KEY/BTS_MINIO_SECRET_KEY 를 반드시 설정하세요.",
            )
        }
    }

    /**
     * 기동 시 아바타 버킷이 없으면 생성하는 초기화 작업(idempotent).
     *
     * best-effort — MinIO 미가용 시 [AvatarStorageException] 을 warn 으로 흡수하고 부팅을 진행한다.
     *
     * @param adapter 아바타 저장 어댑터.
     * @return 버킷 보장을 수행하는 [ApplicationRunner].
     */
    @Bean
    fun avatarMinioBucketEnsurer(adapter: MinioAvatarStorageAdapter): ApplicationRunner =
        ApplicationRunner {
            try {
                adapter.ensureBucket()
            } catch (e: AvatarStorageException) {
                log.warn("아바타 버킷 보장 실패 — MinIO 미가용 가능. 부팅은 계속하며 업로드 시점에 재확인. cause={}", e.message)
            }
        }
}
