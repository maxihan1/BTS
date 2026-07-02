// MinioImportStorageAdapter 통합 테스트 — Testcontainers MinIO 로 put/get/delete 를 검증한다.

package com.bts.search.imports.job.storage

import io.minio.MinioClient
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.MinIOContainer

/**
 * [MinioImportStorageAdapter] Testcontainers 통합 테스트.
 *
 * Testcontainers [MinIOContainer] 를 JVM-singleton 패턴으로 기동하고,
 * put/get/delete 오퍼레이션을 검증한다.
 *
 * issue-tracking `MinioStorageAdapterTest`, `export` BC `ExportJobEndToEndIntegrationTest` 와
 * 동일한 JVM-singleton 컨테이너 패턴을 사용한다 (`@Container` 대신 companion object 의
 * `.apply { start() }` 로 JVM 라이프사이클에 바인딩 — Ryuk 이 JVM 종료 시 자동 정리).
 * 이미지 버전은 같은 모듈(search-export-import)의 `ExportJobEndToEndIntegrationTest` 가 사용하는
 * pinned 버전을 그대로 재사용한다.
 *
 * ## 검증 항목
 * - put + get: 저장한 바이트와 읽은 바이트가 동일해야 한다.
 * - delete 후 get: [MinioImportStorageException] 이 발생해야 한다.
 * - 존재하지 않는 key get: [MinioImportStorageException] 이 발생해야 한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MinioImportStorageAdapterTest {
    companion object {
        /**
         * JVM 단위 singleton MinIO container.
         * `.apply { start() }` 로 JVM 시작 시점에 한 번만 기동되며, Ryuk 이 JVM 종료 시 자동 정리.
         */
        @JvmStatic
        val minioContainer: MinIOContainer =
            MinIOContainer("minio/minio:RELEASE.2023-09-04T19-57-37Z")
                .apply { start() }
    }

    private lateinit var adapter: MinioImportStorageAdapter
    private val testBucket = "bts-imports-test"

    /**
     * MinioClient + [MinioImportStorageAdapter] 를 초기화한다.
     * singleton 컨테이너가 이미 기동된 상태에서 동적 endpoint/credentials 를 주입한다.
     */
    @BeforeAll
    fun setup() {
        val client =
            MinioClient.builder()
                .endpoint(minioContainer.s3URL)
                .credentials(minioContainer.userName, minioContainer.password)
                .build()

        val properties =
            MinioImportStorageConfig.Properties(
                endpoint = minioContainer.s3URL,
                accessKey = minioContainer.userName,
                secretKey = minioContainer.password,
                importBucket = testBucket,
            )

        adapter = MinioImportStorageAdapter(client, properties)
        adapter.ensureBucket()
    }

    @Test
    fun `put 한 뒤 get 하면 원본 바이트와 동일해야 한다`() {
        val key = "ATLAS/job-1.csv"
        val content = "안녕하세요 Import".toByteArray(Charsets.UTF_8)

        content.inputStream().use { stream ->
            adapter.put(key, stream, content.size.toLong(), "text/csv; charset=UTF-8")
        }

        val result = adapter.get(key).use { it.readBytes() }
        assertThat(result).isEqualTo(content)
    }

    @Test
    fun `delete 후 get 하면 MinioImportStorageException 이 발생해야 한다`() {
        val key = "ATLAS/job-2.csv"
        val content = "삭제 예정".toByteArray(Charsets.UTF_8)

        content.inputStream().use { stream ->
            adapter.put(key, stream, content.size.toLong(), "text/csv")
        }

        adapter.delete(key)

        assertThatThrownBy { adapter.get(key) }
            .isInstanceOf(MinioImportStorageException::class.java)
    }

    @Test
    fun `존재하지 않는 key 를 get 하면 MinioImportStorageException 이 발생해야 한다`() {
        assertThatThrownBy { adapter.get("ATLAS/missing.csv") }
            .isInstanceOf(MinioImportStorageException::class.java)
    }
}
