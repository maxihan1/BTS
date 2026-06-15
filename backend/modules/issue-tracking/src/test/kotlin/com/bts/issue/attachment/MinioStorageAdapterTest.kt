// MinioStorageAdapter 통합 테스트 — Testcontainers MinIO 를 사용해 put/get/remove 를 검증한다.

package com.bts.issue.attachment

import com.bts.issue.attachment.adapter.MinioStorageAdapter
import com.bts.issue.attachment.adapter.MinioStorageConfig
import io.minio.MinioClient
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * [MinioStorageAdapter] Testcontainers 통합 테스트.
 *
 * Testcontainers [MinIOContainer] 를 singleton 패턴으로 기동하고,
 * put/get/remove 3개 오퍼레이션과 bucket 자동 보장 동작을 검증한다.
 *
 * ## 검증 항목
 * - put + get: 저장한 바이트와 읽은 바이트가 동일해야 한다.
 * - remove 후 get: [MinioStorageException] 이 발생해야 한다.
 * - bucket 자동 보장: [MinioStorageConfig] 초기화 시 bucket 이 존재하지 않으면 생성된다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MinioStorageAdapterTest : AttachmentMinioTestcontainersBase() {
    private lateinit var adapter: MinioStorageAdapter
    private val testBucket = "bts-attachments-test"

    /**
     * MinioClient + [MinioStorageAdapter] 를 초기화한다.
     * singleton 컨테이너가 이미 기동된 상태에서 동적 endpoint/credentials 를 주입한다.
     */
    @BeforeAll
    fun setup() {
        val client =
            MinioClient.builder()
                .endpoint(minioContainer.s3URL)
                .credentials(minioContainer.userName, minioContainer.password)
                .build()

        val config =
            MinioStorageConfig.Properties(
                endpoint = minioContainer.s3URL,
                accessKey = minioContainer.userName,
                secretKey = minioContainer.password,
                bucket = testBucket,
            )

        adapter = MinioStorageAdapter(client, config)
        adapter.ensureBucket()
    }

    @Test
    fun `put 한 뒤 get 하면 원본 바이트와 동일해야 한다`() {
        val key = "test/hello.txt"
        val content = "안녕하세요 MinIO".toByteArray(Charsets.UTF_8)

        content.inputStream().use { stream ->
            adapter.put(key, stream, content.size.toLong(), "text/plain")
        }

        val result = adapter.get(key).use { it.readBytes() }
        assertThat(result).isEqualTo(content)
    }

    @Test
    fun `remove 후 get 하면 MinioStorageException 이 발생해야 한다`() {
        val key = "test/to-be-removed.txt"
        val content = "삭제 예정".toByteArray(Charsets.UTF_8)

        content.inputStream().use { stream ->
            adapter.put(key, stream, content.size.toLong(), "text/plain")
        }

        adapter.remove(key)

        assertThatThrownBy { adapter.get(key) }
            .isInstanceOf(MinioStorageException::class.java)
    }

    @Test
    fun `bucket 이 없으면 ensureBucket 이 자동으로 생성해야 한다`() {
        val newBucket = "bts-auto-created-test"
        val client =
            MinioClient.builder()
                .endpoint(minioContainer.s3URL)
                .credentials(minioContainer.userName, minioContainer.password)
                .build()

        val config =
            MinioStorageConfig.Properties(
                endpoint = minioContainer.s3URL,
                accessKey = minioContainer.userName,
                secretKey = minioContainer.password,
                bucket = newBucket,
            )

        val newAdapter = MinioStorageAdapter(client, config)
        // bucket 미생성 상태에서 ensureBucket 호출 — 예외 없이 통과해야 한다.
        newAdapter.ensureBucket()

        // bucket 에 실제로 데이터를 put 해 동작을 검증한다.
        val content = "버킷 자동 생성 테스트".toByteArray(Charsets.UTF_8)
        content.inputStream().use { stream ->
            newAdapter.put("probe.txt", stream, content.size.toLong(), "text/plain")
        }
        val result = newAdapter.get("probe.txt").use { it.readBytes() }
        assertThat(result).isEqualTo(content)
    }
}
