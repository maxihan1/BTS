// 아바타 전용 MinIO 배선 — @ConfigurationProperties(bts.minio) 재사용 + avatarMinioClient 빈 + 버킷 보장
package com.atlas.bts.identity.profile.avatar

import io.minio.MinioClient
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** identity-access 에 `bts.minio.endpoint` 미설정 시 폴백 endpoint(docker-compose dev 기준). */
private const val DEFAULT_MINIO_ENDPOINT = "http://localhost:9000"

/** 아바타 전용 기본 버킷 — 첨부 버킷과 분리. `bts.minio.avatar-bucket` 으로 override. */
private const val DEFAULT_AVATAR_BUCKET = "bts-avatars"

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
 * identity-access 는 `bts.minio.*` 설정이 없는 full-boot 통합 테스트가 다수다. endpoint 를 빈 값에
 * fail-fast 하면 그 테스트 부팅이 전부 깨지므로, endpoint 는 빈 값이면 로컬 기본값으로 폴백해 MinioClient
 * 빈이 항상 생성되게 한다(연결은 lazy — 생성 시점 네트워크 없음). 버킷 보장도 best-effort 로, MinIO 미가용
 * 시 warn 만 남기고 부팅을 진행한다(실제 실패는 업로드 시점에 드러난다).
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
     * @param properties MinIO 접속 설정.
     * @return 초기화된 [MinioClient](endpoint 가 빈 값이면 로컬 기본값 사용).
     */
    @Bean
    fun avatarMinioClient(properties: Properties): MinioClient {
        val endpoint = properties.endpoint.ifBlank { DEFAULT_MINIO_ENDPOINT }
        log.info("avatarMinioClient 초기화 — endpoint={}", endpoint)
        return MinioClient.builder()
            .endpoint(endpoint)
            .credentials(properties.accessKey, properties.secretKey)
            .build()
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
